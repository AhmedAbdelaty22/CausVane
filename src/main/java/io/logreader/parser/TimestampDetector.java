package io.logreader.parser;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects timestamps in log lines: ISO-8601, {@code yyyy/MM/dd}, syslog, Apache/nginx, US style,
 * epoch seconds / milliseconds and time-only prefixes.
 *
 * <p>Timestamps are kept as written (zone offsets are not applied) so they match the log text;
 * epoch values are rendered in UTC. Time-only stamps are anchored on {@link #TIME_ONLY_DATE}.
 */
public final class TimestampDetector {

    /** Date used for stamps that carry a time of day only (e.g. logback's {@code HH:mm:ss.SSS}). */
    public static final LocalDate TIME_ONLY_DATE = LocalDate.EPOCH;

    /** A timestamp found in a line, with its character span. */
    public record Match(int start, int end, LocalDateTime value) {}

    private static final String MONTHS = "Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec";
    private static final List<String> MONTH_NAMES = List.of(MONTHS.split("\\|"));
    private static final String FRACTION = "(?:[.,](\\d{1,9}))?";

    private static final Pattern ISO = Pattern.compile(
            "(\\d{4})[-/](\\d{2})[-/](\\d{2})[T ](\\d{2}):(\\d{2}):(\\d{2})" + FRACTION
                    + "(?:\\s?(?:Z|[+-]\\d{2}:?\\d{2})\\b)?");
    private static final Pattern SYSLOG = Pattern.compile(
            "(" + MONTHS + ")\\s+(\\d{1,2})\\s+(\\d{2}):(\\d{2}):(\\d{2})" + FRACTION);
    private static final Pattern APACHE = Pattern.compile(
            "(\\d{2})/(" + MONTHS + ")/(\\d{4}):(\\d{2}):(\\d{2}):(\\d{2})(?:\\s[+-]\\d{4})?");
    private static final Pattern US = Pattern.compile(
            "(\\d{2})/(\\d{2})/(\\d{4})[ ,]+(\\d{2}):(\\d{2}):(\\d{2})" + FRACTION);
    private static final Pattern EPOCH = Pattern.compile(
            "^(\\d{10})(?:[.,](\\d{1,9})|(\\d{3}))?(?=[\\s\\]:,]|$)");
    private static final Pattern TIME_ONLY = Pattern.compile(
            "^[\\[(]?(\\d{2}):(\\d{2}):(\\d{2})" + FRACTION);

    /** What may precede a timestamp for the line to still count as "starting with" it. */
    private static final Pattern LEAD_PREFIX = Pattern.compile(
            "^(?:<\\d{1,3}>\\d?\\s*|[\\[(<]?[A-Z]{3,9}[\\])>]?:?\\s+|\\d{1,7}[:\\s]\\s*)?[\\[(<\"]?$");

    private static final int HEAD = 96;

    /** Finds a timestamp at (or very near) the start of the line: the mark of a new log event. */
    public Match detectLeading(String line) {
        if (line.isEmpty()) {
            return null;
        }
        String head = line.length() > HEAD ? line.substring(0, HEAD) : line;
        Matcher m = EPOCH.matcher(head);
        if (m.find()) {
            LocalDateTime value = epoch(m);
            if (value != null) {
                return new Match(0, m.end(), value);
            }
        }
        m = TIME_ONLY.matcher(head);
        if (m.find()) {
            LocalDateTime value = timeOnly(m);
            if (value != null) {
                return new Match(m.start(), m.end(), value);
            }
        }
        Match found = find(head);
        if (found != null && LEAD_PREFIX.matcher(head.substring(0, found.start())).matches()) {
            return found;
        }
        return null;
    }

    /** Finds the first timestamp anywhere in the text, or returns null. */
    public LocalDateTime parse(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Match m = find(text.length() > 160 ? text.substring(0, 160) : text);
        if (m != null) {
            return m.value();
        }
        Matcher epoch = EPOCH.matcher(text.strip());
        return epoch.find() ? epoch(epoch) : null;
    }

    /** Converts an epoch number (seconds, millis, micros or nanos, judged by magnitude). */
    public LocalDateTime fromEpoch(double value) {
        // work in whole microseconds so binary floating point cannot shave a millisecond off
        double micros = value * 1e6;
        if (value >= 1e17) {
            micros = value / 1e3;
        } else if (value >= 1e14) {
            micros = value;
        } else if (value >= 1e11) {
            micros = value * 1e3;
        }
        long totalMicros = Math.round(micros);
        long whole = Math.floorDiv(totalMicros, 1_000_000L);
        long nanos = Math.floorMod(totalMicros, 1_000_000L) * 1_000L;
        try {
            return LocalDateTime.ofInstant(Instant.ofEpochSecond(whole, nanos), ZoneOffset.UTC);
        } catch (DateTimeException e) {
            return null;
        }
    }

    private Match find(String text) {
        Match best = null;
        Matcher m = ISO.matcher(text);
        if (m.find()) {
            best = pick(best, m, dateTime(num(m, 1), num(m, 2), num(m, 3), m, 4));
        }
        if (text.indexOf(':') < 0) {
            return best;
        }
        m = SYSLOG.matcher(text);
        if (m.find()) {
            best = pick(best, m, dateTime(Year.now().getValue(), month(m.group(1)), num(m, 2), m, 3));
        }
        m = APACHE.matcher(text);
        if (m.find()) {
            best = pick(best, m, dateTime(num(m, 3), month(m.group(2)), num(m, 1), m, 4));
        }
        m = US.matcher(text);
        if (m.find()) {
            LocalDateTime value = dateTime(num(m, 3), num(m, 1), num(m, 2), m, 4);
            if (value == null) {
                value = dateTime(num(m, 3), num(m, 2), num(m, 1), m, 4); // dd/MM/yyyy
            }
            best = pick(best, m, value);
        }
        return best;
    }

    private static Match pick(Match best, Matcher m, LocalDateTime value) {
        if (value == null || (best != null && best.start() <= m.start())) {
            return best;
        }
        return new Match(m.start(), m.end(), value);
    }

    /** Builds a date-time from explicit date parts and matcher groups {@code g..g+3} (h, m, s, fraction). */
    private static LocalDateTime dateTime(int year, int month, int day, Matcher m, int g) {
        try {
            int groups = m.groupCount();
            String fraction = g + 3 <= groups ? m.group(g + 3) : null;
            return LocalDateTime.of(year, month, day, num(m, g), num(m, g + 1), num(m, g + 2), nanos(fraction));
        } catch (DateTimeException e) {
            return null;
        }
    }

    private static LocalDateTime timeOnly(Matcher m) {
        try {
            return LocalDateTime.of(TIME_ONLY_DATE, LocalTime.of(num(m, 1), num(m, 2), num(m, 3), nanos(m.group(4))));
        } catch (DateTimeException e) {
            return null;
        }
    }

    private LocalDateTime epoch(Matcher m) {
        long seconds = Long.parseLong(m.group(1));
        if (seconds < 946_684_800L || seconds > 4_102_444_800L) { // 2000-01-01 .. 2100-01-01
            return null;
        }
        String fraction = m.group(2) != null ? m.group(2) : m.group(3);
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(seconds, nanos(fraction)), ZoneOffset.UTC);
    }

    private static int num(Matcher m, int group) {
        return Integer.parseInt(m.group(group));
    }

    private static int month(String name) {
        return MONTH_NAMES.indexOf(name) + 1;
    }

    private static int nanos(String fraction) {
        if (fraction == null || fraction.isEmpty()) {
            return 0;
        }
        String padded = (fraction + "000000000").substring(0, 9);
        return Integer.parseInt(padded);
    }
}
