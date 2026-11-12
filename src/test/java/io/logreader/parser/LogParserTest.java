package io.logreader.parser;

import io.logreader.model.LogEvent;
import io.logreader.model.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Multi-line event grouping and structured logs")
class LogParserTest {

    private static List<LogEvent> parse(String text) {
        List<LogEvent> events = new ArrayList<>();
        LogParser parser = new LogParser(events::add);
        long number = 0;
        long offset = 0;
        for (String line : text.split("\n", -1)) {
            parser.accept(++number, offset, line);
            offset += line.length() + 1;
        }
        parser.flush();
        return events;
    }

    @Test
    void stackTraceLinesAreGroupedWithTheirHeader() {
        List<LogEvent> events = parse("""
                2026-03-14 09:16:02.000  INFO 1 --- [main] c.a.App : before
                2026-03-14 09:16:03.118 ERROR 1 --- [exec-4] c.a.Handler : Request failed traceId=c90b12aa
                com.acme.OrderException: Could not place order
                \tat com.acme.OrderService.placeOrder(OrderService.java:87)
                Caused by: java.sql.SQLException: timeout
                \tat com.acme.OrderRepository.save(OrderRepository.java:139)
                \t... 9 more
                2026-03-14 09:16:03.120  INFO 1 --- [exec-4] c.a.App : after""");

        assertEquals(3, events.size());
        LogEvent error = events.get(1);
        assertEquals(2, error.lineNumber());
        assertEquals(6, error.lineCount());
        assertEquals(7, error.lastLineNumber());
        assertEquals(Severity.ERROR, error.severity());
        assertEquals(LocalDateTime.of(2026, 3, 14, 9, 16, 3, 118_000_000), error.timestamp());
        assertEquals("c90b12aa", error.traceId());
        assertEquals("OrderRepository.java:139", error.trace().pinpoint().location());
        assertTrue(error.isProblem());
        assertFalse(events.get(0).isProblem());
        assertEquals(8, events.get(2).lineNumber());
    }

    @Test
    void multiLineSqlAndJsonDumpsStayInOneEvent() {
        List<LogEvent> events = parse("""
                2026-03-14 09:14:22.318 DEBUG Executing SQL:
                    INSERT INTO orders (id, customer_id)
                    VALUES (?, ?)
                2026-03-14 09:14:22.400 WARN Rejected payload:
                {
                  "orderId": 88120,
                  "items": []
                }
                2026-03-14 09:14:22.500 INFO done""");
        assertEquals(3, events.size());
        assertEquals(3, events.get(0).lineCount());
        assertEquals(5, events.get(1).lineCount());
        assertEquals(Severity.WARN, events.get(1).severity());
    }

    @Test
    void untimestampedPythonTracebackIsOneEvent() {
        List<LogEvent> events = parse("""
                Starting worker
                Traceback (most recent call last):
                  File "/app/worker.py", line 12, in <module>
                    main()
                  File "/app/worker.py", line 8, in main
                    return 1 / 0
                ZeroDivisionError: division by zero
                Worker exited""");
        assertEquals(3, events.size());
        LogEvent crash = events.get(1);
        assertEquals(6, crash.lineCount());
        assertEquals(Severity.ERROR, crash.severity());
        assertEquals("ZeroDivisionError", crash.trace().topType());
        assertEquals("worker.py:8", crash.trace().pinpoint().location());
        assertEquals("main", crash.trace().pinpoint().function());
    }

    @Test
    void untimestampedGoPanicIsOneFatalEvent() {
        List<LogEvent> events = parse("""
                listening on :8080
                panic: assignment to entry in nil map

                goroutine 1 [running]:
                main.register(...)
                \t/app/main.go:42 +0x1d
                main.main()
                \t/app/main.go:15 +0x25
                exit status 2""");
        assertEquals(2, events.size());
        LogEvent panic = events.get(1);
        assertEquals(Severity.FATAL, panic.severity());
        assertEquals(8, panic.lineCount());
        assertEquals("main.go:42", panic.trace().pinpoint().location());
        assertEquals("main.register", panic.trace().pinpoint().qualifiedFunction());
    }

    @Test
    void crashDumpAfterAnInfoLineStartsItsOwnEvent() {
        List<LogEvent> events = parse("""
                2026-03-14 10:00:00 INFO job started
                Traceback (most recent call last):
                  File "/app/job.py", line 3, in run
                    work()
                RuntimeError: boom
                2026-03-14 10:00:01 INFO job restarted""");
        assertEquals(3, events.size());
        assertEquals(Severity.INFO, events.get(0).severity());
        assertNull(events.get(0).trace());
        assertEquals("RuntimeError", events.get(1).trace().topType());
    }

