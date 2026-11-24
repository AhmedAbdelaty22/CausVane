package io.causvane.report;

import io.causvane.explain.ErrorExplainer;
import io.causvane.explain.Explanation;
import io.causvane.explain.IncidentLinker;
import io.causvane.model.AnalysisResult;
import io.causvane.model.ErrorGroup;
import io.causvane.model.LogEvent;
import io.causvane.model.StackFrame;

import java.util.List;

/** Ticket-ready Markdown summaries of a single error or a whole analysis. */
public final class SummaryFormatter {

    private static final int EXCERPT_LINES = 30;

    private SummaryFormatter() {}

    /** Summary of one deduplicated error, suitable for pasting into a support ticket. */
    public static String errorSummary(AnalysisResult result, ErrorGroup group) {
        StringBuilder sb = new StringBuilder();
        sb.append("**[").append(group.severity()).append("] ").append(Formats.mdText(group.displayType()));
        if (group.pinpoint() != null) {
            sb.append(" at ").append(Formats.mdText(group.location()));
        }
        sb.append("**\n\n");
        appendDetails(sb, result, group,
                IncidentLinker.index(IncidentLinker.find(result.groups())).get(group.id()));
        return sb.toString();
    }

    /** Overview of the top errors of an analysis. */
    public static String overview(AnalysisResult result, List<ErrorGroup> groups, int top) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Log analysis: ").append(Formats.mdText(String.valueOf(result.file().getFileName()))).append("**\n\n");
        sb.append("- Lines scanned: ").append(Formats.count(result.totalLines())).append('\n');
        sb.append("- Errors: ").append(Formats.count(result.errorCount()))
                .append(" (").append(result.uniqueErrorTypes()).append(" unique), warnings: ")
                .append(Formats.count(result.warningCount())).append('\n');
        sb.append("- Time span: ").append(Formats.duration(result.timeSpan()))
                .append(" (").append(Formats.timestamp(result.firstTimestamp())).append(" → ")
                .append(Formats.timestamp(result.lastTimestamp())).append(")\n\n");
        int n = 0;
        for (ErrorGroup g : groups) {
            if (++n > top) {
                sb.append("… and ").append(groups.size() - top).append(" more\n");
                break;
            }
            sb.append(n).append(". [").append(g.severity()).append("] ").append(Formats.mdText(g.displayType()))
                    .append(" at ").append(Formats.mdCode(g.location())).append(" ×").append(g.count());
            if (g.rootCauseType() != null && !g.rootCauseType().equals(g.errorType())) {
                sb.append(" (root cause: ").append(Formats.mdText(ErrorGroup.simpleName(g.rootCauseType()))).append(')');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** "What this means" section: plain-language meaning, likely causes and next steps. */
    static void appendExplanation(StringBuilder sb, Explanation explanation) {
        sb.append("\n**What this means: ").append(explanation.title()).append("** _(").append(explanation.category()).append(")_\n\n");
        sb.append(explanation.meaning());
        if (explanation.detail() != null) {
            sb.append(' ').append(explanation.detail());
        }
        sb.append("\n\n");
        explanation.context().forEach(line -> sb.append("- ").append(line).append('\n'));
        sb.append("\n**Likely causes**\n\n");
        explanation.likelyCauses().forEach(line -> sb.append("- ").append(line).append('\n'));
        sb.append("\n**What to check next**\n\n");
        int n = 0;
        for (String step : explanation.nextSteps()) {
            sb.append(++n).append(". ").append(step).append('\n');
        }
    }

    /** One sentence on how this problem relates to its incident, in plain words; Markdown-safe. */
    static String incidentSentence(ErrorGroup group, IncidentLinker.Membership membership) {
        IncidentLinker.Incident incident = membership.incident();
        ErrorGroup lead = incident.lead();
        return switch (membership.part()) {
            case LEAD -> "This is the first failure of a probable incident: " + incident.followers().size()
                    + " other problem(s) started within " + IncidentLinker.WINDOW.toMinutes()
                    + " minutes after it and look like knock-on effects.";
            case FOLLOWER -> "Probably a knock-on effect of " + describe(lead) + ", which started "
                    + IncidentLinker.gap(lead.firstSeen(), group.firstSeen()) + " earlier. Look at that one first.";
            case EARLY_WARNING -> "A warning sign: it appeared " + IncidentLinker.gap(group.firstSeen(), lead.firstSeen())
                    + " before " + describe(lead) + ".";
        };
    }

    /** "the database connection was cut while in use (`X` at `File.java:12`)", Markdown-safe. */
    static String describe(ErrorGroup group) {
        String meaning = ErrorExplainer.standard().explain(group).title().replace("`", "");
        return Formats.mdText(Character.toLowerCase(meaning.charAt(0)) + meaning.substring(1)) + " ("
                + Formats.mdCode(group.displayType()) + (group.pinpoint() != null ? " at " + Formats.mdCode(group.location()) : "") + ")";
    }

    static void appendDetails(StringBuilder sb, AnalysisResult result, ErrorGroup group,
                              IncidentLinker.Membership membership) {
        appendDetails(sb, result, group);
        if (membership != null) {
            sb.append("\n**Incident:** ").append(incidentSentence(group, membership)).append('\n');
        }
    }

    static void appendDetails(StringBuilder sb, AnalysisResult result, ErrorGroup group) {
        sb.append("- **Log file:** ").append(Formats.mdText(String.valueOf(result.file().getFileName()))).append('\n');
        StackFrame pin = group.pinpoint();
        if (pin != null) {
            sb.append("- **Pinpoint:** ").append(Formats.mdCode(pin.location()));
            if (pin.qualifiedFunction() != null) {
                sb.append(" in ").append(Formats.mdCode(pin.qualifiedFunction()));
            }
            sb.append(pin.application() ? " (application code)" : " (framework / library code)").append('\n');
            if (pin.file() != null && !pin.file().equals(pin.fileName())) {
                sb.append("- **File path:** ").append(Formats.mdCode(pin.file())).append('\n');
            }
        }
        if (group.errorType() != null) {
            sb.append("- **Error:** ").append(Formats.mdCode(group.errorType()));
            if (group.message() != null && !group.message().isBlank()) {
                sb.append(" — ").append(Formats.mdText(Formats.truncate(group.message(), 300)));
            }
            sb.append('\n');
        } else if (group.message() != null) {
            sb.append("- **Message:** ").append(Formats.mdText(Formats.truncate(group.message(), 300))).append('\n');
        }
        if (group.rootCauseType() != null) {
            sb.append("- **Root cause:** ").append(Formats.mdCode(group.rootCauseType()));
            if (group.rootCauseMessage() != null) {
                sb.append(": ").append(Formats.mdText(Formats.truncate(group.rootCauseMessage(), 300)));
            }
            sb.append('\n');
        }
        if (group.chain().size() > 1) {
            sb.append("- **Exception chain:** ")
                    .append(String.join(" → ", group.chain().stream().map(Formats::mdText).toList())).append('\n');
        }
        sb.append("- **Occurrences:** ").append(Formats.count(group.count()))
                .append(" (first ").append(Formats.timestamp(group.firstSeen()))
                .append(", last ").append(Formats.timestamp(group.lastSeen())).append(")\n");
        sb.append("- **Log lines:** first at ").append(group.firstLine()).append(", last at ").append(group.lastLine()).append('\n');
        if (!group.traceIds().isEmpty()) {
            sb.append("- **Trace IDs:** ")
                    .append(String.join(", ", group.traceIds().stream().limit(5).map(Formats::mdCode).toList()));
            if (group.traceIds().size() > 5) {
                sb.append(" (+").append(group.traceIds().size() - 5).append(" more)");
            }
            sb.append('\n');
        }
        appendExplanation(sb, ErrorExplainer.standard().explain(group));
        if (!group.samples().isEmpty()) {
            LogEvent sample = group.samples().get(0);
            int pinIndex = pin == null ? -1 : pin.eventLineIndex();
            int limit = Math.min(sample.lines().size(), Math.max(EXCERPT_LINES, pinIndex + 3));
            // the fence is longer than any backtick run inside, so the log text cannot end the block early
            String fence = Formats.mdFence(sample.lines().subList(0, limit));
            sb.append('\n').append(fence).append("text\n");
            for (int i = 0; i < limit; i++) {
                sb.append(i == pinIndex ? ">>> " : "    ").append(Formats.truncate(sample.lines().get(i), 400)).append('\n');
            }
            if (limit < sample.lines().size()) {
                sb.append("    … ").append(sample.lines().size() - limit).append(" more lines\n");
            }
            sb.append(fence).append('\n');
        }
    }
}
