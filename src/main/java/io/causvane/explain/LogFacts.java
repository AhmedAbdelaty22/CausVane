package io.causvane.explain;

import io.causvane.model.AnalysisResult;
import io.causvane.model.ErrorGroup;
import io.causvane.model.Language;
import io.causvane.model.LogContents;
import io.causvane.model.LogEvent;
import io.causvane.model.ParsedTrace;
import io.causvane.model.Severity;
import io.causvane.model.StackFrame;
import io.causvane.report.Formats;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Everything CausVane read, as a list of facts, each with a sentence saying what it means. One list for
 * the whole file and one for a single problem. The window and the saved reports both show these
 * same facts, so the wording stays the same everywhere.
 */
public final class LogFacts {

    /**
     * One fact.
     *
     * @param label   short name, such as "Entries"
     * @param value   what the log showed; may contain text from the log
     * @param meaning one plain sentence on what the fact means
     */
    public record Fact(String label, String value, String meaning) {}

    /** Trace IDs listed by name for one problem; the rest are counted. */
    private static final int TRACE_IDS_SHOWN = 3;

    private LogFacts() {
    }

    /** What the whole file contained. */
    public static List<Fact> forLog(AnalysisResult r, int incidents) {
        LogContents c = r.contents();
        List<Fact> facts = new ArrayList<>();
        facts.add(new Fact("File", r.file().getFileName() + "  ·  " + Formats.bytes(r.fileSize()),
                "The file that was read, and its size."));
        facts.add(new Fact("Lines", Formats.count(r.totalLines()), "Lines of text in the file."));
        facts.add(new Fact("Entries", Formats.count(r.totalEvents())
                + (c.multiLine() > 0 ? "  ·  " + Formats.count(c.multiLine()) + " of them longer than one line" : ""),
                "An entry is one thing the application wrote down. Most take one line; an error with its stack trace takes several."));
        if (r.firstTimestamp() != null) {
            facts.add(new Fact("Period covered", Formats.timestamp(r.firstTimestamp()) + " to " + Formats.timestamp(r.lastTimestamp())
                    + (r.timeSpan() != null ? "  ·  " + Formats.duration(r.timeSpan()) : ""),
                    "From the first to the last time written in the log. "
                            + Formats.count(c.timestamped()) + " of the entries began with a time."));
        } else {
            facts.add(new Fact("Period covered", "Unknown", "No entry began with a date or time, so problems cannot be placed in time."));
        }
        facts.add(new Fact("Fatal", Formats.count(c.level(Severity.FATAL)),
                "Entries saying the application could not go on. These usually mean it stopped or crashed."));
        facts.add(new Fact("Errors", Formats.count(c.level(Severity.ERROR)),
                "Entries saying something failed: a request, a task or a connection."));
        facts.add(new Fact("Warnings", Formats.count(c.level(Severity.WARN)),
                "Entries saying something looked wrong but work went on. They often come before an error."));
        facts.add(new Fact("Normal activity", Formats.count(c.normal()) + "  ·  " + Formats.count(c.level(Severity.INFO)) + " info, "
                + Formats.count(c.level(Severity.DEBUG)) + " debug, " + Formats.count(c.level(Severity.TRACE)) + " trace",
                "Routine entries describing what the application was doing. Nothing to act on, but useful around an error."));
        if (c.level(Severity.UNKNOWN) > 0) {
            facts.add(new Fact("Without a level", Formats.count(c.level(Severity.UNKNOWN)),
                    "Entries that did not say how serious they were, such as a crash dump or plain text."));
        }
        long fatal = r.groups().stream().filter(g -> g.severity() == Severity.FATAL).count();
        long errors = r.groups().stream().filter(g -> g.severity() == Severity.ERROR).count();
        long warnings = r.groups().stream().filter(g -> g.severity() == Severity.WARN).count();
        facts.add(new Fact("Different problems", Formats.count(r.groups().size())
                + "  ·  " + fatal + " fatal, " + errors + " errors, " + warnings + " warnings",
                "Each repeat of the same error at the same place is counted as one problem, so a thousand repeats make one row in the list."));
        facts.add(new Fact("Probable incidents", String.valueOf(incidents),
                "Groups of problems that probably share one cause: a first failure and what it set off."));
        facts.add(new Fact("Stack traces", Formats.count(c.withStackTrace()) + " entries",
                "Lists of code locations written with an error. CausVane reads them to find where each error started."));
        if (c.withTraceId() > 0) {
            facts.add(new Fact("Trace IDs", Formats.count(c.withTraceId()) + " entries  ·  "
                    + (c.distinctTraceIds() >= LogContents.TRACE_ID_LIMIT ? "more than " : "")
                    + Formats.count(c.distinctTraceIds()) + " different",
                    "An ID attached to every entry of one request, so that one request can be followed through the log."));
        } else {
            facts.add(new Fact("Trace IDs", "None", "No entry carried a trace or request ID."));
        }
        if (c.structured() > 0) {
            facts.add(new Fact("JSON entries", Formats.count(c.structured()),
                    "Entries written as JSON records. CausVane reads their time, level, message and error fields."));
        }
        String languages = r.groups().stream().map(ErrorGroup::language).filter(l -> l != Language.UNKNOWN).distinct()
                .map(Language::displayName).collect(Collectors.joining(", "));
        facts.add(new Fact("Language", languages.isEmpty() ? "Not recognised" : languages,
                languages.isEmpty() ? "No stack trace showed which programming language the application is written in."
                        : "The programming language of the application, recognised from its stack traces."));
        if (!c.technologies().isEmpty()) {
            facts.add(new Fact("Technologies", String.join("  ·  ", c.technologies().keySet()),
                    "Frameworks, databases and services the log mentions, from package names, start-up lines and messages. "
                            + "The advice for each problem includes steps for these."));
        }
        if (r.unlistedOccurrences() > 0) {
            facts.add(new Fact("Not listed", Formats.count(r.unlistedOccurrences()),
                    "Occurrences counted in the totals but not listed, because the log held more different problems than the list can keep."));
        }
        return facts;
    }

