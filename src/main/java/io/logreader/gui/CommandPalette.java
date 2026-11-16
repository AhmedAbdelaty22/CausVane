package io.logreader.gui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.icons.FlatSearchIcon;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Locale;

/**
 * Command palette: one search box that finds every action of the window and every error in the list.
 * Type a few letters, press Enter. Opened with Ctrl/Cmd+K.
 */
final class CommandPalette extends JDialog {

    /**
     * One entry of the palette.
     *
     * @param title what is shown and searched; may contain text from the log
     * @param hint  small right-aligned note such as a shortcut or a category
     */
    record Command(String title, String hint, Runnable action) {}

    private static final int MAX_SHOWN = 60;

    private final List<Command> commands;
    private final DefaultListModel<Command> model = new DefaultListModel<>();
    private final JList<Command> list = new JList<>(model);
    private final JTextField field = new JTextField();

    private CommandPalette(JFrame owner, List<Command> commands) {
        super(owner, false);
        this.commands = commands;
        setUndecorated(true);

        field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Type a command or the name of an error…");
        field.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, new FlatSearchIcon());
        field.setFont(field.getFont().deriveFont(15f));
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.CARD_BORDER),
                BorderFactory.createEmptyBorder(12, 12, 12, 12)));
        field.setBackground(Theme.CARD);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(Theme.CARD);
        list.setFixedCellHeight(36);
        list.setCellRenderer(new Row());
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                run();
            }
        });
        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        scroll.getViewport().setBackground(Theme.CARD);
        scroll.setBackground(Theme.CARD);

        JLabel footer = new JLabel("↑ ↓ to move   ·   Enter to run   ·   Esc to close");
        footer.setForeground(Theme.DIM);
        footer.setFont(footer.getFont().deriveFont(11.5f));
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.CARD_BORDER),
                BorderFactory.createEmptyBorder(7, 14, 7, 14)));

        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(Theme.CARD);
        content.setBorder(BorderFactory.createLineBorder(Theme.CARD_BORDER));
        content.add(field, BorderLayout.NORTH);
        content.add(scroll, BorderLayout.CENTER);
        content.add(footer, BorderLayout.SOUTH);
        setContentPane(content);

        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { filter(); }
            @Override public void removeUpdate(DocumentEvent e) { filter(); }
            @Override public void changedUpdate(DocumentEvent e) { filter(); }
        });
        bind(KeyEvent.VK_DOWN, () -> move(1));
        bind(KeyEvent.VK_UP, () -> move(-1));
        bind(KeyEvent.VK_ENTER, this::run);
        bind(KeyEvent.VK_ESCAPE, this::dispose);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowDeactivated(WindowEvent e) {
                dispose(); // clicking anywhere else dismisses it
            }
        });
        filter();
    }

    /** Opens the palette over the upper part of the window. */
    static void open(JFrame owner, List<Command> commands) {
        CommandPalette palette = new CommandPalette(owner, commands);
        int width = Math.min(640, owner.getWidth() - 80);
        palette.setSize(new Dimension(width, 420));
        palette.setLocation(owner.getX() + (owner.getWidth() - width) / 2, owner.getY() + Math.max(70, owner.getHeight() / 7));
        palette.setVisible(true);
        SwingUtilities.invokeLater(palette.field::requestFocusInWindow);
    }

    private void bind(int key, Runnable action) {
        JComponent root = getRootPane();
        String name = "palette-" + key;
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key, 0), name);
        root.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    /** Keeps the commands that contain every typed word, in their original order. */
    private void filter() {
        String[] words = field.getText().strip().toLowerCase(Locale.ROOT).split("\\s+");
        model.clear();
        for (Command command : commands) {
            String haystack = (command.title() + " " + command.hint()).toLowerCase(Locale.ROOT);
            boolean matches = true;
            for (String word : words) {
                if (!word.isEmpty() && !haystack.contains(word)) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                model.addElement(command);
                if (model.size() >= MAX_SHOWN) {
                    break;
                }
            }
        }
        if (!model.isEmpty()) {
            list.setSelectedIndex(0);
        }
    }

    private void move(int delta) {
        if (model.isEmpty()) {
            return;
        }
        int next = Math.floorMod(list.getSelectedIndex() + delta, model.size());
        list.setSelectedIndex(next);
        list.ensureIndexIsVisible(next);
    }

    private void run() {
        Command command = list.getSelectedValue();
        dispose();
        if (command != null) {
            SwingUtilities.invokeLater(command.action());
        }
    }

    /** One row: the title on the left, the hint on the right. */
    private static final class Row extends JPanel implements javax.swing.ListCellRenderer<Command> {
        private final DefaultListCellRenderer title = new DefaultListCellRenderer();
        private final JLabel hint = new JLabel();

        Row() {
            super(new BorderLayout(12, 0));
            setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
            title.putClientProperty(Theme.HTML_DISABLE, Boolean.TRUE); // error names come from the log
            hint.putClientProperty(Theme.HTML_DISABLE, Boolean.TRUE);
            hint.setFont(hint.getFont().deriveFont(Font.PLAIN, 11.5f));
            add(title, BorderLayout.CENTER);
            add(hint, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Command> list, Command value, int index,
                                                      boolean selected, boolean focused) {
            title.getListCellRendererComponent(list, value.title(), index, false, false);
            title.setOpaque(false);
            title.setForeground(Theme.TEXT);
            hint.setText(value.hint());
            hint.setForeground(Theme.DIM);
            setBackground(selected ? Theme.SELECTION : Theme.CARD);
            return this;
        }
    }
}
