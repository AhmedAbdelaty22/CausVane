package io.causvane.explain;

import io.causvane.analysis.TechDetector;
import io.causvane.model.AnalysisResult;
import io.causvane.model.ErrorGroup;
import io.causvane.model.Severity;
import io.causvane.report.Formats;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * The log told as six slides for a meeting: what happened, when, the impact, the likely cause, its knock-on
 * effects and what to do next. Written for people who did not read the log; the technical names are left out
 * in Simple detail and added as a line of their own in Expert detail.
 */
public final class Briefing {

    /** How much technical detail the slides carry. */
    public enum Level { SIMPLE, NORMAL, EXPERT }

    /**
     * One slide.
     *
     * @param kicker    the small heading: "1 \u00b7 WHAT HAPPENED"
     * @param title     the one sentence to remember
     * @param points    the supporting points, plain words
     * @param technical error names and code locations for Expert detail, or null
     */
    public record Slide(String kicker, String title, List<String> points, String technical) {
        public Slide {
            points = List.copyOf(points);
        }
    }

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);
    private static final int MAX_POINTS = 5;

    private Briefing() {}

    public static List<Slide> build(AnalysisResult result, List<IncidentLinker.Incident> incidents, Level level) {
        LogOverview.Overview overview = LogOverview.of(result, incidents);
        TechDetector.Profile profile = TechDetector.profile(result);
        ErrorExplainer explainer = ErrorExplainer.standard();
        IncidentLinker.Incident main = incidents.stream()
                .max(Comparator.comparingInt(IncidentLinker.Incident::size)
                        .thenComparingLong(i -> i.lead().count())).orElse(null);
        ErrorGroup cause = main != null ? main.lead() : overview.startWith().isEmpty() ? null : overview.startWith().get(0);
        Explanation explained = cause == null ? null : explainer.explain(cause);
        boolean expert = level == Level.EXPERT;
        boolean simple = level == Level.SIMPLE;

        List<Slide> slides = new ArrayList<>();

        // 1 \u00b7 what happened
        List<String> happened = new ArrayList<>();
        if (profile.sentence() != null && !simple) {
            happened.add("The application looks like " + profile.sentence() + ".");
        }
        for (ErrorGroup g : overview.startWith()) {
            if (happened.size() < MAX_POINTS - 1) {
                happened.add(plain(explainer.explain(g).title()) + " (" + times(g.count()) + ")");
            }
        }
        if (result.groups().isEmpty()) {
            happened.add("The log holds no errors or warnings.");
        }
        String verdict = plain(overview.verdict());
        int stop = verdict.indexOf(". ");
        if (stop > 0) {
            happened.add(0, verdict.substring(stop + 2));
            verdict = verdict.substring(0, stop + 1);
        }
        slides.add(new Slide("1 \u00b7 WHAT HAPPENED", verdict, happened,
                expert ? technical(overview.startWith()) : null));

        // 2 \u00b7 when
        List<String> when = new ArrayList<>();
        if (result.firstTimestamp() != null && result.lastTimestamp() != null) {
            when.add("The log covers " + DAY.format(result.firstTimestamp()) + " to " + DAY.format(result.lastTimestamp()) + ".");
        }
        LocalDateTime troubleStart = result.groups().stream().filter(g -> g.severity().isAtLeast(Severity.ERROR))
                .map(ErrorGroup::firstSeen).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        if (overview.peak() != null) {
            LogOverview.Peak p = overview.peak();
            when.add("The busiest moment was " + CLOCK.format(p.start()) + ", with " + count(p.errors(), "error")
                    + " and " + count(p.warnings(), "warning") + ".");
        }
        for (LogOverview.Moment m : overview.timeline()) {
            if (when.size() < MAX_POINTS && m.start() != null) {
                when.add(CLOCK.format(m.start()) + "  " + m.kind() + ": " + plain(m.text()));
            }
        }
        String whenTitle = troubleStart == null ? "The log does not say when the problems happened."
                : "The trouble began at " + DAY.format(troubleStart)
                + (main != null && span(main) != null ? " and lasted about " + span(main) + "." : ".");
        slides.add(new Slide("2 \u00b7 WHEN", whenTitle, when, null));

        // 3 \u00b7 impact
        long fatal = result.contents().level(Severity.FATAL);
        long errors = result.errorCount();
        List<String> impact = new ArrayList<>();
        impact.add(count(errors, "error") + " and " + count(result.warningCount(), "warning") + " in "
                + Formats.count(result.groups().size()) + " different problems.");
        Set<String> requests = new HashSet<>();
        result.groups().forEach(g -> requests.addAll(g.traceIds()));
        if (!requests.isEmpty()) {
            impact.add("At least " + count(requests.size(), "request") + " (trace IDs) ran into a problem.");
        }
        if (fatal > 0) {
            impact.add("The application stopped or crashed " + times(fatal) + ".");
        }
        if (main != null) {
            impact.add("The main incident involved " + main.size() + " linked problems.");
        }
        String impactTitle = fatal > 0 ? "The application went down: " + count(fatal, "fatal error") + "."
                : errors == 0 ? "Nothing failed; at most there were warnings."
                : "Work failed " + times(errors) + ".";
        slides.add(new Slide("3 \u00b7 IMPACT", impactTitle, impact, null));

        // 4 \u00b7 likely cause
        if (cause == null) {
            slides.add(new Slide("4 \u00b7 LIKELY CAUSE", "There is no clear cause to point at.",
                    List.of("No error stood out as the first in a chain of problems."), null));
        } else {
            List<String> why = new ArrayList<>();
            why.add(plain(explained.meaning()));
            if (!simple && cause.pinpoint() != null) {
                why.add("It started in " + cause.location() + ".");
            }
            explained.likelyCauses().stream().limit(simple ? 2 : 3).forEach(c -> why.add("Often: " + plain(c)));
            slides.add(new Slide("4 \u00b7 LIKELY CAUSE", plain(explained.title()) + ".", why,
                    expert ? technical(List.of(cause)) + (cause.rootCauseType() != null ? "  \u00b7  root cause " + cause.rootCauseType() : "") : null));
        }

        // 5 \u00b7 knock-on effects
        List<String> knock = new ArrayList<>();
        if (main != null) {
            // the same kind of problem in several places is one knock-on effect
            java.util.Map<String, long[]> effects = new java.util.LinkedHashMap<>();
            for (ErrorGroup g : main.followers()) {
                String title = plain(explainer.explain(g).title());
                long after = g.firstSeen() != null && main.lead().firstSeen() != null && !g.firstSeen().isBefore(main.lead().firstSeen())
                        ? Duration.between(main.lead().firstSeen(), g.firstSeen()).toSeconds() : -1;
                long[] e = effects.computeIfAbsent(title, t -> new long[] {after, 0, 0});
                e[1] += g.count();
                e[2]++;
            }
            for (var e : effects.entrySet()) {
                if (knock.size() < MAX_POINTS) {
                    long seconds = e.getValue()[0];
                    String at = seconds < 0 ? "" : seconds < 5 ? "At the same moment: "
                            : IncidentLinker.gap(main.lead().firstSeen(), main.lead().firstSeen().plusSeconds(seconds)) + " later: ";
                    knock.add(at + (at.isEmpty() ? e.getKey() : Character.toLowerCase(e.getKey().charAt(0)) + e.getKey().substring(1)) + " (" + times(e.getValue()[1])
                            + (e.getValue()[2] > 1 ? ", in " + e.getValue()[2] + " places" : "") + ")");
                }
            }
            if (!main.earlyWarnings().isEmpty() && knock.size() < MAX_POINTS) {
                knock.add("Warning signs before it: " + main.earlyWarnings().stream().limit(2)
                        .map(g -> plain(explainer.explain(g).title())).reduce((a, b) -> a + "; " + b).orElse(""));
            }
        }
        String knockTitle = main == null || main.followers().isEmpty() ? "No other problems were linked to it."
                : main.followers().size() + (main.followers().size() == 1 ? " other problem" : " other problems")
                + " probably followed from it, and should stop once it is fixed.";
        slides.add(new Slide("5 \u00b7 KNOCK-ON EFFECTS", knockTitle, knock,
                expert && main != null ? technical(main.followers()) : null));

        // 6 \u00b7 what to do next
        List<String> todo = new ArrayList<>();
        if (explained != null) {
            explained.nextSteps().stream().limit(simple ? 3 : 4).forEach(s -> todo.add(plain(s)));
            if (!simple) {
                TechAdvice.steps(cause, explained, profile).stream().limit(2).forEach(s -> todo.add(plain(s)));
            }
        } else {
            todo.add("Keep the log for reference; nothing needs fixing now.");
        }
        slides.add(new Slide("6 \u00b7 WHAT TO DO NEXT", explained == null ? "No action is needed."
                : "Fix the likely cause first; the knock-on effects should follow.", todo, null));
        return slides;
    }

    /** "IllegalStateException at OrderService.java:42 \u00b7 ..." for Expert detail. */
    private static String technical(List<ErrorGroup> groups) {
        return groups.stream().limit(3).map(g -> g.displayType() + (g.pinpoint() != null ? " at " + g.location() : ""))
                .reduce((a, b) -> a + "  \u00b7  " + b).orElse(null);
    }

    private static String span(IncidentLinker.Incident incident) {
        List<ErrorGroup> all = new ArrayList<>(incident.followers());
        all.add(incident.lead());
        LocalDateTime start = all.stream().map(ErrorGroup::firstSeen).filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
        LocalDateTime end = all.stream().map(ErrorGroup::lastSeen).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        if (start == null || end == null || Duration.between(start, end).toSeconds() < 60) {
            return null;
        }
        return IncidentLinker.gap(start, end);
    }

    private static String plain(String s) {
        return s == null ? "" : s.replace("`", "").strip();
    }

    private static String times(long n) {
        return n == 1 ? "once" : Formats.count(n) + " times";
    }

    private static String count(long n, String word) {
        return Formats.count(n) + " " + word + (n == 1 ? "" : "s");
    }
}
