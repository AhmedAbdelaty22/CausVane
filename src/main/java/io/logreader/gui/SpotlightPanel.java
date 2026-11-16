package io.logreader.gui;

import io.logreader.explain.ErrorExplainer;
import io.logreader.explain.Explanation;
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
    private final JButton previous = new JButton(new Glyphs.Triangle(Glyphs.Triangle.LEFT));
    private final JButton next = new JButton(new Glyphs.Triangle(Glyphs.Triangle.RIGHT));
    private int occurrence;
    private int occurrences;

    /** @param onOccurrenceChange invoked with the new 0-based occurrence index when the user navigates */
    SpotlightPanel(IntConsumer onOccurrenceChange) {
        super(new BorderLayout());
        setBackground(Theme.CARD);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, Theme.ORANGE),
                BorderFactory.createEmptyBorder(12, 18, 6, 10)));

        JLabel title = new JLabel("WHAT WENT WRONG");
        title.setForeground(Theme.ORANGE);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 12f));
        occurrenceLabel.setForeground(Theme.DIM);
        previous.setToolTipText("Previous occurrence");
        next.setToolTipText("Next occurrence");
        previous.addActionListener(e -> onOccurrenceChange.accept(occurrence - 1));
        next.addActionListener(e -> onOccurrenceChange.accept(occurrence + 1));

        JPanel header = new JPanel();
        header.setOpaque(false);
        header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
        header.add(title);
        header.add(Box.createHorizontalGlue());
        header.add(occurrenceLabel);
        header.add(Box.createHorizontalStrut(8));
        header.add(previous);
        header.add(Box.createHorizontalStrut(4));
        header.add(next);
        headline.setFont(headline.getFont().deriveFont(Font.BOLD, 19f));
        headline.setForeground(Theme.TEXT);
        headline.putClientProperty(Theme.HTML_DISABLE, Boolean.TRUE); // the error name comes from the log
        headline.setBorder(BorderFactory.createEmptyBorder(8, 0, 6, 0));
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
        css.addRule(".sub { color: " + Theme.hex(Theme.DIM) + "; font-weight: bold; font-size: 90%; }");
        css.addRule(".code { font-family: '" + mono + "'; color: " + Theme.hex(Theme.ORANGE) + "; }");
        css.addRule(".k { color: " + Theme.hex(Theme.DIM) + "; padding-right: 14px; }");
        css.addRule(".hot { color: " + Theme.hex(Theme.ORANGE) + "; font-weight: bold; font-size: 115%; font-family: '" + mono + "'; }");
        css.addRule(".line { color: " + Theme.hex(Theme.RED) + "; font-weight: bold; font-size: 125%; font-family: '" + mono + "'; }");
        css.addRule(".cause { color: " + Theme.hex(Theme.RED) + "; font-weight: bold; }");
        css.addRule(".app { color: " + Theme.hex(Theme.GREEN) + "; font-weight: bold; }");
        css.addRule(".lib { color: " + Theme.hex(Theme.DIM) + "; }");
        css.addRule(".dim { color: " + Theme.hex(Theme.DIM) + "; }");
        css.addRule(".frames { font-family: '" + mono + "'; margin-top: 4px; }");
        css.addRule(".pin { color: " + Theme.hex(Theme.ORANGE) + "; font-weight: bold; }");
        kit.setStyleSheet(css);
        html.setEditorKit(kit);
        html.setEditable(false);
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

    void clear() {
        occurrences = 0;
        occurrenceLabel.setText("");
        previous.setEnabled(false);
        next.setEnabled(false);
        headline.setText(" ");
        pills.removeAll();
        pills.revalidate();
        html.setText("<html><body><span class='dim'>Select an error in the table to see what it means and where it started.</span></body></html>");
    }

    void show(ErrorGroup group, int occurrenceIndex) {
        occurrences = group.samples().size();
        occurrence = occurrenceIndex;
        LogEvent event = group.samples().get(occurrenceIndex);
        String shown = "showing time " + (occurrenceIndex + 1) + " of " + Formats.count(group.count()) + " it happened";
        if (group.count() > occurrences) {
            shown += " (first " + occurrences + " browsable)";
        }
        occurrenceLabel.setText(shown);
        previous.setEnabled(occurrenceIndex > 0);
        next.setEnabled(occurrenceIndex < occurrences - 1);
        Explanation explanation = ErrorExplainer.standard().explain(group);
        headline.setText(group.displayType());
        headline.setToolTipText(Theme.literal(group.errorType()));
        pills.removeAll();
        pills.add(new Pill(event.severity().name(), Theme.severity(event.severity()), false));
        pills.add(new Pill(explanation.category(), Theme.BLUE, false));
        if (group.pinpoint() != null) {
            pills.add(new Pill(Formats.truncate(group.location(), 44), Theme.ORANGE, true));
        }
        pills.add(new Pill(Formats.count(group.count()) + (group.count() == 1 ? " occurrence" : " occurrences"), Theme.DIM, false));
        if (group.language() != io.logreader.model.Language.UNKNOWN) {
            pills.add(new Pill(group.language().displayName(), Theme.DIM, false));
        }
        pills.revalidate();
        pills.repaint();
        html.setText(render(group, event, explanation));
        html.setCaretPosition(0);
    }

    private static String render(ErrorGroup group, LogEvent event, Explanation explanation) {
        ParsedTrace trace = event.trace();
        StackFrame pin = trace == null ? null : trace.pinpoint();
        StringBuilder sb = new StringBuilder("<html><body>");

        explanation(sb, explanation);

        sb.append("<p class='sub'>WHERE IT STARTED</p><table cellspacing='0' cellpadding='1'>");
        if (pin != null) {
            row(sb, "File", "<span class='hot'>" + esc(pin.fileName() == null ? "(unknown)" : pin.fileName()) + "</span>"
                    + (pin.file() != null && !pin.file().equals(pin.fileName())
                    ? " &nbsp;<span class='dim'>" + esc(pin.file()) + "</span>" : ""));
            row(sb, "Function", "<span class='hot'>"
                    + esc(pin.qualifiedFunction() == null ? "(unknown)" : pin.qualifiedFunction()) + "</span>");
            row(sb, "Line", "<span class='line'>" + (pin.line() > 0 ? String.valueOf(pin.line()) : "?") + "</span>"
                    + (pin.column() > 0 ? " <span class='dim'>column " + pin.column() + "</span>" : ""));
            row(sb, "Whose code", pin.application()
                    ? "<span class='app'>The application's own code</span> <span class='dim'>(the place to look first)</span>"
                    : "<span class='lib'>A library the application uses</span> <span class='dim'>(no line of the application's own code was logged)</span>");
        } else {
            row(sb, "Location", "<span class='dim'>The application did not log where in the code this happened. The log line itself is marked below.</span>");
        }
        if (trace != null && trace.rootType() != null) {
            row(sb, "Underlying error", "<span class='cause'>" + esc(trace.rootType()) + "</span>"
                    + (trace.rootMessage() != null ? ": " + esc(Formats.truncate(trace.rootMessage(), 500)) : ""));
        } else {
            row(sb, "Message", esc(Formats.truncate(event.message(), 500)));
        }
        if (trace != null && trace.chain().size() > 1) {
            row(sb, "Chain of errors", esc(String.join("  →  ", trace.chain())));
        }
        row(sb, "Details", "<b><font color='" + Theme.hex(Theme.severity(event.severity())) + "'>"
                + event.severity() + "</font></b> &nbsp;·&nbsp; " + esc(group.language().displayName())
                + " &nbsp;·&nbsp; log line " + Formats.count(event.lineNumber())
                + " &nbsp;·&nbsp; " + esc(Formats.timestamp(event.timestamp())));
        if (event.traceId() != null) {
            row(sb, "Trace ID", esc(event.traceId()));
        }
        sb.append("</table>");

        if (trace != null && trace.root() != null) {
            List<StackFrame> frames = framesToShow(trace);
            if (!frames.isEmpty()) {
                long app = frames.stream().filter(StackFrame::application).count();
                sb.append("<p class='sub'>THE PATH THROUGH THE CODE, NEWEST FIRST &nbsp;<span class='dim'>").append(app)
                        .append(" in the application's own code · ").append(frames.size() - app).append(" in libraries</span></p>")
                        .append("<div class='frames'>");
                int shown = 0;
                for (StackFrame frame : frames) {
                    if (++shown > MAX_FRAMES) {
                        sb.append("<span class='dim'>… ").append(frames.size() - MAX_FRAMES).append(" more frames</span>");
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

    /** The "what this means" callout: meaning, this error's specifics, likely causes and next steps. */
    private static void explanation(StringBuilder sb, Explanation e) {
        sb.append("<table width='100%' cellspacing='0' cellpadding='10' bgcolor='").append(Theme.hex(Theme.CALLOUT))
                .append("' style='margin-top: 4px; margin-bottom: 4px;'><tr><td>");
        sb.append("<span class='cat'>IN PLAIN WORDS &nbsp;·&nbsp; ").append(esc(e.category().toUpperCase(java.util.Locale.ROOT))).append("</span><br>");
        sb.append("<span class='means'>").append(rich(e.title())).append("</span>");
        sb.append("<p>").append(rich(e.meaning()));
        if (e.detail() != null) {
            sb.append(" <b>").append(rich(e.detail())).append("</b>");
        }
        sb.append("</p>");
        for (String line : e.context()) {
            sb.append("<p>").append(rich(line)).append("</p>");
        }
        sb.append("<table width='100%' cellspacing='0' cellpadding='0'><tr><td width='50%'>");
        sb.append("<span class='sub'>LIKELY CAUSES</span><ul>");
        e.likelyCauses().forEach(cause -> sb.append("<li>").append(rich(cause)).append("</li>"));
        sb.append("</ul></td><td width='50%'><span class='sub'>WHAT TO CHECK NEXT</span><ol>");
        e.nextSteps().forEach(step -> sb.append("<li>").append(rich(step)).append("</li>"));
        sb.append("</ol></td></tr></table>");
        sb.append("</td></tr></table>");
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

    private static void row(StringBuilder sb, String key, String valueHtml) {
        sb.append("<tr><td class='k' nowrap>").append(key).append("</td><td>").append(valueHtml).append("</td></tr>");
    }

    /** Escapes text and renders {@code `code`} spans in the monospace accent style. */
    private static String rich(String s) {
        return esc(s).replaceAll("`([^`]+)`", "<span class='code'>$1</span>");
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
