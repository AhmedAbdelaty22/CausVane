package io.causvane.gui;

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

    WelcomePanel(Runnable onOpen, Runnable onPaste, java.util.List<java.nio.file.Path> recent,
                 java.util.function.Consumer<java.nio.file.Path> openRecent) {
        super(new GridBagLayout());
        setBackground(Theme.EDITOR);

        dropZone.setOpaque(false);
        dropZone.setLayout(new BoxLayout(dropZone, BoxLayout.Y_AXIS));
        dropZone.setBorder(BorderFactory.createEmptyBorder(44, 64, 44, 64));

        JLabel arrow = new JLabel(new Glyphs.DropArrow(56, Theme.BLUE));
        JLabel title = new JLabel("Drop a log file here");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 24f));
        title.setForeground(Theme.TEXT);
        JLabel subtitle = new JLabel("New to logs? CausVane finds every problem, explains it in plain words and walks you through the lines behind it.");
        subtitle.setForeground(Theme.DIM);
        subtitle.setFont(subtitle.getFont().deriveFont(13.5f));

        JButton open = new JButton("Open Log File");
        open.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
        open.setFont(open.getFont().deriveFont(Font.BOLD, 14f));
        open.setMargin(new java.awt.Insets(8, 22, 8, 22));
        open.addActionListener(e -> onOpen.run());
        JButton paste = new JButton("Paste Log Text");
        paste.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
        paste.setFont(paste.getFont().deriveFont(14f));
        paste.setMargin(new java.awt.Insets(8, 22, 8, 22));
        paste.setToolTipText("Analyse log lines copied from a chat, ticket or e-mail, without saving a file first");
        paste.addActionListener(e -> onPaste.run());
        javax.swing.JPanel buttons = new javax.swing.JPanel();
        buttons.setOpaque(false);
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(open);
        buttons.add(Box.createHorizontalStrut(10));
        buttons.add(paste);

        boolean mac = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
        String key = mac ? "\u2318" : "Ctrl+";
        String shiftKey = mac ? "\u21e7\u2318" : "Ctrl+Shift+";
        JLabel hints = new JLabel(key + "O  open a file      " + shiftKey + "V  paste log text      " + key + "K  quick actions");
        hints.setForeground(Theme.DIM);
        hints.setFont(hints.getFont().deriveFont(12f));

        for (Component c : new Component[] {arrow, Box.createVerticalStrut(6), title, Box.createVerticalStrut(8), subtitle,
                Box.createVerticalStrut(24), buttons, Box.createVerticalStrut(22), hints}) {
            if (c instanceof javax.swing.JComponent jc) {
                jc.setAlignmentX(Component.CENTER_ALIGNMENT);
            }
            dropZone.add(c);
        }
        if (!recent.isEmpty()) {
            recentParts.add(dropZone.add(Box.createVerticalStrut(26)));
            JLabel heading = new JLabel("RECENT FILES");
            heading.setForeground(Theme.DIM);
            heading.setFont(heading.getFont().deriveFont(Font.BOLD, 11f));
            heading.setAlignmentX(Component.CENTER_ALIGNMENT);
            recentParts.add(dropZone.add(heading));
            recentParts.add(dropZone.add(Box.createVerticalStrut(6)));
            for (java.nio.file.Path file : recent.subList(0, Math.min(5, recent.size()))) {
                JButton entry = new JButton(file.getFileName().toString());
                entry.putClientProperty(Theme.HTML_DISABLE, Boolean.TRUE);
                entry.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
                entry.setForeground(Theme.BLUE);
                entry.setToolTipText(Theme.literal(file.toAbsolutePath().toString()));
                entry.setAlignmentX(Component.CENTER_ALIGNMENT);
                entry.addActionListener(e -> openRecent.accept(file));
                recentParts.add(dropZone.add(entry));
            }
            JButton clear = new JButton("Clear recent files");
            clear.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
            clear.setForeground(Theme.DIM);
            clear.setFont(clear.getFont().deriveFont(11.5f));
            clear.setToolTipText("Forget the list of recently opened files (the files themselves are not touched)");
            clear.setAlignmentX(Component.CENTER_ALIGNMENT);
            clear.addActionListener(e -> {
                Settings.get().clearRecent();
                clearRecent();
            });
            recentParts.add(dropZone.add(Box.createVerticalStrut(4)));
            recentParts.add(dropZone.add(clear));
        }
        add(dropZone);
    }

    private final java.util.List<Component> recentParts = new java.util.ArrayList<>();

    /** Removes the recent-files list from the screen, after the list was cleared. */
    void clearRecent() {
        recentParts.forEach(dropZone::remove);
        recentParts.clear();
        revalidate();
        repaint();
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
