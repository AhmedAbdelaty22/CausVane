package io.logreader.model;

import java.util.List;

/**
 * A fully analysed stack trace.
 *
 * @param sections every exception in the trace in the order it was printed
 * @param root     the deepest root cause
 * @param pinpoint the frame where the error originated (first application frame of the root cause), or null
 * @param chain    exception types from the outermost error down to the root cause
 */
public record ParsedTrace(
        Language language,
        List<TraceSection> sections,
        TraceSection root,
        StackFrame pinpoint,
        List<String> chain) {

    public ParsedTrace {
        sections = List.copyOf(sections);
        chain = List.copyOf(chain);
    }

    /** Outermost exception type: what the application finally reported. */
    public String topType() {
        return chain.isEmpty() ? null : chain.get(0);
    }

    public String rootType() {
        return root == null ? null : root.exceptionType();
    }

    public String rootMessage() {
        return root == null ? null : root.message();
    }

    public boolean hasFrames() {
        return sections.stream().anyMatch(s -> !s.frames().isEmpty());
    }
}
