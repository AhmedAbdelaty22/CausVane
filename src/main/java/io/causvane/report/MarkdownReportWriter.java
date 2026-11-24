package io.causvane.report;

import io.causvane.explain.IncidentLinker;
import io.causvane.explain.LogOverview;
import io.causvane.model.AnalysisResult;
import io.causvane.model.ErrorGroup;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Markdown report: summary metrics, error table and one detail section per error. */
public final class MarkdownReportWriter implements ReportWriter {

    @Override
    public void write(AnalysisResult result, List<ErrorGroup> groups, Appendable out) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# CausVane Report\n\n");
        sb.append("- **File:** ").append(Formats.mdCode(result.file().toAbsolutePath().toString())).append('\n');
        sb.append("- **Size:** ").append(Formats.bytes(result.fileSize())).append('\n');
        sb.append("- **Generated:** ").append(Formats.timestamp(LocalDateTime.now())).append("\n\n");

        sb.append("## Summary\n\n");
        sb.append("| Metric | Value |\n|---|---|\n");
        sb.append("| Total lines scanned | ").append(Formats.count(result.totalLines())).append(" |\n");
        sb.append("| Log events | ").append(Formats.count(result.totalEvents())).append(" |\n");
        sb.append("| Total errors | ").append(Formats.count(result.errorCount())).append(" |\n");
        sb.append("| Warnings | ").append(Formats.count(result.warningCount())).append(" |\n");
        sb.append("| Unique error types | ").append(result.uniqueErrorTypes()).append(" |\n");
        if (result.unlistedOccurrences() > 0) {
            sb.append("| Not listed (over the listing limit) | ").append(Formats.count(result.unlistedOccurrences())).append(" occurrences |\n");
        }
        sb.append("| Time span | ").append(Formats.duration(result.timeSpan())).append(" (")
                .append(Formats.timestamp(result.firstTimestamp())).append(" → ")
                .append(Formats.timestamp(result.lastTimestamp())).append(") |\n\n");

        if (!result.groups().isEmpty()) {
            LogOverview.Overview overview = LogOverview.of(result);
            sb.append("## Overview\n\n");
            sb.append("**").append(overview.health().label()).append(".** ").append(Formats.mdText(overview.verdict())).append("\n\n");
            sb.append("**What happened, in order**\n\n");
            for (LogOverview.Moment m : overview.timeline()) {
                sb.append("- ").append(m.start() == null ? "" : Formats.timestamp(m.start()) + " — ")
                        .append("**").append(m.kind()).append(":** ").append(Formats.mdText(m.text())).append('\n');
            }
            if (overview.hiddenMoments() > 0) {
                sb.append("- and ").append(overview.hiddenMoments()).append(" smaller problems in the list below\n");
            }
            sb.append("\n**Where to start**\n\n");
            int k = 0;
            for (ErrorGroup g : overview.startWith()) {
                sb.append(++k).append(". ").append(SummaryFormatter.describe(g)).append('\n');
            }
            sb.append('\n');
            if (overview.peak() != null) {
                sb.append("- **Busiest moment:** ").append(Formats.timestamp(overview.peak().start())).append(" — ")
                        .append(Formats.count(overview.peak().errors())).append(" errors, ")
                        .append(Formats.count(overview.peak().warnings())).append(" warnings\n");
            }
            if (!overview.byKind().isEmpty()) {
                sb.append("- **Kinds of problem:** ").append(Formats.mdText(overview.byKind().entrySet().stream()
                        .map(e -> e.getKey() + " " + e.getValue()).collect(java.util.stream.Collectors.joining(" · ")))).append('\n');
            }
            if (!overview.places().isEmpty()) {
                sb.append("- **Where in the code:** ").append(overview.places().stream()
                        .map(p -> Formats.mdCode(p.file()) + " (" + Formats.count(p.occurrences()) + "×)")
                        .collect(java.util.stream.Collectors.joining(" · "))).append('\n');
            }
            sb.append('\n');
        }
        sb.append("## What the log contains\n\n| Fact | Value | What it means |\n|---|---|---|\n");
        for (io.causvane.explain.LogFacts.Fact fact : io.causvane.explain.LogFacts.forLog(result,
                io.causvane.explain.IncidentLinker.find(result.groups()).size())) {
            sb.append("| ").append(fact.label()).append(" | ").append(Formats.mdText(fact.value()))
                    .append(" | ").append(fact.meaning()).append(" |\n");
        }
        sb.append('\n');
        sb.append("## Deduplicated errors\n\n");
        if (groups.isEmpty()) {
            sb.append("_No errors found._\n");
        } else {
            sb.append("| # | Severity | Error type | Location | Count | First seen | Last seen |\n");
            sb.append("|---|---|---|---|---:|---|---|\n");
            int n = 0;
            for (ErrorGroup g : groups) {
                sb.append("| ").append(++n).append(" | ").append(g.severity()).append(" | ")
                        .append(Formats.mdText(g.displayType())).append(" | ").append(Formats.mdCode(g.location())).append(" | ")
                        .append(Formats.count(g.count())).append(" | ").append(Formats.timestamp(g.firstSeen()))
                        .append(" | ").append(Formats.timestamp(g.lastSeen())).append(" |\n");
            }
            List<IncidentLinker.Incident> incidents = IncidentLinker.find(result.groups());
            Map<String, IncidentLinker.Membership> membership = IncidentLinker.index(incidents);
            if (!incidents.isEmpty()) {
                sb.append("\n## Probable incidents\n\n");
                sb.append("Problems linked by timing and kind of problem: a first failure and the problems that started ")
                        .append("within ").append(IncidentLinker.WINDOW.toMinutes()).append(" minutes after it. A strong hint, not proof.\n");
                int k = 0;
                for (IncidentLinker.Incident incident : incidents) {
                    sb.append("\n### Incident ").append(++k).append(", from ")
                            .append(Formats.timestamp(incident.lead().firstSeen())).append("\n\n");
                    sb.append("- **First failure:** ").append(SummaryFormatter.describe(incident.lead())).append('\n');
                    for (ErrorGroup w : incident.earlyWarnings()) {
                        sb.append("- **Warning sign** (").append(IncidentLinker.gap(w.firstSeen(), incident.lead().firstSeen()))
                                .append(" before): ").append(SummaryFormatter.describe(w)).append('\n');
                    }
                    for (ErrorGroup f : incident.followers()) {
                        sb.append("- **Knock-on** (+").append(IncidentLinker.gap(incident.lead().firstSeen(), f.firstSeen()))
                                .append(", ×").append(Formats.count(f.count())).append("): ").append(SummaryFormatter.describe(f)).append('\n');
                    }
                }
            }
            sb.append("\n## Details\n\n");
            n = 0;
            for (ErrorGroup g : groups) {
                sb.append("### ").append(++n).append(". [").append(g.severity()).append("] ")
                        .append(Formats.mdText(g.displayType())).append(" — ").append(Formats.mdText(g.location())).append("\n\n");
                SummaryFormatter.appendDetails(sb, result, g, membership.get(g.id()));
                sb.append('\n');
            }
        }
        out.append(sb);
    }
}
