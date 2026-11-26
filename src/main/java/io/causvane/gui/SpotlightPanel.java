package io.causvane.gui;

import io.causvane.explain.ErrorExplainer;
import io.causvane.explain.Explanation;
import io.causvane.explain.IncidentLinker;
import io.causvane.explain.LogFacts;
import io.causvane.model.ErrorGroup;
import io.causvane.model.LogEvent;
import io.causvane.model.ParsedTrace;
import io.causvane.model.StackFrame;
import io.causvane.model.TraceSection;
import io.causvane.report.Formats;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Root Cause Spotlight: says in plain language what the selected error is, then shows the exact file,
 * function and line it started at, its deepest root cause, and the frames split into application and
 * framework code.
 */
final class SpotlightPanel extends JPanel {

    private static final int MAX_FRAMES = 40;

    private final JEditorPane html = new JEditorPane();
    private final JLabel headline = new JLabel(" ");
    private final JPanel pills = new JPanel();
    private final JLabel occurrenceLabel = new JLabel();
    private final JLabel subline = new JLabel(" ");
    private final JButton copy = new JButton("Copy for a ticket");
    private final JButton back = new JButton("\u2190  Overview");
    /** Whether the incident timeline lists every problem or only the main steps. */
    private boolean incidentAll;
    /** "The error, step by step" and which of its branches are open. */
    private final ErrorTree errorTree = new ErrorTree();
    private ErrorGroup shownGroup;
    private IncidentLinker.Membership shownIncident;
    private final JButton previous = new JButton(new Glyphs.Triangle(Glyphs.Triangle.LEFT));
    private final JButton next = new JButton(new Glyphs.Triangle(Glyphs.Triangle.RIGHT));
    private int occurrence;
    private int occurrences;

