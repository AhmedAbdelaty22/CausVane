package io.logreader.gui;

import io.logreader.model.Severity;
import io.logreader.report.Formats;

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

/** Cell renderers of the error navigator table. */
final class TableRenderers {

    private TableRenderers() {}

    /** Paints the severity as a rounded colored badge. */
    static final class SeverityBadge extends JComponent implements TableCellRenderer {
        private Severity severity = Severity.UNKNOWN;
        private Color background;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            severity = value instanceof Severity s ? s : Severity.UNKNOWN;
            background = selected ? table.getSelectionBackground() : table.getBackground();
            setFont(table.getFont().deriveFont(Font.BOLD, 11f));
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setColor(background);
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            String text = severity.name();
            FontMetrics fm = g2.getFontMetrics(getFont());
            int width = Math.min(getWidth() - 8, fm.stringWidth(text) + 16);
            int height = Math.min(getHeight() - 6, fm.getHeight() + 2);
            int x = 6;
            int y = (getHeight() - height) / 2;
            g2.setColor(Theme.severity(severity));
            g2.fillRoundRect(x, y, width, height, height, height);
            g2.setColor(new Color(0x1E1F22));
            g2.setFont(getFont());
            g2.drawString(text, x + (width - fm.stringWidth(text)) / 2, y + (height - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    /** Occurrence count with a proportional bar behind it, so the most frequent errors stand out. */
    static final class CountBar extends JComponent implements TableCellRenderer {
        private long value;
        private long max = 1;
        private Color background;
        private Color foreground;

        @Override
        public Component getTableCellRendererComponent(JTable table, Object cell, boolean selected, boolean focus,
                                                       int row, int column) {
            value = cell instanceof Long n ? n : 0;
            max = table.getModel() instanceof ErrorTableModel model ? Math.max(1, model.maxCount()) : 1;
            background = selected ? table.getSelectionBackground() : table.getBackground();
            foreground = selected ? table.getSelectionForeground() : table.getForeground();
            setFont(table.getFont().deriveFont(Font.BOLD));
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
            int track = getWidth() - 12;
            int barHeight = 4;
            int width = (int) Math.max(4, Math.round(track * (value / (double) max)));
            int y = getHeight() - barHeight - 4;
            g2.setColor(new Color(255, 255, 255, 22));
            g2.fillRoundRect(6, y, track, barHeight, barHeight, barHeight);
            g2.setColor(Theme.ORANGE);
            g2.fillRoundRect(6, y, width, barHeight, barHeight, barHeight);
            String text = Formats.count(value);
            FontMetrics fm = g2.getFontMetrics(getFont());
            g2.setFont(getFont());
            g2.setColor(foreground);
            g2.drawString(text, getWidth() - 6 - fm.stringWidth(text), (getHeight() - barHeight - 4 - fm.getHeight()) / 2 + fm.getAscent() + 1);
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
            } else if (value == null && column >= ErrorTableModel.FIRST_SEEN && column <= ErrorTableModel.LAST_SEEN) {
                shown = "—";
            }
            super.getTableCellRendererComponent(table, shown, selected, false, row, column);
            if (color != null && !selected) {
                setForeground(color);
            } else if (!selected) {
                setForeground(table.getForeground());
            }
            Font font = mono ? Theme.mono(table.getFont().getSize2D()) : table.getFont();
            setFont(bold ? font.deriveFont(Font.BOLD) : font);
            String text = shown == null ? null : shown.toString();
            setToolTipText(text == null || text.length() < 20 ? null : Theme.literal(Formats.truncate(text, 400)));
            return this;
        }
    }
}
