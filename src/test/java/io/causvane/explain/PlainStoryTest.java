package io.causvane.explain;

import io.causvane.explain.PlainStory.Kind;
import io.causvane.explain.PlainStory.Step;
import io.causvane.io.ContextBuilder;
import io.causvane.io.LogSession;
import io.causvane.model.AnalysisResult;
import io.causvane.model.ErrorGroup;
import io.causvane.model.LogEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("An error and its surroundings retold in plain sentences")
class PlainStoryTest {

    private static List<Step> story(String sample, String type, String location) throws IOException {
        LogSession session = new LogSession(Path.of("samples", sample));
        AnalysisResult result = session.load(null);
        ErrorGroup group = result.groups().stream()
                .filter(g -> g.displayType().equals(type) && g.location().equals(location)).findFirst().orElseThrow();
        LogEvent event = group.samples().get(0);
        return PlainStory.build(event, ContextBuilder.build(session, event, 8, 8));
    }

    private static Step first(List<Step> steps, Kind kind) {
        return steps.stream().filter(s -> s.kind() == kind).findFirst().orElseThrow(() -> new AssertionError("no " + kind));
    }

    @Test
    void chainedJavaErrorIsToldFromReportToRootCause() throws IOException {
        List<Step> steps = story("checkout-db-failover.log", "CannotCreateTransactionException", "OrderRepository.java:118");
        assertTrue(first(steps, Kind.REPORTED).sentence().startsWith("The application reported an error: `Unhandled exception for POST /api/checkout"));
        assertEquals("The error: `Could not open JPA EntityManager for transaction`", first(steps, Kind.ERROR).sentence());
        assertTrue(first(steps, Kind.ROOT_CAUSE).sentence().contains("`Connection refused`"));
        assertTrue(first(steps, Kind.STARTS_HERE).sentence().contains("function `OrderRepository.save` in file `OrderRepository.java`, line 118"));
        assertTrue(first(steps, Kind.CALLED_FROM).sentence().contains("`OrderService.placeOrder`"));
        assertTrue(first(steps, Kind.HIDDEN).sentence().contains("more lines of this entry are technical detail"));
        assertTrue(steps.stream().anyMatch(s -> s.kind() == Kind.BEFORE));
        assertTrue(steps.stream().filter(s -> s.kind() == Kind.BEFORE).count() <= PlainStory.ENTRIES_BEFORE);
        assertTrue(steps.stream().filter(s -> s.kind() == Kind.AFTER).count() <= PlainStory.ENTRIES_AFTER);
        int reported = steps.indexOf(first(steps, Kind.REPORTED));
        assertTrue(steps.subList(0, reported).stream().allMatch(s -> s.kind() == Kind.BEFORE), "before-entries come first");
    }

    @Test
    void repeatedAndMissingMessagesReadNaturally() throws IOException {
        List<Step> dotnet = story("orders-api-threadpool-starvation.log", "TaskCanceledException", "PricingClient.cs:58");
        assertTrue(dotnet.stream().anyMatch(s -> s.sentence().contains("the same problem, reported one level deeper")));
        assertTrue(first(dotnet, Kind.ROOT_CAUSE).sentence().contains("`SocketException` (it has no message of its own)"));

        List<Step> python = story("payments-worker-deadlocks.log", "OperationalError", "ledger.py:131");
        assertEquals("The error: `deadlock detected`", first(python, Kind.ERROR).sentence());
        assertTrue(first(python, Kind.STARTS_HERE).sentence().contains("function `transfer` in file `ledger.py`, line 131"));
    }

    @Test
    void jsonAndKeyValueLogsGetTimesAndMessages() throws IOException {
        List<Step> json = story("api-gateway-cert-expiry.log", "Error", "paymentsClient.js:88");
        Step before = first(json, Kind.BEFORE);
        assertEquals("Normal activity: `request completed`", before.sentence());
        assertFalse(before.time().isEmpty(), "times are read from numeric JSON timestamps");
        assertEquals("The error: `certificate has expired`", first(json, Kind.ERROR).sentence());

        assertEquals("batch flushed (records=811 took=102ms sink=warehouse)",
                PlainStory.simplify("time=2026-11-13T03:10:01.533Z level=info msg=\"batch flushed\" records=811 took=102ms sink=warehouse"));
    }

    @Test
    void logLinesAreStrippedToTheirMessage() {
        assertEquals("GET /api/cart/701234 200 12ms traceId=ab12",
                PlainStory.simplify("2026-11-09 14:00:01.123  INFO 1 --- [http-nio-8080-exec-3] c.s.checkout.web.CartController          : GET /api/cart/701234 200 12ms traceId=ab12"));
        assertEquals("Task payments.settle[x] received",
                PlainStory.simplify("[2026-11-11 02:12:01,123: INFO/MainProcess] Task payments.settle[x] received"));
        assertEquals("HTTP GET /api/orders responded 200 in 6.2 ms",
                PlainStory.simplify("2026-11-12 09:38:02.000 +00:00 [INF] HTTP GET /api/orders responded 200 in 6.2 ms"));
        assertEquals("request completed", PlainStory.simplify("{\"level\":30,\"time\":1773508051877,\"msg\":\"request completed\"}"));
        assertEquals("plain text without a date", PlainStory.simplify("plain text without a date"));
    }
}
