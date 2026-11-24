package io.causvane.model;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Immutable snapshot of an analysed log file.
 *
 * @param errorCount   occurrences at ERROR or FATAL level
 * @param warningCount occurrences at WARN level
 * @param groups       deduplicated errors, most frequent first
 * @param timeline     errors and warnings per slice of time, in chronological order
 * @param unlistedOccurrences problems counted in the totals but not listed, because the log held more
 *                     distinct errors than the listing limit
 * @param contents     what the whole file contained, problems or not
 */
public record AnalysisResult(
        Path file,
        long fileSize,
        long totalLines,
        long totalEvents,
        long errorCount,
        long warningCount,
        LocalDateTime firstTimestamp,
        LocalDateTime lastTimestamp,
        List<ErrorGroup> groups,
        List<TimeBucket> timeline,
        long unlistedOccurrences,
        LogContents contents) {

    public AnalysisResult {
        contents = contents == null ? LogContents.empty() : contents;
        groups = List.copyOf(groups);
        timeline = List.copyOf(timeline);
    }

    /** Number of distinct errors at ERROR level or above. */
    public long uniqueErrorTypes() {
        return groups.stream().filter(g -> g.severity().isAtLeast(Severity.ERROR)).count();
    }

    public Duration timeSpan() {
        if (firstTimestamp == null || lastTimestamp == null) {
            return null;
        }
        return Duration.between(firstTimestamp, lastTimestamp);
    }
}
