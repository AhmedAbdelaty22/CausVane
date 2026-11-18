package io.easylog.gui;

import io.easylog.explain.IncidentLinker;
import io.easylog.model.Severity;
import io.easylog.report.Formats;

import javax.swing.JComponent;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.time.LocalDateTime;

/** Cell renderers of the error list. */
final class TableRenderers {

    /** Client property on the table holding the view row under the mouse, or absent. */
    static final String HOVER_ROW = "EasyLog.hoverRow";

    private TableRenderers() {}

    /** Row background: selection, then hover, then the plain surface. */
    static Color background(JTable table, int row, boolean selected) {
        if (selected) {
            return table.getSelectionBackground();
        }
        return Integer.valueOf(row).equals(table.getClientProperty(HOVER_ROW)) ? Theme.HOVER : table.getBackground();
    }

    /**
     * The title of an error and, beneath it, what it means in plain language.
     * Sorts by title.
     */
    record Issue(String title, String meaning, IncidentLinker.Part part) implements Comparable<Issue> {
        Issue(String title, String meaning) {
            this(title, meaning, null);
        }

        @Override
        public int compareTo(Issue other) {
            return title.compareToIgnoreCase(other.title);
        }

        @Override
        public String toString() {
            return title;
        }
    }

    /** Severity as a soft pill: colored text on a faint tint of the same color. */
    static final class SeverityBadge extends JComponent implements TableCellRenderer {
        private Severity severity = Severity.UNKNOWN;
        private Color background;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            severity = value instanceof Severity s ? s : Severity.UNKNOWN;
            background = background(table, row, selected);
            setFont(table.getFont().deriveFont(Font.BOLD, 10.5f));
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g;
            g2.setColor(background);
            g2.fillRect(0, 0, getWidth(), getHeight());
            String text = severity.name();
            FontMetrics fm = g2.getFontMetrics(getFont());
            int width = Math.min(getWidth() - 12, fm.stringWidth(text) + 18);
            int height = fm.getHeight() + 6;
            Pill.paint(g2, text, Theme.severity(severity), getFont(), 12, (getHeight() - height) / 2, width, height);
        }
    }

    /** Two lines: the error's name in bold and its plain-language meaning underneath. */
    static final class IssueCell extends JComponent implements TableCellRenderer {
        private Issue issue = new Issue("", "");
        private Color background;
        private Font titleFont;
        private Font meaningFont;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            issue = value instanceof Issue i ? i : new Issue(String.valueOf(value), "");
            background = background(table, row, selected);
            titleFont = table.getFont().deriveFont(Font.BOLD, 13f);
            meaningFont = Theme.mono(11f);
            setToolTipText(Theme.literal(issue.title() + (issue.meaning().isEmpty() ? "" : " — " + issue.meaning())));
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(background);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int x = 6;
            int width = getWidth() - x - 8;
            FontMetrics titleMetrics = g2.getFontMetrics(titleFont);
            FontMetrics meaningMetrics = g2.getFontMetrics(meaningFont);
            int block = titleMetrics.getHeight() + meaningMetrics.getHeight();
            int top = (getHeight() - block) / 2;
            String tag = tag(issue.part());
            Font tagFont = titleFont.deriveFont(Font.BOLD, 10f);
            FontMetrics tagMetrics = g2.getFontMetrics(tagFont);
            int tagWidth = tag == null ? 0 : tagMetrics.stringWidth(tag) + 14;
            g2.setFont(titleFont);
            g2.setColor(Theme.TEXT);
            // what it means first, in plain words; the technical name underneath
            String plain = issue.meaning().isBlank() ? issue.title() : issue.meaning();
            String title = clip(plain, titleMetrics, width - (tag == null ? 0 : tagWidth + 8));
            g2.drawString(title, x, top + titleMetrics.getAscent());
            if (tag != null) {
                int tagHeight = tagMetrics.getHeight() + 2;
                Pill.paint(g2, tag, tagColor(issue.part()), tagFont, x + titleMetrics.stringWidth(title) + 8,
                        top + (titleMetrics.getHeight() - tagHeight) / 2, tagWidth, tagHeight);
            }
            g2.setFont(meaningFont);
            g2.setColor(Theme.DIM);
            g2.drawString(clip(issue.meaning().isBlank() ? "" : issue.title(), meaningMetrics, width), x,
                    top + titleMetrics.getHeight() + meaningMetrics.getAscent());
            g2.dispose();
        }
    }

    static String tag(IncidentLinker.Part part) {
        if (part == null) {
            return null;
        }
        return switch (part) {
            case LEAD -> "FIRST FAILURE";
            case FOLLOWER -> "KNOCK-ON";
            case EARLY_WARNING -> "WARNING SIGN";
        };
    }

    static Color tagColor(IncidentLinker.Part part) {
        return switch (part) {
            case LEAD -> Theme.RED;
            case FOLLOWER -> Theme.BLUE;
            case EARLY_WARNING -> Theme.YELLOW;
        };
    }

    /** Occurrence count with a proportional bar under it, so the most frequent errors stand out. */
    static final class CountBar extends JComponent implements TableCellRenderer {
        private long value;
        private long max = 1;
        private Color background;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object cell, boolean selected, boolean focus,
                                                       int row, int column) {
            value = cell instanceof Long n ? n : 0;
            max = table.getModel() instanceof ErrorTableModel model ? Math.max(1, model.maxCount()) : 1;
            background = background(table, row, selected);
            setFont(table.getFont().deriveFont(Font.BOLD, 13f));
            setToolTipText(Formats.count(value) + (value == 1 ? " occurrence" : " occurrences"));
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(background);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int track = getWidth() - 16;
            int barHeight = 4;
            FontMetrics fm = g2.getFontMetrics(getFont());
            int block = fm.getHeight() + 3 + barHeight;
            int top = (getHeight() - block) / 2;
            String text = Formats.count(value);
            g2.setFont(getFont());
            g2.setColor(Theme.TEXT);
            g2.drawString(text, getWidth() - 8 - fm.stringWidth(text), top + fm.getAscent());
            int y = top + fm.getHeight() + 3;
            g2.setColor(Theme.TRACK);
            g2.fillRoundRect(8, y, track, barHeight, barHeight, barHeight);
            g2.setColor(Theme.DIM);
            g2.fillRoundRect(8, y, (int) Math.max(4, Math.round(track * (value / (double) max))), barHeight, barHeight, barHeight);
            g2.dispose();
        }
    }

    /** Text cell with optional color, monospace font and alignment; shows the full value as a tooltip. */
    static final class Text extends DefaultTableCellRenderer {
        private final Color color;
        private final boolean mono;
        private final boolean bold;

        Text(Color color, boolean mono, boolean bold, int alignment) {
            this.color = color;
            this.mono = mono;
            this.bold = bold;
            setHorizontalAlignment(alignment);
            putClientProperty(Theme.HTML_DISABLE, Boolean.TRUE); // cell text comes from the log file
        }

        static Text plain() {
            return new Text(null, false, false, SwingConstants.LEADING);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            Object shown = value;
            if (value instanceof LocalDateTime time) {
                shown = Formats.timestamp(time);
            } else if (value instanceof Long number) {
                shown = Formats.count(number);
            } else if (value == null && table.getColumnClass(column) == LocalDateTime.class) {
                shown = "—";
            }
            super.getTableCellRendererComponent(table, shown, selected, false, row, column);
            setBackground(background(table, row, selected));
            setForeground(color != null ? color : Theme.TEXT);
            Font font = mono ? Theme.mono(12f) : table.getFont();
            setFont(bold ? font.deriveFont(Font.BOLD) : font);
            String text = shown == null ? null : shown.toString();
            setToolTipText(text == null || text.length() < 20 ? null : Theme.literal(Formats.truncate(text, 400)));
            return this;
        }
    }

    /** Shortens text with an ellipsis so it fits the given width. */
    static String clip(String text, FontMetrics metrics, int width) {
        if (text == null || text.isEmpty() || metrics.stringWidth(text) <= width) {
            return text == null ? "" : text;
        }
        int end = text.length();
        while (end > 1 && metrics.stringWidth(text.substring(0, end) + "…") > width) {
            end -= Math.max(1, end / 12);
        }
        return text.substring(0, Math.max(1, end)) + "…";
    }
}
