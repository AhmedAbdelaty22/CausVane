package io.causvane;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.causvane.explain.ErrorExplainer;
import io.causvane.explain.Explanation;
import io.causvane.io.ContextBuilder;
import io.causvane.io.LogSession;
import io.causvane.io.TextSanitizer;
import io.causvane.model.AnalysisResult;
import io.causvane.model.ErrorGroup;
import io.causvane.report.Formats;
import io.causvane.report.ReportWriter;
import io.causvane.report.SummaryFormatter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("A log file is untrusted input: it must not be able to act on the terminal, a report or the app")
class SecurityTest {

    private static final char ESC = 0x1B;

    /** What the window can produce from a log: the saved Markdown report and the copied ticket summary. */
    private static String reportOf(Path log) throws IOException {
        AnalysisResult result = new LogSession(log).load(null);
        StringBuilder text = new StringBuilder();
        ReportWriter.Format.MARKDOWN.writer().write(result, result.groups(), text);
        for (ErrorGroup group : result.groups()) {
            text.append(SummaryFormatter.errorSummary(result, group));
        }
        return text.toString();
    }

    @Test
    void terminalEscapeSequencesInALogNeverReachTheWindowOrReports(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("escape.log");
        Files.writeString(log, "2026-03-14 10:00:00 INFO before" + ESC + "]0;pwned title\u0007\n"
                + "2026-03-14 10:00:01 ERROR payment failed " + ESC + "[2J" + ESC + "[1;1H" + ESC + "[8mhidden\n"
                + "java.lang.IllegalStateException: boom \u009B31m and ‮desrever\n"
                + "\tat com.acme.Pay.run(Pay.java:7)\n"
                + "{\"level\":\"error\",\"message\":\"json \\u001b[31mred\\u001b[0m\",\"stack\":\"Error: x\\u001b[2J\\n    at f (/app/a.js:1:2)\"}\n");

        String text = reportOf(log);
        assertTrue(text.contains("Pay.java:7"));
        assertTrue(text.contains("a.js:1"));
        assertEquals(-1, text.indexOf(ESC), "raw ESC must not reach a report");
        assertEquals(-1, text.indexOf('\u009B'), "one-byte CSI must not reach a report");
        assertEquals(-1, text.indexOf('\u0007'), "BEL must not reach a report");
        assertEquals(-1, text.indexOf('‮'), "direction override must not reach a report");

        LogSession session = new LogSession(log);
        AnalysisResult result = session.load(null);
        for (ErrorGroup group : result.groups()) {
            for (String line : group.samples().get(0).lines()) {
                assertEquals(TextSanitizer.clean(line), line);
            }
            for (ContextBuilder.ContextLine line : ContextBuilder.build(session, group.samples().get(0), 5, 5)) {
                assertEquals(-1, line.text().indexOf(ESC));
            }
        }
    }

    @Test
    void sanitizerKeepsOrdinaryTextUntouched() {
        String ordinary = "2026-03-14 ERROR café 日本語 😀\tindented \"quoted\" <b>";
        assertTrue(ordinary == TextSanitizer.clean(ordinary), "unchanged text must not be copied");
        assertEquals("a�b�c", TextSanitizer.clean("a" + ESC + "b\u0000c"));
        assertEquals("x�", TextSanitizer.clean("x\u007F"));
    }

