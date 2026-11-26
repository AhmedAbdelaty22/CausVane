package io.causvane.gui;

import io.causvane.model.ErrorGroup;
import io.causvane.model.LogEvent;
import io.causvane.model.ParsedTrace;
import io.causvane.model.StackFrame;
import io.causvane.model.TraceSection;
import io.causvane.report.Formats;

import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * "The error, step by step": one occurrence of an error as a folding tree. The spine is what the application
 * reported, then each error that caused the one above it, down to the root cause; under the root cause hangs
 * the path through the code, oldest call first, ending where it started. Runs of library code fold into one
 * line. Works the same for every language, because the parser stores every trace in one common form.
 */
final class ErrorTree {

    private static final String MID = "&#9500;&#9472;&nbsp;";
    private static final String END = "&#9492;&#9472;&nbsp;";
    private static final String BAR = "&#9474;&nbsp;&nbsp;&nbsp;";
    private static final String GAP = "&nbsp;&nbsp;&nbsp;&nbsp;";

    /** A step of the tree. {@code html} is already escaped; {@code key} is set when the node folds. */
    private record Node(String key, String html, String detail, Color color, List<Node> children) {}

    /** Branches the reader opened or closed, compared with how they start out. */
    private final Set<String> toggled = new HashSet<>();
    private String shownFor;

    /** Folds library runs again whenever another occurrence is shown; keeps the reader's choices otherwise. */
    void prepare(ErrorGroup group, LogEvent event) {
        String key = group.id() + "@" + event.lineNumber();
        if (!key.equals(shownFor)) {
            shownFor = key;
            toggled.clear();
        }
    }

    boolean click(String link) {
        if (link.startsWith("et:")) {
            String key = link.substring(3);
            if (!toggled.remove(key)) {
                toggled.add(key);
            }
            return true;
        }
        return false;
    }

    String html(LogEvent event) {
        ParsedTrace trace = event.trace();
        StringBuilder sb = new StringBuilder();
        if (trace == null || !trace.hasFrames()) {
            return ""; // a single line with no stack trace: nothing to show as a tree, the panel stays as it was
        }
        sb.append("<p class='sub'>THE ERROR, STEP BY STEP</p>");
        Node reported = new Node(null, "<span class='role'>REPORTED</span>&nbsp;&nbsp;" + quote(event.message()), null, Theme.DIM, new ArrayList<>());
        List<TraceSection> chain = orderedChain(trace);
        Node parent = reported;
        for (int i = 0; i < chain.size(); i++) {
            TraceSection section = chain.get(i);
            boolean root = i == chain.size() - 1;
            String role = root ? (chain.size() == 1 ? "THE ERROR &middot; ROOT CAUSE" : "ROOT CAUSE &middot; where it all began")
                    : i == 0 ? "THE ERROR" : "CAUSED BY";
            Color color = root ? Theme.RED : i == 0 ? Theme.ORANGE : Theme.ORANGE;
            String type = section.exceptionType() == null ? "Error" : ErrorGroup.simpleName(section.exceptionType());
            Node node = new Node(null, "<span class='role' style='color: " + Theme.hex(color) + "'>" + role + "</span>&nbsp;&nbsp;<b>"
                    + esc(type) + "</b>", section.message() == null || section.message().isBlank() ? null : quote(section.message()),
                    color, new ArrayList<>());
            parent.children().add(node);
            parent = node;
        }
        List<StackFrame> frames = new ArrayList<>(framesOf(trace));
        java.util.Collections.reverse(frames); // oldest call first: from where the work came in to where it failed
        if (!frames.isEmpty()) {
            parent.children().add(path(frames, trace.pinpoint()));
        } else {
            parent.children().add(new Node(null, "<span class='dim'>No code locations were listed for this error.</span>", null, Theme.GUTTER, List.of()));
        }
        row(sb, reported, "", "", true);
        return sb.toString();
    }

    /** The errors from what was reported down to the root cause, each matched to its printed section. */
    private static List<TraceSection> orderedChain(ParsedTrace trace) {
        List<TraceSection> candidates = trace.sections().stream()
                .filter(s -> s.kind() != TraceSection.Kind.SUPPRESSED && s.kind() != TraceSection.Kind.OTHER).toList();
        List<TraceSection> ordered = new ArrayList<>();
        Set<TraceSection> used = new LinkedHashSet<>();
        for (String type : trace.chain()) {
            for (TraceSection s : candidates) {
                if (!used.contains(s) && type != null && type.equals(s.exceptionType())) {
                    ordered.add(s);
                    used.add(s);
                    break;
                }
            }
        }
        if (ordered.isEmpty()) {
            ordered.addAll(candidates.isEmpty() ? trace.sections() : candidates);
        }
        if (trace.root() != null && !ordered.contains(trace.root()) && !ordered.isEmpty()) {
            ordered.add(trace.root());
        }
        return ordered;
    }

    /** The frames of the root cause, or of the section where the error started when the root lists none. */
    private static List<StackFrame> framesOf(ParsedTrace trace) {
        if (trace.root() != null && !trace.root().frames().isEmpty()
                && (trace.pinpoint() == null || trace.root().frames().contains(trace.pinpoint()))) {
            return trace.root().frames();
        }
        for (TraceSection section : trace.sections()) {
            if (trace.pinpoint() != null && section.frames().stream().anyMatch(f -> f.eventLineIndex() == trace.pinpoint().eventLineIndex())) {
                return section.frames();
            }
        }
        return trace.root() == null ? List.of() : trace.root().frames();
    }

