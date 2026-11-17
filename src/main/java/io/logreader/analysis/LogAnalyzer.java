package io.logreader.analysis;

import io.logreader.model.AnalysisResult;
import io.logreader.model.ErrorGroup;
import io.logreader.model.Language;
import io.logreader.model.LogContents;
import io.logreader.model.LogEvent;
import io.logreader.model.ParsedTrace;
import io.logreader.model.Severity;
import io.logreader.model.StackFrame;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Consumes parsed events and aggregates them into deduplicated error groups and summary metrics.
 * Keeps a bounded number of sample occurrences per group, so memory stays flat on huge logs.
 * Not thread-safe: use from the parsing thread and publish {@link #snapshot} results.
 */
public final class LogAnalyzer implements Consumer<LogEvent> {

    /** Occurrences kept per group for context viewing. */
    public static final int MAX_SAMPLES = 25;
    private static final int MAX_TRACE_IDS = 500;

    /** Distinct errors listed at most. A log cannot make the result grow without bound. */
    public static final int MAX_GROUPS = 10_000;
    /** Stored sample text (characters) after which only the first occurrence of each error is kept. */
    static final long SAMPLE_SOFT_BUDGET = 32_000_000L;
    /** Stored sample text (characters) after which no further distinct errors are listed. */
    static final long SAMPLE_HARD_BUDGET = 64_000_000L;

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern HEX = Pattern.compile("\\b0x[0-9a-fA-F]+\\b|\\b[0-9a-fA-F]{12,}\\b");
    private static final Pattern QUOTED = Pattern.compile("'[^']{0,200}'|\"[^\"]{0,200}\"");
    private static final Pattern MIXED_ID = Pattern.compile("\\b(?=[A-Za-z_-]*\\d)(?=[\\d_-]*[A-Za-z])[\\w-]{6,}\\b");
    private static final Pattern NUMBER = Pattern.compile("\\d+");

    private final Map<String, Accumulator> groups = new LinkedHashMap<>();
    private final Timeline timeline = new Timeline();
    private final int maxGroups;
    private final long softBudget;
    private final long hardBudget;
    private long storedChars;
    private long unlistedOccurrences;
    private long totalEvents;
    private long errorCount;
    private long warningCount;
    private LocalDateTime first;
    private LocalDateTime last;
    private final long[] byLevel = new long[Severity.values().length];
    private long timestamped;
    private long multiLine;
    private long structured;
    private long withStackTrace;
    private long withTraceId;
    private final Set<String> traceIdsSeen = new HashSet<>();

    public LogAnalyzer() {
        this(MAX_GROUPS, SAMPLE_SOFT_BUDGET, SAMPLE_HARD_BUDGET);
    }

    /** For tests: the same analyzer with smaller limits. */
    LogAnalyzer(int maxGroups, long softBudget, long hardBudget) {
        this.maxGroups = maxGroups;
        this.softBudget = softBudget;
        this.hardBudget = hardBudget;
    }

    @Override
    public void accept(LogEvent event) {
        totalEvents++;
        byLevel[event.severity().ordinal()]++;
        if (event.lineCount() > 1) {
            multiLine++;
        }
        if (event.structured()) {
            structured++;
        }
        if (event.trace() != null && event.trace().hasFrames()) {
            withStackTrace++;
        }
        if (event.traceId() != null) {
            withTraceId++;
            if (traceIdsSeen.size() < LogContents.TRACE_ID_LIMIT) {
                traceIdsSeen.add(event.traceId());
            }
        }
        LocalDateTime ts = event.timestamp();
        if (ts != null) {
            timestamped++;
            if (first == null || ts.isBefore(first)) {
                first = ts;
            }
            if (last == null || ts.isAfter(last)) {
                last = ts;
            }
        }
        if (!event.isProblem()) {
            return;
        }
        if (event.severity().isAtLeast(Severity.ERROR)) {
            errorCount++;
            if (ts != null) {
                timeline.add(ts, true);
            }
        } else if (event.severity() == Severity.WARN) {
            warningCount++;
            if (ts != null) {
                timeline.add(ts, false);
            }
        }
        String key = dedupKey(event);
        Accumulator group = groups.get(key);
        if (group == null) {
            if (groups.size() >= maxGroups || storedChars >= hardBudget) {
                unlistedOccurrences++; // still counted in the totals and the timeline, just not listed
                return;
            }
            group = new Accumulator(key, event);
            groups.put(key, group);
        }
        boolean keepSample = group.count == 0 || storedChars < softBudget;
        storedChars += group.add(event, keepSample);
    }

    public long totalEvents() {
        return totalEvents;
    }

    /** Builds an immutable snapshot, most frequent errors first. */
    public AnalysisResult snapshot(Path file, long fileSize, long totalLines) {
        List<ErrorGroup> result = new ArrayList<>(groups.size());
        for (Accumulator acc : groups.values()) {
            result.add(acc.toGroup());
        }
        result.sort(Comparator.comparingLong(ErrorGroup::count).reversed()
                .thenComparing(ErrorGroup::severity, Comparator.reverseOrder())
                .thenComparingLong(ErrorGroup::firstLine));
        return new AnalysisResult(file, fileSize, totalLines, totalEvents, errorCount, warningCount, first, last, result,
                timeline.snapshot(), unlistedOccurrences, contents());
    }

    private LogContents contents() {
        Map<Severity, Long> levels = new EnumMap<>(Severity.class);
        for (Severity s : Severity.values()) {
            levels.put(s, byLevel[s.ordinal()]);
        }
        return new LogContents(levels, timestamped, multiLine, structured, withStackTrace, withTraceId,
                traceIdsSeen.size());
    }

    /**
     * Same error = same exception type at the same pinpointed location. Without a stack trace, the
     * message with its variable parts (ids, numbers, quoted values) masked is used instead.
     */
    static String dedupKey(LogEvent event) {
        ParsedTrace trace = event.trace();
        String type = trace == null ? null : trace.topType();
        if (trace != null && trace.pinpoint() != null) {
            StackFrame pin = trace.pinpoint();
            return type + "@" + pin.location() + "#" + pin.function();
        }
        String normalized = normalize(event.message());
        return type != null ? type + "|" + normalized : event.severity() + "|" + normalized;
    }

    static String normalize(String message) {
        if (message == null) {
            return "";
        }
        String s = message.length() > 300 ? message.substring(0, 300) : message;
        s = UUID.matcher(s).replaceAll("<id>");
        s = HEX.matcher(s).replaceAll("<hex>");
        s = QUOTED.matcher(s).replaceAll("<str>");
        s = MIXED_ID.matcher(s).replaceAll("<id>");
        s = NUMBER.matcher(s).replaceAll("<n>");
        return s.strip();
    }

    private static final class Accumulator {
        private final String key;
        private final LogEvent firstEvent;
        private final List<LogEvent> samples = new ArrayList<>();
        private final Set<String> traceIds = new LinkedHashSet<>();
        private Severity severity = Severity.UNKNOWN;
        private long count;
        private LocalDateTime firstSeen;
        private LocalDateTime lastSeen;
        private long lastLine;

        Accumulator(String key, LogEvent firstEvent) {
            this.key = key;
            this.firstEvent = firstEvent;
        }

        /** @return the number of characters of sample text this call stored */
        long add(LogEvent event, boolean keepSample) {
            long stored = 0;
            count++;
            lastLine = event.lineNumber();
            if (event.severity().isAtLeast(severity)) {
                severity = event.severity();
            }
            LocalDateTime ts = event.timestamp();
            if (ts != null) {
                if (firstSeen == null || ts.isBefore(firstSeen)) {
                    firstSeen = ts;
                }
                if (lastSeen == null || ts.isAfter(lastSeen)) {
                    lastSeen = ts;
                }
            }
            if (keepSample && samples.size() < MAX_SAMPLES) {
                samples.add(event);
                for (String line : event.lines()) {
                    stored += line.length();
                }
            }
            if (event.traceId() != null && traceIds.size() < MAX_TRACE_IDS) {
                traceIds.add(event.traceId());
            }
            return stored;
        }

        ErrorGroup toGroup() {
            ParsedTrace trace = firstEvent.trace();
            String type = trace == null ? null : trace.topType();
            String message = firstEvent.message();
            if (trace != null && !trace.sections().isEmpty() && trace.sections().get(0).message() != null
                    && (message == null || message.isBlank())) {
                message = trace.sections().get(0).message();
            }
            StringBuilder search = new StringBuilder();
            search.append(type).append('\n').append(message).append('\n');
            if (trace != null) {
                search.append(trace.rootType()).append(' ').append(trace.rootMessage()).append('\n');
                if (trace.pinpoint() != null) {
                    search.append(trace.pinpoint().location()).append(' ')
                            .append(trace.pinpoint().qualifiedFunction()).append(' ')
                            .append(trace.pinpoint().file()).append('\n');
                }
            }
            traceIds.forEach(id -> search.append(id).append(' '));
            search.append('\n');
            for (LogEvent sample : samples) {
                int limit = sample == firstEvent ? sample.lines().size() : 1;
                for (int i = 0; i < limit; i++) {
                    search.append(sample.lines().get(i)).append('\n');
                }
            }
            return new ErrorGroup(
                    Integer.toHexString(key.hashCode()) + "-" + firstEvent.lineNumber(),
                    severity, type, message,
                    trace == null ? null : trace.pinpoint(),
                    trace == null ? null : trace.rootType(),
                    trace == null ? null : trace.rootMessage(),
                    trace == null ? List.of() : trace.chain(),
                    trace == null ? Language.UNKNOWN : trace.language(),
                    count, firstSeen, lastSeen, firstEvent.lineNumber(), lastLine,
                    samples, traceIds, search.toString().toLowerCase(Locale.ROOT));
        }
    }
}
