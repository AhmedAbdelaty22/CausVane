package io.causvane.model;

import java.util.EnumMap;
import java.util.Map;

/**
 * What the whole file contained, not only its problems: every entry counted by severity, and how many
 * entries had a time, a stack trace, a trace ID, more than one line, or were written as JSON.
 *
 * @param byLevel          entries per severity, every level present (zero when none)
 * @param timestamped      entries that began with a date or time
 * @param multiLine        entries that spanned more than one line
 * @param structured       entries written as JSON records
 * @param withStackTrace   entries that included a stack trace
 * @param withTraceId      entries that carried a trace or request ID
 * @param distinctTraceIds different trace IDs seen, counted up to {@link #TRACE_ID_LIMIT}
 */
public record LogContents(
        Map<Severity, Long> byLevel,
        long timestamped,
        long multiLine,
        long structured,
        long withStackTrace,
        long withTraceId,
        long distinctTraceIds) {

    /** Different trace IDs are counted up to this number, to keep memory bounded. */
    public static final int TRACE_ID_LIMIT = 100_000;

    public LogContents {
        EnumMap<Severity, Long> all = new EnumMap<>(Severity.class);
        for (Severity s : Severity.values()) {
            all.put(s, byLevel == null ? 0L : byLevel.getOrDefault(s, 0L));
        }
        byLevel = Map.copyOf(all);
    }

    public static LogContents empty() {
        return new LogContents(Map.of(), 0, 0, 0, 0, 0, 0);
    }

    public long level(Severity severity) {
        return byLevel.getOrDefault(severity, 0L);
    }

    /** Entries that were normal activity: INFO, DEBUG and TRACE. */
    public long normal() {
        return level(Severity.INFO) + level(Severity.DEBUG) + level(Severity.TRACE);
    }
}
