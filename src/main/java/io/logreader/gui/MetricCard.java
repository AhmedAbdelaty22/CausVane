package io.logreader.gui;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/** Rounded summary card: caption, large value and a small detail line. */
final class MetricCard extends JPanel {

    private final JLabel value = new JLabel("—");
    private final JLabel detail = new JLabel(" ");

    MetricCard(String caption, Color accent) {
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));

        JLabel title = new JLabel(caption.toUpperCase(java.util.Locale.ROOT));
        title.setForeground(Theme.DIM);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 11f));
        value.setForeground(accent);
        value.setFont(value.getFont().deriveFont(Font.BOLD, 24f));
        detail.setForeground(Theme.DIM);
        detail.setFont(detail.getFont().deriveFont(11f));
        for (JLabel label : new JLabel[] {title, value, detail}) {
            label.setAlignmentX(Component.LEFT_ALIGNMENT);
            label.putClientProperty(Theme.HTML_DISABLE, Boolean.TRUE);
            add(label);
        }
    }

    void set(String valueText, String detailText) {
        value.setText(valueText);
        detail.setText(detailText == null || detailText.isEmpty() ? " " : detailText);
        detail.setToolTipText(Theme.literal(detailText));
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(Theme.CARD);
        g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
        g2.setColor(Theme.CARD_BORDER);
        g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14);
        g2.dispose();
        super.paintComponent(g);
    }
}
