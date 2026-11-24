package io.causvane.analysis;

import io.causvane.model.AnalysisResult;
import io.causvane.parser.LogParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogAnalyzerBudgetTest {

    private static AnalysisResult analyze(LogAnalyzer analyzer, int distinct, int repeats) {
        LogParser parser = new LogParser(analyzer);
        long line = 0;
        for (int r = 0; r < repeats; r++) {
            for (int i = 0; i < distinct; i++) {
                parser.accept(++line, 0, "2026-03-14 10:00:00 ERROR request failed " + "x".repeat(200));
                parser.accept(++line, 0, "com.acme.E" + (char) ('a' + i) + "Exception: boom");
                parser.accept(++line, 0, "\tat com.acme.S.run(S.java:" + (i + 1) + ")");
            }
        }
        parser.flush();
        return analyzer.snapshot(Path.of("x.log"), 0, line);
    }

    @Test
    void pastTheSoftBudgetOnlyTheFirstOccurrenceOfEachErrorIsKept() {
        AnalysisResult result = analyze(new LogAnalyzer(100, 1_000, 1_000_000), 10, 5);
        assertEquals(10, result.groups().size());
        assertEquals(50, result.errorCount());
        assertEquals(0, result.unlistedOccurrences());
        result.groups().forEach(g -> assertEquals(5, g.count()));
        assertTrue(result.groups().stream().mapToInt(g -> g.samples().size()).sum() < 20, "samples must stop accumulating");
        result.groups().forEach(g -> assertTrue(g.samples().size() >= 1, "every listed error keeps one occurrence to show"));
    }

    @Test
    void pastTheHardBudgetNoNewErrorsAreListedButAllAreCounted() {
        AnalysisResult result = analyze(new LogAnalyzer(100, 500, 1_500), 20, 2);
        assertEquals(40, result.errorCount());
        assertTrue(result.groups().size() < 20, "listing must stop once the budget is spent");
        assertTrue(result.unlistedOccurrences() > 0);
        long listed = result.groups().stream().mapToLong(g -> g.count()).sum();
        assertEquals(40, listed + result.unlistedOccurrences());
    }
}
