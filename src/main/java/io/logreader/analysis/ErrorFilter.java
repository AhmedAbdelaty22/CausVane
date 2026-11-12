package io.logreader.analysis;

import io.logreader.model.ErrorGroup;
import io.logreader.model.Severity;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Filter applied to the error navigator: free text (keyword, trace id or regex) plus an optional severity.
 *
 * @param severity exact severity to keep, or null for all
 */
public record ErrorFilter(TextMatcher text, Severity severity) implements Predicate<ErrorGroup> {

    public static final ErrorFilter NONE = new ErrorFilter(new TextMatcher.All(), null);

    /** How the free-text part of the filter is matched. */
    public sealed interface TextMatcher {
        /** Matches everything. */
        record All() implements TextMatcher {}

        /** Case-insensitive match of every whitespace-separated term. */
        record Keyword(List<String> terms) implements TextMatcher {}

        /** Case-insensitive regular expression. */
        record Regex(Pattern pattern) implements TextMatcher {}

        /**
         * @throws PatternSyntaxException when {@code regex} is true and the query is not a valid pattern
         */
        static TextMatcher of(String query, boolean regex) {
            if (query == null || query.isBlank()) {
                return new All();
            }
            if (regex) {
                return new Regex(Pattern.compile(query.strip(), Pattern.CASE_INSENSITIVE));
            }
            return new Keyword(List.of(query.strip().toLowerCase(Locale.ROOT).split("\\s+")));
        }
    }

    @Override
    public boolean test(ErrorGroup group) {
        if (severity != null && group.severity() != severity) {
            return false;
        }
        String haystack = group.searchText();
        return switch (text) {
            case TextMatcher.All all -> true;
            case TextMatcher.Keyword keyword -> keyword.terms().stream().allMatch(haystack::contains);
            case TextMatcher.Regex regex -> findWithinBudget(regex.pattern(), haystack);
        };
    }

    /** Time a single regex match may take before it is abandoned and counted as "no match". */
    private static final long REGEX_BUDGET_NANOS = 150_000_000L;

    /**
     * Runs a user-supplied pattern with a time budget, so an expression with catastrophic backtracking
     * (such as {@code (a+)+$}) cannot freeze the window.
     */
    private static boolean findWithinBudget(Pattern pattern, String text) {
        try {
            return pattern.matcher(new BudgetedText(text, System.nanoTime() + REGEX_BUDGET_NANOS)).find();
        } catch (RegexBudgetExceeded e) {
            return false;
        }
    }

    private static final class RegexBudgetExceeded extends RuntimeException {
        private static final long serialVersionUID = 1L;

        RegexBudgetExceeded() {
            super("regex time budget exceeded", null, false, false);
        }
    }

    /** Character sequence that aborts the regex engine once the deadline has passed. */
    private static final class BudgetedText implements CharSequence {
        private final CharSequence text;
        private final long deadline;
        private int reads;

        BudgetedText(CharSequence text, long deadline) {
            this.text = text;
            this.deadline = deadline;
        }

        @Override
        public char charAt(int index) {
            if ((++reads & 0xFFF) == 0 && System.nanoTime() > deadline) {
                throw new RegexBudgetExceeded();
            }
            return text.charAt(index);
        }

        @Override
        public int length() {
            return text.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new BudgetedText(text.subSequence(start, end), deadline);
        }

        @Override
        public String toString() {
            return text.toString();
        }
    }
}
