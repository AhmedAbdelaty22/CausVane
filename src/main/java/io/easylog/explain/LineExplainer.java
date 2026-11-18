package io.easylog.explain;

import io.easylog.model.LogEvent;
import io.easylog.model.ParsedTrace;
import io.easylog.model.Severity;
import io.easylog.model.StackFrame;
import io.easylog.model.TraceSection;
import io.easylog.parser.SeverityDetector;
import io.easylog.parser.StackTraceParser;
import io.easylog.parser.TimestampDetector;
import io.easylog.report.Formats;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Says, for one line of a log, what role it plays and what it means in plain words, so that someone who
 * has never read a stack trace can follow an error line by line.
 */
public final class LineExplainer {

    /** What part a line plays in the story of an error. */
    public enum Role {
        /** The line the application wrote to announce the problem. */
        LOGGED("LOGGED"),
        /** An error's own name and message. */
        ERROR_TEXT("ERROR"),
        /** A deeper reason for the error above it. */
        CAUSE("CAUSED BY"),
        /** The deepest reason. */
        ROOT_CAUSE("ROOT CAUSE"),
        /** The line of the application's own code where the problem started. */
        STARTS_HERE("STARTS HERE"),
        /** Another location in the application's own code. */
        YOUR_CODE("YOUR CODE"),
        /** A location inside a library or framework. */
        LIBRARY("LIBRARY"),
        /** Lines the application left out. */
        SKIPPED("SKIPPED"),
        /** Anything else belonging to the error. */
        DETAIL("DETAIL"),
        /** A log entry written before the error. */
        BEFORE("BEFORE"),
        /** A log entry written after the error. */
        AFTER("AFTER"),
        /** A remark added by EasyLog, not a log line. */
        NOTE("");

        private final String tag;

        Role(String tag) {
            this.tag = tag;
        }

        /** Short label shown beside the line. */
        public String tag() {
            return tag;
        }
    }

    /** The role of a line and one or two sentences about it. */
    public record Note(Role role, String text) {}

    private static final Pattern CAUSED_BY = Pattern.compile("^\\s*(?:Caused by:|caused by:|\\[cause\\]:)\\s*(.*)$");
    private static final Pattern OMITTED = Pattern.compile("^\\s*\\.\\.\\. (\\d+) (?:more|common frames omitted)");
    private static final Pattern TRACEBACK = Pattern.compile("^[\\s|+]*Traceback \\(most recent call last\\):");
    private static final Pattern GOROUTINE = Pattern.compile("^goroutine \\d+ \\[([^\\]]*)\\]");
    private static final Pattern EXIT_STATUS = Pattern.compile("^exit status (\\d+)");
    private static final Pattern DATABASE_DETAIL = Pattern.compile("^(DETAIL|HINT|CONTEXT|STATEMENT):\\s");
    private static final Pattern JSON_LEVEL = Pattern.compile("\"(?:level|severity|lvl|@l)\"\\s*:\\s*\"?(\\w+)\"?");
    private static final Pattern FUNCTION_ONLY = Pattern.compile("^[^\\s(]\\S*\\(.*\\)\\s*$|^\\s*\\d+:\\s+\\S.*$|^created by .*");

    private static final SeverityDetector SEVERITIES = new SeverityDetector();
    private static final TimestampDetector TIMESTAMPS = new TimestampDetector();

    private LineExplainer() {}

