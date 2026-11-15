package io.logreader.io;

/**
 * Neutralises characters in log text that could act on whatever displays them rather than be shown.
 *
 * <p>A log file is untrusted input. Terminal escape sequences inside it can rewrite the screen, change
 * the window title or hide text when the line is printed by the CLI; bidirectional override characters
 * can make text read differently from what it says. Both are replaced with the visible replacement
 * character, so the content is still shown but can no longer act.
 */
public final class TextSanitizer {

    private static final char REPLACEMENT = '�';

    private TextSanitizer() {}

    /** Returns the text with control and direction-override characters replaced; tabs are kept. */
    public static String clean(String text) {
        if (text == null) {
            return null;
        }
        int length = text.length();
        int i = 0;
        while (i < length && !isUnsafe(text.charAt(i))) {
            i++;
        }
        if (i == length) {
            return text; // the common case: nothing to change, no copy
        }
        char[] chars = text.toCharArray();
        for (; i < length; i++) {
            if (isUnsafe(chars[i])) {
                chars[i] = REPLACEMENT;
            }
        }
        return new String(chars);
    }

    static boolean isUnsafe(char c) {
        if (c < 0x20) {
            return c != '\t';
        }
        if (c < 0x7F) {
            return false;
        }
        return c == 0x7F                          // DEL
                || (c >= 0x80 && c <= 0x9F)       // C1 controls, including the one-byte CSI
                || (c >= 0x202A && c <= 0x202E)   // bidirectional embeddings and overrides
                || (c >= 0x2066 && c <= 0x2069)   // bidirectional isolates
                || c == 0x2028 || c == 0x2029;    // line / paragraph separators
    }
}
