package io.logreader.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.logreader.explain.ErrorExplainer;
import io.logreader.explain.Explanation;
import io.logreader.explain.IncidentLinker;
import io.logreader.explain.LogOverview;
import io.logreader.model.AnalysisResult;
import io.logreader.model.ErrorGroup;
import io.logreader.model.LogEvent;
import io.logreader.model.StackFrame;
import io.logreader.model.TraceSection;

import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/** Machine-readable JSON report. */
public final class JsonReportWriter implements ReportWriter {

    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Override
    public void write(AnalysisResult result, List<ErrorGroup> groups, Appendable out) throws IOException {
        ObjectNode root = mapper.createObjectNode();
        root.put("tool", "LogReader");
        root.put("file", result.file().toAbsolutePath().toString());
        root.put("fileSizeBytes", result.fileSize());
        root.put("generatedAt", LocalDateTime.now().toString());

        ObjectNode summary = root.putObject("summary");
        summary.put("totalLines", result.totalLines());
        summary.put("totalEvents", result.totalEvents());
        summary.put("totalErrors", result.errorCount());
        summary.put("totalWarnings", result.warningCount());
        summary.put("uniqueErrorTypes", result.uniqueErrorTypes());
        summary.put("unlistedOccurrences", result.unlistedOccurrences());
        LogOverview.Overview whole = LogOverview.of(result);
        ObjectNode overviewNode = root.putObject("overview");
        overviewNode.put("health", whole.health().label());
        overviewNode.put("verdict", whole.verdict());
        ArrayNode timeline = overviewNode.putArray("whatHappened");
        for (LogOverview.Moment m : whole.timeline()) {
            ObjectNode moment = timeline.addObject();
            putTime(moment, "from", m.start());
            putTime(moment, "until", m.end());
            moment.put("kind", m.kind());
            moment.put("text", m.text());
            moment.put("errorType", m.group().errorType());
            moment.put("location", m.group().location());
        }
        ArrayNode start = overviewNode.putArray("whereToStart");
        whole.startWith().forEach(g -> start.add(brief(g, null)));
        ObjectNode kinds = overviewNode.putObject("kindsOfProblem");
        whole.byKind().forEach(kinds::put);
        if (whole.peak() != null) {
            ObjectNode peak = overviewNode.putObject("busiestMoment");
            putTime(peak, "from", whole.peak().start());
            peak.put("seconds", whole.peak().seconds());
            peak.put("errors", whole.peak().errors());
            peak.put("warnings", whole.peak().warnings());
        }
        ArrayNode places = overviewNode.putArray("whereInTheCode");
        whole.places().forEach(p -> places.addObject().put("file", p.file()).put("problems", p.problems()).put("occurrences", p.occurrences()));
        io.logreader.model.LogContents c = result.contents();
        ObjectNode contents = root.putObject("contents");
        ObjectNode levels = contents.putObject("entriesByLevel");
        for (io.logreader.model.Severity level : io.logreader.model.Severity.values()) {
            levels.put(level.name(), c.level(level));
        }
        contents.put("entriesWithTime", c.timestamped());
        contents.put("entriesOverSeveralLines", c.multiLine());
        contents.put("jsonEntries", c.structured());
        contents.put("entriesWithStackTrace", c.withStackTrace());
        contents.put("entriesWithTraceId", c.withTraceId());
        contents.put("differentTraceIds", c.distinctTraceIds());
        putTime(summary, "firstTimestamp", result.firstTimestamp());
        putTime(summary, "lastTimestamp", result.lastTimestamp());
        Duration span = result.timeSpan();
        if (span != null) {
            summary.put("timeSpanSeconds", span.getSeconds());
        }

        List<IncidentLinker.Incident> incidents = IncidentLinker.find(result.groups());
        java.util.Map<String, IncidentLinker.Membership> membership = IncidentLinker.index(incidents);
        ArrayNode incidentArray = root.putArray("incidents");
        for (IncidentLinker.Incident incident : incidents) {
            ObjectNode node = incidentArray.addObject();
            putTime(node, "startedAt", incident.lead().firstSeen());
            node.set("firstFailure", brief(incident.lead(), null));
            ArrayNode knockOn = node.putArray("knockOn");
            incident.followers().forEach(f -> knockOn.add(brief(f, incident.lead())));
            ArrayNode warnings = node.putArray("warningSigns");
            incident.earlyWarnings().forEach(w -> warnings.add(brief(w, incident.lead())));
        }
        ArrayNode errors = root.putArray("errors");
        for (ErrorGroup g : groups) {
            ObjectNode e = errors.addObject();
            e.put("severity", g.severity().name());
            if (membership.containsKey(g.id())) {
                e.put("incidentRole", switch (membership.get(g.id()).part()) {
                    case LEAD -> "firstFailure";
                    case FOLLOWER -> "knockOn";
                    case EARLY_WARNING -> "warningSign";
                });
            }
            e.put("errorType", g.errorType());
            e.put("message", g.message());
            e.put("language", g.language().displayName());
            e.put("count", g.count());
            putTime(e, "firstSeen", g.firstSeen());
            putTime(e, "lastSeen", g.lastSeen());
            e.put("firstLogLine", g.firstLine());
            e.put("lastLogLine", g.lastLine());
            if (g.pinpoint() != null) {
                e.set("pinpoint", frame(g.pinpoint()));
            }
            if (g.rootCauseType() != null) {
                ObjectNode cause = e.putObject("rootCause");
                cause.put("type", g.rootCauseType());
                cause.put("message", g.rootCauseMessage());
            }
            Explanation explanation = ErrorExplainer.standard().explain(g);
            ObjectNode explained = e.putObject("explanation");
            explained.put("category", explanation.category());
            explained.put("title", explanation.title());
            explained.put("meaning", explanation.meaning());
            explained.put("detail", explanation.detail());
            explanation.context().forEach(explained.putArray("context")::add);
            explanation.likelyCauses().forEach(explained.putArray("likelyCauses")::add);
            explanation.nextSteps().forEach(explained.putArray("nextSteps")::add);
            ArrayNode chain = e.putArray("exceptionChain");
            g.chain().forEach(chain::add);
            ArrayNode ids = e.putArray("traceIds");
            g.traceIds().forEach(ids::add);

            if (!g.samples().isEmpty()) {
                LogEvent sample = g.samples().get(0);
                if (sample.trace() != null) {
                    ArrayNode frames = e.putArray("rootCauseFrames");
                    TraceSection section = sample.trace().root();
                    section.frames().stream().limit(50).forEach(f -> frames.add(frame(f)));
                }
                ArrayNode excerpt = e.putArray("excerpt");
                sample.lines().stream().limit(60).forEach(excerpt::add);
            }
        }
        out.append(mapper.writeValueAsString(root)).append('\n');
    }

    private ObjectNode brief(ErrorGroup g, ErrorGroup lead) {
        ObjectNode node = mapper.createObjectNode();
        node.put("errorType", g.errorType());
        node.put("location", g.location());
        node.put("meaning", ErrorExplainer.standard().explain(g).title().replace("`", ""));
        node.put("count", g.count());
        if (lead != null && g.firstSeen() != null && lead.firstSeen() != null) {
            node.put("secondsFromFirstFailure", java.time.Duration.between(lead.firstSeen(), g.firstSeen()).getSeconds());
        }
        return node;
    }

    private ObjectNode frame(StackFrame f) {
        ObjectNode node = mapper.createObjectNode();
        node.put("location", f.location());
        node.put("file", f.file());
        node.put("module", f.module());
        node.put("function", f.function());
        if (f.line() > 0) {
            node.put("line", f.line());
        }
        if (f.column() > 0) {
            node.put("column", f.column());
        }
        node.put("applicationCode", f.application());
        return node;
    }

    private static void putTime(ObjectNode node, String field, LocalDateTime value) {
        if (value != null) {
            node.put(field, Formats.timestamp(value));
        }
    }
}
