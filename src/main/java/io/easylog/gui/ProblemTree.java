package io.easylog.gui;

import io.easylog.explain.ErrorExplainer;
import io.easylog.explain.Explanation;
import io.easylog.explain.IncidentLinker;
import io.easylog.model.AnalysisResult;
import io.easylog.model.ErrorGroup;
import io.easylog.model.Severity;
import io.easylog.report.Formats;

import java.awt.Color;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * "Map of all problems": every problem of the log as a folding tree. Each probable incident is a branch
 * (its first failure, warning signs and knock-on effects); every other problem sits under its kind
 * (Database, Network, Null reference…). Branches open and close with a click; each problem opens with Open.
 */
final class ProblemTree {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final String MID = "&#9500;&#9472;&nbsp;";  // ├─
    private static final String END = "&#9492;&#9472;&nbsp;";  // └─
    private static final String BAR = "&#9474;&nbsp;&nbsp;&nbsp;"; // │
    private static final String GAP = "&nbsp;&nbsp;&nbsp;&nbsp;";

    /** A branch or a problem of the tree. */
    private record Node(String key, String label, String detail, Color color, ErrorGroup problem, List<Node> children) {}

    private final Set<String> open = new HashSet<>();
    private Object shownFor;

    /** Opens the first incident the first time a log is shown; keeps the reader's choices after that. */
    void prepare(AnalysisResult result, List<IncidentLinker.Incident> incidents) {
        if (shownFor != result.file()) {
            shownFor = result.file();
            open.clear();
            open.add("root");
            if (!incidents.isEmpty()) {
                open.add("incident:0");
            }
        }
    }

    /** Handles a click on a branch; true when the link was the tree's own. */
    boolean click(String link, AnalysisResult result, List<IncidentLinker.Incident> incidents) {
        if (link.startsWith("tree:")) {
            String key = link.substring(5);
            if (!open.remove(key)) {
                open.add(key);
            }
            return true;
        }
        if (link.equals("tree-all")) {
            collect(build(result, incidents), open);
            return true;
        }
        if (link.equals("tree-none")) {
            open.clear();
            open.add("root");
            return true;
        }
        return false;
    }

    private static void collect(Node node, Set<String> keys) {
        if (node.problem() == null) {
            keys.add(node.key());
            node.children().forEach(child -> collect(child, keys));
        }
    }

    String html(AnalysisResult result, List<IncidentLinker.Incident> incidents) {
        Node root = build(result, incidents);
        StringBuilder sb = new StringBuilder();
        sb.append("<div class='sub'>MAP OF ALL PROBLEMS &nbsp;<a class='small' href='tree-all'>open all</a>")
                .append(" &nbsp;<a class='small' href='tree-none'>close all</a></div>");
        row(sb, root, "", "", true);
        return sb.toString();
    }

    private void row(StringBuilder sb, Node node, String indent, String branch, boolean root) {
        boolean leaf = node.problem() != null;
        boolean expanded = !leaf && open.contains(node.key());
        // each row is its own small table, so the branch lines sit right next to the row's label
        sb.append("<table cellspacing='0' cellpadding='0' width='100%'><tr>");
        if (!indent.isEmpty() || !branch.isEmpty()) {
            int depth = count(indent, BAR) + count(indent, GAP) + 1;
            sb.append("<td valign='top' class='tree' nowrap width='").append(depth * 30).append("'>").append(indent).append(branch).append("</td>");
        }
        sb.append("<td valign='top' class='node'>");
        if (!leaf) {
            sb.append("<a class='branch' href='tree:").append(node.key()).append("' title='")
                    .append(expanded ? "Close" : "Open").append(" this branch'>").append(expanded ? "&minus;" : "+").append("</a>&nbsp;");
        }
        sb.append("<font color='").append(Theme.hex(node.color())).append("'>&#9679;</font>&nbsp;");
        sb.append(leaf ? "" : "<b>").append(esc(node.label())).append(leaf ? "" : "</b>");
        if (node.detail() != null) {
            sb.append("<br><span class='tech'>").append(node.detail()).append("</span>");
        }
        sb.append("</td><td valign='top' align='right' width='64' nowrap>");
        if (leaf) {
            sb.append("<a href='group:").append(esc(node.problem().id())).append("'>Open&nbsp;&rsaquo;</a>");
        }
        sb.append("</td></tr></table>");
        if (!expanded) {
            return;
        }
        String childIndent = root ? "" : indent + (branch.equals(END) ? GAP : BAR);
        for (int i = 0; i < node.children().size(); i++) {
            boolean last = i == node.children().size() - 1;
            row(sb, node.children().get(i), childIndent, last ? END : MID, false);
        }
    }