    /** @param onOccurrenceChange invoked with the new 0-based occurrence index when the user navigates */
    SpotlightPanel(IntConsumer onOccurrenceChange) {
        super(new BorderLayout());
        setBackground(Theme.CARD);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 0, Theme.CARD_BORDER),
                BorderFactory.createEmptyBorder(12, 18, 6, 10)));

        JLabel title = new JLabel("WHAT WENT WRONG");
        title.setForeground(Theme.DIM);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 11f));
        copy.putClientProperty(com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE,
                com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
        copy.setFont(copy.getFont().deriveFont(11.5f));
        copy.setToolTipText("Copy a short, ready-to-paste summary of this problem");
        copy.setEnabled(false);
        occurrenceLabel.setForeground(Theme.DIM);
        previous.setToolTipText("Previous occurrence");
        next.setToolTipText("Next occurrence");
        previous.addActionListener(e -> onOccurrenceChange.accept(occurrence - 1));
        next.addActionListener(e -> onOccurrenceChange.accept(occurrence + 1));

        back.putClientProperty(com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE,
                com.formdev.flatlaf.FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
        back.setFont(back.getFont().deriveFont(Font.BOLD, 12f));
        back.setToolTipText("Back to the overview of the whole log (Esc)");
        back.setFocusable(false);
        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
        header.add(back);
        header.add(Box.createHorizontalStrut(12));
        header.add(title);
        header.add(Box.createHorizontalGlue());
        header.add(occurrenceLabel);
        header.add(Box.createHorizontalStrut(8));
        header.add(previous);
        header.add(Box.createHorizontalStrut(4));
        header.add(next);
        header.add(Box.createHorizontalStrut(10));
        header.add(copy);
        headline.setFont(headline.getFont().deriveFont(Font.BOLD, 18f));
        headline.setForeground(Theme.TEXT);
        headline.putClientProperty(Theme.HTML_DISABLE, Boolean.TRUE); // the error name comes from the log
        headline.setBorder(BorderFactory.createEmptyBorder(8, 0, 2, 0));
        subline.setForeground(Theme.DIM);
        subline.setFont(subline.getFont().deriveFont(12f));
        subline.putClientProperty(Theme.HTML_DISABLE, Boolean.TRUE);
        subline.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        subline.setAlignmentX(LEFT_ALIGNMENT);
        headline.setAlignmentX(LEFT_ALIGNMENT);
        pills.setOpaque(false);
        pills.setLayout(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        pills.setBorder(BorderFactory.createEmptyBorder(0, -6, 4, 0));
        pills.setAlignmentX(LEFT_ALIGNMENT);
        header.setAlignmentX(LEFT_ALIGNMENT);

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.add(header);
        top.add(headline);
        top.add(subline);
        top.add(pills);
        add(top, BorderLayout.NORTH);

        HTMLEditorKit kit = new HTMLEditorKit();
        StyleSheet css = new StyleSheet();
        css.addStyleSheet(kit.getStyleSheet());
        String mono = Theme.mono(12f).getFamily();
        css.addRule("body { color: " + Theme.hex(Theme.TEXT) + "; margin: 6px 0 0 0; }");
        css.addRule("td { vertical-align: top; }");
        css.addRule("p { margin-top: 5px; margin-bottom: 5px; }");
        css.addRule("ul { margin-left: 16px; margin-top: 2px; margin-bottom: 6px; }");
        css.addRule("ol { margin-left: 20px; margin-top: 2px; margin-bottom: 2px; }");
        css.addRule("li { margin-bottom: 2px; }");
        css.addRule(".headline { font-size: 150%; font-weight: bold; }");
        css.addRule(".at { color: " + Theme.hex(Theme.DIM) + "; }");
        css.addRule(".cat { color: " + Theme.hex(Theme.BLUE) + "; font-weight: bold; font-size: 90%; }");
        css.addRule(".means { font-size: 120%; font-weight: bold; }");
        css.addRule(".lead { font-size: 108%; }");
        css.addRule(".toggle { color: " + Theme.hex(Theme.BLUE) + "; font-weight: bold; font-size: 95%; }");
        css.addRule(".sub { color: " + Theme.hex(Theme.DIM) + "; font-weight: bold; font-size: 90%; }");
        css.addRule(Theme.codeRule("code", mono));
        css.addRule(".k { color: " + Theme.hex(Theme.DIM) + "; padding-right: 14px; }");
        css.addRule(".hot { color: " + Theme.hex(Theme.GREEN) + "; font-weight: bold; font-size: 115%; font-family: '" + mono + "'; }");
        css.addRule(".line { color: " + Theme.hex(Theme.RED) + "; font-weight: bold; font-size: 125%; font-family: '" + mono + "'; }");
        css.addRule(".cause { color: " + Theme.hex(Theme.RED) + "; font-weight: bold; }");
        css.addRule(".app { color: " + Theme.hex(Theme.GREEN) + "; font-weight: bold; }");
        css.addRule(".lib { color: " + Theme.hex(Theme.DIM) + "; }");
        css.addRule(".dim { color: " + Theme.hex(Theme.DIM) + "; }");
        css.addRule(".inc { color: " + Theme.hex(Theme.RED) + "; font-weight: bold; font-size: 90%; }");
        css.addRule("a { color: " + Theme.hex(Theme.BLUE) + "; text-decoration: none; font-weight: bold; }");
        css.addRule(".tech { color: " + Theme.hex(Theme.DIM) + "; font-size: 92%; }");
        css.addRule(".expl { color: " + Theme.hex(Theme.DIM) + "; font-size: 92%; font-weight: normal; }");
        css.addRule(".fact { padding-bottom: 6px; }");
        css.addRule(".tree { color: " + Theme.hex(Theme.GUTTER) + "; font-family: '" + mono + "'; font-size: 12px; padding-top: 1px; }");
        css.addRule(".role { color: " + Theme.hex(Theme.DIM) + "; font-weight: bold; font-size: 85%; }");
        css.addRule(".branch { color: " + Theme.hex(Theme.BLUE) + "; font-weight: bold; font-family: '" + mono + "'; }");
        css.addRule(".time { color: " + Theme.hex(Theme.DIM) + "; font-family: '" + mono + "'; font-size: 90%; padding-top: 1px; }");
        css.addRule(".frames { font-family: '" + mono + "'; margin-top: 4px; }");
        css.addRule(".pin { color: " + Theme.hex(Theme.RED) + "; font-weight: bold; }");
        kit.setStyleSheet(css);
        html.setEditorKit(kit);
        html.setEditable(false);
        html.addHyperlinkListener(e -> {
            if (e.getEventType() != javax.swing.event.HyperlinkEvent.EventType.ACTIVATED || e.getDescription() == null) {
                return;
            }
            if (e.getDescription().startsWith("group:")) {
                onReveal.accept(e.getDescription().substring("group:".length()));
            } else if (errorTree.click(e.getDescription())) {
                if (shownGroup != null) {
                    int at = scroll().getViewport().getViewPosition().y;
                    show(shownGroup, occurrence, shownIncident);
                    javax.swing.SwingUtilities.invokeLater(() -> scroll().getViewport().setViewPosition(new java.awt.Point(0, at)));
                }
            } else if (e.getDescription().equals("incident-all")) {
                incidentAll = !incidentAll;
                if (shownGroup != null) {
                    int at = scroll().getViewport().getViewPosition().y;
                    show(shownGroup, occurrence, shownIncident);
                    javax.swing.SwingUtilities.invokeLater(() -> scroll().getViewport().setViewPosition(new java.awt.Point(0, at)));
                }
            }
        });
        html.setOpaque(false);
        html.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);

        JScrollPane scroll = new JScrollPane(html, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
        clear();
    }

    JEditorPane htmlPane() {
        return html;
    }

    /** Draws the shown problem again, for example after the detail level changed. */
    void refresh() {
        if (shownGroup != null) {
            int at = scroll().getViewport().getViewPosition().y;
            show(shownGroup, occurrence, shownIncident);
            javax.swing.SwingUtilities.invokeLater(() -> scroll().getViewport().setViewPosition(new java.awt.Point(0, at)));
        }
    }

    /** Whether the whole incident is listed; carried over when the theme changes. */
    boolean incidentAll() {
        return incidentAll;
    }

    void incidentAll(boolean everything) {
        incidentAll = everything;
    }

    /** What the Overview button does. */
    void onBack(Runnable action) {
        back.addActionListener(e -> action.run());
    }

    /** What the "Copy for a ticket" button does. */
    void onCopy(Runnable action) {
        copy.addActionListener(e -> action.run());
    }

    void clear() {
        shownGroup = null;
        copy.setEnabled(false);
        subline.setText(" ");
        occurrences = 0;
        occurrenceLabel.setText("");
        previous.setEnabled(false);
        next.setEnabled(false);
        headline.setText(" ");
        pills.removeAll();
        pills.revalidate();
        html.setText("<html><body><span class='dim'>Select an error in the table to see what it means and where it started.</span></body></html>");
    }

    private java.util.function.Consumer<String> onReveal = id -> { };

    /** Called with a problem id when the user clicks a link to another problem. */
    void onReveal(java.util.function.Consumer<String> listener) {
        onReveal = listener;
    }

    void show(ErrorGroup group, int occurrenceIndex) {
        show(group, occurrenceIndex, null);
    }

    void show(ErrorGroup group, int occurrenceIndex, IncidentLinker.Membership incident) {
        shownGroup = group;
        shownIncident = incident;
        copy.setEnabled(true);
        occurrences = group.samples().size();
        occurrence = occurrenceIndex;
        LogEvent event = group.samples().get(occurrenceIndex);
        String shown = (occurrenceIndex + 1) + " of " + Formats.count(group.count());
        occurrenceLabel.setText(shown);
        occurrenceLabel.setToolTipText("This problem happened " + Formats.count(group.count()) + " times; the arrows step through "
                + (group.count() > occurrences ? "the first " + occurrences : "each") + " of them");
        previous.setEnabled(occurrenceIndex > 0);
        next.setEnabled(occurrenceIndex < occurrences - 1);
        Explanation explanation = ErrorExplainer.standard().explain(group);
        headline.setText(explanation.title().replace("`", ""));
        headline.setToolTipText(Theme.literal(explanation.title().replace("`", "")));
        String when = group.firstSeen() == null ? ""
                : group.count() == 1 || group.firstSeen().equals(group.lastSeen())
                ? "  ·  " + Formats.timestamp(group.firstSeen())
                : "  ·  " + Formats.timestamp(group.firstSeen()) + " to " + Formats.timestamp(group.lastSeen());
        String technicalName = Detail.simple() ? ""
                : (Detail.expert() && group.errorType() != null ? group.errorType() : group.displayType())
                + (group.pinpoint() != null ? " at " + group.location() : "") + "  ·  ";
        subline.setText(technicalName + Formats.count(group.count()) + (group.count() == 1 ? " time" : " times") + when);
        subline.setToolTipText(Theme.literal(group.errorType()));
        pills.removeAll();
        pills.add(new Pill(event.severity().name(), Theme.severity(event.severity()), false));
        if (incident != null) {
            pills.add(new Pill(TableRenderers.tag(incident.part()), TableRenderers.tagColor(incident.part()), false));
        }
        pills.revalidate();
        pills.repaint();
        errorTree.prepare(group, event);
        html.setText(render(group, event, explanation, incident, incidentAll, Detail.simple() ? "" : errorTree.html(event)));
        html.setCaretPosition(0);
    }

    private JScrollPane scroll() {
        return (JScrollPane) javax.swing.SwingUtilities.getAncestorOfClass(JScrollPane.class, html);
    }

    private static String render(ErrorGroup group, LogEvent event, Explanation explanation,
                                 IncidentLinker.Membership incident, boolean incidentAll, String treeHtml) {
        ParsedTrace trace = event.trace();
        StackFrame pin = trace == null ? null : trace.pinpoint();
        StringBuilder sb = new StringBuilder("<html><body>");
        if (incident != null) {
            incident(sb, group, incident, incidentAll);
        }
        boolean simple = Detail.simple();
        boolean expert = Detail.expert();
        java.util.function.Function<String, String> words = simple ? SpotlightPanel::plain : SpotlightPanel::rich;
        sb.append("<p class='lead'>").append(words.apply(explanation.meaning()));
        if (explanation.detail() != null) {
            sb.append(" <b>").append(words.apply(explanation.detail())).append("</b>");
        }
        sb.append("</p>");
        if (expert) {
            // the technical picture first: the error tree with every step open, then the facts
            sb.append(treeHtml);
            facts(sb, group, event, explanation, false);
        }
        sb.append("<p class='sub'>WHAT TO DO</p><ol>");
        explanation.nextSteps().forEach(step -> sb.append("<li>").append(words.apply(step)).append("</li>"));
        sb.append("</ol>");
        if (!explanation.likelyCauses().isEmpty()) {
            sb.append("<p class='sub'>WHY IT USUALLY HAPPENS</p><ul>");
            explanation.likelyCauses().forEach(cause -> sb.append("<li>").append(words.apply(cause)).append("</li>"));
            sb.append("</ul>");
        }
        if (!explanation.context().isEmpty() && !simple) {
            sb.append("<p class='sub'>IN THIS LOG</p>");
            for (String line : explanation.context()) {
                sb.append("<p>").append(words.apply(line)).append("</p>");
            }
        }
        if (!expert) {
            facts(sb, group, event, explanation, simple);
            sb.append(treeHtml);
        }
        if (simple) {
            sb.append("<p class='dim'>Showing plain words only. Switch the detail to Normal or Expert to see error names, "
                    + "code locations and the error step by step.</p>");
        }
        return sb.append("</body></html>").toString();
    }

    /** Facts a reader can use without knowing the code; the rest are shown at Normal and Expert detail. */
    private static final java.util.Set<String> PLAIN_FACTS =
            java.util.Set.of("How serious", "Kind of problem", "How often", "Requests affected");

    private static void facts(StringBuilder sb, ErrorGroup group, LogEvent event, Explanation explanation, boolean simple) {
        sb.append("<p class='sub'>").append(simple ? "THE BASICS" : "EVERYTHING THE LOG SAYS ABOUT IT")
                .append("</p><table cellspacing='0' cellpadding='0'>");
        for (LogFacts.Fact fact : LogFacts.forProblem(group, event, explanation)) {
            if (simple && !PLAIN_FACTS.contains(fact.label())) {
                continue;
            }
            String value = simple ? esc(fact.value()) : factValue(fact.value());
            sb.append("<tr><td class='k' valign='top' nowrap>").append(esc(fact.label())).append("</td><td width='14'></td><td class='fact' valign='top'>").append(value)
                    .append("<br><span class='expl'>").append(esc(fact.meaning())).append("</span></td></tr>");
        }
        sb.append("</table>");
    }

    /** Text with its `quoted` code parts kept as ordinary words, for Simple detail. */
    private static String plain(String s) {
        return esc(s.replace("`", ""));
    }

    /** Rows of the incident timeline shown before "show the whole incident". */
    private static final int TIMELINE_ROWS = 7;
    private static final java.time.format.DateTimeFormatter CLOCK =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss", java.util.Locale.ROOT);

    /**
     * The whole incident as one timeline, the same on every problem in it: the warning signs, the first
     * failure and the knock-on effects in the order they began, with this problem marked, so the reader
     * sees the full path of the issue and where this problem sits in it.
     */
    private static void incident(StringBuilder sb, ErrorGroup group, IncidentLinker.Membership membership, boolean all) {
        IncidentLinker.Incident incident = membership.incident();
        ErrorGroup lead = incident.lead();
        List<ErrorGroup> steps = new java.util.ArrayList<>(incident.earlyWarnings());
        steps.add(lead);
        steps.addAll(incident.followers());
        java.util.Comparator<java.time.LocalDateTime> time = java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder());
        steps.sort(java.util.Comparator.comparing(ErrorGroup::firstSeen, time)
                .thenComparing(g -> g == lead ? 0 : 1));
        java.time.LocalDateTime start = steps.stream().map(ErrorGroup::firstSeen).filter(java.util.Objects::nonNull)
                .min(java.util.Comparator.naturalOrder()).orElse(null);
        java.time.LocalDateTime end = steps.stream().map(ErrorGroup::lastSeen).filter(java.util.Objects::nonNull)
                .max(java.util.Comparator.naturalOrder()).orElse(null);

        sb.append("<table width='100%' cellspacing='0' cellpadding='9' bgcolor='").append(Theme.hex(Theme.CALLOUT))
                .append("' style='margin-bottom: 8px;'><tr><td>");
        sb.append("<span class='sub'>THE WHOLE INCIDENT &nbsp;&middot;&nbsp; ").append(steps.size()).append(" problems")
                .append(start != null && end != null ? " over " + IncidentLinker.gap(start, end) : "").append("</span><br>");
        sb.append(switch (membership.part()) {
            case FOLLOWER -> "This problem is probably <b>a knock-on effect</b>, not the real cause. The incident most likely began with the"
                    + "<span class='cause'>&nbsp;first failure</span> below; fixing that one is likely to stop this one too.";
            case LEAD -> "This problem is probably <b>the real cause</b>: the knock-on effects below began after it and are the kind of "
                    + "problem it causes. Fixing it first is likely to clear them.";
            case EARLY_WARNING -> "This warning was probably <b>an early sign</b>: it began before the"
                    + "<span class='cause'>&nbsp;first failure</span> below and stopped around when that failure began.";
        });

        // which rows to show: everything, or the main steps plus this problem
        java.util.Set<ErrorGroup> shown = new java.util.LinkedHashSet<>();
        if (all || steps.size() <= TIMELINE_ROWS) {
            shown.addAll(steps);
        } else {
            shown.add(lead);
            shown.add(group);
            incident.earlyWarnings().stream().limit(2).forEach(shown::add);
            for (ErrorGroup g : steps) {
                if (shown.size() >= TIMELINE_ROWS) {
                    break;
                }
                shown.add(g);
            }
        }
        sb.append("<table width='100%' cellspacing='0' cellpadding='0' style='margin-top: 8px;'>");
        int hidden = 0;
        for (ErrorGroup g : steps) {
            if (!shown.contains(g)) {
                hidden++;
                continue;
            }
            if (hidden > 0) {
                hiddenRow(sb, hidden, false);
                hidden = 0;
            }
            timelineRow(sb, g, g == group, g == lead ? IncidentLinker.Part.LEAD
                    : incident.earlyWarnings().contains(g) ? IncidentLinker.Part.EARLY_WARNING : IncidentLinker.Part.FOLLOWER, lead);
        }
        if (hidden > 0) {
            hiddenRow(sb, hidden, true);
        }
        sb.append("</table>");
        if (steps.size() > TIMELINE_ROWS) {
            sb.append("<p><a class='toggle' href='incident-all'>").append(all ? "Show only the main steps"
                    : "Show the whole incident (" + steps.size() + " problems)").append("</a></p>");
        }
        sb.append("<span class='dim'>Linked by timing and kind of problem, so treat it as a strong hint rather than proof.</span>");
        sb.append("</td></tr></table>");
    }

    private static void hiddenRow(StringBuilder sb, int hidden, boolean atEnd) {
        sb.append("<tr><td></td><td></td><td class='dim' style='padding-bottom: 6px;'>&middot;&middot;&middot; ").append(hidden)
                .append(atEnd ? (hidden == 1 ? " more knock-on effect after these" : " more knock-on effects after these")
                        : (hidden == 1 ? " more problem in between" : " more problems in between")).append("</td><td></td></tr>");
    }

    /** One step of the incident: when, what part it plays, what it is, and a way to open it. */
    private static void timelineRow(StringBuilder sb, ErrorGroup g, boolean current, IncidentLinker.Part part, ErrorGroup lead) {
        String meaning = ErrorExplainer.standard().explain(g).title().replace("`", "");
        java.awt.Color color = switch (part) {
            case LEAD -> Theme.RED;
            case EARLY_WARNING -> Theme.YELLOW;
            case FOLLOWER -> Theme.BLUE;
        };
        String role = switch (part) {
            case LEAD -> "FIRST FAILURE";
            case EARLY_WARNING -> "WARNING SIGN";
            case FOLLOWER -> "KNOCK-ON";
        };
        String when = g.firstSeen() == null || lead.firstSeen() == null || g == lead ? ""
                : g.firstSeen().isBefore(lead.firstSeen()) ? IncidentLinker.gap(g.firstSeen(), lead.firstSeen()) + " before the first failure"
                : IncidentLinker.gap(lead.firstSeen(), g.firstSeen()) + " after the first failure";
        String bg = current ? " bgcolor='" + Theme.hex(Theme.SELECTION) + "'" : "";
        sb.append("<tr").append(bg).append("><td class='time' valign='top' width='64' nowrap>")
                .append(g.firstSeen() == null ? "" : CLOCK.format(g.firstSeen()))
                .append("</td><td valign='top' width='16'><font color='").append(Theme.hex(color)).append("'>")
                .append(part == IncidentLinker.Part.LEAD ? "&#9679;" : "&#9675;").append("</font></td>")
                .append("<td valign='top' class='fact'><font color='").append(Theme.hex(color)).append("'><b>").append(role)
                .append("</b></font>&nbsp;&nbsp;").append(current || part == IncidentLinker.Part.LEAD ? "<b>" : "").append(esc(meaning))
                .append(current || part == IncidentLinker.Part.LEAD ? "</b>" : "").append("<br><span class='tech'>").append(Detail.simple() ? "" : esc(g.displayType())
                        + (g.pinpoint() != null ? " at&nbsp;<span class='code'>&nbsp;" + esc(g.location()) + "&nbsp;</span>" : "")
                        + " &nbsp;&middot;&nbsp; ").append(Formats.count(g.count())).append(g.count() == 1 ? " time" : " times")
                .append(when.isEmpty() ? "" : " &nbsp;&middot;&nbsp; " + esc(when)).append("</span></td>")
                .append("<td valign='top' align='right' width='92' nowrap>")
                .append(current ? "<span class='dim'>&#9664; you are here</span>" : "<a href='group:" + esc(g.id()) + "'>Open&nbsp;&rsaquo;</a>")
                .append("</td></tr>");
    }

    /** Escapes text and renders {@code `code`} spans in the monospace accent style. */
    private static String rich(String s) {
        StringBuilder out = new StringBuilder();
        String[] parts = s.split("`", -1);
        for (int i = 0; i < parts.length; i++) {
            boolean quoted = i % 2 == 1 && i < parts.length - 1;
            out.append(quoted ? "<span class='code'>" + esc(parts[i]) + "</span>" : Theme.markFiles(esc(parts[i]), "code"));
        }
        return Theme.keepSpaceBefore(out.toString(), "code");
    }

    /** A fact's value: each part that names a file or code is shown as code. */
    private static String factValue(String value) {
        StringBuilder out = new StringBuilder();
        String[] parts = value.split("  ·  ", -1);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                out.append(" &nbsp;&middot;&nbsp; ");
            }
            out.append(Theme.looksLikeCode(parts[i]) ? "<span class='code'>" + esc(parts[i]) + "</span>"
                    : Theme.markFiles(esc(parts[i]), "code"));
        }
        return Theme.keepSpaceBefore(out.toString(), "code");
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
