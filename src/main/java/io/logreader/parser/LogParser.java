package io.logreader.parser;

import io.logreader.model.LogEvent;
import io.logreader.model.ParsedTrace;
import io.logreader.model.Severity;
import io.logreader.parser.JsonLineParser.JsonRecord;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Streaming, push-based log parser. Feed it physical lines one at a time; it groups them into logical
 * {@link LogEvent}s (header + stack trace / multi-line payload) and hands each finished event to the sink.
 *
 * <p>Holds only the event currently being assembled, so memory use is independent of file size.
 */
public final class LogParser {

    /** Lines kept per event; longer events are still counted but their tail is not stored. */
    public static final int MAX_EVENT_LINES = 600;

    /** Characters kept per stored line. Bounds memory and regex work on pathological (e.g. base64) lines. */
    public static final int MAX_LINE_CHARS = 2000;

    /** Lines that always begin a new event even without a timestamp (raw crash output). */
    private static final Pattern STRONG_START = Pattern.compile(
            "^(?:panic: |fatal error: |Unhandled exception|Unhandled Exception|Exception in thread |thread '[^']*' panicked"
                    + "|PHP (?:Fatal|Parse|Warning|Notice)|Uncaught |Program received signal |\\S+\\.rb:\\d+:in )");
    private static final Pattern TRACEBACK = Pattern.compile("^Traceback \\(most recent call last\\):");
    /** Unindented lines that nevertheless belong to the preceding event. */
    private static final Pattern CONTINUATION = Pattern.compile(
            "^(?:Caused by:|caused by:|Suppressed:|During handling of the above exception|The above exception was"
                    + "|--->|--- |\\.\\.\\. \\d+ (?:more|common)|goroutine \\d+ \\[|created by |exit status |\\[signal "
                    + "|Stack trace:|stack backtrace:|#\\d+\\s|note: |\\[cause\\]|[}\\])])");
    private static final Pattern PY_FINAL_LINE = Pattern.compile("^[A-Za-z_][\\w.]*(?::.*)?$");
    private static final Pattern GO_FUNCTION_LINE = Pattern.compile("^[^\\s(]\\S*\\(.*\\)\\s*$");
    private static final Pattern GOROUTINE = Pattern.compile("^goroutine \\d+ \\[");
    private static final Pattern RUST_PANIC_LOCATION = Pattern.compile("panicked at \\S+:\\d+:\\d+:\\s*$");
    private static final Pattern TRACE_HINT = Pattern.compile("Exception|Error|panic|Traceback|Throwable|\\bat \\S+:\\d+");
    private static final Pattern LEADING_SEPARATORS = Pattern.compile("^[\\]\\s:|>-]+");

    private final Consumer<LogEvent> sink;
    private final TimestampDetector timestamps = new TimestampDetector();
    private final SeverityDetector severities = new SeverityDetector();
    private final TraceIdExtractor traceIds = new TraceIdExtractor();
    private final JsonLineParser json = new JsonLineParser(timestamps);
    private final StackTraceParser traces;

    private Pending pending;
    /** True once a timestamped line has been seen: from then on unstamped lines are continuations. */
    private boolean timestamped;

    public LogParser(Consumer<LogEvent> sink) {
        this(sink, new StackTraceParser());
    }

    public LogParser(Consumer<LogEvent> sink, StackTraceParser traces) {
        this.sink = sink;
        this.traces = traces;
    }

    /** Feeds the next physical line. */
    public void accept(long lineNumber, long byteOffset, String line) {
        boolean indented = line.isEmpty() || Character.isWhitespace(line.charAt(0));
        if (!indented && line.charAt(0) == '{') {
            JsonRecord record = json.tryParse(line);
            if (record != null) {
                flush();
                sink.accept(structuredEvent(lineNumber, byteOffset, record));
                return;
            }
        }
        TimestampDetector.Match stamp = indented ? null : timestamps.detectLeading(line);
        if (pending == null || stamp != null || startsNewEvent(line, indented)) {
            flush();
            if (stamp != null) {
                timestamped = true;
            }
            String header = stamp == null ? line : line.substring(stamp.end());
            pending = new Pending(lineNumber, byteOffset, stamp, severities.detect(header));
        }
        pending.append(line);
    }

    /** Emits the event being assembled, if any. Call at end of input. */
    public void flush() {
        if (pending != null) {
            Pending done = pending;
            pending = null;
            sink.accept(plainEvent(done));
        }
    }

    public boolean hasPending() {
        return pending != null;
    }

