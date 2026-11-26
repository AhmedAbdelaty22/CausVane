package io.causvane.explain;

import io.causvane.io.LogSession;
import io.causvane.model.AnalysisResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Meeting slides")
class BriefingTest {

    private static AnalysisResult sample() throws IOException {
        return new LogSession(Path.of("samples/checkout-db-failover.log")).load(null);
    }

    @Test
    void sixSlidesInTheOrderAMeetingNeeds() throws IOException {
        AnalysisResult r = sample();
        List<Briefing.Slide> slides = Briefing.build(r, IncidentLinker.find(r.groups()), Briefing.Level.NORMAL);
        assertEquals(List.of("1 · WHAT HAPPENED", "2 · WHEN", "3 · IMPACT", "4 · LIKELY CAUSE",
                "5 · KNOCK-ON EFFECTS", "6 · WHAT TO DO NEXT"), slides.stream().map(Briefing.Slide::kicker).toList());
        assertTrue(slides.get(3).title().contains("database connection"), slides.get(3).title());
        assertTrue(slides.get(4).points().stream().anyMatch(p -> p.contains("in 3 places")), slides.get(4).points().toString());
        slides.forEach(s -> s.points().forEach(p -> assertFalse(p.contains("`"), p)));
    }

    @Test
    void simpleLeavesOutTheTechnicalNamesAndExpertAddsThem() throws IOException {
        AnalysisResult r = sample();
        List<IncidentLinker.Incident> incidents = IncidentLinker.find(r.groups());
        List<Briefing.Slide> simple = Briefing.build(r, incidents, Briefing.Level.SIMPLE);
        simple.forEach(s -> assertNull(s.technical()));
        assertTrue(simple.get(3).points().stream().noneMatch(p -> p.contains(".java")), simple.get(3).points().toString());
        List<Briefing.Slide> expert = Briefing.build(r, incidents, Briefing.Level.EXPERT);
        assertNotNull(expert.get(3).technical());
        assertTrue(expert.get(3).technical().contains("DataAccessResourceFailureException"), expert.get(3).technical());
    }
}
