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
    private static final Color CALLOUT = new Color(0x25272B);

    private final JEditorPane html = new JEditorPane();
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
                BorderFactory.createMatteBorder(0, 4, 0, 0, Theme.ORANGE),
                BorderFactory.createEmptyBorder(10, 14, 6, 8)));

        JLabel title = new JLabel("ROOT CAUSE SPOTLIGHT");
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
        add(header, BorderLayout.NORTH);

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

        JScrollPane scroll = new JScrollPane(html);
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
        html.setText("<html><body><span class='dim'>Select an error in the table to see what it means and where it started.</span></body></html>");
    }

    void show(ErrorGroup group, int occurrenceIndex) {
        occurrences = group.samples().size();
        occurrence = occurrenceIndex;
        LogEvent event = group.samples().get(occurrenceIndex);
        String shown = "occurrence " + (occurrenceIndex + 1) + " of " + Formats.count(group.count());
        if (group.count() > occurrences) {
            shown += " (first " + occurrences + " browsable)";
        }
        occurrenceLabel.setText(shown);
        previous.setEnabled(occurrenceIndex > 0);
        next.setEnabled(occurrenceIndex < occurrences - 1);
        html.setText(render(group, event));
        html.setCaretPosition(0);
    }

    private static String render(ErrorGroup group, LogEvent event) {
        ParsedTrace trace = event.trace();
        StackFrame pin = trace == null ? null : trace.pinpoint();
        StringBuilder sb = new StringBuilder("<html><body>");

        // headline: what and where, in one line
        sb.append("<span class='headline'><font color='").append(Theme.hex(Theme.severity(event.severity()))).append("'>")
                .append(esc(group.displayType())).append("</font></span>");
        if (pin != null) {
            sb.append(" &nbsp;<span class='at'>at</span> <span class='hot'>").append(esc(pin.location())).append("</span>");
        }

        explanation(sb, ErrorExplainer.standard().explain(group));

        sb.append("<p class='sub'>WHERE IT STARTED</p><table cellspacing='0' cellpadding='1'>");
        if (pin != null) {
            row(sb, "File", "<span class='hot'>" + esc(pin.fileName() == null ? "(unknown)" : pin.fileName()) + "</span>"
                    + (pin.file() != null && !pin.file().equals(pin.fileName())
                    ? " &nbsp;<span class='dim'>" + esc(pin.file()) + "</span>" : ""));
            row(sb, "Method / Function", "<span class='hot'>"
                    + esc(pin.qualifiedFunction() == null ? "(unknown)" : pin.qualifiedFunction()) + "</span>");
            row(sb, "Line", "<span class='line'>" + (pin.line() > 0 ? String.valueOf(pin.line()) : "?") + "</span>"
                    + (pin.column() > 0 ? " <span class='dim'>column " + pin.column() + "</span>" : ""));
            row(sb, "Frame type", pin.application()
                    ? "<span class='app'>APPLICATION CODE</span>"
                    : "<span class='lib'>FRAMEWORK / LIBRARY CODE</span> <span class='dim'>(no application frame in this trace)</span>");
        } else {
            row(sb, "Location", "<span class='dim'>No stack trace logged for this error. The log line is marked below.</span>");
        }
        if (trace != null && trace.rootType() != null) {
            row(sb, "Deepest root cause", "<span class='cause'>" + esc(trace.rootType()) + "</span>"
                    + (trace.rootMessage() != null ? ": " + esc(Formats.truncate(trace.rootMessage(), 500)) : ""));
        } else {
            row(sb, "Message", esc(Formats.truncate(event.message(), 500)));
        }
        if (trace != null && trace.chain().size() > 1) {
            row(sb, "Exception chain", esc(String.join("  →  ", trace.chain())));
        }
        row(sb, "Severity / Runtime", "<b><font color='" + Theme.hex(Theme.severity(event.severity())) + "'>"
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
                sb.append("<p class='sub'>STACK FRAMES &nbsp;<span class='dim'>").append(app)
                        .append(" application · ").append(frames.size() - app).append(" framework / library</span></p>")
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
                            .append(frame.application() ? "APP " : "LIB ")
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
        sb.append("<table width='100%' cellspacing='0' cellpadding='10' bgcolor='").append(Theme.hex(CALLOUT))
                .append("' style='margin-top: 8px; margin-bottom: 4px;'><tr><td>");
        sb.append("<span class='cat'>WHAT THIS MEANS &nbsp;·&nbsp; ").append(esc(e.category().toUpperCase(java.util.Locale.ROOT))).append("</span><br>");
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
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
