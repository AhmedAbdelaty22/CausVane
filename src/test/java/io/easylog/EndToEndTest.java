package io.easylog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.easylog.analysis.ErrorFilter;
import io.easylog.analysis.ErrorFilter.TextMatcher;
import io.easylog.cli.CliRunner;
import io.easylog.io.ContextBuilder;
import io.easylog.io.ContextBuilder.ContextLine;
import io.easylog.io.LogSession;
import io.easylog.model.AnalysisResult;
import io.easylog.model.ErrorGroup;
import io.easylog.model.Severity;
import io.easylog.report.ReportWriter;
import io.easylog.report.SummaryFormatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;
import java.util.regex.PatternSyntaxException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("End to end: sample logs, tailing, context, reports and CLI")
class EndToEndTest {

    private static final Path SAMPLES = Path.of("src", "test", "resources", "fixtures");

    private static AnalysisResult analyze(String sample) throws IOException {
        return new LogSession(SAMPLES.resolve(sample)).load(null);
    }

    private static ErrorGroup group(AnalysisResult result, String location) {
        return result.groups().stream().filter(g -> g.location().equals(location)).findFirst()
                .orElseThrow(() -> new AssertionError("no error pinpointed at " + location + " in "
                        + result.groups().stream().map(ErrorGroup::location).toList()));
    }

    @Test
    void javaSampleIsDeduplicatedAndPinpointed() throws IOException {
        AnalysisResult result = analyze("java-spring-app.log");
        assertEquals(117, result.totalLines());
        assertEquals(9, result.errorCount());
        assertEquals(3, result.warningCount());
        assertEquals(6, result.uniqueErrorTypes());
        assertEquals("PT13M1.306S", result.timeSpan().toString());

        ErrorGroup pool = result.groups().get(0);
        assertEquals("OrderProcessingException", pool.displayType());
        assertEquals("ConnectionProvider.java:58", pool.location());
        assertEquals(3, pool.count());
        assertEquals("java.sql.SQLTransientConnectionException", pool.rootCauseType());
        assertEquals(3, pool.traceIds().size());
        assertEquals(10, pool.firstLine());

        assertEquals(2, group(result, "TaxCalculator.java:64").count());
        assertEquals("LedgerVerifier.kt:51", group(result, "LedgerVerifier.kt:51").pinpoint().location());
        assertEquals(Severity.FATAL, group(result, "CsvExporter.java:73").severity());
        assertEquals(3, result.groups().stream().filter(g -> g.severity() == Severity.WARN).findFirst().orElseThrow().count());
    }

    @Test
    void otherLanguageSamplesArePinpointed() throws IOException {
        AnalysisResult dotnet = analyze("dotnet-api.log");
        assertEquals(2, group(dotnet, "DiscountCalculator.cs:39").count());
        assertEquals("Microsoft.Data.SqlClient.SqlException", group(dotnet, "InvoiceRepository.cs:74").rootCauseType());
        assertEquals("System.TimeoutException", group(dotnet, "PdfClient.cs:52").rootCauseType());
        assertEquals(Severity.FATAL, group(dotnet, "Program.cs:22").severity());

        AnalysisResult python = analyze("python-django.log");
        assertEquals(2, group(python, "shipping.py:37").count());
        assertEquals("smtplib.SMTPRecipientsRefused", group(python, "smtp.py:71").rootCauseType());
        assertEquals("psycopg.OperationalError", group(python, "product.py:67").rootCauseType());

        AnalysisResult node = analyze("node-express.log");
        assertEquals(3, group(node, "settingsService.js:42").count());
        assertEquals("s3Client.ts:34", group(node, "s3Client.ts:34").location());
        assertFalse(group(node, "json.js:92").pinpoint().application());

        AnalysisResult json = analyze("json-structured.log");
        assertEquals(2, group(json, "IndexClient.java:121").count());
        assertEquals(2, group(json, "product.js:27").count());
        assertEquals(1, group(json, "webhooks.py:58").count());
        assertEquals(1, group(json, "TemplateLoader.cs:31").count());

        AnalysisResult mixed = analyze("go-polyglot-crashes.log");
        assertEquals("panic", group(mixed, "batch.go:91").displayType());
        assertEquals(Severity.FATAL, group(mixed, "assign.go:47").severity());
        assertEquals("CartException", group(mixed, "CheckoutService.php:142").displayType());
        assertEquals("NoMethodError", group(mixed, "revenue_report.rb:64").displayType());
        assertEquals("etl::pipeline::transform::normalize_row", group(mixed, "transform.rs:118").pinpoint().function());
        assertEquals("SIGSEGV", group(mixed, "image_cache.cpp:203").displayType());
        assertEquals(2, group(mixed, "envelope.go:63").count());
    }

