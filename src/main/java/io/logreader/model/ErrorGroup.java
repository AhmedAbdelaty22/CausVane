package io.logreader.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * A deduplicated error: every occurrence of the same error type at the same pinpointed location.
 *
 * @param errorType  outermost exception type, or null for plain error messages
 * @param pinpoint   root-cause frame, or null when no stack trace was logged
 * @param chain      exception types from the outermost error to the root cause
 * @param samples    the first occurrences, kept for context viewing
 * @param searchText lower-cased text used by keyword filters
 */
public record ErrorGroup(
        String id,
        Severity severity,
        String errorType,
        String message,
        StackFrame pinpoint,
        String rootCauseType,
        String rootCauseMessage,
        List<String> chain,
        Language language,
        long count,
        LocalDateTime firstSeen,
        LocalDateTime lastSeen,
        long firstLine,
        long lastLine,
        List<LogEvent> samples,
        Set<String> traceIds,
        String searchText) {

    public ErrorGroup {
        chain = List.copyOf(chain);
        samples = List.copyOf(samples);
        traceIds = Set.copyOf(traceIds);
    }

    /** Short type name for display, e.g. {@code IllegalStateException}. */
    public String displayType() {
        if (errorType == null) {
            return switch (severity) {
                case FATAL -> "Fatal message";
                case ERROR -> "Error message";
                case WARN -> "Warning";
                default -> "Log message";
            };
        }
        return simpleName(errorType);
    }

    public String location() {
        return pinpoint == null ? "—" : pinpoint.location();
    }

    public static String simpleName(String type) {
        if (type == null) {
            return null;
        }
        int cut = Math.max(type.lastIndexOf('.'), Math.max(type.lastIndexOf('\\'), type.lastIndexOf(':')));
        return cut >= 0 && cut < type.length() - 1 ? type.substring(cut + 1) : type;
    }
}
