package io.logreader.io;

import io.logreader.analysis.LogAnalyzer;
import io.logreader.model.AnalysisResult;
import io.logreader.parser.LogParser;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * One open log file: streams it through the parser and analyzer, remembers how far it has read so the
 * file can be tailed, and serves arbitrary line ranges for the context viewer.
 */
public final class LogSession {

    /** Receives read progress from {@link #load}; invoked on the loading thread. */
    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long bytesRead, long totalBytes);
    }

    private static final long PROGRESS_INTERVAL_NANOS = 100_000_000L;

    private final Path file;
    private final LineIndex index = new LineIndex();
    private LogAnalyzer analyzer;
    private LogParser parser;
    private volatile LogEncoding encoding = LogEncoding.UTF_8;
    private long offset;
    private long lines;

    public LogSession(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    /** Reads the whole file from the start. Interrupt the calling thread to cancel. */
    public synchronized AnalysisResult load(ProgressListener listener) throws IOException {
        analyzer = new LogAnalyzer();
        parser = new LogParser(analyzer);
        index.clear();
        encoding = LogEncoding.detect(file);
        offset = 0;
        lines = 0;
        read(true, listener);
        parser.flush();
        return snapshot();
    }

    /**
     * Live tail step: parses whatever was appended since the last read.
     *
     * @return a fresh result when something changed, empty otherwise
     */
    public synchronized Optional<AnalysisResult> poll() throws IOException {
        if (parser == null) {
            return Optional.of(load(null));
        }
        long size = Files.size(file);
        if (size < offset) {
            return Optional.of(load(null)); // truncated or rotated: start over
        }
        long before = lines;
        if (size > offset) {
            read(false, null);
        }
        if (lines != before) {
            return Optional.of(snapshot());
        }
        if (parser.hasPending()) {
            parser.flush(); // writer went quiet: the last event is complete
            return Optional.of(snapshot());
        }
        return Optional.empty();
    }

    /** Reads physical lines {@code from..to} (1-based, inclusive). */
    public List<String> readLines(long from, long to) throws IOException {
        return index.read(file, encoding, from, to);
    }

    /** Text encoding detected when the file was loaded. */
    public LogEncoding encoding() {
        return encoding;
    }

    public long lineCount() {
        return index.lineCount();
    }

    private void read(boolean includePartial, ProgressListener listener) throws IOException {
        long total = Files.size(file);
        long lastReport = System.nanoTime();
        try (OffsetLineReader reader = new OffsetLineReader(file, offset, encoding)) {
            String line;
            while ((line = reader.readLine(includePartial)) != null) {
                lines++;
                index.record(lines, reader.lineStartOffset());
                parser.accept(lines, reader.lineStartOffset(), line);
                if ((lines & 0x3FF) == 0) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException("Loading cancelled");
                    }
                    long now = System.nanoTime();
                    if (listener != null && now - lastReport > PROGRESS_INTERVAL_NANOS) {
                        lastReport = now;
                        listener.onProgress(reader.consumedOffset(), total);
                    }
                }
            }
            offset = reader.consumedOffset();
        }
    }

    private AnalysisResult snapshot() {
        return analyzer.snapshot(file, offset, lines);
    }
}