    @Test
    void jsonLogWithStackTraceField() {
        List<LogEvent> events = parse("""
                {"@timestamp":"2026-03-14T17:06:58.332Z","level":"ERROR","message":"Search request failed","trace_id":"f20a7c55","stack_trace":"com.acme.SearchException: Query failed\\n\\tat com.acme.SearchService.search(SearchService.java:74)\\nCaused by: java.util.concurrent.TimeoutException: Shard 4 timed out\\n\\tat com.acme.IndexClient.query(IndexClient.java:121)\\n"}
                {"@timestamp":"2026-03-14T17:06:59.000Z","level":"INFO","message":"ok"}""");

        assertEquals(2, events.size());
        LogEvent error = events.get(0);
        assertTrue(error.structured());
        assertEquals(Severity.ERROR, error.severity());
        assertEquals("Search request failed", error.message());
        assertEquals(LocalDateTime.of(2026, 3, 14, 17, 6, 58, 332_000_000), error.timestamp());
        assertEquals("f20a7c55", error.traceId());
        assertEquals("java.util.concurrent.TimeoutException", error.trace().rootType());
        assertEquals("IndexClient.java:121", error.trace().pinpoint().location());
        assertEquals(1, error.lineCount());
    }

    @Test
    void jsonLogWithNestedErrorObjectAndNumericLevel() {
        List<LogEvent> events = parse("""
                {"level":50,"time":1773508045120,"msg":"request errored","reqId":"req-41","err":{"type":"ValidationError","message":"price must be positive","stack":"ValidationError: price must be positive\\n    at validateProduct (/app/src/validators/product.js:27:11)\\n    at Layer.handle (/app/node_modules/express/lib/router/layer.js:95:5)"}}""");
        LogEvent error = events.get(0);
        assertEquals(Severity.ERROR, error.severity());
        assertEquals("request errored", error.message());
        assertEquals("req-41", error.traceId());
        assertEquals(LocalDateTime.of(2026, 3, 14, 17, 7, 25, 120_000_000), error.timestamp());
        assertEquals("ValidationError", error.trace().topType());
        assertEquals("product.js:27", error.trace().pinpoint().location());
    }

    @Test
    void jsonLogWithExceptionFieldVariants() {
        List<LogEvent> events = parse("""
                {"timestamp":"2026-03-14T17:08:19.550+00:00","level":"error","event":"webhook failed","exception":"Traceback (most recent call last):\\n  File \\"/srv/app/billing/webhooks.py\\", line 58, in handle\\n    invoice = get()\\nbilling.models.Invoice.DoesNotExist: Invoice matching query does not exist."}
                {"@t":"2026-03-14T17:12:30.661Z","@l":"Error","@mt":"Export failed","@x":"System.IO.FileNotFoundException: Could not find file\\n   at Acme.Export.TemplateLoader.Load(String name) in /src/Acme.Export/TemplateLoader.cs:line 31"}
                {"ts":"2026-03-14T17:10:44.009Z","level":"fatal","msg":"config reload failed","error":"open /etc/synonyms.yaml: permission denied"}""");

        assertEquals(3, events.size());
        assertEquals("webhook failed", events.get(0).message());
        assertEquals("webhooks.py:58", events.get(0).trace().pinpoint().location());
        assertEquals("billing.models.Invoice.DoesNotExist", events.get(0).trace().topType());
        assertEquals("TemplateLoader.cs:31", events.get(1).trace().pinpoint().location());
        assertEquals(Severity.FATAL, events.get(2).severity());
        assertTrue(events.get(2).lines().contains("open /etc/synonyms.yaml: permission denied"));
    }

    @Test
    void jsonPayloadWithoutLogFieldsIsNotAStructuredEvent() {
        List<LogEvent> events = parse("""
                2026-03-14 10:00:00 WARN Rejected payload:
                {"orderId": 88120, "items": []}
                2026-03-14 10:00:01 INFO next""");
        assertEquals(2, events.size());
        assertFalse(events.get(0).structured());
        assertEquals(2, events.get(0).lineCount());
    }

    @Test
    void uncaughtExceptionWithoutLevelIsPromotedToFatal() {
        List<LogEvent> events = parse("""
                Exception in thread "main" java.lang.IllegalStateException: no config
                \tat com.acme.Main.main(Main.java:12)""");
        assertEquals(1, events.size());
        assertEquals(Severity.FATAL, events.get(0).severity());
        assertNotNull(events.get(0).trace().pinpoint());
    }

    @Test
    void veryLongEventsAreCountedButStoredTruncated() {
        StringBuilder text = new StringBuilder("2026-03-14 10:00:00 ERROR java.lang.StackOverflowError: null\n");
        for (int i = 0; i < 2000; i++) {
            text.append("\tat com.acme.Tree.walk(Tree.java:17)\n");
        }
        text.append("2026-03-14 10:00:01 INFO recovered");
        List<LogEvent> events = parse(text.toString());
        assertEquals(2, events.size());
        assertEquals(2001, events.get(0).lineCount());
        assertEquals(LogParser.MAX_EVENT_LINES, events.get(0).lines().size());
        assertEquals(2002, events.get(1).lineNumber());
    }
}
