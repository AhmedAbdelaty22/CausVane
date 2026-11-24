package io.causvane.explain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.causvane.explain.LogFacts.Fact;
import io.causvane.io.LogSession;
import io.causvane.model.AnalysisResult;
import io.causvane.model.ErrorGroup;
import io.causvane.model.LogContents;
import io.causvane.model.Severity;
import io.causvane.report.ReportWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Everything read from the log, explained")
class LogFactsTest {

    private static Fact fact(List<Fact> facts, String label) {
        return facts.stream().filter(f -> f.label().equals(label)).findFirst()
                .orElseThrow(() -> new AssertionError("no fact " + label + " in " + facts));
    }

    @Test
    void everyEntryIsCountedByLevel(@TempDir Path dir) throws IOException {
        Path log = Files.writeString(dir.resolve("app.log"), """
                2026-03-14 10:00:00 INFO started traceId=aaa111
                2026-03-14 10:00:01 DEBUG cache warmed traceId=aaa111
                2026-03-14 10:00:02 WARN slow query traceId=bbb222
                2026-03-14 10:00:03 ERROR Request failed traceId=bbb222
                java.lang.IllegalStateException: closed
                    at com.acme.Pool.take(Pool.java:12)
                2026-03-14 10:00:04 INFO done
                """);
        AnalysisResult r = new LogSession(log).load(null);
        LogContents c = r.contents();
        assertEquals(2, c.level(Severity.INFO));
        assertEquals(1, c.level(Severity.DEBUG));
        assertEquals(1, c.level(Severity.WARN));
        assertEquals(1, c.level(Severity.ERROR));
        assertEquals(3, c.normal());
        assertEquals(5, c.timestamped());
        assertEquals(1, c.multiLine());
        assertEquals(1, c.withStackTrace());
        assertEquals(4, c.withTraceId());
        assertEquals(2, c.distinctTraceIds());

        List<Fact> facts = LogFacts.forLog(r, 0);
        assertTrue(fact(facts, "Normal activity").value().startsWith("3"), fact(facts, "Normal activity").value());
        assertTrue(fact(facts, "Trace IDs").value().contains("2 different"));
        assertTrue(fact(facts, "Language").value().contains("Java"));
        facts.forEach(f -> assertFalse(f.meaning().isBlank(), "every fact is explained: " + f.label()));
    }

    @Test
    void aProblemListsEverythingKnownAboutIt() throws IOException {
        AnalysisResult r = new LogSession(Path.of("samples", "checkout-db-failover.log")).load(null);
        ErrorGroup g = r.groups().stream().filter(x -> "DataAccessResourceFailureException".equals(x.displayType()))
                .findFirst().orElseThrow();
        List<Fact> facts = LogFacts.forProblem(g, g.samples().get(0), ErrorExplainer.standard().explain(g));
        assertEquals("ERROR", fact(facts, "How serious").value());
        assertEquals("Database", fact(facts, "Kind of problem").value());
        assertTrue(fact(facts, "Started in").value().startsWith("OrderRepository.java:118"), fact(facts, "Started in").value());
        assertTrue(fact(facts, "Chain of errors").value().contains("→"));
        assertTrue(fact(facts, "This occurrence").value().startsWith("line "));
        facts.forEach(f -> assertFalse(f.meaning().isBlank(), "every fact is explained: " + f.label()));
    }

    @Test
    void reportsIncludeTheContents() throws IOException {
        AnalysisResult r = new LogSession(Path.of("samples", "checkout-db-failover.log")).load(null);
        StringBuilder md = new StringBuilder();
        ReportWriter.Format.MARKDOWN.writer().write(r, r.groups(), md);
        assertTrue(md.toString().contains("## What the log contains"));
        assertTrue(md.toString().contains("| Normal activity |"));
        StringBuilder out = new StringBuilder();
        ReportWriter.Format.JSON.writer().write(r, r.groups(), out);
        JsonNode json = new ObjectMapper().readTree(out.toString());
        assertEquals(r.contents().level(Severity.INFO), json.path("contents").path("entriesByLevel").path("INFO").asLong());
        assertTrue(json.path("contents").path("differentTraceIds").asLong() > 0);
    }
}
