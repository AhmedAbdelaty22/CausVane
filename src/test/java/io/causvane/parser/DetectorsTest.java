package io.causvane.parser;

import io.causvane.model.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDateTime;
import java.time.Year;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("Timestamp, severity and trace id detection")
class DetectorsTest {

    private final TimestampDetector timestamps = new TimestampDetector();
    private final SeverityDetector severities = new SeverityDetector();
    private final TraceIdExtractor traceIds = new TraceIdExtractor();

    private LocalDateTime leading(String line) {
        TimestampDetector.Match match = timestamps.detectLeading(line);
        assertNotNull(match, "no leading timestamp in: " + line);
        return match.value();
    }

    @Test
    void isoTimestamps() {
        assertEquals(LocalDateTime.of(2026, 3, 14, 9, 16, 3, 118_000_000), leading("2026-03-14 09:16:03.118 ERROR boom"));
        assertEquals(LocalDateTime.of(2026, 3, 14, 15, 21, 30, 4_000_000), leading("2026-03-14T15:21:30.004Z error: boom"));
        assertEquals(LocalDateTime.of(2026, 3, 14, 13, 42, 31, 675_000_000), leading("[2026-03-14 13:42:31,675] ERROR django"));
        assertEquals(LocalDateTime.of(2026, 3, 14, 11, 5, 12, 730_000_000), leading("2026-03-14 11:05:12.730 +00:00 [ERR] x"));
        assertEquals(LocalDateTime.of(2026, 3, 14, 19, 30, 0), leading("2026/03/14 19:30:00 INFO starting"));
        assertEquals(LocalDateTime.of(2026, 3, 14, 9, 0, 0), leading("ERROR 2026-03-14 09:00:00 level first"));
    }

    @Test
    void syslogTimestampUsesCurrentYear() {
        LocalDateTime value = leading("Mar 14 06:25:01 web-01 app[2211]: connection reset");
        assertEquals(LocalDateTime.of(Year.now().getValue(), 3, 14, 6, 25, 1), value);
        assertNotNull(timestamps.detectLeading("<34>Mar  4 06:25:01 web-01 su: failed"));
    }

    @Test
    void epochTimestamps() {
        assertEquals(LocalDateTime.of(2026, 3, 14, 17, 7, 25), leading("1773508045 worker started"));
        assertEquals(LocalDateTime.of(2026, 3, 14, 17, 7, 25, 120_000_000), leading("1773508045120 worker started"));
        assertEquals(LocalDateTime.of(2026, 3, 14, 17, 7, 25, 500_000_000), leading("1773508045.5 worker started"));
        assertEquals(LocalDateTime.of(2026, 3, 14, 17, 7, 25, 120_000_000), timestamps.fromEpoch(1773508045120d));
    }

    @Test
    void timeOnlyAndCustomDates() {
        assertEquals(LocalDateTime.of(TimestampDetector.TIME_ONLY_DATE, java.time.LocalTime.of(10, 23, 45, 123_000_000)),
                leading("10:23:45.123 [main] ERROR c.e.Service - failed"));
        assertEquals(LocalDateTime.of(2026, 3, 14, 10, 23, 45),
                timestamps.parse("127.0.0.1 - - [14/Mar/2026:10:23:45 +0000] \"GET / HTTP/1.1\" 500 12"));
        assertEquals(LocalDateTime.of(2026, 3, 14, 10, 23, 45), leading("03/14/2026 10:23:45 Service stopped"));
    }

    @Test
    void continuationLinesHaveNoLeadingTimestamp() {
        assertNull(timestamps.detectLeading("\tat com.acme.Foo.bar(Foo.java:10)"));
        assertNull(timestamps.detectLeading("Caused by: java.io.IOException: closed"));
        assertNull(timestamps.detectLeading("where created_at > '2026-03-14 00:00:00'"));
        assertNull(timestamps.detectLeading("1234567 rows affected"));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "ERROR 4821 --- [main] c.a.Foo : failed                | ERROR",
            "[ERR] An unhandled exception has occurred             | ERROR",
            "SEVERE: Servlet.service() threw exception             | ERROR",
            "EXCEPTION in worker 4                                 | ERROR",
            "error: Unhandled error in GET /v1/users               | ERROR",
            "fail: Microsoft.AspNetCore.Server.Kestrel[13]         | ERROR",
            "level=error msg=\"db down\"                           | ERROR",
            "FATAL main - cannot start                             | FATAL",
            "[FTL] Host terminated unexpectedly                    | FATAL",
            "CRITICAL shop.db: Database connection lost            | FATAL",
            "PANIC: could not write to file                        | FATAL",
            "PHP Fatal error:  Uncaught Exception                  | FATAL",
            "WARN 4821 --- [exec-3] c.a.Client : slow              | WARN",
            "WARNING shop.cache: Redis latency 412 ms              | WARN",
            "[WRN] Cache miss ratio above threshold                | WARN",
            "warn: Upstream responded in 1840ms                    | WARN",
            "INFO starting ingest-worker                           | INFO",
            "info: API gateway listening on :3000                  | INFO",
            "DEBUG Executing SQL                                   | DEBUG",
            "Tomcat started on port 8080                           | UNKNOWN",
            "connection error occurred while reading               | UNKNOWN",
    })
    void severityNormalisation(String header, Severity expected) {
        assertEquals(expected, severities.detect(header.strip()));
    }

    @Test
    void numericAndTokenLevels() {
        assertEquals(Severity.ERROR, Severity.fromNumeric(50));
        assertEquals(Severity.FATAL, Severity.fromNumeric(60));
        assertEquals(Severity.WARN, Severity.fromNumeric(40));
        assertEquals(Severity.INFO, Severity.fromNumeric(30));
        assertEquals(Severity.FATAL, Severity.fromToken("critical"));
        assertEquals(Severity.ERROR, Severity.fromToken("Error"));
    }

    @Test
    void traceIds() {
        assertEquals("c90b12aa3e7745f1", traceIds.extract("Request failed: POST /api/orders traceId=c90b12aa3e7745f1"));
        assertEquals("0HN4B2K9Q1J7C", traceIds.extract("responded 500 in 211.9 ms RequestId=0HN4B2K9Q1J7C"));
        assertEquals("4c1d9e77a3b2", traceIds.extract("Internal Server Error: /checkout request_id=4c1d9e77a3b2"));
        assertEquals("ab12-cd34", traceIds.extract("[correlation-id: ab12-cd34] payment declined"));
        assertNull(traceIds.extract("Tomcat started on port 8080"));
    }
}
