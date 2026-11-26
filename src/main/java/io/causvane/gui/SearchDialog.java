package io.causvane.gui;

import com.formdev.flatlaf.FlatClientProperties;
import io.causvane.explain.WebSearch;
import io.causvane.model.ErrorGroup;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.KeyEvent;

/**
 * Shows exactly what would be sent to a search site, with anything identifying already taken out, and lets
 * the reader edit it and choose the site. Nothing leaves the computer until Search is clicked.
 */
final class SearchDialog {

    private SearchDialog() {}

    static void show(JFrame owner, ErrorGroup group) {
        WebSearch.Query query = WebSearch.forProblem(group);
        JDialog dialog = new JDialog(owner, "Search the Web", true);

        JLabel intro = new JLabel("<html><b>This is all that will be sent to the search site.</b> Change it as you like; "
                + "nothing is sent until you click Search.</html>");
        JTextArea text = new JTextArea(query.text(), 3, 44);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setFont(Theme.mono(13f));
        text.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        // Enter searches; the text is one line of words
        text.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "search");
        JLabel removed = new JLabel("<html>" + esc(WebSearch.describeRemoved(query.removed())
                + " Plain words from the message can still name your systems: read it once before searching.") + "</html>");
        removed.setForeground(Theme.DIM);
        removed.setFont(removed.getFont().deriveFont(11.5f));

        JComboBox<WebSearch.Engine> engine = new JComboBox<>(WebSearch.Engine.values());
        engine.setSelectedItem(Settings.get().searchEngine());
        engine.setToolTipText("Which site to search; remembered for next time");
        JButton cancel = new JButton("Cancel");
        JButton search = new JButton("Search");
        search.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
        Buttons.style(cancel, Buttons.Role.NEUTRAL);
        Buttons.style(search, Buttons.Role.WEB);
        Runnable go = () -> {
            String words = text.getText().replaceAll("\\s+", " ").strip();
            if (words.isEmpty()) {
                text.requestFocusInWindow();
                return;
            }
            WebSearch.Engine chosen = (WebSearch.Engine) engine.getSelectedItem();
            Settings.get().searchEngine(chosen);
            dialog.dispose();
            AppMenuBar.browse(chosen.url(words));
        };
        search.addActionListener(e -> go.run());
        cancel.addActionListener(e -> dialog.dispose());
        text.getActionMap().put("search", new javax.swing.AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { go.run(); }
        });
        text.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void update() { search.setEnabled(!text.getText().isBlank()); }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { update(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { update(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { update(); }
        });

        JPanel field = new JPanel(new BorderLayout());
        field.setBorder(BorderFactory.createLineBorder(Theme.CARD_BORDER));
        field.add(text);

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.add(new JLabel("Search on "));
        engine.setMaximumSize(engine.getPreferredSize());
        buttons.add(engine);
        buttons.add(Box.createHorizontalGlue());
        buttons.add(cancel);
        buttons.add(Box.createHorizontalStrut(8));
        buttons.add(search);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(18, 20, 16, 20));
        for (JComponent c : new JComponent[] {intro, field, removed, buttons}) {
            c.setAlignmentX(JComponent.LEFT_ALIGNMENT);
        }
        intro.setFont(intro.getFont().deriveFont(Font.PLAIN, 13f));
        body.add(intro);
        body.add(Box.createVerticalStrut(10));
        body.add(field);
        body.add(Box.createVerticalStrut(8));
        body.add(removed);
        body.add(Box.createVerticalStrut(16));
        body.add(buttons);

        dialog.setContentPane(body);
        dialog.getRootPane().setDefaultButton(search);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(520, dialog.getHeight()));
        dialog.setSize(new Dimension(Math.max(560, dialog.getWidth()), dialog.getHeight()));
        dialog.setLocationRelativeTo(owner);
        text.requestFocusInWindow();
        text.selectAll();
        dialog.setVisible(true);
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
