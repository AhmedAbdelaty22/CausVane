package io.causvane.gui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.icons.FlatSearchIcon;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
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
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Quick actions: one search box that finds every action of the window and every problem in the list,
 * arranged in sections. Type a few letters, press Enter. Opened with Ctrl/Cmd+K.
 */
final class CommandPalette extends JDialog {

    /**
     * One entry.
     *
     * @param section  heading it is listed under, such as "Actions" or "Problems"
     * @param title    what is shown; may contain text from the log
     * @param hint     short note on the right, such as a shortcut or a count
     * @param keywords extra words it can be found by, not shown
     */
    record Command(String section, String title, String hint, String keywords, Runnable action) {}

    /** A row of the list: either a section heading or a command. */
    private record Row(String heading, Command command) {}

    /** Entries listed per section before "type to see more". */
    private static final int MAX_PER_SECTION = 8;
    private static final int ROW_HEIGHT = 34;
    private static final int HEADING_HEIGHT = 26;

    private final List<Command> commands;
    private final DefaultListModel<Row> model = new DefaultListModel<>();
    private final JList<Row> list = new JList<>(model);
    private final JTextField field = new JTextField();

    private CommandPalette(JFrame owner, List<Command> commands) {
        super(owner, false);
        this.commands = commands;
        setUndecorated(true);

        field.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Search actions and problems…");
        field.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, new FlatSearchIcon());
        field.setFont(field.getFont().deriveFont(15f));
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.CARD_BORDER),
                BorderFactory.createEmptyBorder(12, 12, 12, 12)));
        field.setBackground(Theme.CARD);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(Theme.CARD);
        list.setCellRenderer(new RowRenderer());
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int index = list.locationToIndex(e.getPoint());
                if (index >= 0 && model.get(index).command() != null) {
                    list.setSelectedIndex(index);
                    run();
                }
            }
        });
        // entries are cut to the width of the box, so it never needs to scroll sideways
        JScrollPane scroll = new JScrollPane(list, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        scroll.getViewport().setBackground(Theme.CARD);
        scroll.setBackground(Theme.CARD);
        scroll.getVerticalScrollBar().setUnitIncrement(ROW_HEIGHT);

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

    /** Opens Quick actions over the upper part of the window. */
    static void open(JFrame owner, List<Command> commands) {
        CommandPalette palette = new CommandPalette(owner, commands);
        int width = Math.min(620, owner.getWidth() - 80);
        palette.setSize(new Dimension(width, 460));
        palette.setLocation(owner.getX() + (owner.getWidth() - width) / 2, owner.getY() + Math.max(70, owner.getHeight() / 8));
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

    /** Keeps the commands that contain every typed word, grouped under their section headings. */
    private void filter() {
        String query = field.getText().strip().toLowerCase(Locale.ROOT);
        String[] words = query.isEmpty() ? new String[0] : query.split("\\s+");
        Map<String, List<Command>> bySection = new LinkedHashMap<>();
        Map<String, Integer> hidden = new LinkedHashMap<>();
        for (Command command : commands) {
            String haystack = (command.title() + " " + command.hint() + " " + command.keywords()).toLowerCase(Locale.ROOT);
            boolean matches = true;
            for (String word : words) {
                if (!haystack.contains(word)) {
                    matches = false;
                    break;
                }
            }
            if (!matches) {
                continue;
            }
            List<Command> section = bySection.computeIfAbsent(command.section(), s -> new ArrayList<>());
            // only the problem list can be long; actions are always shown in full
            int limit = "Problems".equals(command.section()) ? MAX_PER_SECTION : 100;
            if (section.size() < limit) {
                section.add(command);
            } else {
                hidden.merge(command.section(), 1, Integer::sum);
            }
        }
        model.clear();
        for (Map.Entry<String, List<Command>> section : bySection.entrySet()) {
            int more = hidden.getOrDefault(section.getKey(), 0);
            model.addElement(new Row(section.getKey().toUpperCase(Locale.ROOT)
                    + (more > 0 ? "   ·   " + more + " more, type to narrow" : ""), null));
            section.getValue().forEach(c -> model.addElement(new Row(null, c)));
        }
        list.clearSelection();
        move(1);
        SwingUtilities.invokeLater(() -> list.ensureIndexIsVisible(0)); // keep the first heading in view
    }

    /** Moves the selection to the next command in the given direction, skipping headings. */
    private void move(int delta) {
        int size = model.size();
        if (size == 0) {
            return;
        }
        int index = list.getSelectedIndex();
        for (int step = 0; step < size; step++) {
            index = index < 0 ? (delta > 0 ? 0 : size - 1) : Math.floorMod(index + delta, size);
            if (model.get(index).command() != null) {
                list.setSelectedIndex(index);
                list.ensureIndexIsVisible(index);
                return;
            }
        }
    }

    private void run() {
        Row row = list.getSelectedValue();
        dispose();
        if (row != null && row.command() != null) {
            SwingUtilities.invokeLater(row.command().action());
        }
    }

    /** Paints a heading or a command, cutting long text with an ellipsis. */
    private static final class RowRenderer extends JComponent implements ListCellRenderer<Row> {
        private Row row;
        private boolean selected;
        private int width;

        @Override
        public Component getListCellRendererComponent(JList<? extends Row> list, Row value, int index,
                                                      boolean isSelected, boolean focused) {
            row = value;
            selected = isSelected && value.command() != null;
            width = list.getWidth();
            setFont(list.getFont());
            return this;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(Math.max(1, width), row != null && row.command() == null ? HEADING_HEIGHT : ROW_HEIGHT);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            if (row.command() == null) {
                Font font = getFont().deriveFont(Font.BOLD, 10.5f);
                FontMetrics fm = g2.getFontMetrics(font);
                g2.setFont(font);
                g2.setColor(Theme.DIM);
                g2.drawString(TableRenderers.clip(row.heading(), fm, w - 24), 12, h - 7);
                g2.dispose();
                return;
            }
            if (selected) {
                g2.setColor(Theme.SELECTION);
                g2.fillRoundRect(2, 1, w - 4, h - 2, 8, 8);
            }
            Font hintFont = getFont().deriveFont(Font.PLAIN, 11.5f);
            FontMetrics hintMetrics = g2.getFontMetrics(hintFont);
            String hint = row.command().hint() == null ? "" : row.command().hint();
            int hintWidth = Math.min(hintMetrics.stringWidth(hint), w / 3);
            Font titleFont = getFont().deriveFont(13f);
            FontMetrics titleMetrics = g2.getFontMetrics(titleFont);
            int baseline = (h - titleMetrics.getHeight()) / 2 + titleMetrics.getAscent();
            g2.setFont(titleFont);
            g2.setColor(selected && Theme.mode() == Theme.Mode.DARK ? java.awt.Color.WHITE : Theme.TEXT);
            g2.drawString(TableRenderers.clip(row.command().title(), titleMetrics, w - 36 - hintWidth), 14, baseline);
            g2.setFont(hintFont);
            g2.setColor(Theme.DIM);
            String shownHint = TableRenderers.clip(hint, hintMetrics, w / 3);
            g2.drawString(shownHint, w - 14 - hintMetrics.stringWidth(shownHint), baseline);
            g2.dispose();
        }
    }
}