    /** Everything known about one problem; {@code event} is the occurrence being shown, or null. */
    public static List<Fact> forProblem(ErrorGroup g, LogEvent event, Explanation explanation) {
        List<Fact> facts = new ArrayList<>();
        facts.add(new Fact("How serious", g.severity().name(), switch (g.severity()) {
            case FATAL -> "The application said it could not go on.";
            case ERROR -> "The application said something failed.";
            case WARN -> "The application said something looked wrong, but work went on.";
            default -> "Logged as normal activity, but it included an error with a stack trace.";
        }));
        facts.add(new Fact("Kind of problem", explanation.category(), explanation.recognised()
                ? "The family of problems it belongs to."
                : "CausVane has no specific explanation for this error, so the advice is general."));
        String when = g.firstSeen() == null ? "No time was logged"
                : g.count() == 1 || Objects.equals(g.firstSeen(), g.lastSeen()) ? Formats.timestamp(g.firstSeen())
                : Formats.timestamp(g.firstSeen()) + " to " + Formats.timestamp(g.lastSeen());
        facts.add(new Fact("How often", Formats.count(g.count()) + (g.count() == 1 ? " time" : " times") + "  ·  " + when,
                "Every time the same error was logged at the same place, from the first time to the last."));
        facts.add(new Fact("Where in the file", g.firstLine() == g.lastLine() ? "line " + Formats.count(g.firstLine())
                : "first at line " + Formats.count(g.firstLine()) + ", last at line " + Formats.count(g.lastLine()),
                "Line numbers in the log file, to find it in any text editor."));
        facts.add(new Fact("Error name", g.errorType() == null ? "None" : g.errorType(), g.errorType() == null
                ? "No error name was logged, so it is grouped by its message."
                : "The name the program gave this error. Searching for it online finds more help."));
        facts.add(new Fact("Message", g.message() == null || g.message().isBlank() ? "None" : Formats.truncate(g.message(), 600),
                "What the application wrote about it, with numbers and IDs as in the first occurrence."));
        if (g.rootCauseType() != null && !g.rootCauseType().equals(g.errorType())) {
            facts.add(new Fact("Underlying error", g.rootCauseType()
                    + (g.rootCauseMessage() != null ? ": " + Formats.truncate(g.rootCauseMessage(), 400) : ""),
                    "The deepest error in the chain. It is usually the real reason; the others only pass it on."));
        }
        if (g.chain().size() > 1) {
            facts.add(new Fact("Chain of errors", g.chain().stream().map(ErrorGroup::simpleName).collect(Collectors.joining("  →  ")),
                    "Each error was caused by the one after it."));
        }
        StackFrame pin = g.pinpoint();
        if (pin != null) {
            facts.add(new Fact("Started in", pin.location()
                    + (pin.qualifiedFunction() != null ? "  ·  " + pin.qualifiedFunction() : ""),
                    pin.application() ? "The file, line and function in the application's own code where it began. Start reading the code here."
                            : "Only library code was listed, so this is the nearest place; the cause may be in how the application calls it."));
        } else {
            facts.add(new Fact("Started in", "Not logged", "There was no stack trace, so the place in the code is not known."));
        }
        if (g.language() != Language.UNKNOWN) {
            facts.add(new Fact("Language", g.language().displayName(), "Recognised from the stack trace."));
        }
        if (!g.traceIds().isEmpty()) {
            List<String> ids = g.traceIds().stream().sorted().limit(TRACE_IDS_SHOWN).toList();
            facts.add(new Fact("Requests affected", g.traceIds().size() + (g.traceIds().size() == 1 ? " trace ID" : " trace IDs")
                    + "  ·  " + String.join(", ", ids) + (g.traceIds().size() > ids.size() ? ", …" : ""),
                    "The requests that hit this problem. Search for a trace ID to see everything that happened in that request."));
        }
        if (event != null) {
            ParsedTrace trace = event.trace();
            facts.add(new Fact("This occurrence", "line " + Formats.count(event.lineNumber())
                    + (event.lineCount() > 1 ? " (" + event.lineCount() + " lines)" : "")
                    + (event.timestamp() != null ? "  ·  " + Formats.timestamp(event.timestamp()) : "")
                    + (event.traceId() != null ? "  ·  trace " + event.traceId() : "")
                    + (trace != null && trace.hasFrames() ? "  ·  " + trace.sections().stream().mapToInt(s -> s.frames().size()).sum()
                    + " code locations" : ""),
                    "The occurrence shown below. The arrows at the top step through the others."));
        }
        return facts;
    }
}
