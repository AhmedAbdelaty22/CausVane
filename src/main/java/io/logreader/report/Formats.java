package io.logreader.report;

import io.logreader.parser.TimestampDetector;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Shared display formatting for timestamps, durations and counts. */
public final class Formats {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS", Locale.ROOT);

    private Formats() {}

    public static String timestamp(LocalDateTime value) {
        if (value == null) {
            return "—";
        }
        // time-only log stamps carry no date
        return value.toLocalDate().equals(TimestampDetector.TIME_ONLY_DATE) ? TIME.format(value) : DATE_TIME.format(value);
    }

    public static String duration(Duration d) {
        if (d == null) {
            return "—";
        }
        long seconds = Math.abs(d.getSeconds());
        if (seconds == 0) {
            return d.toMillis() + " ms";
        }
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        long secs = seconds % 60;
        if (days > 0) {
            return days + "d " + hours + "h " + minutes + "m";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m " + secs + "s";
        }
        if (minutes > 0) {
            return minutes + "m " + secs + "s";
        }
        return secs + "s";
    }

    public static String count(long n) {
        return String.format(Locale.US, "%,d", n);
    }

    public static String bytes(long n) {
        if (n < 1024) {
            return n + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = n;
        int unit = -1;
        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.US, "%.1f %s", value, units[unit]);
    }

    public static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
