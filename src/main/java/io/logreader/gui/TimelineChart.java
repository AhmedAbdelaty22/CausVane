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
 * "Problems over time": stacked bars of errors and warnings across the time span of the log, with a scale
 * on the left, evenly spaced times underneath and totals in the legend. Hovering a bar shows its exact
 * numbers in the title row; clicking or dragging lists only that period.
 */
final class TimelineChart extends JComponent {

    private static final int BAR_SLOT = 9;   // bar plus gap, in pixels
    private static final int GAP = 2;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", java.util.Locale.ENGLISH);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.ENGLISH);
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("MMM d HH:mm", java.util.Locale.ENGLISH);

    /** One drawn bar: a time slice of the plot. */
    private record Bin(LocalDateTime start, LocalDateTime end, long errors, long warnings) {}

    private List<TimeBucket> buckets = List.of();
    private Bin[] bins = new Bin[0];
    private int binsForWidth = -1;
    private int hover = -1;
    /** Slim form for the summary strip: no caption, the legend alone on the top row. */
    private boolean compact;
    private int dragStart = -1;
    private int dragEnd = -1;
    private LocalDateTime selectedFrom;
    private LocalDateTime selectedTo;
    private java.util.function.BiConsumer<LocalDateTime, LocalDateTime> onRange;
    private int plotX;
    private int plotWidth;

    TimelineChart() {
        setOpaque(false);
        ToolTipManager.sharedInstance().registerComponent(this);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragStart = binAt(e.getX());
                dragEnd = dragStart;
                repaint();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragStart >= 0) {
                    int index = binAt(Math.max(plotX, Math.min(plotX + plotWidth - 1, e.getX())));
                    if (index >= 0 && index != dragEnd) {
                        dragEnd = index;
                        repaint();
                    }
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (dragStart < 0 || dragEnd < 0) {
                    dragStart = -1;
                    return; // pressed outside the bars
                }
                int first = Math.min(dragStart, dragEnd);
                int last = Math.max(dragStart, dragEnd);
                dragStart = -1;
                dragEnd = -1;
                selectedFrom = bins[first].start();
                selectedTo = bins[last].end();
                repaint();
                if (onRange != null) {
                    onRange.accept(selectedFrom, selectedTo);
                }
            }

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

    /** Called with the period the user clicked or dragged across. */
    void onRangeSelected(java.util.function.BiConsumer<LocalDateTime, LocalDateTime> listener) {
        onRange = listener;
    }

    void setCompact(boolean compact) {
        this.compact = compact;
        repaint();
    }

    void clearSelection() {
        selectedFrom = null;
        selectedTo = null;
        repaint();
    }

    void select(LocalDateTime from, LocalDateTime to) {
        selectedFrom = from;
        selectedTo = to;
        repaint();
    }

