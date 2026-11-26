package io.causvane.analysis;

import io.causvane.explain.ErrorExplainer;
import io.causvane.explain.TechAdvice;
import io.causvane.io.LogSession;
import io.causvane.model.AnalysisResult;
import io.causvane.model.ErrorGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Recognising the application's technology")
class TechDetectorTest {

    private static AnalysisResult load(Path dir, String text) throws IOException {
        Path file = dir.resolve("app.log");
        Files.writeString(file, text);
        return new LogSession(file).load(null);
    }

    @Test
    void springBootServiceWithItsDatabasePoolAndBroker(@TempDir Path dir) throws IOException {
        AnalysisResult r = load(dir, """
                2026-03-14 10:00:00.001  INFO 1 --- [main] o.s.b.w.embedded.tomcat.TomcatWebServer  : Tomcat started on port(s): 8080
                2026-03-14 10:00:00.002  INFO 1 --- [main] com.zaxxer.hikari.HikariDataSource       : HikariPool-1 - Starting...
                2026-03-14 10:00:00.003  INFO 1 --- [main] o.a.k.clients.consumer.KafkaConsumer     : Subscribed to topic(s): orders
                2026-03-14 10:00:05.000 ERROR 1 --- [http-1] c.s.OrderService : failed
                java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30000ms.
                \tat com.zaxxer.hikari.pool.HikariPool.createTimeoutException(HikariPool.java:696)
                \tat com.shop.OrderService.place(OrderService.java:42)
                Caused by: org.postgresql.util.PSQLException: Connection to db:5432 refused.
                \tat org.postgresql.core.v3.ConnectionFactoryImpl.openConnectionImpl(ConnectionFactoryImpl.java:319)
                """);
        TechDetector.Profile p = TechDetector.profile(r);
        assertEquals("a Java Spring Boot service using PostgreSQL, HikariCP, Kafka and Tomcat", p.sentence());
        ErrorGroup g = r.groups().get(0);
        var steps = TechAdvice.steps(g, ErrorExplainer.standard().explain(g), p);
        assertTrue(steps.stream().anyMatch(s -> s.startsWith("HikariCP:")), steps.toString());
        assertTrue(steps.stream().anyMatch(s -> s.startsWith("PostgreSQL:")), steps.toString());
    }

    @Test
    void pythonFromItsTracebackAndFramework(@TempDir Path dir) throws IOException {
        AnalysisResult r = load(dir, """
                2026-03-14 10:00:00,001 INFO django.server "GET /health HTTP/1.1" 200
                2026-03-14 10:00:01,000 ERROR django.request Internal Server Error: /orders
                Traceback (most recent call last):
                  File "/app/shop/views.py", line 12, in place
                    order.save()
                psycopg2.OperationalError: server closed the connection unexpectedly
                """);
        assertEquals("a Python Django service using PostgreSQL", TechDetector.profile(r).sentence());
    }

    @Test
    void wordsThatOnlyLookLikeTechnologiesAreIgnored(@TempDir Path dir) throws IOException {
        AnalysisResult r = load(dir, """
                2026-03-14 10:00:00 INFO redistributing work to the other workers
                2026-03-14 10:00:01 WARN the expression could not be evaluated
                """);
        assertTrue(r.contents().technologies().isEmpty(), r.contents().technologies().toString());
        assertNull(TechDetector.profile(r).sentence());
        assertFalse(TechDetector.profile(r).has("Redis"));
    }
}
