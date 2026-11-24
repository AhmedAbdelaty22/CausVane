package io.causvane.gui;

import io.causvane.analysis.ErrorFilter;
import io.causvane.analysis.ErrorFilter.TextMatcher;
import io.causvane.io.LogSession;
import io.causvane.model.AnalysisResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsTest {

    @BeforeAll
    static void neverTouchTheRealPreferences() {
        System.setProperty("causvane.noPersist", "true");
    }

    @Test
    void recentFilesAreNewestFirstWithoutDuplicatesOrMissingFiles(@TempDir Path dir) throws IOException {
        Settings settings = Settings.get();
        settings.clearRecent();
        for (int i = 0; i < 10; i++) {
            settings.addRecent(Files.writeString(dir.resolve("log" + i + ".log"), "x"));
        }
        settings.addRecent(dir.resolve("log3.log"));
        var recent = settings.recentFiles();
        assertEquals(Settings.MAX_RECENT, recent.size());
        assertEquals("log3.log", recent.get(0).getFileName().toString());
        assertEquals("log9.log", recent.get(1).getFileName().toString());
        assertEquals(recent.size(), recent.stream().distinct().count());
        Files.delete(dir.resolve("log9.log"));
        assertTrue(settings.recentFiles().stream().noneMatch(p -> p.getFileName().toString().equals("log9.log")));
        assertEquals(dir.toAbsolutePath(), settings.lastFolder());
    }

    @Test
    void valuesRoundTripAndBadValuesFallBack() {
        Settings settings = Settings.get();
        settings.contextLines(12);
        assertEquals(12, settings.contextLinesOr(8));
        settings.theme(Theme.Mode.LIGHT);
        assertEquals(Theme.Mode.LIGHT, settings.theme());
        settings.theme(Theme.Mode.DARK);
        assertEquals(Theme.Mode.DARK, settings.theme());
        settings.lastFolder(Path.of("/definitely/not/a/folder"));
        assertNull(settings.lastFolder());
    }

    @Test
    void timeRangeFilterKeepsProblemsActiveDuringThePeriod() throws IOException {
        AnalysisResult r = new LogSession(Path.of("samples", "checkout-db-failover.log")).load(null);
        ErrorFilter outage = ErrorFilter.NONE.during(LocalDateTime.of(2026, 11, 9, 14, 6, 0), LocalDateTime.of(2026, 11, 9, 14, 7, 0));
        var kept = r.groups().stream().filter(outage).toList();
        assertTrue(kept.stream().anyMatch(g -> g.displayType().equals("CallNotPermittedException")));
        assertTrue(kept.stream().noneMatch(g -> g.displayType().equals("DataAccessResourceFailureException")),
                "the connection-killed errors ended at 14:05:18");
        ErrorFilter quiet = new ErrorFilter(new TextMatcher.All(), null).during(LocalDateTime.of(2026, 11, 9, 13, 0), LocalDateTime.of(2026, 11, 9, 13, 30));
        assertEquals(0, r.groups().stream().filter(quiet).count());
    }
}
