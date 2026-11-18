package io.easylog.gui;

import io.easylog.explain.PlainStory;
import io.easylog.explain.PlainStory.Kind;
import io.easylog.explain.PlainStory.Step;

import javax.swing.BorderFactory;
import javax.swing.JEditorPane;
import javax.swing.JScrollPane;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.Color;
import java.util.List;

/**
 * "In plain words": the selected error and the log entries around it, told as a short sequence of
 * sentences. The original log text is kept in small type under the steps that need it.
 */
final class StoryView extends JScrollPane {

    private final JEditorPane html = new JEditorPane();

    StoryView() {
        HTMLEditorKit kit = new HTMLEditorKit();
        StyleSheet css = new StyleSheet();
        css.addStyleSheet(kit.getStyleSheet());
        String mono = Theme.mono(11f).getFamily();
        css.addRule("body { color: " + Theme.hex(Theme.TEXT) + "; margin: 10px 16px 14px 16px; font-size: 13px; }");
        css.addRule("td { vertical-align: top; padding: 0; }");
        css.addRule(".time { color: " + Theme.hex(Theme.DIM) + "; font-family: '" + mono + "'; font-size: 10px; padding-top: 2px; }");
        css.addRule(".group { color: " + Theme.hex(Theme.DIM) + "; font-weight: bold; font-size: 10px; padding-top: 10px; padding-bottom: 3px; }");
        css.addRule(".ctx { color: " + Theme.hex(Theme.CONTEXT_TEXT) + "; }");
        css.addRule(".q { color: " + Theme.hex(Theme.TEXT) + "; }");
        css.addRule(".qc { color: " + Theme.hex(Theme.CONTEXT_TEXT) + "; }");
        css.addRule(".orig { color: " + Theme.hex(Theme.GUTTER) + "; font-family: '" + mono + "'; font-size: 10.5px; padding-top: 1px; }");
        css.addRule(".hidden { color: " + Theme.hex(Theme.DIM) + "; font-style: italic; font-size: 12px; }");
        css.addRule(".start { font-weight: bold; }");
        css.addRule(Theme.codeRule("code", mono));
        kit.setStyleSheet(css);
        html.setEditorKit(kit);
        html.setEditable(false);
        html.setBackground(Theme.EDITOR);
        html.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        setViewportView(html);
        setHorizontalScrollBarPolicy(HORIZONTAL_SCROLLBAR_NEVER);
        setBorder(BorderFactory.createEmptyBorder());
        getViewport().setBackground(Theme.EDITOR);
        getVerticalScrollBar().setUnitIncrement(18);
    }

    void showMessage(String message) {
        html.setText("<html><body><span class='hidden'>" + esc(message) + "</span></body></html>");
    }

    void show(List<Step> steps) {
        StringBuilder sb = new StringBuilder("<html><body><table width='100%' cellspacing='0' cellpadding='0'>");
        String group = null;
        for (Step step : steps) {
            String next = switch (step.kind()) {
                case BEFORE -> "JUST BEFORE";
                case AFTER -> "JUST AFTER";
                default -> "THE ERROR";
            };
            if (!next.equals(group)) {
                group = next;
                sb.append("<tr><td colspan='3' class='group'>").append(group).append("</td></tr>");
            }
            boolean context = step.kind() == Kind.BEFORE || step.kind() == Kind.AFTER;
            boolean start = step.kind() == Kind.STARTS_HERE;
            String bg = start ? " bgcolor='" + Theme.hex(Theme.tintOpaque(Theme.RED)) + "'" : "";
            sb.append("<tr").append(bg).append("><td class='time' valign='top' width='62' nowrap>").append(esc(step.time())).append("&nbsp;&nbsp;")
                    .append("</td><td valign='top' width='16'><font color='").append(Theme.hex(marker(step.kind()))).append("'>")
                    .append(context ? "&#9675;" : "&#9679;").append("</font></td><td valign='top' style='padding-bottom: 7px;'>");
            if (step.kind() == Kind.HIDDEN) {
                sb.append("<span class='hidden'>").append(esc(step.sentence())).append("</span>");
            } else {
                sb.append("<span class='").append(context ? "ctx" : start ? "start" : "").append("'>")
                        .append(rich(step.sentence(), context)).append("</span>");
                boolean showOriginal = !context && !step.original().isBlank()
                        && (step.kind() == Kind.STARTS_HERE || step.kind() == Kind.ERROR
                        || step.kind() == Kind.CAUSE || step.kind() == Kind.ROOT_CAUSE);
                if (showOriginal) {
                    sb.append("<div class='orig'>").append(esc(step.original().replace("`", ""))).append("</div>");
                }
            }
            sb.append("</td></tr>");
        }
        sb.append("</table></body></html>");
        html.setText(sb.toString());
        html.setCaretPosition(0);
    }

    private static Color marker(Kind kind) {
        return switch (kind) {
            case REPORTED, ERROR -> Theme.RED;
            case CAUSE -> Theme.ORANGE;
            case ROOT_CAUSE, STARTS_HERE -> Theme.RED;
            case CALLED_FROM -> Theme.GREEN;
            default -> Theme.GUTTER;
        };
    }

    /**
     * Escapes text. Quoted names of files and code ({@code `OrderService.java`}) are shown as code; other
     * quotes from the log in a slightly stronger colour with quotation marks; file names in the plain words
     * are shown as code too.
     */
    private static String rich(String text, boolean context) {
        StringBuilder out = new StringBuilder();
        String[] parts = text.split("`", -1);
        for (int i = 0; i < parts.length; i++) {
            boolean quoted = i % 2 == 1 && i < parts.length - 1;
            if (!quoted) {
                out.append(Theme.markFiles(esc(parts[i]), "code"));
            } else if (Theme.looksLikeCode(parts[i])) {
                out.append("<span class='code'>").append(esc(parts[i])).append("</span>");
            } else {
                out.append("<span class='").append(context ? "qc" : "q").append("'>&ldquo;").append(esc(parts[i])).append("&rdquo;</span>");
            }
        }
        return Theme.keepSpaceBefore(out.toString(), "code");
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