    /** "How the code got there": the application's own steps one by one, library steps folded in runs. */
    private Node path(List<StackFrame> frames, StackFrame pinpoint) {
        long own = frames.stream().filter(StackFrame::application).count();
        List<Node> steps = new ArrayList<>();
        int run = 0;
        for (int i = 0; i < frames.size(); i++) {
            StackFrame frame = frames.get(i);
            if (!frame.application()) {
                int j = i;
                while (j < frames.size() && !frames.get(j).application()) {
                    j++;
                }
                List<StackFrame> library = frames.subList(i, j);
                String key = "lib:" + run++;
                List<Node> inside = library.stream().map(f -> step(f, false, false)).toList();
                steps.add(new Node(key, "<span class='dim'>" + library.size() + (library.size() == 1 ? " step" : " steps")
                        + " inside libraries</span>", esc(libraries(library)), Theme.GUTTER, inside));
                i = j - 1;
            } else {
                boolean pin = pinpoint != null && frame.eventLineIndex() == pinpoint.eventLineIndex();
                steps.add(step(frame, true, pin));
            }
        }
        return new Node("path", "<b>How the code got there</b>", frames.size() + (frames.size() == 1 ? " step" : " steps")
                + ", the first call at the top  &middot;  " + own + " in the application's own code", Theme.GREEN, steps);
    }

    private static Node step(StackFrame frame, boolean own, boolean pin) {
        String html = (frame.qualifiedFunction() == null ? "<span class='dim'>an unnamed function</span>"
                : "<span class='code'>" + esc(shortName(frame.qualifiedFunction())) + "</span>")
                + (pin ? "&nbsp;&nbsp;<span class='role' style='color: " + Theme.hex(Theme.RED) + "'>&#9664; STARTED HERE</span>" : "");
        String detail = esc(frame.location()) + (own ? " &nbsp;&middot;&nbsp; the application's own code" : "");
        return new Node(null, html, detail, pin ? Theme.RED : own ? Theme.GREEN : Theme.GUTTER, List.of());
    }

    /** "OrderService.placeOrder" from "com.shop.checkout.service.OrderService.placeOrder". */
    private static String shortName(String qualified) {
        String[] parts = qualified.split("\\.");
        return parts.length <= 2 ? qualified : parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    /** The libraries a run of steps belongs to, named the way each language names them. */
    private static String libraries(List<StackFrame> frames) {
        Set<String> names = new LinkedHashSet<>();
        for (StackFrame f : frames) {
            String name = library(f);
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
            if (names.size() == 3) {
                break;
            }
        }
        return String.join(", ", names);
    }

    static String library(StackFrame f) {
        String file = f.file() == null ? "" : f.file().replace('\\', '/');
        for (String marker : new String[] {"site-packages/", "dist-packages/", "node_modules/"}) {
            int at = file.indexOf(marker);
            if (at >= 0) {
                String[] rest = file.substring(at + marker.length()).split("/");
                return rest[0].startsWith("@") && rest.length > 1 ? rest[0] + "/" + rest[1] : rest[0].replace(".py", "");
            }
        }
        if (file.startsWith("node:")) {
            return file.contains("/") ? file.substring(0, file.indexOf('/')) : file;
        }
        int go = file.indexOf("/go/src/");
        if (go >= 0) {
            String rest = file.substring(go + "/go/src/".length());
            return rest.contains("/") ? rest.substring(0, rest.lastIndexOf('/')) : rest;
        }
        int lib = file.indexOf("/lib/python");
        if (lib >= 0) {
            String[] rest = file.substring(lib + 1).split("/");
            return rest.length > 2 ? rest[2].replace(".py", "") : "Python";
        }
        String module = f.module();
        if (module != null && !module.isBlank() && !module.contains("/")) {
            String[] parts = module.split("\\.");
            return parts.length >= 2 ? parts[0] + "." + parts[1] : parts[0];
        }
        if (!file.isEmpty()) {
            String[] parts = file.split("/");
            return parts.length >= 2 ? parts[parts.length - 2] : parts[0];
        }
        return module;
    }

    private void row(StringBuilder sb, Node node, String indent, String branch, boolean top) {
        boolean folds = node.key() != null && !node.children().isEmpty();
        boolean expanded = node.children().isEmpty() ? false : !folds || (startsOpen(node.key()) != toggled.contains(node.key()));
        sb.append("<table cellspacing='0' cellpadding='0' width='100%'><tr>");
        if (!top) {
            int depth = count(indent, BAR) + count(indent, GAP) + 1;
            sb.append("<td valign='top' class='tree' nowrap width='").append(depth * 30).append("'>").append(indent).append(branch).append("</td>");
        }
        sb.append("<td valign='top' class='fact'>");
        if (folds) {
            sb.append("<a class='branch' href='et:").append(node.key()).append("'>").append(expanded ? "&minus;" : "+").append("</a>&nbsp;");
        }
        sb.append("<font color='").append(Theme.hex(node.color())).append("'>&#9679;</font>&nbsp;").append(node.html());
        if (node.detail() != null) {
            sb.append("<br><span class='tech'>").append(node.detail()).append("</span>");
        }
        sb.append("</td></tr></table>");
        if (!expanded) {
            return;
        }
        String childIndent = top ? "" : indent + (branch.equals(END) ? GAP : BAR);
        for (int i = 0; i < node.children().size(); i++) {
            row(sb, node.children().get(i), childIndent, i == node.children().size() - 1 ? END : MID, false);
        }
    }

    /** "How the code got there" starts open; in Expert detail the library runs inside it do too. */
    private static boolean startsOpen(String key) {
        return "path".equals(key) || Detail.expert();
    }

    private static int count(String text, String part) {
        int n = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            n++;
        }
        return n;
    }

    private static String quote(String text) {
        return text == null ? "" : "&ldquo;" + esc(Formats.truncate(text.strip(), 200)) + "&rdquo;";
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
