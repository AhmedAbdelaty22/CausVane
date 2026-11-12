package io.logreader.gui;

import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.RenderingHints;

/** Start screen shown until a log is opened: a large drop zone with an Open button. */
final class WelcomePanel extends JPanel {

    private final JPanel dropZone = new JPanel();

    WelcomePanel(Runnable onOpen) {
        super(new GridBagLayout());
        setBackground(Theme.EDITOR);

        dropZone.setOpaque(false);
        dropZone.setLayout(new BoxLayout(dropZone, BoxLayout.Y_AXIS));
        dropZone.setBorder(BorderFactory.createEmptyBorder(44, 64, 44, 64));

        JLabel arrow = new JLabel(new Glyphs.DropArrow(56, Theme.ORANGE));
        JLabel title = new JLabel("Drop a log file here");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        title.setForeground(Theme.TEXT);
        JLabel subtitle = new JLabel("LogReader finds every error, explains what it means and pinpoints where it started.");
        subtitle.setForeground(Theme.DIM);
        subtitle.setFont(subtitle.getFont().deriveFont(13.5f));

        JButton open = new JButton("Open Log File");
        open.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
        open.setFont(open.getFont().deriveFont(Font.BOLD, 14f));
        open.setMargin(new java.awt.Insets(8, 22, 8, 22));
        open.addActionListener(e -> onOpen.run());

        for (Component c : new Component[] {arrow, Box.createVerticalStrut(6), title, Box.createVerticalStrut(8), subtitle,
                Box.createVerticalStrut(24), open}) {
            if (c instanceof javax.swing.JComponent jc) {
                jc.setAlignmentX(Component.CENTER_ALIGNMENT);
            }
            dropZone.add(c);
        }
        add(dropZone);
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(200, 200);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int x = dropZone.getX();
        int y = dropZone.getY();
        g2.setColor(Theme.CARD);
        g2.fillRoundRect(x, y, dropZone.getWidth(), dropZone.getHeight(), 28, 28);
        g2.setColor(Theme.CARD_BORDER.brighter());
        g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] {7f, 7f}, 0f));
        g2.drawRoundRect(x, y, dropZone.getWidth() - 1, dropZone.getHeight() - 1, 28, 28);
        g2.dispose();
    }
}
