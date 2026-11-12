package io.logreader.report;

import io.logreader.explain.ErrorExplainer;
import io.logreader.explain.Explanation;
import io.logreader.model.AnalysisResult;
import io.logreader.model.ErrorGroup;
import io.logreader.model.LogEvent;
import io.logreader.model.StackFrame;

import java.util.List;

/** Ticket-ready Markdown summaries of a single error or a whole analysis. */
public final class SummaryFormatter {

    private static final int EXCERPT_LINES = 30;

    private SummaryFormatter() {}

    /** Summary of one deduplicated error, suitable for pasting into a support ticket. */
    public static String errorSummary(AnalysisResult result, ErrorGroup group) {
        StringBuilder sb = new StringBuilder();
        sb.append("**[").append(group.severity()).append("] ").append(group.displayType());
        if (group.pinpoint() != null) {
            sb.append(" at ").append(group.location());
        }
        sb.append("**\n\n");
        appendDetails(sb, result, group);
        return sb.toString();
    }

    /** Overview of the top errors of an analysis. */
    public static String overview(AnalysisResult result, List<ErrorGroup> groups, int top) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Log analysis: ").append(result.file().getFileName()).append("**\n\n");
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
            sb.append(n).append(". [").append(g.severity()).append("] ").append(g.displayType())
                    .append(" at `").append(g.location()).append("` ×").append(g.count());
            if (g.rootCauseType() != null && !g.rootCauseType().equals(g.errorType())) {
                sb.append(" (root cause: ").append(ErrorGroup.simpleName(g.rootCauseType())).append(')');
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

    static void appendDetails(StringBuilder sb, AnalysisResult result, ErrorGroup group) {
        sb.append("- **Log file:** ").append(result.file().getFileName()).append('\n');
        StackFrame pin = group.pinpoint();
        if (pin != null) {
            sb.append("- **Pinpoint:** `").append(pin.location()).append('`');
            if (pin.qualifiedFunction() != null) {
                sb.append(" in `").append(pin.qualifiedFunction()).append('`');
            }
            sb.append(pin.application() ? " (application code)" : " (framework / library code)").append('\n');
            if (pin.file() != null && !pin.file().equals(pin.fileName())) {
                sb.append("- **File path:** `").append(pin.file()).append("`\n");
            }
        }
        if (group.errorType() != null) {
            sb.append("- **Error:** `").append(group.errorType()).append('`');
            if (group.message() != null && !group.message().isBlank()) {
                sb.append(" — ").append(Formats.truncate(group.message(), 300));
            }
            sb.append('\n');
        } else if (group.message() != null) {
            sb.append("- **Message:** ").append(Formats.truncate(group.message(), 300)).append('\n');
        }
        if (group.rootCauseType() != null) {
            sb.append("- **Root cause:** `").append(group.rootCauseType()).append('`');
            if (group.rootCauseMessage() != null) {
                sb.append(": ").append(Formats.truncate(group.rootCauseMessage(), 300));
            }
            sb.append('\n');
        }
        if (group.chain().size() > 1) {
            sb.append("- **Exception chain:** ").append(String.join(" → ", group.chain())).append('\n');
        }
        sb.append("- **Occurrences:** ").append(Formats.count(group.count()))
                .append(" (first ").append(Formats.timestamp(group.firstSeen()))
                .append(", last ").append(Formats.timestamp(group.lastSeen())).append(")\n");
        sb.append("- **Log lines:** first at ").append(group.firstLine()).append(", last at ").append(group.lastLine()).append('\n');
        if (!group.traceIds().isEmpty()) {
            sb.append("- **Trace IDs:** ")
                    .append(String.join(", ", group.traceIds().stream().limit(5).toList()));
            if (group.traceIds().size() > 5) {
                sb.append(" (+").append(group.traceIds().size() - 5).append(" more)");
            }
            sb.append('\n');
        }
        appendExplanation(sb, ErrorExplainer.standard().explain(group));
        if (!group.samples().isEmpty()) {
            LogEvent sample = group.samples().get(0);
            int pinIndex = pin == null ? -1 : pin.eventLineIndex();
            sb.append("\n```text\n");
            int limit = Math.min(sample.lines().size(), Math.max(EXCERPT_LINES, pinIndex + 3));
            for (int i = 0; i < limit; i++) {
                sb.append(i == pinIndex ? ">>> " : "    ").append(Formats.truncate(sample.lines().get(i), 400)).append('\n');
            }
            if (limit < sample.lines().size()) {
                sb.append("    … ").append(sample.lines().size() - limit).append(" more lines\n");
            }
            sb.append("```\n");
        }
    }
}
