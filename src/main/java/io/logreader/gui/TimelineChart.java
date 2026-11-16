package io.logreader.gui;

import io.logreader.model.TimeBucket;
import io.logreader.report.Formats;

import javax.swing.JComponent;
import javax.swing.ToolTipManager;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * "Problems over time" card: stacked bars of errors and warnings across the time span of the log,
 * so bursts and the moment trouble started are visible at a glance. Hover a bar for exact counts.
 */
final class TimelineChart extends JComponent {

    private static final int BAR_SLOT = 9;   // bar plus gap, in pixels
    private static final int GAP = 2;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", java.util.Locale.ENGLISH);
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("MMM d HH:mm", java.util.Locale.ENGLISH);

    /** One drawn bar: a time slice of the plot. */
    private record Bin(LocalDateTime start, LocalDateTime end, long errors, long warnings) {}

    private List<TimeBucket> buckets = List.of();
    private Bin[] bins = new Bin[0];
    private int binsForWidth = -1;
    private int hover = -1;
    private int plotX;
    private int plotWidth;

    TimelineChart() {
        setOpaque(false);
        ToolTipManager.sharedInstance().registerComponent(this);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int index = binAt(e.getX());
                if (index != hover) {
                    hover = index;
                    repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = -1;
                repaint();
            }
        };
        addMouseMotionListener(mouse);
        addMouseListener(mouse);
    }

    void setBuckets(List<TimeBucket> buckets) {
        this.buckets = List.copyOf(buckets);
        binsForWidth = -1;
        hover = -1;
        repaint();
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        int index = binAt(event.getX());
        if (index < 0) {
            return null;
        }
        Bin bin = bins[index];
        boolean sameDay = bin.start().toLocalDate().equals(bin.end().toLocalDate());
        return "<html><b>" + label(bin.start(), !sameDay) + " – " + label(bin.end(), !sameDay) + "</b><br>"
                + Formats.count(bin.errors()) + (bin.errors() == 1 ? " error" : " errors") + " · "
                + Formats.count(bin.warnings()) + (bin.warnings() == 1 ? " warning" : " warnings") + "</html>";
    }

    private int binAt(int x) {
        if (bins.length == 0 || x < plotX || x >= plotX + plotWidth) {
            return -1;
        }
        return Math.min(bins.length - 1, (x - plotX) / BAR_SLOT);
    }

    /** Re-slices the data so each bar is {@value #BAR_SLOT} pixels wide at the current size. */
    private void rebin(int width) {
        int count = Math.max(1, width / BAR_SLOT);
        if (count == binsForWidth) {
            return;
        }
        binsForWidth = count;
        if (buckets.isEmpty()) {
            bins = new Bin[0];
            return;
        }
        LocalDateTime first = buckets.get(0).start();
        LocalDateTime last = buckets.get(buckets.size() - 1).end();
        long spanSeconds = Math.max(1, Duration.between(first, last).getSeconds());
        long sliceSeconds = Math.max(buckets.get(0).seconds(), (long) Math.ceil(spanSeconds / (double) count));
        int used = (int) Math.min(count, Math.max(1, Math.ceil(spanSeconds / (double) sliceSeconds)));
        long[][] totals = new long[used][2];
        for (TimeBucket bucket : buckets) {
            int index = (int) Math.min(used - 1, Duration.between(first, bucket.start()).getSeconds() / sliceSeconds);
            totals[index][0] += bucket.errors();
            totals[index][1] += bucket.warnings();
        }
        bins = new Bin[used];
        for (int i = 0; i < used; i++) {
            LocalDateTime start = first.plusSeconds(i * sliceSeconds);
            bins[i] = new Bin(start, start.plusSeconds(sliceSeconds), totals[i][0], totals[i][1]);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int w = getWidth();
        int h = getHeight();
        g2.setColor(Theme.CARD);
        g2.fillRoundRect(0, 0, w - 1, h - 1, 14, 14);
        g2.setColor(Theme.CARD_BORDER);
        g2.drawRoundRect(0, 0, w - 1, h - 1, 14, 14);

        Font small = getFont().deriveFont(11f);
        Font caption = small.deriveFont(Font.BOLD);
        FontMetrics fm = g2.getFontMetrics(small);
        int pad = 16;

        g2.setFont(caption);
        g2.setColor(Theme.DIM);
        g2.drawString("PROBLEMS OVER TIME", pad, 10 + fm.getAscent());

        // legend: colored swatch carries identity, the label stays in text ink
        g2.setFont(small);
        int legendX = w - pad;
        legendX = legend(g2, fm, "Warnings", Theme.CHART_WARNING, legendX, 10);
        legend(g2, fm, "Errors", Theme.CHART_ERROR, legendX - 12, 10);

        plotX = pad;
        int plotTop = 10 + fm.getHeight() + 8;
        int axisY = h - 10 - fm.getHeight() - 3;
        int plotHeight = axisY - plotTop;
        rebin(w - 2 * pad);
        plotWidth = bins.length * BAR_SLOT;

        g2.setColor(Theme.CARD_BORDER);
        g2.drawLine(pad, axisY, w - pad, axisY);

        if (bins.length == 0 || plotHeight < 8) {
            g2.setColor(Theme.DIM);
            String empty = "No timestamped errors or warnings";
            g2.drawString(empty, (w - fm.stringWidth(empty)) / 2, plotTop + plotHeight / 2 + fm.getAscent() / 2);
            g2.dispose();
            return;
        }

        long peak = 1;
        for (Bin bin : bins) {
            peak = Math.max(peak, bin.errors() + bin.warnings());
        }
        int barWidth = BAR_SLOT - GAP;
        for (int i = 0; i < bins.length; i++) {
            Bin bin = bins[i];
            long total = bin.errors() + bin.warnings();
            if (total == 0) {
                continue;
            }
            int x = plotX + i * BAR_SLOT;
            int fullHeight = (int) Math.max(3, Math.round(plotHeight * (total / (double) peak)));
            int errorHeight = bin.errors() == 0 ? 0
                    : (int) Math.max(3, Math.round(fullHeight * (bin.errors() / (double) total)));
            int warningHeight = bin.warnings() == 0 ? 0 : Math.max(3, fullHeight - errorHeight);
            boolean hovered = i == hover;
            if (errorHeight > 0) {
                bar(g2, x, axisY - errorHeight, barWidth, errorHeight, shade(Theme.CHART_ERROR, hovered), warningHeight == 0);
            }
            if (warningHeight > 0) {
                int gap = errorHeight > 0 ? GAP : 0; // surface gap between stacked segments
                bar(g2, x, axisY - errorHeight - gap - warningHeight, barWidth, warningHeight, shade(Theme.CHART_WARNING, hovered), true);
            }
        }

        // axis: only the two ends of the time span, plus the scale of the tallest bar
        g2.setColor(Theme.DIM);
        g2.setFont(small);
        boolean multiDay = !bins[0].start().toLocalDate().equals(bins[bins.length - 1].end().toLocalDate());
        String from = label(bins[0].start(), multiDay);
        String to = label(bins[bins.length - 1].end(), multiDay);
        int textY = axisY + 3 + fm.getAscent();
        g2.drawString(from, pad, textY);
        g2.drawString(to, w - pad - fm.stringWidth(to), textY);
        String scale = "tallest bar: " + Formats.count(peak);
        int scaleX = (w - fm.stringWidth(scale)) / 2;
        if (scaleX > pad + fm.stringWidth(from) + 12) {
            g2.drawString(scale, scaleX, textY);
        }
        g2.dispose();
    }

    /** Draws a legend entry right-aligned at {@code right}; returns the x where it starts. */
    private static int legend(Graphics2D g2, FontMetrics fm, String text, Color color, int right, int top) {
        int x = right - fm.stringWidth(text);
        g2.setColor(Theme.TEXT);
        g2.drawString(text, x, top + fm.getAscent());
        g2.setColor(color);
        g2.fillRoundRect(x - 14, top + (fm.getHeight() - 9) / 2, 9, 9, 3, 3);
        return x - 14;
    }

    /** A bar segment; the data end (top) is rounded when it is the top of the stack. */
    private static void bar(Graphics2D g2, int x, int y, int width, int height, Color color, boolean roundTop) {
        g2.setColor(color);
        if (roundTop && height >= 6) {
            g2.fillRoundRect(x, y, width, height, 6, 6);
            g2.fillRect(x, y + height / 2, width, height - height / 2);
        } else {
            g2.fillRect(x, y, width, height);
        }
    }

    private static Color shade(Color color, boolean hovered) {
        return hovered ? color.brighter() : color;
    }

    private static String label(LocalDateTime time, boolean withDay) {
        return (withDay ? DAY_TIME : TIME).format(time);
    }
}
