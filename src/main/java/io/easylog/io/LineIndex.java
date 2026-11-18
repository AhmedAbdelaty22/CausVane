package io.easylog.io;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Sparse index from line number to byte offset (one checkpoint every {@link #STRIDE} lines).
 * Lets the context viewer fetch any line range of a huge file with a single short seek + scan,
 * at a memory cost of 8 bytes per {@value #STRIDE} lines. Thread-safe.
 */
public final class LineIndex {

    static final int STRIDE = 256;

    private long[] checkpoints = new long[1024];
    private int size;
    private long lineCount;

    /** Records the byte offset of a line; lines must be recorded in order starting at 1. */
    public synchronized void record(long lineNumber, long byteOffset) {
        lineCount = lineNumber;
        if ((lineNumber - 1) % STRIDE == 0) {
            if (size == checkpoints.length) {
                checkpoints = Arrays.copyOf(checkpoints, size * 2);
            }
            checkpoints[size++] = byteOffset;
        }
    }

    public synchronized long lineCount() {
        return lineCount;
    }

    public synchronized void clear() {
        size = 0;
        lineCount = 0;
    }

    /** Reads lines {@code from..to} (1-based, inclusive), clamped to the indexed range. */
    public List<String> read(Path file, LogEncoding encoding, long from, long to) throws IOException {
        long checkpointLine;
        long offset;
        synchronized (this) {
            from = Math.max(1, from);
            to = Math.min(to, lineCount);
            if (size == 0 || from > to) {
                return List.of();
            }
            int slot = (int) Math.min((from - 1) / STRIDE, size - 1);
            checkpointLine = (long) slot * STRIDE + 1;
            offset = checkpoints[slot];
        }
        List<String> lines = new ArrayList<>((int) Math.min(to - from + 1, 4096));
        try (OffsetLineReader reader = new OffsetLineReader(file, offset, encoding)) {
            long current = checkpointLine;
            String line;
            while (current <= to && (line = reader.readLine(true)) != null) {
                if (current >= from) {
                    lines.add(line);
                }
                current++;
            }
        }
        return lines;
    }
}
