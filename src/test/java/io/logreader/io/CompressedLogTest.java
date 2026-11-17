package io.logreader.io;

import io.logreader.model.AnalysisResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Rotated, gzip-compressed logs")
class CompressedLogTest {

    private static final Path SAMPLE = Path.of("samples", "checkout-db-failover.log");

    private static Path gzip(Path source, Path target) throws IOException {
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(target))) {
            Files.copy(source, out);
        }
        return target;
    }

    @Test
    void compressedLogGivesTheSameAnalysisAsThePlainOne(@TempDir Path dir) throws IOException {
        AnalysisResult plain = new LogSession(SAMPLE).load(null);
        Path gz = gzip(SAMPLE, dir.resolve("checkout.log.1.gz"));
        LogSession session = new LogSession(gz);
        AnalysisResult packed = session.load(null);

        assertTrue(session.isCompressed());
        assertEquals(plain.totalLines(), packed.totalLines());
        assertEquals(plain.errorCount(), packed.errorCount());
        assertEquals(plain.groups().size(), packed.groups().size());
        assertEquals(plain.groups().get(0).location(), packed.groups().get(0).location());
        assertEquals(gz, packed.file(), "the user's file name is shown, not the temporary copy");
        assertEquals(List.of(Files.readAllLines(SAMPLE).get(99)), session.readLines(100, 100));
        assertTrue(session.poll().isEmpty(), "an archive is never tailed");
        session.close();
    }

    @Test
    void detectionUsesTheContentNotTheName(@TempDir Path dir) throws IOException {
        LogSession disguised = new LogSession(gzip(SAMPLE, dir.resolve("no-extension")));
        assertTrue(disguised.load(null).totalLines() > 9000);
        assertTrue(disguised.isCompressed());
        disguised.close();

        LogSession plain = new LogSession(Files.copy(SAMPLE, dir.resolve("plain.gz")));
        plain.load(null);
        assertFalse(plain.isCompressed());
    }

    @Test
    void joinedArchivesAreReadInFull(@TempDir Path dir) throws IOException {
        Path a = Files.writeString(dir.resolve("a.log"), "2026-03-14 10:00:00 ERROR first part\n");
        Path b = Files.writeString(dir.resolve("b.log"), "2026-03-14 10:00:01 ERROR second part\n");
        byte[] first = Files.readAllBytes(gzip(a, dir.resolve("a.gz")));
        byte[] second = Files.readAllBytes(gzip(b, dir.resolve("b.gz")));
        byte[] joined = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, joined, first.length, second.length);
        Path both = Files.write(dir.resolve("joined.gz"), joined);

        LogSession session = new LogSession(both);
        AnalysisResult result = session.load(null);
        assertEquals(2, result.totalLines());
        assertEquals(2, result.errorCount());
        session.close();
    }

    @Test
    void damagedAndOversizedArchivesAreRefusedWithAClearMessage(@TempDir Path dir) throws IOException {
        Path gz = gzip(SAMPLE, dir.resolve("x.gz"));
        byte[] bytes = Files.readAllBytes(gz);
        Path damaged = Files.write(dir.resolve("damaged.gz"), Arrays.copyOf(bytes, bytes.length / 2));
        IOException broken = assertThrows(IOException.class, () -> new LogSession(damaged).load(null));
        assertFalse(broken.getMessage() == null || broken.getMessage().isBlank());

        IOException tooBig = assertThrows(IOException.class, () -> Decompressor.toTempFile(gz, 100_000, null));
        assertTrue(tooBig.getMessage().contains("expands to more than"), tooBig.getMessage());
    }

    @Test
    void theDecompressedCopyIsPrivateAndRemovedOnClose(@TempDir Path dir) throws IOException {
        Path gz = gzip(SAMPLE, dir.resolve("x.gz"));
        Path temp = Decompressor.toTempFile(gz, Long.MAX_VALUE, null);
        assertEquals(Files.size(SAMPLE), Files.size(temp));
        if (temp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(temp)));
        }
        Files.delete(temp);

        LogSession session = new LogSession(gz);
        session.load(null);
        long before = countTemp();
        session.close();
        assertEquals(before - 1, countTemp());
    }

    private static long countTemp() throws IOException {
        try (var files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return files.filter(p -> p.getFileName().toString().startsWith("logreader-")).count();
        }
    }
}