    private static Node build(AnalysisResult result, List<IncidentLinker.Incident> incidents) {
        ErrorExplainer explainer = ErrorExplainer.standard();
        Map<ErrorGroup, Explanation> explained = new LinkedHashMap<>();
        for (ErrorGroup g : result.groups()) {
            explained.put(g, explainer.explain(g));
        }
        List<Node> top = new ArrayList<>();
        Set<ErrorGroup> placed = new HashSet<>();
        for (int i = 0; i < incidents.size(); i++) {
            IncidentLinker.Incident incident = incidents.get(i);
            ErrorGroup lead = incident.lead();
            List<Node> parts = new ArrayList<>();
            parts.add(problem(lead, explained, "First failure: "));
            placed.add(lead);
            if (!incident.earlyWarnings().isEmpty()) {
                parts.add(branch("incident:" + i + ":warnings", "Warning signs before it", incident.earlyWarnings(), explained, Theme.YELLOW));
                placed.addAll(incident.earlyWarnings());
            }
            if (!incident.followers().isEmpty()) {
                parts.add(branch("incident:" + i + ":followers", "Knock-on effects after it", incident.followers(), explained, Theme.BLUE));
                placed.addAll(incident.followers());
            }
            long occurrences = parts.stream().mapToLong(ProblemTree::occurrences).sum();
            String when = lead.firstSeen() == null ? "" : "  ·  from " + CLOCK.format(lead.firstSeen());
            top.add(new Node("incident:" + i, "Incident: " + plain(explained.get(lead)),
                    incident.size() + " problems  ·  " + Formats.count(occurrences) + " times" + esc(when), Theme.RED, null, parts));
        }
        Map<String, List<ErrorGroup>> byKind = new LinkedHashMap<>();
        for (ErrorGroup g : result.groups()) {
            if (!placed.contains(g)) {
                byKind.computeIfAbsent(explained.get(g).category(), k -> new ArrayList<>()).add(g);
            }
        }
        List<Map.Entry<String, List<ErrorGroup>>> kinds = new ArrayList<>(byKind.entrySet());
        kinds.sort(Comparator.comparingLong((Map.Entry<String, List<ErrorGroup>> e) ->
                e.getValue().stream().mapToLong(ErrorGroup::count).sum()).reversed());
        for (Map.Entry<String, List<ErrorGroup>> kind : kinds) {
            Severity worst = kind.getValue().stream().map(ErrorGroup::severity).max(Comparator.naturalOrder()).orElse(Severity.WARN);
            top.add(branch("kind:" + kind.getKey(), kind.getKey(), kind.getValue(), explained, Theme.severity(worst)));
        }
        long all = result.groups().stream().mapToLong(ErrorGroup::count).sum();
        return new Node("root", "The whole log", result.groups().size() + " problems  ·  " + Formats.count(all) + " times  ·  "
                + incidents.size() + (incidents.size() == 1 ? " probable incident" : " probable incidents"), Theme.DIM, null, top);
    }

    private static Node branch(String key, String label, List<ErrorGroup> problems, Map<ErrorGroup, Explanation> explained, Color color) {
        List<ErrorGroup> sorted = new ArrayList<>(problems);
        sorted.sort(Comparator.comparingLong(ErrorGroup::count).reversed());
        List<Node> children = sorted.stream().map(g -> problem(g, explained, "")).toList();
        long times = sorted.stream().mapToLong(ErrorGroup::count).sum();
        return new Node(key, label, sorted.size() + (sorted.size() == 1 ? " problem" : " problems") + "  ·  " + Formats.count(times)
                + (times == 1 ? " time" : " times"), color, null, children);
    }

    private static Node problem(ErrorGroup g, Map<ErrorGroup, Explanation> explained, String prefix) {
        String detail = esc(g.displayType()) + (g.pinpoint() != null ? " at&nbsp;<span class='code'>&nbsp;" + esc(g.location()) + "&nbsp;</span>" : "")
                + " &nbsp;&middot;&nbsp; " + Formats.count(g.count()) + (g.count() == 1 ? " time" : " times")
                + (g.firstSeen() != null ? " &nbsp;&middot;&nbsp; from " + CLOCK.format(g.firstSeen()) : "");
        return new Node("problem:" + g.id(), prefix + plain(explained.get(g)), detail, Theme.severity(g.severity()), g, List.of());
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            n++;
        }
        return n;
    }

    private static long occurrences(Node node) {
        return node.problem() != null ? node.problem().count() : node.children().stream().mapToLong(ProblemTree::occurrences).sum();
    }

    private static String plain(Explanation explanation) {
        String title = explanation.title().replace("`", "");
        return Objects.requireNonNullElse(title, "");
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
