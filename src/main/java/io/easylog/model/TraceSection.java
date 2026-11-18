package io.easylog.model;

import java.util.List;

/**
 * One exception inside a (possibly chained) stack trace, with its frames ordered most-recent-call first.
 */
public record TraceSection(Kind kind, String exceptionType, String message, List<StackFrame> frames) {

    public enum Kind {
        /** The first exception printed. */
        PRIMARY,
        /** A nested cause: Java {@code Caused by:}, .NET {@code --->}, JS {@code [cause]:}. */
        CAUSE,
        /** An exception that wraps an earlier one (Python chained tracebacks, .NET outer frames). */
        WRAPPER,
        /** Java suppressed exceptions. */
        SUPPRESSED,
        /** Unrelated frames such as the other goroutines of a Go panic dump. */
        OTHER
    }

    public TraceSection {
        frames = List.copyOf(frames);
    }
}
