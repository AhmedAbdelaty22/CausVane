package io.causvane;

import io.causvane.io.ContextBuilder;
import io.causvane.io.LogEncoding;
import io.causvane.io.LogSession;
import io.causvane.model.AnalysisResult;
import io.causvane.model.ErrorGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Behaviour that differs between Windows, macOS and Linux")
class PortabilityTest {

    private static final String WINDOWS_LOG = """
            2026-03-14 11:06:40.105 +00:00 [ERR] Unhandled exception. RequestId=0HN4B2K9Q1J7F café\r
            System.NullReferenceException: Object reference not set to an instance of an object.\r
               at Acme.Billing.Services.DiscountCalculator.Apply(Order order) in C:\\src\\Acme.Billing\\Services\\DiscountCalculator.cs:line 39\r
               at Microsoft.AspNetCore.Routing.EndpointMiddleware.Invoke(HttpContext httpContext)\r
            2026-03-14 11:07:02.556 +00:00 [INF] next request\r
            """;

    private static byte[] withBom(byte[] bom, String text, Charset charset) {
        byte[] body = text.getBytes(charset);
        byte[] all = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, all, 0, bom.length);
        System.arraycopy(body, 0, all, bom.length, body.length);
        return all;
    }

    private static void assertWindowsLogParsed(Path file, LogEncoding expected) throws IOException {
        LogSession session = new LogSession(file);
        AnalysisResult result = session.load(null);
        assertEquals(expected, session.encoding());
        assertEquals(5, result.totalLines());
        assertEquals(1, result.errorCount());
        ErrorGroup error = result.groups().get(0);
        assertEquals("DiscountCalculator.cs:39", error.location());
        assertEquals("C:\\src\\Acme.Billing\\Services\\DiscountCalculator.cs", error.pinpoint().file());
        assertTrue(error.samples().get(0).lines().get(0).endsWith("café"), "non-ASCII text and CRLF must survive");
        assertEquals(List.of("2026-03-14 11:07:02.556 +00:00 [INF] next request"), session.readLines(5, 5));
        assertEquals(3, ContextBuilder.build(session, error.samples().get(0), 0, 0).stream()
                .filter(l -> l.kind() == ContextBuilder.ContextLine.Kind.PINPOINT).findFirst().orElseThrow().lineNumber());
    }

    @Test
    void windowsLineEndingsAndPathsInUtf8(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("utf8.log");
        Files.write(file, WINDOWS_LOG.getBytes(StandardCharsets.UTF_8));
        assertWindowsLogParsed(file, LogEncoding.UTF_8);
    }

    @Test
    void utf16LittleEndianAsWrittenByPowerShell(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("powershell.log");
        Files.write(file, withBom(new byte[] {(byte) 0xFF, (byte) 0xFE}, WINDOWS_LOG, StandardCharsets.UTF_16LE));
        assertWindowsLogParsed(file, LogEncoding.UTF_16LE);
    }

    @Test
    void utf16BigEndianAndBomLessUtf16(@TempDir Path dir) throws IOException {
        Path bigEndian = dir.resolve("be.log");
        Files.write(bigEndian, withBom(new byte[] {(byte) 0xFE, (byte) 0xFF}, WINDOWS_LOG, StandardCharsets.UTF_16BE));
        assertWindowsLogParsed(bigEndian, LogEncoding.UTF_16BE);

        Path noBom = dir.resolve("nobom.log");
        Files.write(noBom, WINDOWS_LOG.getBytes(StandardCharsets.UTF_16LE));
        assertWindowsLogParsed(noBom, LogEncoding.UTF_16LE);
    }

    @Test
    void utf16FilesCanBeTailedAndIndexedAcrossBufferBoundaries(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("tail16.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 40_000; i++) {
            sb.append("2026-03-14 10:00:00 INFO line ").append(i).append(i % 3 == 0 ? "\r\n" : "\n");
        }
        Files.write(file, withBom(new byte[] {(byte) 0xFF, (byte) 0xFE}, sb.toString(), StandardCharsets.UTF_16LE));
        LogSession session = new LogSession(file);
        assertEquals(40_000, session.load(null).totalLines());
        assertEquals(List.of("2026-03-14 10:00:00 INFO line 39999"), session.readLines(39_999, 39_999));
        assertEquals(List.of("2026-03-14 10:00:00 INFO line 1"), session.readLines(1, 1));

        // half a character arrives first: it must wait until the writer finishes the line
        byte[] appended = "2026-03-14 10:00:05 ERROR disk failure\n".getBytes(StandardCharsets.UTF_16LE);
        Files.write(file, java.util.Arrays.copyOf(appended, appended.length - 3), StandardOpenOption.APPEND);
        assertTrue(session.poll().isEmpty());
        Files.write(file, java.util.Arrays.copyOfRange(appended, appended.length - 3, appended.length), StandardOpenOption.APPEND);
        AnalysisResult update = session.poll().orElseThrow();
        assertEquals(40_001, update.totalLines());
        assertEquals(List.of("2026-03-14 10:00:05 ERROR disk failure"), session.readLines(40_001, 40_001));
    }

    @Test
    void outputDoesNotDependOnTheSystemLanguage() throws IOException {
        Locale original = Locale.getDefault();
        try {
            for (Locale locale : new Locale[] {Locale.forLanguageTag("ar-EG"), Locale.forLanguageTag("tr-TR"),
                    Locale.forLanguageTag("hi-IN-u-nu-deva"), Locale.GERMANY}) {
                Locale.setDefault(locale);
                AnalysisResult result = new LogSession(Path.of("src", "test", "resources", "fixtures", "java-spring-app.log")).load(null);
                StringBuilder report = new StringBuilder();
                io.causvane.report.ReportWriter.Format.MARKDOWN.writer().write(result, result.groups(), report);
                String text = report.toString();
                assertTrue(text.contains("OrderProcessingException"), locale + ":\n" + text);
                assertTrue(text.contains("2026-03-14 09:16:03.118"), locale + ": dates keep Western digits");
                assertTrue(text.contains("| 3 |") || text.contains("×3") || text.contains(" 3 "), locale + ": counts keep Western digits");
                assertFalse(text.contains("\r"), "one newline style only");
            }
        } finally {
            Locale.setDefault(original);
        }
    }
}