    /** Explains line {@code index} of {@link LogEvent#lines()}. */
    public static Note explainEventLine(LogEvent event, int index) {
        String line = event.lines().get(index);
        ParsedTrace trace = event.trace();

        if (trace != null) {
            for (TraceSection section : trace.sections()) {
                for (StackFrame frame : section.frames()) {
                    if (frame.eventLineIndex() == index && !(index == 0 && !event.structured())) {
                        return frameNote(frame, trace.pinpoint() != null && trace.pinpoint().eventLineIndex() == index);
                    }
                }
            }
        }
        if (index == 0) {
            return headerNote(event);
        }
        if (line.isBlank()) {
            return new Note(Role.DETAIL, "An empty line inside this log entry.");
        }

        Matcher m = CAUSED_BY.matcher(line);
        if (m.matches()) {
            return causeNote(trace, m.group(1));
        }
        if (line.contains("---> ")) {
            return causeNote(trace, line.substring(line.lastIndexOf("---> ") + 5));
        }
        if ((m = OMITTED.matcher(line)).find()) {
            return new Note(Role.SKIPPED, m.group(1) + " further code locations were left out by the application, "
                    + "because they repeat lines already shown above.");
        }
        if (TRACEBACK.matcher(line).find()) {
            return new Note(Role.DETAIL, "A list of code locations starts here. In this format they run from oldest to "
                    + "newest, so the last location listed is where it failed.");
        }
        if (line.startsWith("During handling of the above exception")) {
            return new Note(Role.DETAIL, "While the application was dealing with the error above, a second error happened. It follows below.");
        }
        if (line.startsWith("The above exception was the direct cause")) {
            return new Note(Role.DETAIL, "The error above is the reason for the next error, which follows below.");
        }
        if (line.contains("End of inner exception stack trace")) {
            return new Note(Role.DETAIL, "End of the inner error. The lines below belong to the outer error that wrapped it.");
        }
        if ((m = GOROUTINE.matcher(line)).find()) {
            return new Note(Role.DETAIL, m.group(1).startsWith("running")
                    ? "The task that was running when the program crashed. Its code locations follow, newest first."
                    : "Another task that was active at that moment; usually not involved in the crash.");
        }
        if ((m = EXIT_STATUS.matcher(line)).find()) {
            return new Note(Role.DETAIL, "The program stopped with exit code " + m.group(1) + ". Any code other than 0 means it ended because of a failure.");
        }
        if (line.strip().equals("Stack trace:") || line.strip().equals("stack backtrace:")) {
            return new Note(Role.DETAIL, "The list of code locations follows.");
        }
        if (line.stripLeading().startsWith("Suppressed:")) {
            return new Note(Role.DETAIL, "An additional error that happened while the application was handling the main one.");
        }
        if ((m = DATABASE_DETAIL.matcher(line)).find()) {
            return new Note(Role.DETAIL, "Extra information supplied by the database about this error.");
        }

        StackTraceParser.Header header = Character.isWhitespace(line.charAt(0)) ? null : StackTraceParser.parseHeader(line);
        if (header != null) {
            boolean root = trace != null && header.type().equals(trace.rootType());
            String text = "The error itself, named `" + simple(header.type()) + "`"
                    + (header.message() != null ? ", with the message: " + Formats.truncate(header.message(), 220) : ".");
            return new Note(root && trace.chain().size() > 1 ? Role.ROOT_CAUSE : Role.ERROR_TEXT,
                    root && trace.chain().size() > 1 ? "The deepest reason, the root cause. " + text : text);
        }
        if (index + 1 < event.lines().size() && hasFrameAt(trace, index + 1) && FUNCTION_ONLY.matcher(line).find()) {
            return new Note(Role.DETAIL, "The name of the function whose location is on the next line.");
        }
        if (index > 0 && hasFrameAt(trace, index - 1) && Character.isWhitespace(line.charAt(0))) {
            return new Note(Role.DETAIL, "The line of source code at the location shown just above.");
        }
        return new Note(Role.DETAIL, "More detail that belongs to this log entry.");
    }

    /** Explains a line that is not part of the error: something logged just before or after it. */
    public static Note explainContextLine(String line, boolean before) {
        Role role = before ? Role.BEFORE : Role.AFTER;
        String when = before ? "before" : "after";
        if (line.isBlank()) {
            return new Note(role, "An empty line.");
        }
        boolean json = line.startsWith("{");
        boolean startsEntry = json || (!Character.isWhitespace(line.charAt(0)) && TIMESTAMPS.detectLeading(line) != null);
        if (!startsEntry) {
            return new Note(role, "Part of a neighbouring log entry written " + when + " this error.");
        }
        Severity severity = SEVERITIES.detect(line);
        if (json) {
            Matcher m = JSON_LEVEL.matcher(line);
            if (m.find()) {
                String level = m.group(1);
                severity = level.chars().allMatch(Character::isDigit) && level.length() < 4
                        ? Severity.fromNumeric(Integer.parseInt(level)) : Severity.fromToken(level);
            }
        }
        return new Note(role, switch (severity) {
            case FATAL, ERROR -> "A different error, logged " + when + " this one. It has its own row in the list.";
            case WARN -> "A warning logged shortly " + when + " the error. Warnings just before an error are often related to it.";
            case INFO -> "Normal activity logged " + when + " the error. It shows what the application was doing at the time.";
            case DEBUG, TRACE -> "Low-level detail logged " + when + " the error, meant for developers.";
            default -> "Another log entry written " + when + " the error.";
        });
    }

