package io.causvane.explain;

import io.causvane.io.ContextBuilder.ContextLine;
import io.causvane.model.LogEvent;
import io.causvane.model.ParsedTrace;
import io.causvane.model.Severity;
import io.causvane.model.StackFrame;
import io.causvane.model.TraceSection;
import io.causvane.parser.SeverityDetector;
import io.causvane.parser.TimestampDetector;
import io.causvane.report.Formats;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Retells one error and the log entries around it as a few plain sentences, for readers without a
 * technical background. Every step keeps the original text it came from, so no detail is lost.
 */
public final class PlainStory {

    /** What a step of the story is about. */
    public enum Kind {
        BEFORE, REPORTED, ERROR, CAUSE, ROOT_CAUSE, STARTS_HERE, CALLED_FROM, HIDDEN, AFTER
    }

    /**
     * One step.
     *
     * @param time       clock time, or empty
     * @param sentence   the plain sentence; text in {@code `backticks`} came from the log
     * @param original   the log text it was made from, for readers who want it; may be empty
     * @param lineNumber line in the file it refers to, or 0
     */
    public record Step(Kind kind, String time, String sentence, String original, long lineNumber) {}

    /** Log entries shown before and after the error. */
    public static final int ENTRIES_BEFORE = 4;
    public static final int ENTRIES_AFTER = 3;

