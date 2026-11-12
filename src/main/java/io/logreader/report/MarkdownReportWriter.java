package io.logreader.report;

import io.logreader.model.AnalysisResult;
import io.logreader.model.ErrorGroup;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

/** Markdown report: summary metrics, error table and one detail section per error. */
public final class MarkdownReportWriter implements ReportWriter {

    @Override
    public void write(AnalysisResult result, List<ErrorGroup> groups, Appendable out) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# LogReader Report\n\n");
        sb.append("- **File:** `").append(result.file().toAbsolutePath()).append("`\n");
        sb.append("- **Size:** ").append(Formats.bytes(result.fileSize())).append('\n');
        sb.append("- **Generated:** ").append(Formats.timestamp(LocalDateTime.now())).append("\n\n");

        sb.append("## Summary\n\n");
        sb.append("| Metric | Value |\n|---|---|\n");
        sb.append("| Total lines scanned | ").append(Formats.count(result.totalLines())).append(" |\n");
        sb.append("| Log events | ").append(Formats.count(result.totalEvents())).append(" |\n");
        sb.append("| Total errors | ").append(Formats.count(result.errorCount())).append(" |\n");
        sb.append("| Warnings | ").append(Formats.count(result.warningCount())).append(" |\n");
        sb.append("| Unique error types | ").append(result.uniqueErrorTypes()).append(" |\n");
        sb.append("| Time span | ").append(Formats.duration(result.timeSpan())).append(" (")
                .append(Formats.timestamp(result.firstTimestamp())).append(" → ")
                .append(Formats.timestamp(result.lastTimestamp())).append(") |\n\n");

        sb.append("## Deduplicated errors\n\n");
        if (groups.isEmpty()) {
            sb.append("_No errors found._\n");
        } else {
            sb.append("| # | Severity | Error type | Location | Count | First seen | Last seen |\n");
            sb.append("|---|---|---|---|---:|---|---|\n");
            int n = 0;
            for (ErrorGroup g : groups) {
                sb.append("| ").append(++n).append(" | ").append(g.severity()).append(" | ")
                        .append(cell(g.displayType())).append(" | `").append(cell(g.location())).append("` | ")
                        .append(Formats.count(g.count())).append(" | ").append(Formats.timestamp(g.firstSeen()))
                        .append(" | ").append(Formats.timestamp(g.lastSeen())).append(" |\n");
            }
            sb.append("\n## Details\n\n");
            n = 0;
            for (ErrorGroup g : groups) {
                sb.append("### ").append(++n).append(". [").append(g.severity()).append("] ")
                        .append(g.displayType()).append(" — ").append(g.location()).append("\n\n");
                SummaryFormatter.appendDetails(sb, result, g);
                sb.append('\n');
            }
        }
        out.append(sb);
    }

    private static String cell(String text) {
        return text == null ? "" : text.replace("|", "\\|");
    }
}