    private static Note headerNote(LogEvent event) {
        String kind = switch (event.severity()) {
            case FATAL -> "a fatal error, meaning the application could not continue";
            case ERROR -> "an error";
            case WARN -> "a warning";
            default -> "this entry";
        };
        StringBuilder text = new StringBuilder("The application logged ").append(kind);
        if (event.timestamp() != null) {
            text.append(" at ").append(Formats.timestamp(event.timestamp()));
        }
        text.append('.');
        if (event.structured()) {
            text.append(" It is a structured (JSON) record; its contents are shown decoded underneath.");
        } else if (event.lineCount() > 1) {
            text.append(" The ").append(event.lineCount() - 1).append(event.lineCount() == 2 ? " line" : " lines")
                    .append(" below belong to the same entry.");
        }
        if (event.traceId() != null) {
            text.append(" The ID ").append(event.traceId()).append(" ties it to one request; searching for it shows everything that request did.");
        }
        return new Note(Role.LOGGED, text.toString());
    }

    private static Note frameNote(StackFrame frame, boolean pinpoint) {
        String where = (frame.qualifiedFunction() != null ? "function `" + simple(frame.qualifiedFunction()) + "`" : "a function")
                + (frame.fileName() != null ? " in file `" + frame.fileName() + "`" : "")
                + (frame.line() > 0 ? ", line " + frame.line() : "");
        if (pinpoint) {
            return new Note(Role.STARTS_HERE, frame.application()
                    ? "This is where the problem started: " + where + ". It is the first line of the application's own code "
                    + "in the root cause, so it is the place to look first."
                    : "The deepest location in this error: " + where + ". It is library code; no line of the application's own code was logged.");
        }
        if (frame.application()) {
            return new Note(Role.YOUR_CODE, "The application's own code was running here: " + where
                    + ". It is part of the path that led to the failure.");
        }
        return new Note(Role.LIBRARY, "Code of a library or framework the application uses was running here ("
                + where + "). This is rarely where the fault is.");
    }

    private static Note causeNote(ParsedTrace trace, String rest) {
        StackTraceParser.Header header = StackTraceParser.parseHeader(rest);
        String type = header != null ? header.type() : rest.strip();
        boolean root = trace != null && type.equals(trace.rootType());
        String name = "`" + simple(type) + "`" + (header != null && header.message() != null
                ? ": " + Formats.truncate(header.message(), 200) : "");
        return root
                ? new Note(Role.ROOT_CAUSE, "The deepest reason, the root cause of everything above: " + name
                + ". Errors are often wrapped several levels deep; this is the innermost one.")
                : new Note(Role.CAUSE, "A deeper reason for the error above it: " + name + ". Keep reading down for the root cause.");
    }

    private static boolean hasFrameAt(ParsedTrace trace, int index) {
        if (trace == null) {
            return false;
        }
        for (TraceSection section : trace.sections()) {
            for (StackFrame frame : section.frames()) {
                if (frame.eventLineIndex() == index) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Last part of a dotted or namespaced name, kept short for readability. */
    private static String simple(String name) {
        String plain = name.replace('`', '\'');
        int paren = plain.indexOf('(');
        String head = paren > 0 ? plain.substring(0, paren) : plain;
        String[] parts = head.split("\\.|::|\\\\");
        if (parts.length >= 2 && !head.contains("/") && !head.contains(" ")) {
            String last = parts[parts.length - 1];
            String owner = parts[parts.length - 2];
            return Character.isUpperCase(owner.isEmpty() ? 'a' : owner.charAt(0)) && !last.isEmpty()
                    && Character.isLowerCase(last.charAt(0)) ? owner + "." + last : last;
        }
        return Formats.truncate(head, 80);
    }
}