    @Test
    void logContentCannotInjectMarkupIntoReportsOrTheClipboardSummary(@TempDir Path dir) throws IOException {
        Path log = dir.resolve("inject.log");
        Files.writeString(log, """
                2026-03-14 10:00:00 ERROR upload failed <img src="https://attacker.example/t.png"> [click here](https://attacker.example) **bold** | cell
                com.acme.UploadException: bad name `x` ``` ![pic](https://attacker.example/p.png) <script>alert(1)</script>
                \tat com.acme.Upload.save(Upload.java:12)
                ```
                # Injected heading
                [phish](https://attacker.example/login)
                ````
                Caused by: totally <b>not</b> a type [l](https://attacker.example) `tick`
                \tat com.acme.Store.put(Store.java:40)
                """);
        AnalysisResult result = new LogSession(log).load(null);
        assertEquals(1, result.groups().size());
        ErrorGroup group = result.groups().get(0);

        StringBuilder markdown = new StringBuilder();
        ReportWriter.Format.MARKDOWN.writer().write(result, result.groups(), markdown);
        String summary = SummaryFormatter.errorSummary(result, group);

        for (String document : List.of(markdown.toString(), summary)) {
            String outsideCode = stripCode(document);
            assertFalse(outsideCode.contains("<img"), outsideCode);
            assertFalse(outsideCode.contains("<script"), outsideCode);
            assertFalse(outsideCode.contains("<b>"), outsideCode);
            assertFalse(outsideCode.matches("(?s).*(?<!\\\\)\\[[^\\]]*\\]\\(https?://.*"), "unescaped link:\n" + outsideCode);
            assertFalse(outsideCode.contains("\n# Injected heading"), outsideCode);
            assertFalse(outsideCode.contains("**bold**"), outsideCode);
        }
        // the excerpt contains a run of four backticks, so its fence must be longer
        assertTrue(markdown.toString().contains("\n`````text\n"), markdown.toString());

        Explanation explanation = ErrorExplainer.standard().explain(group);
        for (String line : explanation.context()) {
            assertEquals(0, line.chars().filter(c -> c == '`').count() % 2, "unbalanced code span: " + line);
            assertFalse(stripSpans(line).contains("<b>"), line);
        }

        StringBuilder json = new StringBuilder();
        ReportWriter.Format.JSON.writer().write(result, result.groups(), json);
        assertEquals(1, new ObjectMapper().readTree(json.toString()).path("errors").size());
    }

    /** Removes fenced blocks and inline code spans, leaving the text a Markdown renderer would interpret. */
    private static String stripCode(String markdown) {
        StringBuilder out = new StringBuilder();
        String fence = null;
        for (String line : markdown.split("\n", -1)) {
            String trimmed = line.strip();
            if (fence == null && trimmed.matches("`{3,}\\w*")) {
                fence = trimmed.replaceAll("\\w", "");
            } else if (fence != null) {
                if (trimmed.equals(fence)) {
                    fence = null;
                }
            } else {
                out.append(stripSpans(line)).append('\n');
            }
        }
        return out.toString();
    }

    private static String stripSpans(String line) {
        return line.replaceAll("(?<!\\\\)`[^`]*`", "");
    }

    @Test
    void markdownHelpersMakeTextLiteral() {
        assertEquals("a \\*b\\* \\[x\\](y) &lt;i&gt; \\| \\`c\\` \\#h", Formats.mdText("a *b* [x](y) <i> | `c` #h"));
        assertEquals("`it's 'quoted' \\| here`", Formats.mdCode("it's `quoted` | here"));
        assertEquals("```", Formats.mdFence(List.of("plain", "one ` tick")));
        assertEquals("`````", Formats.mdFence(List.of("a ```` b")));
    }

    @Test
    void aLogWithEndlessDistinctErrorsCannotGrowTheResultWithoutBound(@TempDir Path dir) throws IOException {
        StringBuilder sb = new StringBuilder();
        int distinct = 12_500;
        for (int i = 0; i < distinct; i++) {
            String name = "" + (char) ('A' + i % 26) + (char) ('a' + (i / 26) % 26) + (char) ('a' + (i / 676) % 26);
            sb.append("2026-03-14 10:00:00 ERROR failed\ncom.acme.").append(name).append("xException: boom\n\tat com.acme.S")
                    .append(name).append(".run(S.java:").append(i + 1).append(")\n");
        }
        Path log = dir.resolve("many.log");
        Files.writeString(log, sb);
        AnalysisResult result = new LogSession(log).load(null);
        assertEquals(distinct, result.errorCount(), "every occurrence is still counted");
        assertEquals(10_000, result.groups().size());
        assertEquals(distinct - 10_000, result.unlistedOccurrences());
        StringBuilder report = new StringBuilder();
        ReportWriter.Format.MARKDOWN.writer().write(result, result.groups(), report);
        assertTrue(report.toString().contains("Not listed (over the listing limit) | 2,500 occurrences"), "the report says what was left out");
    }
}
