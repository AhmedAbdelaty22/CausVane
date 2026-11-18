package io.easylog.gui;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.Timer;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * A short confirmation that appears at the bottom of the window and fades away by itself, for actions
 * whose result is otherwise invisible (copied to the clipboard, report exported).
 */
final class Toast extends JComponent {

    private static final int VISIBLE_MS = 2400;
    private static final int FADE_STEP_MS = 30;

    private final String text;
    private float alpha = 0f;

    private Toast(String text) {
        this.text = text;
        setFont(getFont() == null ? new Font(Font.SANS_SERIF, Font.PLAIN, 13) : getFont().deriveFont(13f));
        putClientProperty(Theme.HTML_DISABLE, Boolean.TRUE);
    }

    /** Shows the message over the given window, replacing any toast still on screen. */
    static void show(JFrame frame, String message) {
        JLayeredPane layers = frame.getLayeredPane();
        for (java.awt.Component c : layers.getComponentsInLayer(JLayeredPane.POPUP_LAYER)) {
            if (c instanceof Toast) {
                layers.remove(c);
            }
        }
        Toast toast = new Toast(message);
        FontMetrics fm = toast.getFontMetrics(toast.getFont());
        int width = Math.min(layers.getWidth() - 40, fm.stringWidth(message) + 44);
        int height = fm.getHeight() + 20;
        toast.setBounds((layers.getWidth() - width) / 2, layers.getHeight() - height - 44, width, height);
        layers.add(toast, JLayeredPane.POPUP_LAYER);
        layers.repaint();

        long shownAt = System.currentTimeMillis();
        Timer timer = new Timer(FADE_STEP_MS, null);
        timer.addActionListener(e -> {
            long age = System.currentTimeMillis() - shownAt;
            if (age < 180) {
                toast.alpha = age / 180f;
            } else if (age < VISIBLE_MS) {
                toast.alpha = 1f;
            } else if (age < VISIBLE_MS + 300) {
                toast.alpha = 1f - (age - VISIBLE_MS) / 300f;
            } else {
                timer.stop();
                layers.remove(toast);
                layers.repaint(toast.getBounds());
                return;
            }
            toast.repaint();
        });
        timer.start();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setComposite(AlphaComposite.SrcOver.derive(Math.max(0f, Math.min(1f, alpha))));
        boolean dark = Theme.mode() == Theme.Mode.DARK;
        g2.setColor(dark ? new Color(0xECEEF2) : new Color(0x1F2328));
        g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
        g2.setColor(dark ? new Color(0x1B1C1F) : Color.WHITE);
        g2.setFont(getFont());
        FontMetrics fm = g2.getFontMetrics();
        String shown = text;
        while (shown.length() > 4 && fm.stringWidth(shown) > getWidth() - 36) {
            shown = shown.substring(0, shown.length() - 2);
        }
        if (!shown.equals(text)) {
            shown = shown + "…";
        }
        g2.drawString(shown, (getWidth() - fm.stringWidth(shown)) / 2, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
        g2.dispose();
    }
}
