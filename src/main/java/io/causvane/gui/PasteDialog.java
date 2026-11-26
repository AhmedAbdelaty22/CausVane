package io.causvane.gui;

import com.formdev.flatlaf.FlatClientProperties;
import io.causvane.io.PastedLog;
import io.causvane.report.Formats;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;

/**
 * An empty box to paste log lines into, for logs that arrive in a chat, a ticket or an e-mail. The text can
 * be checked and edited before it is analysed; nothing is kept until Analyze is clicked.
 */
final class PasteDialog {

    private PasteDialog() {}

    /** Opens the box; {@code analyse} receives the text when the reader clicks Analyze. */
    static void show(JFrame owner, Consumer<String> analyse) {
        JDialog dialog = new JDialog(owner, "Paste Log Text", true);
        boolean mac = AppMenuBar.mac();
        String key = mac ? "⌘" : "Ctrl+";

        JLabel intro = new JLabel("<html><b>Paste the log lines below</b> (" + key + "V), then click Analyze. "
                + "You can also type or trim the text first.</html>");
        intro.setFont(intro.getFont().deriveFont(Font.PLAIN, 13f));

        JTextArea text = new JTextArea() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (getDocument().getLength() == 0) { // a hint inside the empty box
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setColor(Theme.DIM);
                    g2.setFont(getFont());
                    int line = g2.getFontMetrics().getHeight();
                    int x = getInsets().left;
                    int y = getInsets().top + g2.getFontMetrics().getAscent();
                    g2.drawString("Paste log lines here, for example:", x, y);
                    g2.drawString("2026-03-14 10:00:01 ERROR Payment failed", x, y + line);
                    g2.drawString("java.lang.IllegalStateException: card declined", x, y + 2 * line);
                    g2.drawString("    at com.shop.PaymentService.charge(PaymentService.java:42)", x, y + 3 * line);
                    g2.dispose();
                }
            }
        };
        text.setFont(Theme.mono(12.5f));
        text.setBackground(Theme.EDITOR);
        text.setForeground(Theme.TEXT);
        text.setCaretColor(Theme.TEXT);
        text.setTabSize(4);
        text.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        JScrollPane scroll = new JScrollPane(text);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.CARD_BORDER));
        scroll.setPreferredSize(new Dimension(760, 380));

        JLabel counter = new JLabel("Empty");
        counter.setForeground(Theme.DIM);
        counter.setFont(counter.getFont().deriveFont(12f));
        JButton clear = new JButton("Clear");
        clear.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
        clear.setForeground(Theme.DIM);
        clear.setEnabled(false);
        clear.addActionListener(e -> {
            text.setText("");
            text.requestFocusInWindow();
        });
        JButton cancel = new JButton("Cancel");
        JButton analyze = new JButton("Analyze");
        analyze.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
        analyze.setFont(analyze.getFont().deriveFont(Font.BOLD));
        analyze.setToolTipText("Find and explain the problems in this text (" + key + "Enter)");
        analyze.setEnabled(false);

        text.getDocument().addDocumentListener(new DocumentListener() {
            private void update() {
                int length = text.getDocument().getLength();
                boolean any = !text.getText().isBlank();
                analyze.setEnabled(any);
                clear.setEnabled(length > 0);
                if (length == 0) {
                    counter.setText("Empty");
                } else if (length > PastedLog.MAX_CHARS) {
                    counter.setText("Too much text to paste: save it as a file and open that instead");
                    analyze.setEnabled(false);
                } else {
                    int lines = text.getLineCount();
                    counter.setText(Formats.count(lines) + (lines == 1 ? " line" : " lines") + "  ·  " + Formats.bytes(length));
                }
                text.repaint();
            }
            @Override public void insertUpdate(DocumentEvent e) { update(); }
            @Override public void removeUpdate(DocumentEvent e) { update(); }
            @Override public void changedUpdate(DocumentEvent e) { update(); }
        });

        Runnable go = () -> {
            if (!analyze.isEnabled()) {
                return;
            }
            String content = text.getText();
            dialog.dispose();
            analyse.accept(content);
        };
        analyze.addActionListener(e -> go.run());
        cancel.addActionListener(e -> dialog.dispose());
        int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        dialog.getRootPane().registerKeyboardAction(e -> go.run(), KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, menu),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(counter);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(clear);
        buttons.add(Box.createHorizontalGlue());
        buttons.add(cancel);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(analyze);

        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 18));
        body.add(intro, BorderLayout.NORTH);
        body.add(scroll, BorderLayout.CENTER);
        body.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(body);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(520, 360));
        dialog.setLocationRelativeTo(owner);
        text.requestFocusInWindow();
        dialog.setVisible(true);
    }
}
