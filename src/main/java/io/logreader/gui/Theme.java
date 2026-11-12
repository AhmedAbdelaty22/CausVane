package io.logreader.gui;

import io.logreader.model.Severity;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;

/** Colors and fonts of the dark workspace. */
final class Theme {

    static final Color CARD = new Color(0x2B2D30);
    static final Color CARD_BORDER = new Color(0x3C3F41);
    static final Color EDITOR = new Color(0x1E1F22);
    static final Color TEXT = new Color(0xDFE1E5);
    static final Color DIM = new Color(0x8A8F98);
    static final Color ORANGE = new Color(0xFFA53C);
    static final Color RED = new Color(0xFF5F56);
    static final Color PIN_BACKGROUND = new Color(0xC62828);
    static final Color EVENT_BACKGROUND = new Color(0x33272A);
    static final Color GREEN = new Color(0x6AAB73);
    static final Color BLUE = new Color(0x56A8F5);
    static final Color PURPLE = new Color(0xC77DBB);
    static final Color YELLOW = new Color(0xE0B341);
    static final Color MAGENTA = new Color(0xD64FB3);

    private Theme() {}

    static Color severity(Severity severity) {
        return switch (severity) {
            case FATAL -> MAGENTA;
            case ERROR -> RED;
            case WARN -> YELLOW;
            case INFO -> BLUE;
            default -> DIM;
        };
    }

    static Font mono(float size) {
        Font base = UIManager.getFont("monospaced.font");
        if (base == null) {
            base = new Font(Font.MONOSPACED, Font.PLAIN, 13);
        }
        return base.deriveFont(size);
    }

    /**
     * Swing renders any label or tooltip text beginning with {@code <html>} as HTML, which could fetch remote
     * images. Text taken from a log file must go through this so it is always shown literally.
     */
    static String literal(String text) {
        if (text != null && text.stripLeading().regionMatches(true, 0, "<html", 0, 5)) {
            return "\u200B" + text.stripLeading();
        }
        return text;
    }

    /** Client property that turns off HTML rendering for a Swing component. */
    static final String HTML_DISABLE = "html.disable";

    static String hex(Color c) {
        return String.format(java.util.Locale.ROOT, "#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}
