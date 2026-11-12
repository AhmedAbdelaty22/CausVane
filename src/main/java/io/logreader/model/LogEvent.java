package io.logreader.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One logical log event: a header line plus every continuation line (stack trace, SQL dump, ...).
 *
 * @param lineNumber 1-based number of the first physical line in the file
 * @param byteOffset byte offset of the first physical line in the file
 * @param lineCount  number of physical lines the event spans
 * @param lines      event text; for structured (JSON) events this is the decoded message and stack trace
 * @param structured true when the event was decoded from a JSON log line
 * @param trace      parsed stack trace, or null when the event has none
 * @param traceId    correlation / trace / request id, or null
 */
public record LogEvent(
        long lineNumber,
        long byteOffset,
        int lineCount,
        LocalDateTime timestamp,
        Severity severity,
        String message,
        List<String> lines,
        boolean structured,
        ParsedTrace trace,
        String traceId) {

    public LogEvent {
        lines = List.copyOf(lines);
    }

    public long lastLineNumber() {
        return lineNumber + lineCount - 1;
    }

    /** True when the event should appear in the error navigator. */
    public boolean isProblem() {
        if (severity.isAtLeast(Severity.WARN)) {
            return true;
        }
        return trace != null && trace.topType() != null && trace.hasFrames();
    }
}
