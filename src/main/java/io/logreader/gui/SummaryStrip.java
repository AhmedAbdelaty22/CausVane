package io.logreader.gui;

import io.logreader.explain.ErrorExplainer;
import io.logreader.explain.IncidentLinker;
import io.logreader.explain.LogOverview;
import io.logreader.model.AnalysisResult;
import io.logreader.model.ErrorGroup;
import io.logreader.model.Severity;
import io.logreader.report.Formats;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The band above the list: the file in numbers, a few key facts about it, and the chart of problems
 * over time.
 */
final class SummaryStrip extends JPanel {

    private static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter MINUTES = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private final JLabel headline = new JLabel(" ");
    private final JLabel numbers = new JLabel(" ");
    /** Key facts; they wrap onto more lines when the window is narrow. */
    private final JPanel facts = new JPanel(new WrapLayout(FlowLayout.LEFT, 0, 3));
    /** "Most frequent": the problem on one line, its details on the next; both wrap when narrow. */
    private final JPanel frequent = new JPanel();
    private final TimelineChart chart;
    private final Consumer<String> reveal;

    SummaryStrip(TimelineChart chart, Consumer<String> reveal) {
        super(new BorderLayout(16, 0));
        this.reveal = reveal;
        this.chart = chart;
        facts.setOpaque(false);
        facts.setAlignmentX(Component.LEFT_ALIGNMENT);
        frequent.setOpaque(false);
        frequent.setLayout(new BoxLayout(frequent, BoxLayout.Y_AXIS));
        frequent.setAlignmentX(Component.LEFT_ALIGNMENT);
        frequent.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));
        setBorder(BorderFactory.createEmptyBorder(4, 16, 10, 12));

        headline.setFont(headline.getFont().deriveFont(Font.BOLD, 15f));
        headline.setForeground(Theme.TEXT);
        numbers.setForeground(Theme.DIM);
        numbers.setFont(numbers.getFont().deriveFont(12f));
        numbers.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        for (JLabel label : new JLabel[] {headline, numbers}) {
            label.putClientProperty(Theme.HTML_DISABLE, Boolean.TRUE);
            label.setAlignmentX(Component.LEFT_ALIGNMENT);
        }

        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
        text.add(headline);
        text.add(numbers);
        text.add(facts);
        text.add(frequent);
        add(text, BorderLayout.CENTER);
        chart.setCompact(true);
        chart.setPreferredSize(new Dimension(560, 112));
        chart.setToolTipText("Errors and warnings over time. Click a bar, or drag across several, to see only that period.");
        add(chart, BorderLayout.EAST);
    }

    /** Narrower chart in a narrower window, so the numbers and facts keep room. */
    void fitWidth(int windowWidth) {
        int width = windowWidth >= 1250 ? 560 : windowWidth >= 980 ? 420 : 300;
        if (chart.getPreferredSize().width != width) {
            chart.setPreferredSize(new Dimension(width, 112));
            revalidate();
        }
    }

    void reading(String fileName) {
        headline.setText("Reading " + fileName + "…");
        numbers.setText(" ");
        facts.removeAll();
        frequent.removeAll();
        revalidate();
        setToolTipText(null);
    }

    void show(AnalysisResult r, List<IncidentLinker.Incident> incidents, String overview, LogOverview.Overview whole) {
        long problems = r.groups().size();
        if (problems == 0) {
            headline.setText("No problems found");
        } else {
            headline.setText(Formats.count(r.errorCount()) + (r.errorCount() == 1 ? " error" : " errors")
                    + (r.timeSpan() != null ? " in " + Formats.duration(r.timeSpan()) : "")
                    + "  ·  " + problems + (problems == 1 ? " problem" : " different problems"));
        }
        long fatal = r.contents().level(Severity.FATAL);
        numbers.setText(Formats.count(r.totalLines()) + " lines  ·  "
                + (fatal > 0 ? Formats.count(fatal) + " fatal  ·  " : "")
                + Formats.count(r.warningCount()) + (r.warningCount() == 1 ? " warning" : " warnings")
                + (r.firstTimestamp() != null ? "  ·  " + Formats.timestamp(r.firstTimestamp()) + " to " + Formats.timestamp(r.lastTimestamp()) : "")
                + (r.unlistedOccurrences() > 0 ? "  ·  " + Formats.count(r.unlistedOccurrences()) + " not listed (limit)" : ""));

        facts.removeAll();
        frequent.removeAll();
        if (whole != null) {
            fact(facts, "Health", whole.health().label(), health(whole.health()), null,
                    "LogReader's overall rating of this log");
        }
        List<ErrorGroup> serious = r.groups().stream().filter(g -> g.severity().isAtLeast(Severity.ERROR)).toList();
        List<ErrorGroup> pool = serious.isEmpty() ? r.groups() : serious;
        LocalDateTime first = pool.stream().map(ErrorGroup::firstSeen).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        LocalDateTime last = pool.stream().map(ErrorGroup::lastSeen).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        String kind = serious.isEmpty() ? "warning" : "error";
        if (first != null) {
            fact(facts, "First " + kind, SECONDS.format(first), null, null, "When the log first reported a problem");
        }
        if (whole != null && whole.peak() != null) {
            LogOverview.Peak p = whole.peak();
            fact(facts, "Busiest minute", MINUTES.format(p.start()) + "  ·  " + Formats.count(p.errors())
                    + (p.errors() == 1 ? " error" : " errors"), null, null, "The minute with the most errors and warnings");
        }
        if (last != null) {
            fact(facts, "Last " + kind, SECONDS.format(last), null, null, "When the log last reported a problem");
        }
        if (!pool.isEmpty()) {
            mostFrequent(pool, incidents);
        }
        int linked = incidents.stream().mapToInt(IncidentLinker.Incident::size).sum();
        fact(facts, "Incidents", incidents.isEmpty() ? "none found"
                        : incidents.size() + " probable  ·  " + linked + " linked problems", null, null,
                "Groups of problems that probably share one cause");
        setToolTipText(Theme.literal(overview));
        revalidate();
        repaint();
    }

    /** One "label value" pair; the value opens a problem when {@code groupId} is given. */
    private void fact(JPanel facts, String label, String value, Color color, String groupId, String tip) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); // label and value never split apart
        row.setOpaque(false);
        facts.add(row);
        JLabel key = new JLabel(label);
        key.setForeground(Theme.DIM);
        key.setFont(key.getFont().deriveFont(12f));
        key.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
        key.setToolTipText(tip);
        row.add(key);
        if (groupId != null) {
            JButton link = new JButton(value);
            link.putClientProperty(Theme.HTML_DISABLE, Boolean.TRUE);
            link.putClientProperty(com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE,
                    com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE_BORDERLESS);
            link.setForeground(Theme.BLUE);
            link.setFont(key.getFont().deriveFont(Font.BOLD));
            link.setMargin(new java.awt.Insets(0, 0, 0, 0));
            link.setBorder(BorderFactory.createEmptyBorder());
            link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            link.setToolTipText(tip);
            link.addActionListener(e -> reveal.accept(groupId));
            row.add(link);
        } else {
            JLabel shown = new JLabel(value);
            shown.putClientProperty(Theme.HTML_DISABLE, Boolean.TRUE);
            shown.setForeground(color == null ? Theme.TEXT : color);
            shown.setFont(key.getFont().deriveFont(Font.BOLD));
            shown.setToolTipText(tip);
            row.add(shown);
        }
        JLabel gap = new JLabel("    ");
        row.add(gap);
    }

    /**
     * The problem that happened most often, with what makes the number useful: its share of all errors,
     * when it ran, its kind, where in the code, and whether it is only a knock-on effect of another failure.
     */
    private void mostFrequent(List<ErrorGroup> pool, List<IncidentLinker.Incident> incidents) {
        ErrorGroup top = pool.get(0); // the list is sorted by count
        io.logreader.explain.Explanation explanation = ErrorExplainer.standard().explain(top);
        long all = pool.stream().mapToLong(ErrorGroup::count).sum();
        long share = all == 0 ? 0 : Math.round(100.0 * top.count() / all);
        String noun = top.severity().isAtLeast(Severity.ERROR) ? "errors" : "warnings";

        JPanel what = line();
        JPanel more = line();
        frequent.add(what);
        frequent.add(more);
        String tip = "The problem that happened most often in this log";
        JLabel key = label("Most frequent", Theme.DIM, Font.PLAIN, tip, 6);
        what.add(key);
        more.setBorder(BorderFactory.createEmptyBorder(0, key.getPreferredSize().width, 0, 0)); // details under the name
        what.add(label(explanation.title().replace("`", ""), Theme.TEXT, Font.BOLD, tip, 10));
        IncidentLinker.Membership membership = IncidentLinker.index(incidents).get(top.id());
        if (membership != null && membership.part() == IncidentLinker.Part.FOLLOWER) {
            what.add(label("probably a knock-on effect, not the cause", Theme.BLUE, Font.PLAIN,
                    "It started after the first failure of an incident and is the kind of problem that failure causes."
                            + " Fixing the first failure is likely to stop it.", 0));
        } else if (membership != null && membership.part() == IncidentLinker.Part.LEAD) {
            what.add(label("the first failure of an incident", Theme.RED, Font.PLAIN,
                    "Other problems probably followed from this one", 0));
        }
        more.add(label(Formats.count(top.count()) + (top.count() == 1 ? " time" : " times")
                + " (" + share + "% of all " + noun + ")", Theme.DIM, Font.PLAIN,
                "How often it happened and its share of all " + noun, 14));
        if (top.firstSeen() != null && top.lastSeen() != null) {
            more.add(label(SECONDS.format(top.firstSeen()) + (top.firstSeen().equals(top.lastSeen()) ? ""
                    : " – " + SECONDS.format(top.lastSeen())), Theme.DIM, Font.PLAIN, "When it first and last happened", 14));
        }
        more.add(label(explanation.category(), Theme.DIM, Font.PLAIN, "The kind of problem", 14));
        if (top.pinpoint() != null) {
            JLabel where = label(top.location(), Theme.CODE, Font.PLAIN, "Where in the code it started", 0);
            where.setFont(Theme.mono(11.5f));
            where.setOpaque(true);
            where.setBackground(Theme.tintOpaqueOn(Theme.CODE, Theme.mode() == Theme.Mode.DARK ? new Color(0x191919) : new Color(0xF6F6F7)));
            where.setBorder(BorderFactory.createEmptyBorder(1, 5, 1, 5));
            more.add(where);
        }
    }

    private static JPanel line() {
        JPanel line = new JPanel(new WrapLayout(FlowLayout.LEFT, 0, 1));
        line.setOpaque(false);
        line.setAlignmentX(Component.LEFT_ALIGNMENT);
        return line;
    }

    private static JLabel label(String text, Color color, int style, String tip, int gapAfter) {
        JLabel label = new JLabel(text);
        label.putClientProperty(Theme.HTML_DISABLE, Boolean.TRUE);
        label.setForeground(color);
        label.setFont(label.getFont().deriveFont(style, 12f));
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, gapAfter));
        label.setToolTipText(tip);
        return label;
    }

    private static Color health(LogOverview.Health health) {
        return switch (health) {
            case HEALTHY -> Theme.GREEN;
            case MINOR -> Theme.YELLOW;
            case PROBLEMS -> Theme.ORANGE;
            case SERIOUS -> Theme.RED;
        };
    }
}