    private static final TimestampDetector TIMESTAMPS = new TimestampDetector();
    private static final SeverityDetector SEVERITIES = new SeverityDetector();
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    private static final Pattern JSON_MESSAGE = Pattern.compile(
            "\"(?:msg|message|@m|@mt|event)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.){0,400})\"");
    private static final Pattern JSON_LEVEL = Pattern.compile("\"(?:level|severity|lvl|@l)\"\\s*:\\s*\"?(\\w+)\"?");
    private static final Pattern JSON_EPOCH = Pattern.compile("\"(?:time|ts|timestamp)\"\\s*:\\s*(\\d{10,19}(?:\\.\\d+)?)");
    private static final Pattern LEAD_NOISE = Pattern.compile("^[\\s\\]\\[|:,+-]*");
    private static final Pattern LEVEL = Pattern.compile(
            "^\\[?(?:TRACE|DEBUG|INFO|NOTICE|WARN|WARNING|ERROR|FATAL|CRITICAL|SEVERE|INF|WRN|ERR|FTL|DBG|VRB)\\b(?:/[\\w.-]+)?\\]?:?\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LOGFMT_LEVEL = Pattern.compile("^level=\\w+\\s*");
    private static final Pattern PID = Pattern.compile("^\\d+\\s+---\\s+");
    private static final Pattern THREADS = Pattern.compile("^(?:\\[[^\\]]{0,80}\\]\\s*)+");
    private static final Pattern LOGGER = Pattern.compile("^[A-Za-z_][\\w$-]*(?:\\.[\\w$-]+)+\\s*:\\s+");
    private static final Pattern LOGFMT_MESSAGE = Pattern.compile("^msg=\"([^\"]*)\"\\s*(.*)$");

    private PlainStory() {}

    /** Builds the story of one occurrence from the event and the lines shown around it. */
    public static List<Step> build(LogEvent event, List<ContextLine> lines) {
        List<Step> steps = new ArrayList<>();
        List<Step> before = new ArrayList<>();
        List<Step> after = new ArrayList<>();
        for (ContextLine line : lines) {
            if (line.kind() != ContextLine.Kind.CONTEXT || !startsEntry(line.text())) {
                continue;
            }
            boolean isBefore = line.lineNumber() < event.lineNumber();
            Step step = context(line, isBefore);
            if (isBefore) {
                before.add(step);
            } else if (after.size() < ENTRIES_AFTER) {
                after.add(step);
            }
        }
        steps.addAll(before.subList(Math.max(0, before.size() - ENTRIES_BEFORE), before.size()));

        String header = event.lines().isEmpty() ? "" : event.lines().get(0);
        String message = event.structured() ? event.message() : simplify(header);
        steps.add(new Step(Kind.REPORTED, clock(event.timestamp()),
                "The application reported " + switch (event.severity()) {
                    case FATAL -> "a fatal error, and could not carry on";
                    case ERROR -> "an error";
                    case WARN -> "a warning";
                    default -> "a problem";
                } + (message.isBlank() ? "." : ": " + quote(message)),
                Formats.truncate(event.structured() ? header : header.strip(), 400), event.lineNumber()));

        int represented = 1;
        ParsedTrace trace = event.trace();
        if (trace != null) {
            List<TraceSection> chain = chain(trace);
            String previous = null;
            for (int i = 0; i < chain.size(); i++) {
                String type = chain.get(i).exceptionType();
                String text = chain.get(i).message();
                boolean root = i == chain.size() - 1 && chain.size() > 1;
                Kind kind = i == 0 ? Kind.ERROR : root ? Kind.ROOT_CAUSE : Kind.CAUSE;
                String lead = switch (kind) {
                    case ERROR -> "The error";
                    case ROOT_CAUSE -> "The underlying cause, where it all began";
                    default -> "This was caused by";
                };
                String body;
                if (text == null || text.isBlank()) {
                    body = ": " + quote(simpleType(type)) + " (it has no message of its own).";
                } else if (text.equals(previous)) {
                    body = ": the same problem, reported one level deeper as " + quote(simpleType(type)) + ".";
                } else {
                    body = ": " + quote(text);
                }
                steps.add(new Step(kind, "", lead + body, "`" + type + "`", 0));
                previous = text;
                represented++;
            }
            StackFrame pin = trace.pinpoint();
            if (pin != null) {
                long line = event.structured() ? 0 : event.lineNumber() + pin.eventLineIndex();
                steps.add(new Step(Kind.STARTS_HERE, "", (pin.application()
                        ? "It started in the application's own code: "
                        : "It started inside a library the application uses (none of the application's own code was logged): ")
                        + place(pin), pin.file() == null ? "" : "`" + pin.file() + (pin.line() > 0 ? ":" + pin.line() : "") + "`", line));
                represented++;
                List<String> callers = callers(trace, pin);
                if (!callers.isEmpty()) {
                    steps.add(new Step(Kind.CALLED_FROM, "", "It got there from: " + String.join("  →  ", callers) + ".", "", 0));
                    represented += callers.size();
                }
            }
        }
        long hidden = event.lineCount() - represented;
        if (hidden > 0 && !event.structured()) {
            steps.add(new Step(Kind.HIDDEN, "", hidden + (hidden == 1 ? " more line" : " more lines")
                    + " of this entry are technical detail, mostly inside libraries. \"All lines\" shows every one of them.", "", 0));
        }
        steps.addAll(after);
        return steps;
    }

    /** The message of a log line without its date, level, process, thread and logger name. */
    public static String simplify(String line) {
        if (line == null) {
            return "";
        }
        String text = line.strip();
        if (text.startsWith("{")) {
            Matcher m = JSON_MESSAGE.matcher(text);
            return m.find() ? Formats.truncate(m.group(1).replace("\\\"", "\"").replace("\\n", " "), 220) : Formats.truncate(text, 220);
        }
        TimestampDetector.Match stamp = TIMESTAMPS.detectLeading(text);
        if (stamp != null) {
            text = text.substring(stamp.end());
        }
        for (Pattern noise : new Pattern[] {LEAD_NOISE, LOGFMT_LEVEL, LEVEL, LEAD_NOISE, PID, THREADS, LOGGER}) {
            text = noise.matcher(text).replaceFirst("");
        }
        Matcher logfmt = LOGFMT_MESSAGE.matcher(text);
        if (logfmt.matches()) {
            text = logfmt.group(1) + (logfmt.group(2).isBlank() ? "" : " (" + logfmt.group(2).strip() + ")");
        }
        return Formats.truncate(text.replaceAll("\\s+", " ").strip(), 220);
    }

    private static Step context(ContextLine line, boolean before) {
        String text = line.text();
        Severity severity = SEVERITIES.detect(text);
        if (text.startsWith("{")) {
            Matcher m = JSON_LEVEL.matcher(text);
            if (m.find()) {
                String level = m.group(1);
                severity = level.chars().allMatch(Character::isDigit) && level.length() < 4
                        ? Severity.fromNumeric(Integer.parseInt(level)) : Severity.fromToken(level);
            }
        }
        String label = switch (severity) {
            case FATAL, ERROR -> "Another error";
            case WARN -> "A warning";
            case DEBUG, TRACE -> "Developer detail";
            default -> "Normal activity";
        };
        TimestampDetector.Match stamp = text.startsWith("{") ? null : TIMESTAMPS.detectLeading(text);
        LocalDateTime time = stamp != null ? stamp.value() : TIMESTAMPS.parse(text);
        if (time == null && text.startsWith("{")) {
            Matcher epoch = JSON_EPOCH.matcher(text);
            if (epoch.find()) {
                time = TIMESTAMPS.fromEpoch(Double.parseDouble(epoch.group(1)));
            }
        }
        return new Step(before ? Kind.BEFORE : Kind.AFTER, clock(time), label + ": " + quote(simplify(text)),
                Formats.truncate(text.strip(), 400), line.lineNumber());
    }

    private static boolean startsEntry(String text) {
        if (text.isBlank() || Character.isWhitespace(text.charAt(0))) {
            return false;
        }
        return text.startsWith("{") || TIMESTAMPS.detectLeading(text) != null;
    }

    /** The errors of the chain from the reported one down to the root cause, each once. */
    private static List<TraceSection> chain(ParsedTrace trace) {
        boolean python = trace.language() == io.causvane.model.Language.PYTHON;
        List<TraceSection> parts = new ArrayList<>();
        for (TraceSection section : trace.sections()) {
            boolean inChain = section.kind() == TraceSection.Kind.PRIMARY || section.kind() == TraceSection.Kind.CAUSE
                    || (python && section.kind() == TraceSection.Kind.WRAPPER);
            if (inChain && section.exceptionType() != null) {
                parts.add(section);
            }
        }
        if (python) {
            java.util.Collections.reverse(parts); // Python prints the original error first
        }
        List<TraceSection> distinct = new ArrayList<>();
        for (TraceSection part : parts) {
            if (distinct.isEmpty() || !distinct.get(distinct.size() - 1).exceptionType().equals(part.exceptionType())) {
                distinct.add(part);
            }
        }
        return distinct;
    }

    private static String simpleType(String type) {
        int cut = Math.max(type.lastIndexOf('.'), Math.max(type.lastIndexOf('\\'), type.lastIndexOf(':')));
        return cut >= 0 && cut < type.length() - 1 ? type.substring(cut + 1) : type;
    }

    /** "function placeOrder in OrderService.java, line 142". */
    private static String place(StackFrame frame) {
        String function = frame.function() == null ? null : shortName(frame.qualifiedFunction());
        return (function != null ? "function `" + function + "`" : "an unnamed function")
                + (frame.fileName() != null ? " in file `" + frame.fileName() + "`" : "")
                + (frame.line() > 0 ? ", line " + frame.line() : "") + ".";
    }

    /**
     * The application's own functions that led to the starting point, nearest first, at most four. They come
     * from the part of the trace holding the starting point and then from the errors that wrapped it, because
     * traces often shorten repeated lines in the deeper parts.
     */
    private static List<String> callers(ParsedTrace trace, StackFrame pin) {
        List<String> out = new ArrayList<>();
        String self = "`" + shortName(pin.qualifiedFunction()) + "`";
        List<TraceSection> sections = trace.sections();
        int home = -1;
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).frames().contains(pin)) {
                home = i;
            }
        }
        if (home < 0) {
            return out;
        }
        boolean python = trace.language() == io.causvane.model.Language.PYTHON;
        for (int i = home; i >= 0 && out.size() < 4; i--) {
            TraceSection section = sections.get(i);
            if (section.kind() == TraceSection.Kind.SUPPRESSED || section.kind() == TraceSection.Kind.OTHER || (python && i != home)) {
                continue;
            }
            int start = i == home ? section.frames().indexOf(pin) + 1 : 0;
            for (int f = start; f < section.frames().size() && out.size() < 4; f++) {
                StackFrame frame = section.frames().get(f);
                if (frame.application() && frame.function() != null) {
                    String name = "`" + shortName(frame.qualifiedFunction()) + "`";
                    if (!name.equals(self) && !out.contains(name)) {
                        out.add(name);
                    }
                }
            }
        }
        return out;
    }

    /** "OrderService.placeOrder" from "com.shop.OrderService.placeOrder". */
    private static String shortName(String qualified) {
        if (qualified == null) {
            return "";
        }
        String clean = qualified.replace('`', '\'');
        String[] parts = clean.split("\\.");
        if (parts.length >= 2 && !clean.contains("/") && !clean.contains(" ")) {
            return parts[parts.length - 2] + "." + parts[parts.length - 1];
        }
        return Formats.truncate(clean, 80);
    }

    private static String quote(String text) {
        return "`" + text.replace('`', '\'') + "`";
    }

    private static String clock(LocalDateTime time) {
        return time == null ? "" : CLOCK.format(time);
    }
}
