package io.easylog.explain;

import io.easylog.model.AnalysisResult;
import io.easylog.model.ErrorGroup;
import io.easylog.model.Severity;
import io.easylog.model.TimeBucket;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The whole log in plain terms: how serious it is, what happened in order, where to start, what kinds of
 * problem there are, the busiest moment and which parts of the application are involved.
 */
public final class LogOverview {

    /** How serious the log looks overall. */
    public enum Health {
        HEALTHY("No problems"),
        MINOR("Minor problems"),
        PROBLEMS("Problems"),
        SERIOUS("Serious problems");

        private final String label;

        Health(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * One entry of "what happened, in order".
     *
     * @param kind    "Incident", "Crash", "Error" or "Warning"
     * @param text    plain sentence; text in backticks is not used here
     * @param group   the problem to open for this moment
     * @param related other problems that belong to it (for an incident)
     */
    public record Moment(LocalDateTime start, LocalDateTime end, String kind, String text, ErrorGroup group, int related) {}

    /** The minute (or period) with the most errors and warnings. */
    public record Peak(LocalDateTime start, long seconds, long errors, long warnings) {}

    /** A source file of the application where problems started. */
    public record Place(String file, int problems, long occurrences) {}

    /**
     * @param verdict    one or two sentences summing up the log
     * @param timeline   what happened, oldest first
     * @param hiddenMoments moments left out of {@code timeline} to keep it short
     * @param startWith  the problems to look at first, most important first
     * @param byKind     number of problems per kind, most frequent first
     * @param places     files of the application's own code where problems started, most affected first
     */
    public record Overview(Health health, String verdict, List<Moment> timeline, int hiddenMoments,
                           List<ErrorGroup> startWith, Map<String, Integer> byKind, Peak peak, List<Place> places) {}

    public static final int MAX_MOMENTS = 8;

    private static final Set<String> CODE_FAULTS = Set.of("Null reference", "Code defect", "Data", "Validation", "State",
            "Unclassified", "Deployment", "Configuration");

    private LogOverview() {}

    public static Overview of(AnalysisResult result) {
        return of(result, IncidentLinker.find(result.groups()));
    }

    public static Overview of(AnalysisResult result, List<IncidentLinker.Incident> incidents) {
        ErrorExplainer explainer = ErrorExplainer.standard();
        List<ErrorGroup> groups = result.groups();
        Map<String, IncidentLinker.Membership> membership = IncidentLinker.index(incidents);

        // ---- health and verdict
        long fatal = groups.stream().filter(g -> g.severity() == Severity.FATAL).mapToLong(ErrorGroup::count).sum();
        IncidentLinker.Incident biggest = incidents.stream().max(Comparator.comparingInt(IncidentLinker.Incident::size)).orElse(null);
        Health health;
        if (groups.isEmpty()) {
            health = Health.HEALTHY;
        } else if (fatal > 0 || (biggest != null && biggest.size() >= 5) || result.errorCount() >= 100) {
            health = Health.SERIOUS;
        } else if (result.errorCount() > 0) {
            health = Health.PROBLEMS;
        } else {
            health = Health.MINOR;
        }

        // ---- what happened, in order
        List<Moment> moments = new ArrayList<>();
        for (IncidentLinker.Incident incident : incidents) {
            LocalDateTime end = incident.lead().lastSeen();
            for (ErrorGroup follower : incident.followers()) {
                if (follower.lastSeen() != null && (end == null || follower.lastSeen().isAfter(end))) {
                    end = follower.lastSeen();
                }
            }
            int related = incident.followers().size() + incident.earlyWarnings().size();
            moments.add(new Moment(incident.lead().firstSeen(), end, "Incident",
                    lower(plain(explainer.explain(incident.lead()).title())) + ", followed by " + related
                            + (related == 1 ? " related problem" : " related problems"), incident.lead(), related));
        }
        for (ErrorGroup group : groups) {
            if (membership.containsKey(group.id()) || !group.severity().isAtLeast(Severity.ERROR)) {
                continue;
            }
            moments.add(new Moment(group.firstSeen(), group.lastSeen(), group.severity() == Severity.FATAL ? "Crash" : "Error",
                    lower(plain(explainer.explain(group).title())) + times(group.count()), group, 0));
        }
        if (moments.isEmpty()) {
            for (ErrorGroup group : groups) {
                moments.add(new Moment(group.firstSeen(), group.lastSeen(), "Warning",
                        lower(plain(explainer.explain(group).title())) + times(group.count()), group, 0));
            }
        }
        moments.sort(Comparator.comparing(Moment::start, Comparator.nullsLast(Comparator.naturalOrder())));
        int hidden = Math.max(0, moments.size() - MAX_MOMENTS);
        if (hidden > 0) {
            // keep the incidents and crashes, then the most frequent of the rest, in time order
            List<Moment> kept = new ArrayList<>(moments.stream().filter(m -> !m.kind().equals("Error") && !m.kind().equals("Warning")).toList());
            moments.stream().filter(m -> m.kind().equals("Error") || m.kind().equals("Warning"))
                    .sorted(Comparator.comparingLong((Moment m) -> m.group().count()).reversed())
                    .limit(Math.max(0, MAX_MOMENTS - kept.size())).forEach(kept::add);
            kept.sort(Comparator.comparing(Moment::start, Comparator.nullsLast(Comparator.naturalOrder())));
            moments = kept.subList(0, Math.min(MAX_MOMENTS, kept.size()));
            hidden = Math.max(0, countMoments(groups, membership, incidents) - moments.size());
        }

        // ---- where to start
        List<ErrorGroup> startWith = new ArrayList<>();
        // a crash on its own comes first: it takes the whole application down
        groups.stream().filter(g -> g.severity() == Severity.FATAL && !membership.containsKey(g.id()))
                .max(Comparator.comparingLong(ErrorGroup::count)).ifPresent(startWith::add);
        if (biggest != null) {
            startWith.add(biggest.lead());
        }
        groups.stream()
                .filter(g -> !membership.containsKey(g.id()) && g.severity().isAtLeast(Severity.ERROR) && !startWith.contains(g))
                .sorted(Comparator.comparing((ErrorGroup g) -> g.severity() == Severity.FATAL ? 0 : 1)
                        .thenComparing(g -> CODE_FAULTS.contains(explainer.explain(g).category()) ? 0 : 1)
                        .thenComparing(Comparator.comparingLong(ErrorGroup::count).reversed()))
                .limit(Math.max(0, 3 - startWith.size()))
                .forEach(startWith::add);
        if (startWith.isEmpty() && !groups.isEmpty()) {
            startWith.add(groups.get(0));
        }

        String verdict = verdict(result, health, biggest, groups, explainer);

        // ---- kinds, peak, places
        Map<String, Integer> kinds = new TreeMap<>();
        for (ErrorGroup group : groups) {
            kinds.merge(explainer.explain(group).category(), 1, Integer::sum);
        }
        Map<String, Integer> byKind = new LinkedHashMap<>();
        kinds.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .forEach(e -> byKind.put(e.getKey(), e.getValue()));

        Map<String, long[]> files = new LinkedHashMap<>();
        for (ErrorGroup group : groups) {
            if (group.pinpoint() != null && group.pinpoint().application() && group.pinpoint().fileName() != null) {
                long[] tally = files.computeIfAbsent(group.pinpoint().fileName(), f -> new long[2]);
                tally[0]++;
                tally[1] += group.count();
            }
        }
        List<Place> places = files.entrySet().stream()
                .map(e -> new Place(e.getKey(), (int) e.getValue()[0], e.getValue()[1]))
                .sorted(Comparator.comparingLong(Place::occurrences).reversed())
                .limit(5).toList();

        return new Overview(health, verdict, List.copyOf(moments), hidden, List.copyOf(startWith), byKind,
                peak(result.timeline()), places);
    }

    private static String verdict(AnalysisResult r, Health health, IncidentLinker.Incident biggest,
                                  List<ErrorGroup> groups, ErrorExplainer explainer) {
        if (groups.isEmpty()) {
            return "No errors or warnings. As far as this log shows, the application ran normally.";
        }
        if (biggest != null && biggest.size() >= 3) {
            LocalDateTime end = biggest.lead().lastSeen();
            for (ErrorGroup follower : biggest.followers()) {
                if (follower.lastSeen() != null && (end == null || follower.lastSeen().isAfter(end))) {
                    end = follower.lastSeen();
                }
            }
            return "Most of the trouble looks like one incident: " + lower(plain(explainer.explain(biggest.lead()).title()))
                    + (biggest.lead().firstSeen() != null ? ", starting at " + clock(biggest.lead().firstSeen()) : "")
                    + (end != null && biggest.lead().firstSeen() != null && end.isAfter(biggest.lead().firstSeen())
                    ? " and lasting about " + gap(biggest.lead().firstSeen(), end) : "")
                    + ". " + (biggest.size() - 1) + " other problems look like its knock-on effects or warning signs.";
        }
        ErrorGroup crash = groups.stream().filter(g -> g.severity() == Severity.FATAL).findFirst().orElse(null);
        if (crash != null) {
            return "The application stopped with a fatal error: " + lower(plain(explainer.explain(crash).title()))
                    + times(crash.count()) + ". " + (groups.size() - 1) + " other problems were logged as well.";
        }
        ErrorGroup top = groups.get(0);
        if (health == Health.MINOR) {
            return "Only warnings, no errors. The most frequent: " + lower(plain(explainer.explain(top).title()))
                    + times(top.count()) + ".";
        }
        return groups.size() + (groups.size() == 1 ? " problem" : " separate problems")
                + ", with no sign that they share one cause. The most frequent: "
                + lower(plain(explainer.explain(top).title())) + times(top.count()) + ".";
    }

    private static int countMoments(List<ErrorGroup> groups, Map<String, IncidentLinker.Membership> membership,
                                    List<IncidentLinker.Incident> incidents) {
        int count = incidents.size();
        Set<String> seen = new HashSet<>(membership.keySet());
        for (ErrorGroup group : groups) {
            if (!seen.contains(group.id()) && group.severity().isAtLeast(Severity.ERROR)) {
                count++;
            }
        }
        return count;
    }

    /** The busiest minute (or the busiest slice, when slices are longer than a minute). */
    private static Peak peak(List<TimeBucket> buckets) {
        if (buckets.isEmpty()) {
            return null;
        }
        long seconds = Math.max(60, buckets.get(0).seconds());
        Map<LocalDateTime, long[]> slices = new TreeMap<>();
        for (TimeBucket bucket : buckets) {
            LocalDateTime key = seconds == 60 ? bucket.start().truncatedTo(ChronoUnit.MINUTES) : bucket.start();
            long[] tally = slices.computeIfAbsent(key, k -> new long[2]);
            tally[0] += bucket.errors();
            tally[1] += bucket.warnings();
        }
        Map.Entry<LocalDateTime, long[]> best = null;
        for (Map.Entry<LocalDateTime, long[]> entry : slices.entrySet()) {
            if (best == null || entry.getValue()[0] + entry.getValue()[1] > best.getValue()[0] + best.getValue()[1]) {
                best = entry;
            }
        }
        return best == null ? null : new Peak(best.getKey(), seconds, best.getValue()[0], best.getValue()[1]);
    }

    private static String plain(String title) {
        return title.replace("`", "");
    }

    private static String lower(String text) {
        return text.isEmpty() ? text : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    private static String times(long count) {
        return count == 1 ? " (once)" : " (" + String.format(java.util.Locale.ROOT, "%,d", count) + " times)";
    }

    private static String clock(LocalDateTime time) {
        return java.time.format.DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.ROOT).format(time);
    }

    private static String gap(LocalDateTime from, LocalDateTime to) {
        long minutes = Duration.between(from, to).toMinutes();
        if (minutes < 1) {
            return "under a minute";
        }
        return minutes < 120 ? minutes + (minutes == 1 ? " minute" : " minutes") : (minutes / 60) + " hours";
    }
}
