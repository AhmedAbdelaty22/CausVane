package io.logreader.model;

import java.time.LocalDateTime;

/**
 * Number of problems logged in one slice of time.
 *
 * @param start   start of the slice
 * @param seconds length of the slice
 * @param errors  occurrences at ERROR or FATAL level
 * @param warnings occurrences at WARN level
 */
public record TimeBucket(LocalDateTime start, long seconds, long errors, long warnings) {

    public LocalDateTime end() {
        return start.plusSeconds(seconds);
    }
}