    private boolean inSelection(int index) {
        if (dragStart >= 0 && dragEnd >= 0) {
            return index >= Math.min(dragStart, dragEnd) && index <= Math.max(dragStart, dragEnd);
        }
        return selectedFrom != null && bins[index].start().isBefore(selectedTo) && bins[index].end().isAfter(selectedFrom);
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
                + Formats.count(bin.warnings()) + (bin.warnings() == 1 ? " warning" : " warnings")
                + "<br>Click, or drag across several bars, to list only the problems from that period</html>";
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
        g2.fillRoundRect(0, 0, w - 1, h - 1, 12, 12);
        g2.setColor(Theme.CARD_BORDER);
        g2.drawRoundRect(0, 0, w - 1, h - 1, 12, 12);

        Font small = getFont().deriveFont(11f);
        Font title = getFont().deriveFont(Font.BOLD, 12f);
        FontMetrics fm = g2.getFontMetrics(small);
        FontMetrics tm = g2.getFontMetrics(title);
        int pad = 14;
        int top = 9;

        long errors = 0;
        long warnings = 0;
        for (TimeBucket bucket : buckets) {
            errors += bucket.errors();
            warnings += bucket.warnings();
        }
        // legend with totals on the right of the title row: the swatch carries the colour, the text stays neutral
        g2.setFont(small);
        int legendX = legend(g2, fm, "Warnings " + Formats.count(warnings), Theme.CHART_WARNING, w - pad, top + 1);
        legendX = legend(g2, fm, "Errors " + Formats.count(errors), Theme.CHART_ERROR, legendX - 14, top + 1);

        // y-axis scale on the left, so bar heights can be read as numbers
        long peak = 1;
        rebin(Math.max(1, w - 2 * pad - 30));
        for (Bin bin : bins) {
            peak = Math.max(peak, bin.errors() + bin.warnings());
        }
        String peakLabel = Formats.count(peak);
        int scaleWidth = Math.max(fm.stringWidth(peakLabel), fm.stringWidth("0")) + 8;
        plotX = pad + scaleWidth;
        int plotTop = top + tm.getHeight() + 8;
        int axisY = h - 8 - fm.getHeight() - 2;
        int plotHeight = axisY - plotTop;
        rebin(w - plotX - pad);
        plotWidth = bins.length * BAR_SLOT;

        // title row: what the chart shows, or the exact numbers of the bar under the mouse
        g2.setFont(title);
        String heading;
        Color headingColor = Theme.TEXT;
        if (hover >= 0 && hover < bins.length) {
            Bin bin = bins[hover];
            DateTimeFormatter f = Duration.between(bin.start(), bin.end()).getSeconds() < 120 ? TIME : CLOCK;
            heading = f.format(bin.start()) + "–" + f.format(bin.end()) + ":  " + Formats.count(bin.errors())
                    + (bin.errors() == 1 ? " error, " : " errors, ") + Formats.count(bin.warnings())
                    + (bin.warnings() == 1 ? " warning" : " warnings");
        } else {
            heading = selectedFrom != null ? "Problems over time  ·  selected period" : "Problems over time";
        }
        g2.setColor(headingColor);
        g2.drawString(TableRenderers.clip(heading, tm, Math.max(20, legendX - pad - 12)), pad, top + tm.getAscent());

        if (bins.length == 0 || plotHeight < 8) {
            g2.setFont(small);
            g2.setColor(Theme.DIM);
            String empty = "No timestamped errors or warnings";
            g2.drawString(empty, (w - fm.stringWidth(empty)) / 2, plotTop + Math.max(0, plotHeight) / 2 + fm.getAscent() / 2);
            g2.dispose();
            return;
        }

        // gridlines at the top (the busiest bar) and half way, with their values
        g2.setFont(small);
        java.awt.Stroke solid = g2.getStroke();
        g2.setStroke(new java.awt.BasicStroke(1f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_MITER, 1f, new float[] {2f, 3f}, 0f));
        for (long value : peak >= 2 ? new long[] {peak, peak / 2} : new long[] {peak}) {
            int y = axisY - (int) Math.round(plotHeight * (value / (double) peak));
            g2.setColor(Theme.CARD_BORDER);
            g2.drawLine(plotX, y, plotX + plotWidth, y);
            g2.setColor(Theme.DIM);
            String label = Formats.count(value);
            g2.drawString(label, plotX - 6 - fm.stringWidth(label), y + fm.getAscent() / 2 - 1);
        }
        g2.setStroke(solid);
        g2.setColor(Theme.DIM);
        g2.drawString("0", plotX - 6 - fm.stringWidth("0"), axisY + fm.getAscent() / 2 - 1);

        int barWidth = BAR_SLOT - GAP;
        boolean anySelected = selectedFrom != null || (dragStart >= 0 && dragEnd >= 0);
        for (int i = 0; i < bins.length; i++) {
            if ((anySelected && inSelection(i)) || i == hover) {
                g2.setColor(Theme.HOVER_STRONG);
                g2.fillRoundRect(plotX + i * BAR_SLOT - 1, plotTop - 2, BAR_SLOT, axisY - plotTop + 2, 4, 4);
            }
        }
        for (int i = 0; i < bins.length; i++) {
            Bin bin = bins[i];
            long total = bin.errors() + bin.warnings();
            if (total == 0) {
                continue;
            }
            int x = plotX + i * BAR_SLOT;
            int fullHeight = (int) Math.max(3, Math.round(plotHeight * (total / (double) peak)));
            int errorHeight = bin.errors() == 0 ? 0
                    : (int) Math.max(2, Math.round(fullHeight * (bin.errors() / (double) total)));
            int warningHeight = bin.warnings() == 0 ? 0 : Math.max(2, fullHeight - errorHeight);
            if (errorHeight > 0) {
                bar(g2, x, axisY - errorHeight, barWidth, errorHeight, Theme.CHART_ERROR, warningHeight == 0);
            }
            if (warningHeight > 0) {
                int gap = errorHeight > 0 ? 1 : 0;
                bar(g2, x, axisY - errorHeight - gap - warningHeight, barWidth, warningHeight, Theme.CHART_WARNING, true);
            }
        }
        g2.setColor(Theme.CARD_BORDER);
        g2.drawLine(plotX, axisY, plotX + plotWidth, axisY);

        // time axis: evenly spaced ticks, as many as fit without crowding
        boolean multiDay = !bins[0].start().toLocalDate().equals(bins[bins.length - 1].end().toLocalDate());
        LocalDateTime first = bins[0].start();
        LocalDateTime last = bins[bins.length - 1].end();
        long span = Math.max(1, Duration.between(first, last).getSeconds());
        int labelWidth = fm.stringWidth(tick(first, multiDay, span)) + 24;
        int ticks = Math.max(2, Math.min(6, plotWidth / labelWidth + 1));
        int textY = axisY + 3 + fm.getAscent();
        g2.setColor(Theme.DIM);
        for (int t = 0; t < ticks; t++) {
            double f = t / (double) (ticks - 1);
            LocalDateTime at = first.plusSeconds(Math.round(span * f));
            String label = tick(at, multiDay, span);
            int x = plotX + (int) Math.round(plotWidth * f);
            int lx = t == 0 ? x : t == ticks - 1 ? x - fm.stringWidth(label) : x - fm.stringWidth(label) / 2;
            g2.drawLine(x, axisY, x, axisY + 2);
            g2.drawString(label, lx, textY);
        }
        g2.dispose();
    }

    /** A time-axis label: seconds only when the log covers less than ten minutes. */
    private static String tick(LocalDateTime time, boolean withDay, long spanSeconds) {
        if (withDay) {
            return DAY_TIME.format(time);
        }
        return spanSeconds < 600 ? TIME.format(time) : CLOCK.format(time);
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

    private static String label(LocalDateTime time, boolean withDay) {
        return (withDay ? DAY_TIME : TIME).format(time);
    }
}
