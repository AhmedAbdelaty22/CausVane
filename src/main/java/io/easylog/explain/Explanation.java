package io.easylog.explain;

import java.util.List;

/**
 * A plain-language description of an error for someone who did not write the code.
 *
 * @param category     broad kind of problem, e.g. "Database" or "Null reference"
 * @param title        one-line statement of what went wrong
 * @param meaning      what this kind of error means in general
 * @param detail       specifics taken from this error's own message, or null
 * @param context      where and how it happened in this log (location, wrapper vs. root cause, frequency)
 * @param likelyCauses usual reasons, most common first
 * @param nextSteps    what to check or do next
 * @param recognised   false when no specific rule matched and the text is generic guidance
 */
public record Explanation(
        String category,
        String title,
        String meaning,
        String detail,
        List<String> context,
        List<String> likelyCauses,
        List<String> nextSteps,
        boolean recognised) {

    public Explanation {
        context = List.copyOf(context);
        likelyCauses = List.copyOf(likelyCauses);
        nextSteps = List.copyOf(nextSteps);
    }
}
