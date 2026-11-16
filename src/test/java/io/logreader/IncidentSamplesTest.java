package io.logreader;

import io.logreader.explain.ErrorExplainer;
import io.logreader.explain.Explanation;
import io.logreader.io.LogSession;
import io.logreader.model.AnalysisResult;
import io.logreader.model.ErrorGroup;
import io.logreader.model.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("The production-incident samples: thousands of lines, cascading failures, noise")
class IncidentSamplesTest {

    private static AnalysisResult analyze(String name) throws IOException {
        return new LogSession(Path.of("samples", name)).load(null);
    }

    private static ErrorGroup at(AnalysisResult result, String type, String location) {
        return result.groups().stream().filter(g -> g.displayType().equals(type) && g.location().equals(location)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + type + " at " + location + " in "
                        + result.groups().stream().map(g -> g.displayType() + "@" + g.location()).toList()));
    }

    private static Explanation explain(ErrorGroup group) {
        return ErrorExplainer.standard().explain(group);
    }

    @Test
    void databaseFailoverInAJvmService() throws IOException {
        AnalysisResult r = analyze("checkout-db-failover.log");
        assertTrue(r.totalLines() > 9000);
        assertEquals(261, r.errorCount());

        ErrorGroup breaker = at(r, "CallNotPermittedException", "OrderService.java:131");
        assertEquals(139, breaker.count());
        assertEquals("A safety switch (circuit breaker) is blocking calls", explain(breaker).title());
        assertEquals("The circuit breaker involved is `orders-db`.", explain(breaker).detail());

        ErrorGroup refused = at(r, "CannotCreateTransactionException", "OrderRepository.java:118");
        assertEquals("java.net.ConnectException", refused.rootCauseType());
        assertEquals(5, refused.chain().size());
        assertEquals("The service it tried to reach refused the connection", explain(refused).title());

        ErrorGroup killed = at(r, "DataAccessResourceFailureException", "OrderRepository.java:118");
        assertEquals("org.postgresql.util.PSQLException", killed.rootCauseType());
        assertEquals("Database", explain(killed).category());

        // the real bug hiding in the noise of the outage
        ErrorGroup bug = at(r, "NullPointerException", "PromoCodeService.java:63");
        assertEquals(4, bug.count());
        assertEquals("The empty value was `com.shopline.checkout.model.PromoCode.getCampaign()`.", explain(bug).detail());

        assertEquals("A value had the wrong type or format", explain(at(r, "MismatchedInputException", "OrderEventListener.java:48")).title());
        assertEquals("Messaging", explain(at(r, "CommitFailedException", "ConsumerCoordinator.java:1298")).category());
        assertTrue(r.groups().stream().anyMatch(g -> g.severity() == Severity.WARN
                && explain(g).title().equals("The database connection pool is completely in use")));
        assertTrue(r.groups().stream().anyMatch(g -> explain(g).title().equals("A safety switch (circuit breaker) opened")));
    }

    @Test
    void expiredCertificateBehindAJsonGateway() throws IOException {
        AnalysisResult r = analyze("api-gateway-cert-expiry.log");
        assertTrue(r.timeSpan().toMinutes() < 30, "all entries are within the incident window");
        ErrorGroup cert = at(r, "Error", "paymentsClient.js:88");
        assertEquals(r.groups().get(0), cert);
        assertEquals("A secure (TLS/SSL) connection could not be established", explain(cert).title());
        assertEquals("request", cert.pinpoint().function().replace("PaymentsClient.", ""));
        assertEquals("A security certificate is about to expire", explain(r.groups().stream()
                .filter(g -> g.message() != null && g.message().startsWith("Certificate for")).findFirst().orElseThrow()).title());
        assertEquals(Severity.WARN, at(r, "TokenExpiredError", "auth.js:27").severity());
        assertEquals("It tried to read `tier` from a value that was undefined.", explain(at(r, "TypeError", "plans.js:19")).detail());
        assertEquals("The process ran out of file handles", explain(at(r, "Error", "retryQueue.js:64")).title());
        assertEquals(Severity.FATAL, at(r, "Error", "retryQueue.js:64").severity());
        assertTrue(r.groups().stream().anyMatch(g -> explain(g).title().equals("Requests were refused because of a rate limit")));
    }

    @Test
    void deadlocksInAPythonWorker() throws IOException {
        AnalysisResult r = analyze("payments-worker-deadlocks.log");
        ErrorGroup deadlock = at(r, "OperationalError", "ledger.py:131");
        assertEquals("psycopg.errors.DeadlockDetected", deadlock.rootCauseType());
        assertEquals("transfer", deadlock.pinpoint().function());
        assertEquals("Two database operations blocked each other", explain(deadlock).title());
        assertEquals("The missing key was `XOF`.", explain(at(r, "KeyError", "fx.py:44")).detail());
        assertEquals("TimeoutError", at(r, "ReadTimeout", "bank_client.py:67").rootCauseType());
        assertTrue(r.groups().stream().anyMatch(g -> explain(g).category().equals("Lifecycle")));
    }

    @Test
    void threadPoolStarvationInADotNetService() throws IOException {
        AnalysisResult r = analyze("orders-api-threadpool-starvation.log");
        ErrorGroup timeout = at(r, "TaskCanceledException", "PricingClient.cs:58");
        assertEquals(r.groups().get(0), timeout);
        assertEquals("System.Net.Sockets.SocketException", timeout.rootCauseType());
        assertEquals(5, timeout.chain().size());
        assertEquals("An operation took too long and was abandoned", explain(timeout).title());
        assertEquals("Two database operations blocked each other", explain(at(r, "DbUpdateException", "OrderRepository.cs:133")).title());
        ErrorGroup fatal = at(r, "InvalidOperationException", "ThreadPoolHealthCheck.cs:38");
        assertEquals(Severity.FATAL, fatal.severity());
        assertEquals("The application ran out of worker threads", explain(fatal).title());
        assertEquals("412 pieces of work were waiting.", explain(fatal).detail());
        assertEquals("Null reference", explain(at(r, "NullReferenceException", "OrderMapper.cs:42")).category());
    }

    @Test
    void crashLoopInALogfmtService() throws IOException {
        AnalysisResult r = analyze("ingest-crash-loop.log");
        assertEquals("PT23M59.765S", r.timeSpan().toString(), "logfmt time= stamps are read");
        ErrorGroup panic = at(r, "panic", "tagger.go:73");
        assertEquals(9, panic.count());
        assertEquals(Severity.FATAL, panic.severity());
        assertEquals("(*Tagger).Apply", panic.pinpoint().function());
        assertTrue(r.groups().stream().anyMatch(g -> g.location().equals("warehouse.go:142")
                && explain(g).title().equals("An operation took too long and was abandoned")));
        assertTrue(r.groups().stream().anyMatch(g -> explain(g).title().equals("Work is arriving faster than it is being processed")));
    }
}
