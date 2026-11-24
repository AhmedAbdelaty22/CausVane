package io.causvane;

import io.causvane.analysis.ErrorFilter;
import io.causvane.analysis.ErrorFilter.TextMatcher;
import io.causvane.io.LogSession;
import io.causvane.model.AnalysisResult;
import io.causvane.model.LogEvent;
import io.causvane.parser.LogParser;
import io.causvane.report.ReportWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Robustness against hostile, broken and unusual input")
class RobustnessTest {

    /** Fragments chosen to provoke regular-expression backtracking in the parsers. */
    private static final String[] HOSTILE = {
            "a", "a.", "/", "at ", "\"", "'a' ", "com.foo.", "x:1 ", "(", ")", "a (", " in ", ":line ", "File \"", "-",
            "Error: ", "Exception ", "#0 ", ".rb:1:in `", ".php(", "at a.b(", "0x1f ", "=", "\\", "1", " ", "\t",
            "panicked at ", "---> ", "Caused by: ", "a/b.js:", "{", "[", "…", "é", "at async a (", ".go:",
    };

    @Test
    void pathologicalLinesAreParsedInLinearTime() {
        List<String> lines = new ArrayList<>();
        for (String fragment : HOSTILE) {
            String body = fragment.repeat(60_000 / fragment.length());
            lines.add(body);
            lines.add("2026-03-14 10:00:00 ERROR " + body);
            lines.add("\tat " + body);
            lines.add("    at " + body + ":12:34)");
            lines.add("   at " + body + ") in " + body + ":line 5");
            lines.add("  File \"" + body + "\", line 3, in " + body);
            lines.add("{\"level\":\"error\",\"message\":\"" + body.replace("\\", "/").replace("\"", "'").replace("\t", " ")
                    + "\",\"stack\":\"" + body.replace("\\", "/").replace("\"", "'").replace("\t", " ") + "\"}");
        }
        List<LogEvent> events = new ArrayList<>();
        assertTimeoutPreemptively(Duration.ofSeconds(20), () -> {
            LogParser parser = new LogParser(events::add);
            long number = 0;
            for (int round = 0; round < 3; round++) {
                for (String line : lines) {
                    parser.accept(++number, number, line);
                }
            }
            parser.flush();
        });
        assertTrue(events.size() > HOSTILE.length);
        for (LogEvent event : events) {
            for (String stored : event.lines()) {
                assertTrue(stored.length() <= LogParser.MAX_LINE_CHARS + 2, "stored lines must be clipped");
            }
        }
    }

    @Test
    void runawayFilterRegexIsCutOffInsteadOfFreezing() throws IOException {
        AnalysisResult result = new LogSession(Path.of("src", "test", "resources", "fixtures", "java-spring-app.log")).load(null);
        ErrorFilter runaway = new ErrorFilter(TextMatcher.of("(\\w+\\s?)+\\u0000", true), null);
        long matches = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> result.groups().stream().filter(runaway).count());
        assertEquals(0, matches);
        assertEquals(1, result.groups().stream().filter(new ErrorFilter(TextMatcher.of("hikari.*timed", true), null)).count());
    }

    @Test
    void emptyFileProducesAnEmptyResult(@TempDir Path dir) throws IOException {
        Path log = Files.createFile(dir.resolve("empty.log"));
        LogSession session = new LogSession(log);
        AnalysisResult result = session.load(null);
        assertEquals(0, result.totalLines());
        assertEquals(0, result.totalEvents());
        assertTrue(result.groups().isEmpty());
        assertTrue(result.timeline().isEmpty());
        assertTrue(session.readLines(1, 10).isEmpty());
        assertTrue(session.poll().isEmpty());
        StringBuilder report = new StringBuilder();
        ReportWriter.Format.MARKDOWN.writer().write(result, result.groups(), report);
        ReportWriter.Format.JSON.writer().write(result, result.groups(), report);
        assertTrue(report.toString().contains("No errors found"));
    }

    @Test
    void binaryGarbageDoesNotBreakTheParser(@TempDir Path dir) throws IOException {
        byte[] bytes = new byte[2_000_000];
        new Random(42).nextBytes(bytes);
        Path file = dir.resolve("garbage.bin");
        Files.write(file, bytes);
        AnalysisResult result = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> new LogSession(file).load(null));
        assertTrue(result.totalLines() > 0);
        assertEquals(bytes.length, result.fileSize());
    }

    @Test
    void oneGiantLineIsTruncatedNotBuffered(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("one-line.log");
        String giant = "2026-03-14 10:00:00 ERROR payload=" + "QUJD".repeat(2_000_000);
        Files.writeString(file, giant + "\n2026-03-14 10:00:01 INFO next\n");
        LogSession session = new LogSession(file);
        AnalysisResult result = session.load(null);
        assertEquals(2, result.totalLines());
        assertEquals(1, result.errorCount());
        LogEvent stored = result.groups().get(0).samples().get(0);
        assertTrue(stored.lines().get(0).length() <= LogParser.MAX_LINE_CHARS + 2);
        assertTrue(session.readLines(1, 1).get(0).length() < 70_000, "context reads must not load the whole line");
        assertEquals(List.of("2026-03-14 10:00:01 INFO next"), session.readLines(2, 2));
    }

    @Test
    void deeplyNestedJsonIsRejectedSafely() {
        String bomb = "{\"a\":".repeat(5_000) + "1" + "}".repeat(5_000);
        List<LogEvent> events = new ArrayList<>();
        LogParser parser = new LogParser(events::add);
        parser.accept(1, 0, bomb);
        parser.accept(2, bomb.length() + 1, "{\"level\":\"error\",\"message\":\"after\"}");
        parser.flush();
        assertEquals(2, events.size());
        assertTrue(events.get(1).structured());
    }

    @Test
    void manyDistinctErrorsStayBoundedPerGroup(@TempDir Path dir) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            sb.append("2026-03-14 10:00:00 ERROR Request failed\njava.lang.IllegalStateException: boom ").append(i)
                    .append("\n\tat com.acme.Service.run(Service.java:21)\n");
        }
        Path file = dir.resolve("repeat.log");
        Files.writeString(file, sb);
        AnalysisResult result = new LogSession(file).load(null);
        assertEquals(1, result.groups().size());
        assertEquals(20_000, result.groups().get(0).count());
        assertEquals(25, result.groups().get(0).samples().size());
    }
}