    @Test
    void contextMarksTheExactFailingLine() throws IOException {
        LogSession session = new LogSession(SAMPLES.resolve("java-spring-app.log"));
        AnalysisResult result = session.load(null);
        ErrorGroup pool = result.groups().get(0);

        List<ContextLine> lines = ContextBuilder.build(session, pool.samples().get(0), 2, 2);
        assertEquals(8, lines.get(0).lineNumber());
        assertEquals(ContextLine.Kind.CONTEXT, lines.get(0).kind());
        List<ContextLine> pins = lines.stream().filter(l -> l.kind() == ContextLine.Kind.PINPOINT).toList();
        assertEquals(1, pins.size());
        assertEquals(29, pins.get(0).lineNumber());
        assertTrue(pins.get(0).text().contains("ConnectionProvider.java:58"));
        assertEquals(33, lines.get(lines.size() - 1).lineNumber());
        assertEquals(2 + 22 + 2, lines.size());
    }

    @Test
    void filtersMatchKeywordsTraceIdsRegexAndSeverity() throws IOException {
        AnalysisResult result = analyze("java-spring-app.log");
        assertEquals(1, count(result, new ErrorFilter(TextMatcher.of("e4420a9b6c1d4f88", false), null)));
        assertEquals(1, count(result, new ErrorFilter(TextMatcher.of("hikari  timed", false), null)));
        assertEquals(2, count(result, new ErrorFilter(TextMatcher.of("Tax\\w+\\.java:\\d+|ledger", true), null)));
        assertEquals(1, count(result, new ErrorFilter(TextMatcher.of(null, false), Severity.FATAL)));
        assertEquals(1, count(result, new ErrorFilter(TextMatcher.of("", false), Severity.WARN)));
        assertEquals(result.groups().size(), count(result, ErrorFilter.NONE));
        assertThrows(PatternSyntaxException.class, () -> TextMatcher.of("([unclosed", true));
    }

    private static long count(AnalysisResult result, ErrorFilter filter) {
        return result.groups().stream().filter(filter).count();
    }

    @Test
    void liveTailPicksUpAppendedErrorsAndRotation(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("app.log");
        Files.writeString(log, "2026-03-14 10:00:00 INFO started\n");
        LogSession session = new LogSession(log);
        AnalysisResult initial = session.load(null);
        assertEquals(1, initial.totalLines());
        assertTrue(initial.groups().isEmpty());
        assertTrue(session.poll().isEmpty());

        Files.writeString(log, """
                2026-03-14 10:00:05 ERROR Request failed
                java.lang.IllegalStateException: boom
                \tat com.acme.Service.run(Service.java:21)
                2026-03-14 10:00:06 INFO half written li""", StandardOpenOption.APPEND);
        Optional<AnalysisResult> update = session.poll();
        assertTrue(update.isPresent());
        assertEquals(4, update.get().totalLines(), "the unterminated last line must wait for its newline");
        // the error is still open (more stack lines may follow); it is published once the writer goes quiet
        AnalysisResult settled = session.poll().orElseThrow();
        assertEquals("Service.java:21", settled.groups().get(0).location());
        assertTrue(session.poll().isEmpty());

        Files.writeString(log, "ne\n", StandardOpenOption.APPEND);
        assertEquals(5, session.poll().orElseThrow().totalLines());
        assertEquals(List.of("2026-03-14 10:00:06 INFO half written line"), session.readLines(5, 5));

        Files.writeString(log, "2026-03-15 00:00:00 ERROR fresh file after rotation\n");
        AnalysisResult rotated = session.poll().orElseThrow();
        assertEquals(1, rotated.totalLines());
        assertEquals(1, rotated.errorCount());
    }

