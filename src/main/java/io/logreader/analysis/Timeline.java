package io.logreader.analysis;

import io.logreader.model.TimeBucket;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Streaming histogram of errors and warnings over time. The slice length starts at one second and doubles
 * whenever the number of slices would exceed {@link #MAX_BUCKETS}, so memory stays bounded for any time span.
 */
final class Timeline {

    static final int MAX_BUCKETS = 2048;

    private TreeMap<Long, long[]> buckets = new TreeMap<>();
    private long bucketSeconds = 1;

    void add(LocalDateTime timestamp, boolean error) {
        long key = Math.floorDiv(timestamp.toEpochSecond(ZoneOffset.UTC), bucketSeconds);
        buckets.computeIfAbsent(key, k -> new long[2])[error ? 0 : 1]++;
        while (buckets.size() > MAX_BUCKETS) {
            coarsen();
        }
    }

    private void coarsen() {
        TreeMap<Long, long[]> merged = new TreeMap<>();
        for (Map.Entry<Long, long[]> entry : buckets.entrySet()) {
            long[] target = merged.computeIfAbsent(Math.floorDiv(entry.getKey(), 2L), k -> new long[2]);
            target[0] += entry.getValue()[0];
            target[1] += entry.getValue()[1];
        }
        buckets = merged;
        bucketSeconds *= 2;
    }

    List<TimeBucket> snapshot() {
        List<TimeBucket> result = new ArrayList<>(buckets.size());
        for (Map.Entry<Long, long[]> entry : buckets.entrySet()) {
            LocalDateTime start = LocalDateTime.ofEpochSecond(entry.getKey() * bucketSeconds, 0, ZoneOffset.UTC);
            result.add(new TimeBucket(start, bucketSeconds, entry.getValue()[0], entry.getValue()[1]));
        }
        return result;
    }
}
