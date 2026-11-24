package io.causvane.explain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.causvane.explain.LogOverview.Health;
import io.causvane.explain.LogOverview.Overview;
import io.causvane.io.LogSession;
import io.causvane.model.AnalysisResult;
import io.causvane.report.ReportWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("The whole log in plain terms")
class LogOverviewTest {

    private static AnalysisResult analyze(String sample) throws IOException {
        return new LogSession(Path.of("samples", sample)).load(null);
    }

    @Test
    void anIncidentLogIsSummedUpAroundItsFirstFailure() throws IOException {
        AnalysisResult r = analyze("checkout-db-failover.log");
        Overview o = LogOverview.of(r);
        assertEquals(Health.SERIOUS, o.health());
        assertTrue(o.verdict().startsWith("Most of the trouble looks like one incident: the database connection was cut while in use, starting at 14:05"), o.verdict());
        assertEquals(4, o.timeline().size());
        assertEquals("Incident", o.timeline().get(1).kind());
        assertTrue(o.timeline().get(1).text().endsWith("followed by 15 related problems"));
        assertEquals("Error", o.timeline().get(0).kind(), "the unrelated bug that started earlier is listed first");
        assertEquals("DataAccessResourceFailureException", o.startWith().get(0).displayType());
        assertEquals("NullPointerException", o.startWith().get(1).displayType());
        assertEquals(5, o.byKind().get("Database"));
        assertEquals("OrderService.java", o.places().get(0).file());
        assertEquals(48, o.peak().errors());
    }

    @Test
    void aCrashComesFirst() throws IOException {
        Overview o = LogOverview.of(analyze("ingest-crash-loop.log"));
        assertTrue(o.verdict().startsWith("The application stopped with a fatal error"), o.verdict());
        assertEquals("panic", o.startWith().get(0).displayType());
    }

    @Test
    void healthReflectsWhatWasFound(@TempDir Path dir) throws IOException {
        Path clean = Files.writeString(dir.resolve("clean.log"), "2026-03-14 10:00:00 INFO all good\n");
        Overview healthy = LogOverview.of(new LogSession(clean).load(null));
        assertEquals(Health.HEALTHY, healthy.health());
        assertTrue(healthy.verdict().startsWith("No errors or warnings"));

        Path warn = Files.writeString(dir.resolve("warn.log"), "2026-03-14 10:00:00 WARN Redis latency 412 ms\n");
        Overview minor = LogOverview.of(new LogSession(warn).load(null));
        assertEquals(Health.MINOR, minor.health());
        assertEquals("Warning", minor.timeline().get(0).kind());

        Path one = Files.writeString(dir.resolve("one.log"), "2026-03-14 10:00:00 ERROR java.lang.IllegalStateException: boom\n");
        assertEquals(Health.PROBLEMS, LogOverview.of(new LogSession(one).load(null)).health());
    }

    @Test
    void reportsCarryTheOverview() throws IOException {
        AnalysisResult r = analyze("payments-worker-deadlocks.log");
        StringBuilder markdown = new StringBuilder();
        ReportWriter.Format.MARKDOWN.writer().write(r, r.groups(), markdown);
        assertTrue(markdown.toString().contains("## Overview"));
        assertTrue(markdown.toString().contains("**What happened, in order**"));
        assertTrue(markdown.toString().contains("**Where to start**"));

        StringBuilder json = new StringBuilder();
        ReportWriter.Format.JSON.writer().write(r, r.groups(), json);
        JsonNode overview = new ObjectMapper().readTree(json.toString()).path("overview");
        assertEquals("Serious problems", overview.path("health").asText());
        assertEquals(3, overview.path("whatHappened").size());
        assertEquals("OperationalError", overview.path("whereToStart").get(0).path("errorType").asText().replaceAll(".*\\.", ""));
    }
}