    private boolean startsNewEvent(String line, boolean indented) {
        if (pending.absorbNext || indented) {
            return false;
        }
        if (STRONG_START.matcher(line).find()) {
            return true;
        }
        if (TRACEBACK.matcher(line).find()) {
            // a traceback continues an error record, but a bare crash dump after an INFO line is its own event
            boolean continues = pending.pythonSeen || pending.severity.isAtLeast(Severity.WARN)
                    || (timestamped && pending.severity == Severity.UNKNOWN);
            return !continues;
        }
        if (CONTINUATION.matcher(line).find()) {
            return false;
        }
        if (timestamped) {
            return false;
        }
        if (pending.pythonOpen && PY_FINAL_LINE.matcher(line).matches()) {
            pending.pythonOpen = false; // the "ValueError: ..." line that ends a traceback
            return false;
        }
        return !(pending.goTrace && GO_FUNCTION_LINE.matcher(line).matches());
    }

    private LogEvent plainEvent(Pending p) {
        List<String> lines = p.lines;
        int last = lines.size();
        while (last > 1 && lines.get(last - 1).isBlank()) {
            last--;
        }
        int trimmed = lines.size() - last;
        lines = lines.subList(0, last);
        int lineCount = p.physicalLines - (p.physicalLines == p.lines.size() ? trimmed : 0);

        String first = lines.get(0);
        LocalDateTime timestamp = p.stamp != null ? p.stamp.value() : timestamps.parse(first);
        String message = p.stamp != null ? first.substring(p.stamp.end()) : first;
        message = LEADING_SEPARATORS.matcher(message).replaceFirst("").strip();

        Severity severity = p.severity;
        ParsedTrace trace = null;
        if (lines.size() > 1 || severity.isAtLeast(Severity.WARN) || TRACE_HINT.matcher(first).find()) {
            trace = traces.parse(lines).orElse(null);
        }
        if (severity == Severity.UNKNOWN && trace != null && trace.topType() != null) {
            severity = isCrash(trace, first) ? Severity.FATAL : Severity.ERROR;
        }
        return new LogEvent(p.lineNumber, p.byteOffset, lineCount, timestamp, severity, message, lines, false,
                trace, traceIds.extract(first));
    }

    private LogEvent structuredEvent(long lineNumber, long byteOffset, JsonRecord record) {
        String message = clip(record.message());
        List<String> lines = new ArrayList<>(record.stackLines().size() + 1);
        lines.add(message);
        for (String stackLine : record.stackLines()) {
            if (lines.size() >= MAX_EVENT_LINES) {
                break;
            }
            lines.add(clip(stackLine));
        }
        Severity severity = record.severity();
        ParsedTrace trace = null;
        if (lines.size() > 1 || severity.isAtLeast(Severity.WARN) || TRACE_HINT.matcher(message).find()) {
            trace = traces.parse(lines).orElse(null);
        }
        if (severity == Severity.UNKNOWN && trace != null && trace.topType() != null) {
            severity = Severity.ERROR;
        }
        return new LogEvent(lineNumber, byteOffset, 1, record.timestamp(), severity, message, lines, true,
                trace, record.traceId());
    }

    private static String clip(String line) {
        return line.length() <= MAX_LINE_CHARS ? line : line.substring(0, MAX_LINE_CHARS) + " …";
    }

    private static boolean isCrash(ParsedTrace trace, String firstLine) {
        String type = trace.topType();
        return "panic".equals(type) || "fatal error".equals(type) || firstLine.startsWith("Unhandled ")
                || firstLine.startsWith("Exception in thread") || firstLine.startsWith("PHP Fatal");
    }

    /** The event currently being assembled. */
    private static final class Pending {
        final long lineNumber;
        final long byteOffset;
        final TimestampDetector.Match stamp;
        final Severity severity;
        final List<String> lines = new ArrayList<>();
        int physicalLines;
        boolean pythonSeen;
        boolean pythonOpen;
        boolean goTrace;
        boolean absorbNext;

        Pending(long lineNumber, long byteOffset, TimestampDetector.Match stamp, Severity severity) {
            this.lineNumber = lineNumber;
            this.byteOffset = byteOffset;
            this.stamp = stamp;
            this.severity = severity;
        }

        void append(String rawLine) {
            String line = clip(rawLine);
            physicalLines++;
            if (lines.size() < MAX_EVENT_LINES) {
                lines.add(line);
            }
            absorbNext = false;
            if (line.isEmpty()) {
                return;
            }
            char c = line.charAt(0);
            if (c == 'T' && TRACEBACK.matcher(line).find()) {
                pythonSeen = true;
                pythonOpen = true;
            } else if (c == 'g' && GOROUTINE.matcher(line).find()) {
                goTrace = true;
            } else if (physicalLines == 1 && RUST_PANIC_LOCATION.matcher(line).find()) {
                absorbNext = true; // Rust prints the panic message on the line after the location
            }
        }
    }
}
