package io.causvane.io;

import io.causvane.model.AnalysisResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Pasted log text")
class PastedLogTest {

    @Test
    void pastedTextIsAnalysedLikeAFileAndDeletedAfterwards() throws IOException {
        Path file = PastedLog.write("2026-03-14 10:00:00 INFO started\n2026-03-14 10:00:01 ERROR java.lang.IllegalStateException: boom\n"
                + "\tat com.acme.Pay.run(Pay.java:7)");
        assertTrue(PastedLog.isPasted(file));
        assertEquals("Pasted log.log", file.getFileName().toString());
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file),
                    "only the current user can read pasted text");
        }
        AnalysisResult result = new LogSession(file).load(null);
        assertEquals(1, result.errorCount());
        assertEquals("Pay.java:7", result.groups().get(0).location());

        PastedLog.delete(file);
        assertFalse(Files.exists(file));
        assertFalse(Files.exists(file.getParent()), "the private folder goes too");
    }

    @Test
    void ordinaryFilesAreNeverDeleted(@org.junit.jupiter.api.io.TempDir Path dir) throws IOException {
        Path log = Files.writeString(dir.resolve("app.log"), "x\n");
        assertFalse(PastedLog.isPasted(log));
        PastedLog.delete(log);
        assertTrue(Files.exists(log));
    }
}