    @Test
    void largeFilesAreIndexedSparselyAndReadBackByLineNumber(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("big.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 5000; i++) {
            sb.append("2026-03-14 10:00:00 INFO line number ").append(i).append(i % 2 == 0 ? "\r\n" : "\n");
        }
        sb.append("2026-03-14 10:00:01 ERROR last line without newline");
        Files.writeString(log, sb);
        LogSession session = new LogSession(log);
        AnalysisResult result = session.load(null);
        assertEquals(5001, result.totalLines());
        assertEquals(1, result.errorCount());
        assertEquals(List.of("2026-03-14 10:00:00 INFO line number 1"), session.readLines(1, 1));
        assertEquals(List.of("2026-03-14 10:00:00 INFO line number 4096", "2026-03-14 10:00:00 INFO line number 4097"),
                session.readLines(4096, 4097));
        assertEquals(List.of("2026-03-14 10:00:01 ERROR last line without newline"), session.readLines(5001, 6000));
    }

    @Test
    void reportsAreWellFormed() throws IOException {
        AnalysisResult result = analyze("dotnet-api.log");

        StringBuilder json = new StringBuilder();
        ReportWriter.Format.JSON.writer().write(result, result.groups(), json);
        JsonNode root = new ObjectMapper().readTree(json.toString());
        assertEquals(5, root.path("summary").path("totalErrors").asInt());
        assertEquals(result.groups().size(), root.path("errors").size());
        JsonNode first = root.path("errors").get(0);
        assertEquals("DiscountCalculator.cs:39", first.path("pinpoint").path("location").asText());
        assertEquals(39, first.path("pinpoint").path("line").asInt());
        assertTrue(first.path("pinpoint").path("applicationCode").asBoolean());

        StringBuilder markdown = new StringBuilder();
        ReportWriter.Format.MARKDOWN.writer().write(result, result.groups(), markdown);
        assertTrue(markdown.toString().contains("| Total errors | 5 |"));
        assertTrue(markdown.toString().contains("`InvoiceRepository.cs:74`"));
        assertTrue(markdown.toString().contains(">>>    at Acme.Billing.Data.InvoiceRepository.SaveAsync"));

        String summary = SummaryFormatter.errorSummary(result, result.groups().get(0));
        assertTrue(summary.startsWith("**[ERROR] NullReferenceException at DiscountCalculator.cs:39**"));
        assertTrue(summary.contains("Acme.Billing.Services.DiscountCalculator.Apply"));
    }

    @Test
    void cliPrintsPinpointsAndRejectsBadUsage() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        CliRunner cli = new CliRunner(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
        int exit = cli.run(new String[] {SAMPLES.resolve("python-django.log").toString(), "--min-level", "ERROR", "--no-color", "-n", "2"});
        String text = out.toString(StandardCharsets.UTF_8);
        assertEquals(0, exit);
        assertTrue(text.contains("shipping.py:37"));
        assertTrue(text.contains(">>>"));
        assertFalse(text.contains("WARN "));

        assertEquals(2, new CliRunner(new PrintStream(out), new PrintStream(err)).run(new String[] {"--bogus"}));
        assertEquals(1, new CliRunner(new PrintStream(out), new PrintStream(err)).run(new String[] {"does-not-exist.log"}));
    }
}
