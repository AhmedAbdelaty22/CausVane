package io.causvane.gui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import com.formdev.flatlaf.fonts.inter.FlatInterFont;
import com.formdev.flatlaf.fonts.jetbrains_mono.FlatJetBrainsMonoFont;
import io.causvane.model.Severity;

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
    /** Names of code: files, functions, classes. */
    static Color CODE;
    /** Background of a hovered or selected bar in the chart. */
    static Color HOVER_STRONG;
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
     * Theme to start with: {@code -Dcausvane.theme=light|dark} if given, otherwise the operating system's
     * appearance where it can be read without running another program (macOS), otherwise dark.
     */
    static Mode initialMode() {
        String requested = System.getProperty("causvane.theme", "").toLowerCase(Locale.ROOT);
        if (requested.equals("light")) {
            return Mode.LIGHT;
        }
        if (requested.equals("dark")) {
            return Mode.DARK;
        }
        Mode saved = Settings.get().theme();
        if (saved != null) {
            return saved; // the theme the user last picked with the switch
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
                ? Map.of("@background", "#191919", "@accentColor", "#4C8BD6")
                : Map.of("@background", "#F6F6F7", "@accentColor", "#1F5FBF"));
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
        UIManager.put("TableHeader.height", 32);
        UIManager.put("TableHeader.background", CARD);
        UIManager.put("TableHeader.foreground", DIM);
        UIManager.put("TableHeader.separatorColor", CARD);
        UIManager.put("TableHeader.bottomSeparatorColor", CARD_BORDER);
        UIManager.put("SplitPaneDivider.gripDotCount", 0);
        UIManager.put("SplitPane.dividerSize", 7);
        UIManager.put("ToolTip.background", dark ? new Color(0x252526) : new Color(0x1F2328));
        UIManager.put("Table.selectionForeground", TEXT);
        UIManager.put("Table.selectionInactiveForeground", TEXT);
        UIManager.put("ToolTip.foreground", dark ? TEXT : Color.WHITE);
        UIManager.put("PopupMenu.borderCornerRadius", 10);
    }

    private static void palette(Mode newMode) {
        mode = newMode;
        if (newMode == Mode.DARK) {
            // neutral greys for surfaces and text; bright, clearly different colours for meaning:
            // violet fatal, red errors, amber warnings, blue actions, green for the application's own code
            CARD = new Color(0x1E1E1E);
            CARD_BORDER = new Color(0x2C2C2C);
            EDITOR = new Color(0x191919);
            TEXT = new Color(0xDADADA);
            DIM = new Color(0x8E8E8E);
            ORANGE = new Color(0xFF9142);      // the "error" entries of the overview and causes
            RED = new Color(0xFF5A5A);
            PIN_BACKGROUND = new Color(0x7A2424);
            EVENT_BACKGROUND = new Color(0x241E1E);
            GREEN = new Color(0x3DD68C);
            BLUE = new Color(0x4DA3FF);
            PURPLE = DIM;
            YELLOW = new Color(0xFFC547);
            MAGENTA = new Color(0xC77DFF);    // fatal: violet, distinct from errors
            GUTTER = new Color(0x666666);
            CONTEXT_TEXT = new Color(0x9A9A9A);
            CALLOUT = new Color(0x252525);
            SELECTION = new Color(0x2E3135);
            HOVER = new Color(0x262626);
            HOVER_STRONG = new Color(0x2A2A2A);
            CODE = new Color(0x4EC9B0);
            TRACK = new Color(255, 255, 255, 18);
            CHART_ERROR = new Color(0xFF5A5A);
            CHART_WARNING = new Color(0xFFC547);
        } else {
            CARD = new Color(0xFFFFFF);
            CARD_BORDER = new Color(0xE7E8EA);
            EDITOR = new Color(0xFCFCFC);
            TEXT = new Color(0x202124);
            DIM = new Color(0x6E7277);
            ORANGE = new Color(0xE5620A);
            RED = new Color(0xE02D2D);
            PIN_BACKGROUND = new Color(0xE02D2D);
            EVENT_BACKGROUND = new Color(0xFBF1F0);
            GREEN = new Color(0x13A150);
            BLUE = new Color(0x1A6FE6);
            PURPLE = DIM;
            YELLOW = new Color(0xB57A00);
            MAGENTA = new Color(0x9440DD);    // fatal: violet, distinct from errors
            GUTTER = new Color(0xA0A4A9);
            CONTEXT_TEXT = new Color(0x5F6368);
            CALLOUT = new Color(0xF5F6F7);
            SELECTION = new Color(0xECEEF1);
            HOVER = new Color(0xF6F7F8);
            HOVER_STRONG = new Color(0xF0F1F3);
            CODE = new Color(0x0A7C72);
            TRACK = new Color(0, 0, 0, 18);
            CHART_ERROR = new Color(0xE5413C);
            CHART_WARNING = new Color(0xF5B83D);
        }
    }

    static Color severity(Severity severity) {
        return switch (severity) {
            case FATAL -> MAGENTA;
            case ERROR -> RED;
            case WARN -> YELLOW;
            case INFO -> DIM;
            default -> DIM;
        };
    }

    /** The color at low opacity: the background of a soft badge whose text is the color itself. */
    static Color tint(Color color) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), mode == Mode.DARK ? 46 : 30);
    }

    /** A faint, fully opaque version of the colour on the current editor background (for HTML, which has no alpha). */
    static Color tintOpaque(Color color) {
        Color base = EDITOR;
        float f = mode == Mode.DARK ? 0.16f : 0.09f;
        return new Color(Math.round(base.getRed() + (color.getRed() - base.getRed()) * f),
                Math.round(base.getGreen() + (color.getGreen() - base.getGreen()) * f),
                Math.round(base.getBlue() + (color.getBlue() - base.getBlue()) * f));
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

    /** A file name with a source-code extension, optionally with a line number: OrderService.java:131. */
    private static final java.util.regex.Pattern CODE_FILE = java.util.regex.Pattern.compile(
            "(?<![\\w/.-])((?:[\\w.-]+/)*[\\w-]+\\.(?:java|kt|kts|scala|groovy|py|js|mjs|cjs|ts|tsx|jsx|go|rb|php|rs|cs|vb|fs|cpp|cc|cxx|c|h|hpp|m|mm|swift|dart|ex|exs|erl|clj|lua|pl|sh|sql)(?::\\d+(?::\\d+)?)?)(?![\\w.])");
    /** A name like OrderService.placeOrder, pkg::fn or handler(): no spaces, joined by dots, colons or slashes. */
    private static final java.util.regex.Pattern CODE_NAME = java.util.regex.Pattern.compile(
            "[A-Za-z_$][\\w$<>-]*(?:(?:\\.|::|/|#)[A-Za-z_$<][\\w$<>-]*)+(?:\\(\\))?|[A-Za-z_$][\\w$]*\\(\\)");

    /** Whether a quoted piece of text is the name of a file or a piece of code rather than words. */
    static boolean looksLikeCode(String text) {
        String t = text.strip();
        return !t.isEmpty() && !t.contains(" ") && (CODE_FILE.matcher(t).matches() || CODE_NAME.matcher(t).matches());
    }

    /**
     * Wraps file names in already escaped HTML in a span of the given class, so code stands apart from
     * the words around it. Runs on escaped text, so it never touches markup.
     */
    static String markFiles(String escapedHtml, String cssClass) {
        return keepSpaceBefore(CODE_FILE.matcher(escapedHtml).replaceAll("<span class='" + cssClass + "'>$1</span>"), cssClass);
    }

    /** Swing's HTML drops a plain space right before a styled span; a fixed space keeps the words apart. */
    static String keepSpaceBefore(String html, String cssClass) {
        // a fixed space inside each end of the chip too, since Swing may still drop the one before it
        return html.replace(" <span class='" + cssClass + "'>", "&nbsp;<span class='" + cssClass + "'>")
                .replaceAll("<span class='" + cssClass + "'>([^<]*)</span>", "<span class='" + cssClass + "'>&nbsp;$1&nbsp;</span>");
    }

    /** The CSS rule for code in explanations: monospace, the code colour, on a faint chip of it. */
    static String codeRule(String cssClass, String monoFamily) {
        return "." + cssClass + " { font-family: '" + monoFamily + "'; color: " + hex(CODE) + "; background-color: "
                + hex(tintOpaqueOn(CODE, CARD)) + "; }";
    }

    static Color tintOpaqueOn(Color color, Color base) {
        float f = mode == Mode.DARK ? 0.14f : 0.08f;
        return new Color(Math.round(base.getRed() + (color.getRed() - base.getRed()) * f),
                Math.round(base.getGreen() + (color.getGreen() - base.getGreen()) * f),
                Math.round(base.getBlue() + (color.getBlue() - base.getBlue()) * f));
    }

    static String hex(Color c) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }
}
