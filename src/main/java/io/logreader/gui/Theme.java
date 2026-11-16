package io.logreader.gui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.fonts.inter.FlatInterFont;
import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont;
import io.logreader.model.Severity;

import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;
import java.awt.Insets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Colors and fonts of the workspace, in a dark and a light variant.
 *
 * <p>The color fields are reassigned by {@link #apply}. Components read them when they are built or
 * painted, so the window is rebuilt after a theme change rather than patched in place.
 */
final class Theme {

    enum Mode { DARK, LIGHT }

    static Color CARD;
    static Color CARD_BORDER;
    static Color EDITOR;
    static Color TEXT;
    static Color DIM;
    static Color ORANGE;
    static Color RED;
    static Color PIN_BACKGROUND;
    static Color EVENT_BACKGROUND;
    static Color GREEN;
    static Color BLUE;
    static Color PURPLE;
    static Color YELLOW;
    static Color MAGENTA;
    /** Line-number gutter and surrounding (non-error) log lines in the context viewer. */
    static Color GUTTER;
    static Color CONTEXT_TEXT;
    /** Background of the "what this means" callout. */
    static Color CALLOUT;
    static Color SELECTION;
    static Color HOVER;
    /** Empty part of a meter, such as the count bar. */
    static Color TRACK;
    /** Fill colors of the chart series; brighter than the matching text colors in the light theme. */
    static Color CHART_ERROR;
    static Color CHART_WARNING;

    private static Mode mode;

    static {
        palette(Mode.DARK);
    }

    private Theme() {}

    static Mode mode() {
        return mode;
    }

    /**
     * Theme to start with: {@code -Dlogreader.theme=light|dark} if given, otherwise the operating system's
     * appearance where it can be read without running another program (macOS), otherwise dark.
     */
    static Mode initialMode() {
        String requested = System.getProperty("logreader.theme", "").toLowerCase(Locale.ROOT);
        if (requested.equals("light")) {
            return Mode.LIGHT;
        }
        if (requested.equals("dark")) {
            return Mode.DARK;
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")) {
            return macUsesDarkAppearance() ? Mode.DARK : Mode.LIGHT;
        }
        return Mode.DARK;
    }

    /** macOS records dark mode as the key AppleInterfaceStyle in the user's global preferences. */
    private static boolean macUsesDarkAppearance() {
        try {
            Path prefs = Path.of(System.getProperty("user.home"), "Library", "Preferences", ".GlobalPreferences.plist");
            if (!Files.isRegularFile(prefs) || Files.size(prefs) > 4_000_000) {
                return true;
            }
            String content = new String(Files.readAllBytes(prefs), StandardCharsets.ISO_8859_1);
            String key = "AppleInterfaceStyle";
            for (int at = content.indexOf(key); at >= 0; at = content.indexOf(key, at + 1)) {
                if (!content.startsWith("SwitchesAutomatically", at + key.length())) {
                    return true;
                }
            }
            return false;
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }

    /** Installs the look and feel, fonts and palette for the given mode. Call on the event thread. */
    static void apply(Mode newMode) {
        palette(newMode);
        boolean dark = newMode == Mode.DARK;

        FlatInterFont.install();
        FlatJetBrainsMonoFont.install();
        FlatLaf.setPreferredFontFamily(FlatInterFont.FAMILY);
        FlatLaf.setPreferredSemiboldFontFamily(FlatInterFont.FAMILY_SEMIBOLD);
        FlatLaf.setPreferredMonospacedFontFamily(FlatJetBrainsMonoFont.FAMILY);
        FlatLaf.setGlobalExtraDefaults(dark
                ? Map.of("@background", "#1F2024", "@accentColor", "#4C8DF6")
                : Map.of("@background", "#F4F5F7", "@accentColor", "#2F6FEB"));
        if (dark) {
            FlatDarkLaf.setup();
        } else {
            FlatLightLaf.setup();
        }

        UIManager.put("Component.arc", 10);
        UIManager.put("Button.arc", 10);
        UIManager.put("TextComponent.arc", 10);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
        UIManager.put("ScrollBar.showButtons", false);
        UIManager.put("Table.showHorizontalLines", true);
        UIManager.put("Table.showVerticalLines", false);
        UIManager.put("Table.gridColor", CARD_BORDER);
        UIManager.put("Table.background", CARD);
        UIManager.put("Table.selectionBackground", SELECTION);
        UIManager.put("Table.selectionInactiveBackground", SELECTION);
        UIManager.put("Table.selectionForeground", TEXT);
        UIManager.put("Table.selectionInactiveForeground", TEXT);
        UIManager.put("TableHeader.height", 32);
        UIManager.put("TableHeader.background", CARD);
        UIManager.put("TableHeader.foreground", DIM);
        UIManager.put("TableHeader.separatorColor", CARD);
        UIManager.put("TableHeader.bottomSeparatorColor", CARD_BORDER);
        UIManager.put("SplitPaneDivider.gripDotCount", 0);
        UIManager.put("SplitPane.dividerSize", 7);
        UIManager.put("ToolTip.background", dark ? new Color(0x33353A) : new Color(0x1F2328));
        UIManager.put("ToolTip.foreground", dark ? TEXT : Color.WHITE);
        UIManager.put("PopupMenu.borderCornerRadius", 10);
    }

    private static void palette(Mode newMode) {
        mode = newMode;
        if (newMode == Mode.DARK) {
            CARD = new Color(0x2A2C30);
            CARD_BORDER = new Color(0x3A3D42);
            EDITOR = new Color(0x1B1C1F);
            TEXT = new Color(0xE3E5E9);
            DIM = new Color(0x8C919B);
            ORANGE = new Color(0xFFA53C);
            RED = new Color(0xFF6259);
            PIN_BACKGROUND = new Color(0xC62828);
            EVENT_BACKGROUND = new Color(0x33272A);
            GREEN = new Color(0x6FBF7A);
            BLUE = new Color(0x5BA9F7);
            PURPLE = new Color(0xC88AE0);
            YELLOW = new Color(0xE5B847);
            MAGENTA = new Color(0xE868C0);
            GUTTER = new Color(0x5F646C);
            CONTEXT_TEXT = new Color(0xA9ADB5);
            CALLOUT = new Color(0x232529);
            SELECTION = new Color(0x2C3F63);
            HOVER = new Color(0x303238);
            TRACK = new Color(255, 255, 255, 24);
            CHART_ERROR = new Color(0xFF6259);
            CHART_WARNING = new Color(0xE5B847);
        } else {
            CARD = new Color(0xFFFFFF);
            CARD_BORDER = new Color(0xE1E4E9);
            EDITOR = new Color(0xFBFBFC);
            TEXT = new Color(0x1F2328);
            DIM = new Color(0x667085);
            ORANGE = new Color(0xB9500A);
            RED = new Color(0xD1281C);
            PIN_BACKGROUND = new Color(0xD1281C);
            EVENT_BACKGROUND = new Color(0xFDEEEC);
            GREEN = new Color(0x1A7F37);
            BLUE = new Color(0x1B5FCC);
            PURPLE = new Color(0x7A3FD1);
            YELLOW = new Color(0x9A5B06);
            MAGENTA = new Color(0xAD2069);
            GUTTER = new Color(0x9AA1AD);
            CONTEXT_TEXT = new Color(0x59626F);
            CALLOUT = new Color(0xF3F5F8);
            SELECTION = new Color(0xDCE8FD);
            HOVER = new Color(0xF1F3F6);
            TRACK = new Color(0, 0, 0, 22);
            CHART_ERROR = new Color(0xE5483C);
            CHART_WARNING = new Color(0xE9A51B);
        }
    }

    static Color severity(Severity severity) {
        return switch (severity) {
            case FATAL -> MAGENTA;
            case ERROR -> RED;
            case WARN -> YELLOW;
            case INFO -> BLUE;
            default -> DIM;
        };
    }

    /** The color at low opacity: the background of a soft badge whose text is the color itself. */
    static Color tint(Color color) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), mode == Mode.DARK ? 46 : 30);
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
            return "​" + text.stripLeading();
        }
        return text;
    }

    /** Client property that turns off HTML rendering for a Swing component. */
    static final String HTML_DISABLE = "html.disable";

    static String hex(Color c) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}
