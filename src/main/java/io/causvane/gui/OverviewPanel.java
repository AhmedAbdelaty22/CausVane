package io.causvane.gui;

import io.causvane.explain.ErrorExplainer;
import io.causvane.explain.IncidentLinker;
import io.causvane.explain.LogFacts;
import io.causvane.explain.LogOverview;
import io.causvane.explain.LogOverview.Moment;
import io.causvane.model.AnalysisResult;
import io.causvane.model.ErrorGroup;
import io.causvane.report.Formats;

import javax.swing.BorderFactory;
import javax.swing.JEditorPane;
import javax.swing.JScrollPane;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.StyleSheet;
import java.awt.Color;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * "The whole log": shown in the right-hand panel when a file opens and whenever no problem is selected.
 * Health, a one-paragraph verdict, what happened in order, where to start, and a few facts at a glance.
 * Every problem mentioned is a link that opens it.
 */
final class OverviewPanel extends JScrollPane {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);

    private final JEditorPane html = new JEditorPane();
    private final ProblemTree tree = new ProblemTree();
    private AnalysisResult shownResult;
    private LogOverview.Overview shownOverview;
    private List<IncidentLinker.Incident> shownIncidents = List.of();

    OverviewPanel(Consumer<String> reveal) {
        HTMLEditorKit kit = new HTMLEditorKit();
        StyleSheet css = new StyleSheet();
        css.addStyleSheet(kit.getStyleSheet());
        String mono = Theme.mono(11f).getFamily();
        css.addRule("body { color: " + Theme.hex(Theme.TEXT) + "; margin: 16px 22px 18px 20px; font-size: 13px; }");
        css.addRule(".title { color: " + Theme.hex(Theme.DIM) + "; font-weight: bold; font-size: 10.5px; }");
        css.addRule(".verdict { font-size: 15px; margin-top: 6px; margin-bottom: 4px; }");
        css.addRule(".sub { color: " + Theme.hex(Theme.DIM) + "; font-weight: bold; font-size: 10.5px; margin-top: 16px; margin-bottom: 5px; }");
        css.addRule(".time { color: " + Theme.hex(Theme.DIM) + "; font-family: '" + mono + "'; font-size: 11px; padding-top: 1px; }");
        css.addRule(".dim { color: " + Theme.hex(Theme.DIM) + "; }");
        css.addRule(".k { color: " + Theme.hex(Theme.DIM) + "; padding-right: 16px; }");
        css.addRule("td { padding-bottom: 6px; }");
        css.addRule(".item { padding-bottom: 9px; }");
        css.addRule(".num { color: " + Theme.hex(Theme.DIM) + "; }");
        css.addRule(".tech { color: " + Theme.hex(Theme.DIM) + "; font-size: 90%; }");
        css.addRule(".expl { color: " + Theme.hex(Theme.DIM) + "; font-size: 90%; }");
        css.addRule("ol { margin-left: 20px; margin-top: 0; }");
        css.addRule("li { margin-bottom: 4px; }");
        css.addRule(Theme.codeRule("code", mono));
        css.addRule(".tree { color: " + Theme.hex(Theme.GUTTER) + "; font-family: '" + mono + "'; font-size: 12px; padding-top: 1px; }");
        css.addRule(".node { padding-bottom: 6px; }");
        css.addRule(".branch { color: " + Theme.hex(Theme.BLUE) + "; font-weight: bold; font-family: '" + mono + "'; }");
        css.addRule(".small { font-size: 90%; font-weight: normal; }");
        css.addRule("a { color: " + Theme.hex(Theme.BLUE) + "; text-decoration: none; font-weight: bold; font-size: 11.5px; }");
        kit.setStyleSheet(css);
        html.setEditorKit(kit);
        html.setEditable(false);
        html.setBackground(Theme.CARD);
        html.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        html.addHyperlinkListener(e -> {
            if (e.getEventType() != javax.swing.event.HyperlinkEvent.EventType.ACTIVATED || e.getDescription() == null) {
                return;
            }
            if (e.getDescription().startsWith("group:")) {
                reveal.accept(e.getDescription().substring("group:".length()));
            } else if (shownResult != null && tree.click(e.getDescription(), shownResult, shownIncidents)) {
                java.awt.Point at = getViewport().getViewPosition();
                show(shownResult, shownOverview, shownIncidents);
                javax.swing.SwingUtilities.invokeLater(() -> getViewport().setViewPosition(at)); // stay where the reader is
            }
        });
        setViewportView(html);
        setHorizontalScrollBarPolicy(HORIZONTAL_SCROLLBAR_NEVER);
        setBorder(BorderFactory.createEmptyBorder());
        getViewport().setBackground(Theme.CARD);
        getVerticalScrollBar().setUnitIncrement(18);
    }

    JEditorPane htmlPane() {
        return html;
    }

    /** Facts about the whole log that only matter to someone reading the code; left out at Simple detail. */
    private static final java.util.Set<String> TECHNICAL_FACTS = java.util.Set.of("Stack traces", "Trace IDs", "JSON entries");

    /** Draws the overview again, for example after the detail level changed. */
    void refresh() {
        if (shownResult != null) {
            int at = html.getVisibleRect().y;
            show(shownResult, shownOverview, shownIncidents);
            javax.swing.SwingUtilities.invokeLater(() -> html.scrollRectToVisible(new java.awt.Rectangle(0, at, 1, html.getVisibleRect().height)));
        }
    }

    void showMessage(String message) {
        html.setText("<html><body><span class='dim'>" + esc(message) + "</span></body></html>");
    }

    void show(AnalysisResult result, LogOverview.Overview overview, List<IncidentLinker.Incident> incidents) {
        shownResult = result;
        shownOverview = overview;
        shownIncidents = incidents;
        tree.prepare(result, incidents);
        StringBuilder sb = new StringBuilder("<html><body>");
        sb.append("<div class='title'>THE WHOLE LOG &nbsp;&middot;&nbsp; <font color='").append(Theme.hex(health(overview.health())))
                .append("'>").append(overview.health().label().toUpperCase(Locale.ROOT)).append("</font></div>");
        sb.append("<div class='verdict'>").append(esc(overview.verdict())).append("</div>");

        if (!overview.timeline().isEmpty()) {
            sb.append("<div class='sub'>WHAT HAPPENED, IN ORDER</div><table cellspacing='0' cellpadding='0' width='100%'>");
            for (Moment m : overview.timeline()) {
                sb.append("<tr><td class='time' valign='top' width='48' nowrap>").append(m.start() == null ? "" : CLOCK.format(m.start()))
                        .append("</td><td valign='top' width='16'><font color='").append(Theme.hex(color(m.kind()))).append("'>&#9679;</font></td>")
                        .append("<td valign='top' class='item'><b>").append(esc(m.kind())).append("</b>&nbsp;&nbsp;")
                        .append(esc(capital(m.text()))).append("<br><span class='tech'>")
                        .append(technical(m.group())).append(until(m)).append("</span></td>")
                        .append(openCell(m.group())).append("</tr>");
            }
            sb.append("</table>");
            if (overview.hiddenMoments() > 0) {
                sb.append("<div class='dim'>").append(overview.hiddenMoments()).append(" smaller ")
                        .append(overview.hiddenMoments() == 1 ? "problem is" : "problems are").append(" in the list on the left.</div>");
            }
        }

        if (!overview.startWith().isEmpty()) {
            sb.append("<div class='sub'>WHERE TO START</div><table cellspacing='0' cellpadding='0' width='100%'>");
            int n = 0;
            for (ErrorGroup g : overview.startWith()) {
                String meaning = ErrorExplainer.standard().explain(g).title().replace("`", "");
                sb.append("<tr><td class='num' valign='top' width='22' nowrap>").append(++n).append(".</td><td valign='top' class='item'>")
                        .append("<b>").append(esc(meaning)).append("</b><br><span class='tech'>")
                        .append(esc(why(g, overview))).append("<br>").append(technical(g))
                        .append("</span></td>").append(openCell(g)).append("</tr>");
            }
            sb.append("</table>");
        }

        if (!result.groups().isEmpty()) {
            sb.append(tree.html(result, incidents));
        }

        sb.append("<div class='sub'>AT A GLANCE</div><table cellspacing='0' cellpadding='0'>");
        if (overview.peak() != null) {
            LogOverview.Peak p = overview.peak();
            row(sb, "Busiest moment", CLOCK.format(p.start()) + (p.seconds() > 60 ? " – " + CLOCK.format(p.start().plusSeconds(p.seconds())) : "")
                    + ": " + Formats.count(p.errors()) + (p.errors() == 1 ? " error" : " errors") + " and "
                    + Formats.count(p.warnings()) + (p.warnings() == 1 ? " warning" : " warnings")
                    + (p.seconds() > 60 ? "" : " in one minute"), null);
        }
        if (!overview.byKind().isEmpty()) {
            row(sb, "Kinds of problem", overview.byKind().entrySet().stream().limit(6)
                    .map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining("  ·  ")), null);
        }
        if (!overview.places().isEmpty() && !Detail.simple()) {
            row(sb, "Where in the code", overview.places().stream()
                    .map(p -> p.file() + " (" + Formats.count(p.occurrences()) + "×)").collect(Collectors.joining("  ·  ")), null);
        }
        sb.append("</table>");

        sb.append("<div class='sub'>EVERYTHING READ FROM THE LOG</div><table cellspacing='0' cellpadding='0'>");
        for (LogFacts.Fact fact : LogFacts.forLog(result, incidents.size())) {
            if (Detail.simple() && TECHNICAL_FACTS.contains(fact.label())) {
                continue;
            }
            row(sb, fact.label(), fact.value(), fact.meaning());
        }
        sb.append("</table>");
        sb.append("<p class='dim' style='margin-top: 14px;'>Select a problem in the list, or click Open, to see what it means and the log lines behind it.</p>");
        html.setText(sb.append("</body></html>").toString());
        html.setCaretPosition(0);
    }

    /** The technical name and place, kept small under the plain sentence. */
    private static String technical(ErrorGroup g) {
        if (Detail.simple()) {
            return Formats.count(g.count()) + (g.count() == 1 ? " time" : " times");
        }
        return esc(g.displayType()) + (g.pinpoint() != null ? " at&nbsp;<span class='code'>&nbsp;" + esc(g.location()) + "&nbsp;</span>" : "");
    }

    /** Why a problem is on the "where to start" list. */
    private static String why(ErrorGroup g, LogOverview.Overview overview) {
        if (g.severity() == io.causvane.model.Severity.FATAL) {
            return "It stopped the application";
        }
        for (Moment m : overview.timeline()) {
            if (m.group() == g && "Incident".equals(m.kind())) {
                return "It came first; " + m.related() + " other problems probably followed from it";
            }
        }
        return "Happened " + Formats.count(g.count()) + (g.count() == 1 ? " time" : " times") + ", not linked to the others";
    }

    /** "Open ›" in a narrow column of its own, so it lines up and never runs into the text. */
    private static String openCell(ErrorGroup g) {
        return "<td valign='top' align='right' width='64' nowrap><a href='group:" + esc(g.id()) + "'>Open&nbsp;&rsaquo;</a></td>";
    }

    private static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String until(Moment m) {
        LocalDateTime start = m.start();
        LocalDateTime end = m.end();
        if (start == null || end == null || !end.isAfter(start.plusMinutes(1))) {
            return "";
        }
        return "  ·  until " + CLOCK.format(end);
    }

    private static void row(StringBuilder sb, String key, String value, String meaning) {
        sb.append("<tr><td class='k' valign='top' nowrap>").append(esc(key)).append("</td><td width='16'></td><td valign='top'>")
                .append(Theme.markFiles(esc(value), "code"));
        if (meaning != null) {
            sb.append("<br><span class='expl'>").append(esc(meaning)).append("</span>");
        }
        sb.append("</td></tr>");
    }

    private static Color health(LogOverview.Health health) {
        return switch (health) {
            case HEALTHY -> Theme.GREEN;
            case MINOR -> Theme.YELLOW;
            case PROBLEMS -> Theme.ORANGE;
            case SERIOUS -> Theme.RED;
        };
    }

    private static Color color(String kind) {
        return switch (kind) {
            case "Crash" -> Theme.MAGENTA;
            case "Incident" -> Theme.RED;
            case "Error" -> Theme.ORANGE;
            default -> Theme.YELLOW;
        };
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
