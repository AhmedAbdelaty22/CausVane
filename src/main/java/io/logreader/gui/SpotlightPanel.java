package io.logreader.gui;

import io.logreader.explain.ErrorExplainer;
import io.logreader.explain.Explanation;
import io.logreader.explain.IncidentLinker;
import io.logreader.explain.LogFacts;
import io.logreader.model.ErrorGroup;
import io.logreader.model.LogEvent;
import io.logreader.model.ParsedTrace;
import io.logreader.model.StackFrame;
import io.logreader.model.TraceSection;
import io.logreader.report.Formats;

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
    /** Whether the technical details are unfolded; kept while moving between problems. */
    private boolean technical;
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
            } else if (e.getDescription().equals("technical")) {
                technical = !technical;
                if (shownGroup != null) {
                    show(shownGroup, occurrence, shownIncident);
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
        subline.setText(group.displayType() + (group.pinpoint() != null ? " at " + group.location() : "")
                + "  ·  " + Formats.count(group.count()) + (group.count() == 1 ? " time" : " times") + when);
        subline.setToolTipText(Theme.literal(group.errorType()));
        pills.removeAll();
        pills.add(new Pill(event.severity().name(), Theme.severity(event.severity()), false));
        if (incident != null) {
            pills.add(new Pill(TableRenderers.tag(incident.part()), TableRenderers.tagColor(incident.part()), false));
        }
        pills.revalidate();
        pills.repaint();
        html.setText(render(group, event, explanation, incident, technical));
        html.setCaretPosition(0);
    }

    private static String render(ErrorGroup group, LogEvent event, Explanation explanation,
                                 IncidentLinker.Membership incident, boolean technical) {
        ParsedTrace trace = event.trace();
        StackFrame pin = trace == null ? null : trace.pinpoint();
        StringBuilder sb = new StringBuilder("<html><body>");
        if (incident != null) {
            incident(sb, group, incident);
        }
        sb.append("<p class='lead'>").append(rich(explanation.meaning()));
        if (explanation.detail() != null) {
            sb.append(" <b>").append(rich(explanation.detail())).append("</b>");
        }
        sb.append("</p>");
        sb.append("<p class='sub'>WHAT TO DO</p><ol>");
        explanation.nextSteps().forEach(step -> sb.append("<li>").append(rich(step)).append("</li>"));
        sb.append("</ol>");
        if (!explanation.likelyCauses().isEmpty()) {
            sb.append("<p class='sub'>WHY IT USUALLY HAPPENS</p><ul>");
            explanation.likelyCauses().forEach(cause -> sb.append("<li>").append(rich(cause)).append("</li>"));
            sb.append("</ul>");
        }
        if (!explanation.context().isEmpty()) {
            sb.append("<p class='sub'>IN THIS LOG</p>");
            for (String line : explanation.context()) {
                sb.append("<p>").append(rich(line)).append("</p>");
            }
        }
        sb.append("<p class='sub'>EVERYTHING THE LOG SAYS ABOUT IT</p><table cellspacing='0' cellpadding='0'>");
        for (LogFacts.Fact fact : LogFacts.forProblem(group, event, explanation)) {
            String value = factValue(fact.value());
            sb.append("<tr><td class='k' valign='top' nowrap>").append(esc(fact.label())).append("</td><td width='14'></td><td class='fact' valign='top'>").append(value)
                    .append("<br><span class='expl'>").append(esc(fact.meaning())).append("</span></td></tr>");
        }
        sb.append("</table>");
        List<StackFrame> path = trace == null || trace.root() == null ? List.of() : framesToShow(trace);
        if (path.isEmpty()) {
            return sb.append("</body></html>").toString();
        }
        sb.append("<p><a class='toggle' href='technical'>").append(technical ? "Hide the path through the code" : "Show the path through the code")
                .append("</a> <span class='dim'>").append(technical ? "" : "(" + path.size() + " code locations, newest first)")
                .append("</span></p>");
        if (!technical) {
            return sb.append("</body></html>").toString();
        }
        if (trace != null && trace.root() != null) {
            List<StackFrame> frames = framesToShow(trace);
            if (!frames.isEmpty()) {
                long app = frames.stream().filter(StackFrame::application).count();
                sb.append("<p class='dim'>").append(app)
                        .append(" in the application's own code (YOURS), ").append(frames.size() - app)
                        .append(" in libraries it uses (LIB). The line marked &gt;&gt; is where it started.</p>")
                        .append("<div class='frames'>");
                int count = 0;
                for (StackFrame frame : frames) {
                    if (++count > MAX_FRAMES) {
                        sb.append("<span class='dim'>… ").append(frames.size() - MAX_FRAMES).append(" more</span>");
                        break;
                    }
                    boolean isPin = pin != null && frame.eventLineIndex() == pin.eventLineIndex();
                    String function = frame.qualifiedFunction() == null ? "" : "  " + frame.qualifiedFunction();
                    sb.append(isPin ? "<span class='pin'>&gt;&gt; " : "&nbsp;&nbsp;&nbsp;")
                            .append(isPin ? "" : frame.application() ? "<span class='app'>" : "<span class='lib'>")
                            .append(frame.application() ? "YOURS " : "LIB   ")
                            .append(esc(frame.location())).append(esc(Formats.truncate(function, 140)))
                            .append("</span><br>");
                }
                sb.append("</div>");
            }
        }
        return sb.append("</body></html>").toString();
    }

    /** The "part of an incident" callout, with links to the related problems. */
    private static void incident(StringBuilder sb, ErrorGroup group, IncidentLinker.Membership membership) {
        IncidentLinker.Incident incident = membership.incident();
        ErrorGroup lead = incident.lead();
        sb.append("<table width='100%' cellspacing='0' cellpadding='9' bgcolor='").append(Theme.hex(Theme.CALLOUT))
                .append("' style='margin-bottom: 8px;'><tr><td>");
        switch (membership.part()) {
            case LEAD -> {
                sb.append("<span class='inc'>THE FIRST FAILURE OF AN INCIDENT</span><br>");
                int others = incident.followers().size();
                if (others > 0) {
                    ErrorGroup last = incident.followers().get(others - 1);
                    sb.append(others).append(others == 1 ? " other problem" : " other problems")
                            .append(" started within ").append(IncidentLinker.gap(lead.firstSeen(), last.firstSeen()))
                            .append(" after this one and look like knock-on effects of it. Fixing this one first is likely to clear them.");
                    links(sb, incident.followers(), lead, "+");
                }
                if (!incident.earlyWarnings().isEmpty()) {
                    sb.append("<p class='sub'>WARNING SIGNS BEFORE IT</p>");
                    links(sb, incident.earlyWarnings(), lead, "−");
                }
            }
            case FOLLOWER -> sb.append("<span class='inc'>PROBABLY A KNOCK-ON EFFECT</span><br>This is probably not the real problem. It started ")
                    .append(IncidentLinker.gap(lead.firstSeen(), group.firstSeen()))
                    .append(" after the failure below, which looks like where this incident began. Look at that one first:<br>")
                    .append(link(lead));
            case EARLY_WARNING -> sb.append("<span class='inc'>A WARNING SIGN</span><br>It appeared ")
                    .append(IncidentLinker.gap(group.firstSeen(), lead.firstSeen()))
                    .append(" before the failure below and stopped around the time that failure began:<br>").append(link(lead));
        }
        sb.append(membership.part() == IncidentLinker.Part.LEAD ? "" : "<br>")
                .append("<span class='dim'>Linked by timing and kind of problem, so treat it as a strong hint rather than proof.</span>");
        sb.append("</td></tr></table>");
    }

    private static void links(StringBuilder sb, List<ErrorGroup> groups, ErrorGroup lead, String sign) {
        int shown = 0;
        for (ErrorGroup g : groups) {
            if (++shown > 8) {
                sb.append("<p class='dim'>and ").append(groups.size() - 8).append(" more</p>");
                break;
            }
            sb.append(link(g, (sign.equals("+") ? "started " : "appeared ") + IncidentLinker.gap(lead.firstSeen(), g.firstSeen())
                    + (sign.equals("+") ? " after" : " before") + "  ·  " + Formats.count(g.count())
                    + (g.count() == 1 ? " time" : " times")));
        }
    }

    /** A problem named in plain words, its technical name in small type, and an Open link. */
    private static String link(ErrorGroup g) {
        return link(g, null);
    }

    private static String link(ErrorGroup g, String note) {
        String meaning = ErrorExplainer.standard().explain(g).title().replace("`", "");
        return "<table cellspacing='0' cellpadding='0' width='100%'><tr><td valign='top' class='fact'><b>" + esc(meaning)
                + "</b><br><span class='tech'>" + esc(g.displayType()) + (g.pinpoint() != null ? " at&nbsp;<span class='code'>&nbsp;" + esc(g.location()) + "&nbsp;</span>" : "")
                + (note != null ? "  ·  " + esc(note) : "") + "</span></td><td valign='top' align='right' width='64' nowrap><a href='group:" + esc(g.id())
                + "'>Open&nbsp;&rsaquo;</a></td></tr></table>";
    }

    /** Frames of the root cause, or of the section holding the pinpoint when the root cause lists none. */
    private static List<StackFrame> framesToShow(ParsedTrace trace) {
        if (!trace.root().frames().isEmpty()) {
            if (trace.pinpoint() == null || trace.root().frames().contains(trace.pinpoint())) {
                return trace.root().frames();
            }
        }
        for (TraceSection section : trace.sections()) {
            for (StackFrame frame : section.frames()) {
                if (trace.pinpoint() != null && frame.eventLineIndex() == trace.pinpoint().eventLineIndex()) {
                    return section.frames();
                }
            }
        }
        return trace.root().frames();
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
