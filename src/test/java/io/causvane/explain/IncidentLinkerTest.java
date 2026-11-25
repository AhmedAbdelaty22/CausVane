package io.causvane.explain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.causvane.explain.IncidentLinker.Incident;
import io.causvane.explain.IncidentLinker.Part;
import io.causvane.io.LogSession;
import io.causvane.model.AnalysisResult;
import io.causvane.model.ErrorGroup;
import io.causvane.report.ReportWriter;
import io.causvane.report.SummaryFormatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Problems that are probably one incident are linked to its first failure")
class IncidentLinkerTest {

    private static AnalysisResult analyze(String sample) throws IOException {
        return new LogSession(Path.of("samples", sample)).load(null);
    }

    private static ErrorGroup at(AnalysisResult r, String type, String location) {
        return r.groups().stream().filter(g -> g.displayType().equals(type) && g.location().equals(location)).findFirst().orElseThrow();
    }

    @Test
    void databaseFailoverIsOneIncidentWithItsWarningSigns() throws IOException {
        AnalysisResult r = analyze("checkout-db-failover.log");
        List<Incident> incidents = IncidentLinker.find(r.groups());
        assertEquals(1, incidents.size());
        Incident incident = incidents.get(0);
        assertEquals(at(r, "DataAccessResourceFailureException", "OrderRepository.java:118"), incident.lead());
        assertEquals(3, incident.earlyWarnings().size(), "slow queries and the pause before the failover");
        assertTrue(incident.followers().contains(at(r, "CallNotPermittedException", "OrderService.java:131")));
        assertTrue(incident.followers().contains(at(r, "CannotCreateTransactionException", "OrderRepository.java:118")));
        assertEquals(12, incident.followers().size());

        Map<String, IncidentLinker.Membership> index = IncidentLinker.index(incidents);
        assertNull(index.get(at(r, "NullPointerException", "PromoCodeService.java:63").id()), "an unrelated code bug is not linked");
        assertNull(index.get(at(r, "IllegalStateException", "LedgerVerifier.java:58").id()));
        assertEquals(Part.FOLLOWER, index.get(at(r, "CallNotPermittedException", "OrderService.java:131").id()).part());
    }

    @Test
    void otherSamplesLinkTheRightFirstFailure() throws IOException {
        AnalysisResult gateway = analyze("api-gateway-cert-expiry.log");
        Incident cert = IncidentLinker.find(gateway.groups()).get(0);
        assertEquals(at(gateway, "Error", "paymentsClient.js:88"), cert.lead());
        assertEquals("A security certificate is about to expire",
                ErrorExplainer.standard().explain(cert.earlyWarnings().get(0)).title());
        assertTrue(cert.followers().contains(at(gateway, "Error", "retryQueue.js:64")));
        assertFalse(cert.followers().contains(at(gateway, "TypeError", "plans.js:19")));

        AnalysisResult worker = analyze("payments-worker-deadlocks.log");
        List<Incident> workerIncidents = IncidentLinker.find(worker.groups());
        assertEquals(1, workerIncidents.size());
        assertEquals(at(worker, "OperationalError", "ledger.py:131"), workerIncidents.get(0).lead());
        assertTrue(workerIncidents.get(0).followers().stream().anyMatch(g -> g.severity().name().equals("FATAL")));
    }

    @Test
    void incidentsAppearInTheReportsAndSummary() throws IOException {
        AnalysisResult r = analyze("checkout-db-failover.log");

        StringBuilder markdown = new StringBuilder();
        ReportWriter.Format.MARKDOWN.writer().write(r, r.groups(), markdown);
        assertTrue(markdown.toString().contains("## Probable incidents"));
        assertTrue(markdown.toString().contains("**First failure:** the database connection was cut while in use"));
        assertTrue(markdown.toString().contains("**Incident:** Probably a knock-on effect of"));

        StringBuilder json = new StringBuilder();
        ReportWriter.Format.JSON.writer().write(r, r.groups(), json);
        JsonNode root = new ObjectMapper().readTree(json.toString());
        assertEquals(1, root.path("incidents").size());
        assertEquals(12, root.path("incidents").get(0).path("knockOn").size());
        assertTrue(root.path("errors").findValues("incidentRole").stream().anyMatch(n -> n.asText().equals("firstFailure")));

        String summary = SummaryFormatter.errorSummary(r, at(r, "CallNotPermittedException", "OrderService.java:131"));
        assertTrue(summary.contains("Probably a knock-on effect of the database connection was cut while in use"), summary);
    }

    @Test
    void logsWithoutTimestampsOrIncidentsGiveNone() throws IOException {
        AnalysisResult fixtures = new LogSession(Path.of("src", "test", "resources", "fixtures", "go-polyglot-crashes.log")).load(null);
        IncidentLinker.find(fixtures.groups()); // must not fail on groups without timestamps
        assertTrue(IncidentLinker.find(List.of()).isEmpty());
        assertEquals("12 s", IncidentLinker.gap(java.time.LocalDateTime.of(2026, 1, 1, 0, 0, 0), java.time.LocalDateTime.of(2026, 1, 1, 0, 0, 12)));
        assertEquals("1 h 5 min", IncidentLinker.gap(java.time.LocalDateTime.of(2026, 1, 1, 0, 0), java.time.LocalDateTime.of(2026, 1, 1, 1, 5)));
    }
}
