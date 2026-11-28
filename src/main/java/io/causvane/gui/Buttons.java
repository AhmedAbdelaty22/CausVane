package io.causvane.gui;

import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.AbstractButton;
import javax.swing.JToggleButton;
import java.awt.Color;
import java.awt.Font;

/**
 * One look for every button in the window: the same rounded shape, height, padding and font, with a soft
 * tint of a colour that says what kind of thing the button does. Switches that choose between options
 * (severity, log view) keep their segmented look, because they are a choice, not an action.
 */
final class Buttons {

    /** What a button does, which decides its colour. */
    enum Role {
        /** Brings a log in or runs the main action of a window: Open, Paste, Analyze. Blue. */
        PRIMARY,
        /** Closes or clears something: Close, Clear. Red. */
        CLOSE,
        /** Shares or saves: Copy for a ticket, Save as PDF, the More menu of reports. Green. */
        SHARE,
        /** Leaves the application for the web: Search the web, Visit the website. Indigo. */
        WEB,
        /** Follows the file as it is written: Watch live. Amber. */
        LIVE,
        /** Moves around or backs out: Overview, previous and next, Quick actions, Cancel. Grey. */
        NEUTRAL
    }

    private Buttons() {}

    static Color color(Role role) {
        return switch (role) {
            case PRIMARY -> Theme.BLUE;
            case CLOSE -> Theme.RED;
            case SHARE -> Theme.GREEN;
            case WEB -> Theme.mode() == Theme.Mode.DARK ? new Color(0x8F96FF) : new Color(0x4F46E5);
            case LIVE -> Theme.YELLOW;
            case NEUTRAL -> Theme.DIM;
        };
    }

    /** Gives a button the shared look in its role's colour, and returns it. */
    static <B extends AbstractButton> B style(B button, Role role) {
        return style(button, role, false);
    }

    /** The same look, larger, for the main buttons of the start screen. */
    static <B extends AbstractButton> B style(B button, Role role, boolean large) {
        return style(button, role, large ? "8,24,8,24" : "4,14,4,14", large ? 14.5f : 12.5f);
    }

    /**
     * A round button with only an icon, for the tools at the right of the toolbar; the tooltip says what it
     * does. A coloured toggle (Watch live) stays grey until it is switched on.
     */
    static <B extends AbstractButton> B tool(B button, Role role, Glyphs.Line.Kind icon, String tooltip) {
        button.setText(null);
        button.setIcon(new Glyphs.Line(icon));
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        return style(button, role, "6,7,6,7", 12.5f);
    }

    private static <B extends AbstractButton> B style(B button, Role role, String margin, float fontSize) {
        Color accent = color(role);
        boolean dark = Theme.mode() == Theme.Mode.DARK;
        // a coloured toggle looks neutral while it is off and takes its colour when it is on
        boolean quietToggle = button instanceof JToggleButton && role != Role.NEUTRAL;
        boolean neutral = role == Role.NEUTRAL || quietToggle;
        Color background = neutral ? Theme.CARD : mix(Theme.CARD, accent, dark ? 0.16f : 0.10f);
        Color hover = neutral ? Theme.HOVER_STRONG : mix(Theme.CARD, accent, dark ? 0.26f : 0.18f);
        Color pressed = mix(Theme.CARD, accent, dark ? 0.34f : 0.26f);
        Color border = neutral ? Theme.CARD_BORDER : mix(Theme.CARD, accent, dark ? 0.55f : 0.45f);
        Color text = neutral ? Theme.TEXT : accent;
        button.putClientProperty(FlatClientProperties.BUTTON_TYPE, null);
        StringBuilder style = new StringBuilder()
                .append("arc: 999; borderWidth: 1; focusWidth: 0; innerFocusWidth: 0; margin: ").append(margin).append(';')
                .append(" background: ").append(Theme.hex(background)).append(';')
                .append(" foreground: ").append(Theme.hex(text)).append(';')
                .append(" borderColor: ").append(Theme.hex(border)).append(';')
                .append(" hoverBackground: ").append(Theme.hex(hover)).append(';')
                .append(" hoverBorderColor: ").append(Theme.hex(neutral ? Theme.DIM : accent)).append(';')
                .append(" pressedBackground: ").append(Theme.hex(pressed)).append(';')
                .append(" focusedBorderColor: ").append(Theme.hex(neutral ? Theme.DIM : accent)).append(';')
                .append(" disabledBackground: ").append(Theme.hex(Theme.CARD)).append(';')
                // the same look when the button is a window's default (Enter) button
                .append(" default.background: ").append(Theme.hex(background)).append(';')
                .append(" default.foreground: ").append(Theme.hex(text)).append(';')
                .append(" default.borderColor: ").append(Theme.hex(border)).append(';')
                .append(" default.hoverBackground: ").append(Theme.hex(hover)).append(';')
                .append(" default.hoverBorderColor: ").append(Theme.hex(neutral ? Theme.DIM : accent)).append(';')
                .append(" default.pressedBackground: ").append(Theme.hex(pressed)).append(';')
                .append(" default.focusedBorderColor: ").append(Theme.hex(neutral ? Theme.DIM : accent)).append(';')
                .append(" disabledBorderColor: ").append(Theme.hex(Theme.CARD_BORDER)).append(';');
        if (button instanceof JToggleButton) {
            // a toggle that is on is filled more strongly in its colour
            boolean grey = role == Role.NEUTRAL;
            Color on = mix(Theme.CARD, grey ? Theme.DIM : accent, dark ? 0.32f : 0.24f);
            style.append(" selectedBackground: ").append(Theme.hex(on)).append(';')
                    .append(" selectedForeground: ").append(Theme.hex(grey ? Theme.TEXT : accent)).append(';');
        }
        button.putClientProperty(FlatClientProperties.STYLE, style.toString());
        button.setFont(button.getFont().deriveFont(Font.BOLD, fontSize));
        return button;
    }

    /** {@code base} moved {@code amount} of the way towards {@code toward}. */
    static Color mix(Color base, Color toward, float amount) {
        return new Color(Math.round(base.getRed() + (toward.getRed() - base.getRed()) * amount),
                Math.round(base.getGreen() + (toward.getGreen() - base.getGreen()) * amount),
                Math.round(base.getBlue() + (toward.getBlue() - base.getBlue()) * amount));
    }
}
