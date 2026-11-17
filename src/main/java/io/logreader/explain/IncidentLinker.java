package io.logreader.explain;

import io.logreader.model.ErrorGroup;
import io.logreader.model.Severity;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Groups problems that are probably one incident: an infrastructure failure (database, network, certificate,
 * resources, crash) followed within minutes by the errors it typically causes elsewhere, such as refused
 * connections, open circuit breakers or full queues. Also notes warnings that came just before it.
 *
 * <p>This is a heuristic based on timing and the kind of each problem. It says "probably", and the
 * interface presents it that way: a person should confirm the link.
 */
public final class IncidentLinker {

    /** How long after the first failure another problem may start and still be counted as part of it. */
    public static final Duration WINDOW = Duration.ofMinutes(15);
    /** How long before the first failure a warning may start to count as a warning sign. */
    public static final Duration WARNING_LEAD_TIME = Duration.ofMinutes(10);
    /** Above this many distinct problems the analysis is skipped to keep the window responsive. */
    public static final int MAX_GROUPS = 3000;

    /**
     * One probable incident.
     *
     * @param lead          the earliest failure, where to start looking
     * @param followers     problems that started after it and are typical consequences of it, in order of first sight
     * @param earlyWarnings warnings that started shortly before it and stopped around when it began
     */
    public record Incident(ErrorGroup lead, List<ErrorGroup> followers, List<ErrorGroup> earlyWarnings) {
        public Incident {
            followers = List.copyOf(followers);
            earlyWarnings = List.copyOf(earlyWarnings);
        }

        public int size() {
            return 1 + followers.size() + earlyWarnings.size();
        }
    }

    /** The part a problem plays in an incident. */
    public enum Part { LEAD, FOLLOWER, EARLY_WARNING }

    /** For one problem: which incident it belongs to and in what role. */
    public record Membership(Incident incident, Part part) {}

    /** Kinds of problem that can start an incident, and what each typically brings in its wake. */
    private static final Map<String, Set<String>> FOLLOWERS = Map.of(
            "Database", Set.of("Database", "Network", "Protection", "Capacity", "Messaging", "Resources"),
            "Network", Set.of("Network", "Protection", "Capacity", "Messaging"),
            "Security", Set.of("Network", "Protection", "Capacity", "Resources"),
            "Resources", Set.of("Resources", "Capacity", "Network", "Protection", "Database", "Lifecycle"),
            "Memory", Set.of("Memory", "Resources", "Capacity", "Lifecycle"),
            "Crash", Set.of("Crash", "Capacity", "Lifecycle"),
            "Configuration", Set.of("Configuration", "Network", "Lifecycle"),
            "Deployment", Set.of("Deployment", "Crash", "Network", "Lifecycle"));

    private static final Set<String> WARNING_SIGNS = Set.of("Performance", "Database", "Resources", "Security", "Capacity", "Network");

    private IncidentLinker() {}

    /** Finds the probable incidents among the problems of one log. */
    public static List<Incident> find(List<ErrorGroup> groups) {
        if (groups.size() > MAX_GROUPS) {
            return List.of();
        }
        ErrorExplainer explainer = ErrorExplainer.standard();
        Map<ErrorGroup, String> category = new HashMap<>();
        List<ErrorGroup> timed = new ArrayList<>();
        for (ErrorGroup group : groups) {
            if (group.firstSeen() != null && group.severity().isAtLeast(Severity.WARN)) {
                category.put(group, explainer.explain(group).category());
                timed.add(group);
            }
        }
        timed.sort(Comparator.comparing(ErrorGroup::firstSeen)
                .thenComparing(ErrorGroup::severity, Comparator.reverseOrder())
                .thenComparing(ErrorGroup::count, Comparator.reverseOrder()));

        Set<ErrorGroup> taken = new HashSet<>();
        List<Incident> incidents = new ArrayList<>();
        for (ErrorGroup lead : timed) {
            Set<String> allowed = FOLLOWERS.get(category.get(lead));
            if (taken.contains(lead) || allowed == null || !lead.severity().isAtLeast(Severity.ERROR)) {
                continue;
            }
            LocalDateTime start = lead.firstSeen();
            LocalDateTime end = start.plus(WINDOW);
            List<ErrorGroup> followers = new ArrayList<>();
            for (ErrorGroup other : timed) {
                if (other != lead && !taken.contains(other) && allowed.contains(category.get(other))
                        && !other.firstSeen().isBefore(start) && !other.firstSeen().isAfter(end)) {
                    followers.add(other);
                }
            }
            List<ErrorGroup> warnings = new ArrayList<>();
            for (ErrorGroup other : timed) {
                if (other.severity() == Severity.WARN && !taken.contains(other) && !followers.contains(other)
                        && WARNING_SIGNS.contains(category.get(other))
                        && other.firstSeen().isBefore(start)
                        && !other.firstSeen().isBefore(start.minus(WARNING_LEAD_TIME))
                        && other.lastSeen() != null && !other.lastSeen().isAfter(start.plusMinutes(2))) {
                    warnings.add(other);
                }
            }
            if (followers.isEmpty() && warnings.isEmpty()) {
                continue;
            }
            taken.add(lead);
            taken.addAll(followers);
            taken.addAll(warnings);
            incidents.add(new Incident(lead, followers, warnings));
        }
        return incidents;
    }

    /** Index of {@link #find} by problem id. */
    public static Map<String, Membership> index(List<Incident> incidents) {
        Map<String, Membership> byId = new LinkedHashMap<>();
        for (Incident incident : incidents) {
            byId.put(incident.lead().id(), new Membership(incident, Part.LEAD));
            incident.followers().forEach(g -> byId.put(g.id(), new Membership(incident, Part.FOLLOWER)));
            incident.earlyWarnings().forEach(g -> byId.put(g.id(), new Membership(incident, Part.EARLY_WARNING)));
        }
        return byId;
    }

    /** "12 s", "3 min", "1 h 5 min": the gap between two moments, for sentences. */
    public static String gap(LocalDateTime from, LocalDateTime to) {
        long seconds = Math.abs(Duration.between(from, to).getSeconds());
        if (seconds < 60) {
            return seconds + " s";
        }
        if (seconds < 3600) {
            return (seconds / 60) + " min";
        }
        return (seconds / 3600) + " h " + ((seconds % 3600) / 60) + " min";
    }
}
