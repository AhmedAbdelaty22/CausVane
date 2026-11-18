package io.easylog.gui;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/** A soft badge: text in a color on a faint tint of the same color, with fully rounded ends. */
final class Pill extends JComponent {

    private static final int PAD_X = 9;
    private static final int PAD_Y = 3;

    private final String text;
    private final Color color;

    Pill(String text, Color color, boolean monospaced) {
        this.text = text;
        this.color = color;
        setFont(monospaced ? Theme.mono(11.5f) : getFontOrDefault().deriveFont(Font.BOLD, 11.5f));
        setToolTipText(Theme.literal(text));
    }

    private static Font getFontOrDefault() {
        Font font = javax.swing.UIManager.getFont("Label.font");
        return font != null ? font : new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    }

    @Override
    public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        return new Dimension(fm.stringWidth(text) + 2 * PAD_X, fm.getHeight() + 2 * PAD_Y);
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
        paint((Graphics2D) g, text, color, getFont(), 0, 0, getWidth(), getHeight());
    }

    /** Draws a pill into any graphics context; table cells use this directly. */
    static void paint(Graphics2D g, String text, Color color, Font font, int x, int y, int width, int height) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setColor(Theme.tint(color));
        g2.fillRoundRect(x, y, width, height, height, height);
        g2.setColor(color);
        g2.setFont(font);
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(text, x + (width - fm.stringWidth(text)) / 2, y + (height - fm.getHeight()) / 2 + fm.getAscent());
        g2.dispose();
    }
}
