package io.logreader.explain;

import io.logreader.explain.LineExplainer.Note;
import io.logreader.explain.LineExplainer.Role;
import io.logreader.io.ContextBuilder;
import io.logreader.io.ContextBuilder.ContextLine;
import io.logreader.io.LogSession;
import io.logreader.model.AnalysisResult;
import io.logreader.model.ErrorGroup;
import io.logreader.model.LogEvent;
import io.logreader.parser.LogParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Every log line around an error gets a role and a plain-language explanation")
class LineExplainerTest {

    private static LogEvent parse(String text) {
        List<LogEvent> events = new ArrayList<>();
        LogParser parser = new LogParser(events::add);
        long n = 0;
        for (String line : text.split("\n", -1)) {
            parser.accept(++n, 0, line);
        }
        parser.flush();
        return events.stream().filter(LogEvent::isProblem).findFirst().orElseThrow();
    }

    @Test
    void chainedErrorIsLabelledLineByLine() {
        LogEvent event = parse("""
                2026-03-14 09:16:03.118 ERROR [exec-4] c.a.Handler : Request failed traceId=c90b12aa
                com.acme.OrderException: Could not place order
                \tat com.acme.OrderService.placeOrder(OrderService.java:87)
                \tat org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1089)
                Caused by: org.hibernate.exception.JDBCConnectionException: Unable to acquire JDBC Connection
                \tat com.acme.OrderRepository.save(OrderRepository.java:142)
                \t... 7 more
                Caused by: java.sql.SQLTransientConnectionException: Connection is not available
                \tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:181)
                \tat com.acme.ConnectionProvider.acquire(ConnectionProvider.java:58)""");
        Role[] expected = {Role.LOGGED, Role.ERROR_TEXT, Role.YOUR_CODE, Role.LIBRARY, Role.CAUSE, Role.YOUR_CODE,
                Role.SKIPPED, Role.ROOT_CAUSE, Role.LIBRARY, Role.STARTS_HERE};
        for (int i = 0; i < expected.length; i++) {
            Note note = LineExplainer.explainEventLine(event, i);
            assertEquals(expected[i], note.role(), "line " + i + ": " + event.lines().get(i));
            assertFalse(note.text().isBlank());
        }
        assertTrue(LineExplainer.explainEventLine(event, 0).text().contains("9 lines below belong to the same entry"));
        assertTrue(LineExplainer.explainEventLine(event, 0).text().contains("c90b12aa"));
        assertTrue(LineExplainer.explainEventLine(event, 6).text().startsWith("7 further code locations were left out"));
        assertTrue(LineExplainer.explainEventLine(event, 7).text().contains("root cause"));
        Note start = LineExplainer.explainEventLine(event, 9);
        assertTrue(start.text().contains("ConnectionProvider.acquire"));
        assertTrue(start.text().contains("line 58"));
        assertTrue(start.text().contains("place to look first"));
    }

    @Test
    void tracebackFormatAndCrashDumpsAreExplained() {
        LogEvent python = parse("""
                [2026-11-11 02:01:02,413: ERROR/ForkPoolWorker-3] Task payments.sync_fx_rates raised unexpected: KeyError('XOF')
                Traceback (most recent call last):
                  File "/srv/venv/lib/python3.12/site-packages/celery/app/trace.py", line 453, in trace_task
                    R = retval = fun(*args, **kwargs)
                  File "/srv/app/payments/fx.py", line 44, in store
                    decimals = MINOR_UNITS[code]
                KeyError: 'XOF'""");
        assertTrue(LineExplainer.explainEventLine(python, 1).text().contains("last location listed is where it failed"));
        assertEquals(Role.LIBRARY, LineExplainer.explainEventLine(python, 2).role());
        assertTrue(LineExplainer.explainEventLine(python, 3).text().contains("line of source code"));
        assertEquals(Role.STARTS_HERE, LineExplainer.explainEventLine(python, 4).role());
        assertEquals(Role.ERROR_TEXT, LineExplainer.explainEventLine(python, 6).role());

        LogEvent panic = parse("""
                listening
                panic: assignment to entry in nil map

                goroutine 61 [running]:
                github.com/shopline/ingest/internal/enrich.(*Tagger).Apply(0xc0002a4000)
                \t/build/internal/enrich/tagger.go:73 +0x1a5
                exit status 2""");
        assertTrue(LineExplainer.explainEventLine(panic, 2).text().contains("task that was running when the program crashed"));
        assertTrue(LineExplainer.explainEventLine(panic, 3).text().contains("name of the function"));
        assertEquals(Role.STARTS_HERE, LineExplainer.explainEventLine(panic, 4).role());
        assertTrue(LineExplainer.explainEventLine(panic, 5).text().contains("exit code 2"));
    }

    @Test
    void surroundingLinesAreDescribedByWhatTheyAre() {
        assertEquals(Role.BEFORE, LineExplainer.explainContextLine("2026-03-14 09:16:02 INFO started", true).role());
        assertTrue(LineExplainer.explainContextLine("2026-03-14 09:16:02 INFO started", true).text().startsWith("Normal activity logged before"));
        assertTrue(LineExplainer.explainContextLine("2026-03-14 09:16:02 WARN slow query", true).text().contains("often related"));
        assertTrue(LineExplainer.explainContextLine("2026-03-14 09:16:09 ERROR other", false).text().startsWith("A different error, logged after"));
        assertTrue(LineExplainer.explainContextLine("{\"level\":30,\"time\":1773508045120,\"msg\":\"ok\"}", false).text().startsWith("Normal activity"));
        assertTrue(LineExplainer.explainContextLine("{\"level\":\"error\",\"msg\":\"x\"}", true).text().startsWith("A different error"));
        assertTrue(LineExplainer.explainContextLine("\tat com.acme.Foo.bar(Foo.java:1)", true).text().startsWith("Part of a neighbouring log entry"));
    }

    @Test
    void contextViewCarriesAnExplanationForEveryLine() throws IOException {
        LogSession session = new LogSession(Path.of("samples", "checkout-db-failover.log"));
        AnalysisResult result = session.load(null);
        for (ErrorGroup group : result.groups()) {
            List<ContextLine> lines = ContextBuilder.build(session, group.samples().get(0), 4, 4);
            for (ContextLine line : lines) {
                assertFalse(line.explanation() == null || line.explanation().isBlank(), "no explanation for: " + line.text());
            }
            long starts = lines.stream().filter(l -> l.role() == Role.STARTS_HERE).count();
            assertTrue(starts <= 1, "at most one line may be marked as the start");
            if (group.pinpoint() != null) {
                assertEquals(1, starts, group.displayType());
            }
            assertTrue(lines.stream().anyMatch(l -> l.role() == Role.BEFORE));
        }
    }
}
