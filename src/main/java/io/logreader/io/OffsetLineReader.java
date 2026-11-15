package io.logreader.io;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Buffered line reader that starts at any byte offset and reports the byte offset of every line,
 * which makes sparse line indexing and live tailing possible. Over-long lines are truncated rather
 * than buffered whole, so a single multi-gigabyte line cannot exhaust the heap.
 */
public final class OffsetLineReader implements Closeable {

    public static final int MAX_LINE_BYTES = 64 * 1024;
    private static final String TRUNCATION_MARK = " …[line truncated]";

    private final InputStream in;
    private final byte[] buffer = new byte[1 << 16];
    private int position;
    private int limit;
    /** File offset of {@code buffer[0]}. */
    private long bufferOffset;
    private byte[] line = new byte[1024];
    private long lineStart;
    private long consumed;

    private final LogEncoding encoding;

    public OffsetLineReader(Path file, long startOffset) throws IOException {
        this(file, startOffset, LogEncoding.UTF_8);
    }

    /**
     * @param startOffset byte offset to start at; for UTF-16 files it must be even, as every offset
     *                    reported by this class is
     */
    public OffsetLineReader(Path file, long startOffset, LogEncoding encoding) throws IOException {
        this.encoding = encoding;
        FileChannel channel = FileChannel.open(file, StandardOpenOption.READ);
        channel.position(startOffset);
        this.in = Channels.newInputStream(channel);
        this.bufferOffset = startOffset;
        this.consumed = startOffset;
    }

    /**
     * Reads the next line without its terminator, or returns null at end of file.
     *
     * @param includePartial whether to return a final line that has no trailing newline yet; pass false
     *                       when tailing a file that is still being written
     */
    public String readLine(boolean includePartial) throws IOException {
        return encoding.isWide() ? readWideLine(includePartial) : readByteLine(includePartial);
    }

    /** UTF-16: characters are two bytes, so line breaks are searched on two-byte boundaries. */
    private String readWideLine(boolean includePartial) throws IOException {
        boolean littleEndian = encoding == LogEncoding.UTF_16LE;
        long start = bufferOffset + position;
        int length = 0;
        boolean any = false;
        boolean truncated = false;
        while (true) {
            int usable = limit & ~1;
            if (position >= usable) {
                // an odd trailing byte means the writer is mid-character: wait for the rest
                boolean more = (limit & 1) == 0 && fill();
                if (!more || (limit & ~1) == 0) {
                    if (!any || !includePartial) {
                        return null;
                    }
                    break;
                }
                continue;
            }
            any = true;
            int i = position;
            while (i < usable && !(littleEndian ? buffer[i] == '\n' && buffer[i + 1] == 0
                    : buffer[i] == 0 && buffer[i + 1] == '\n')) {
                i += 2;
            }
            int chunk = i - position;
            int copy = Math.min(chunk, MAX_LINE_BYTES - length);
            if (copy > 0) {
                if (length + copy > line.length) {
                    line = Arrays.copyOf(line, Math.max(line.length * 2, length + copy));
                }
                System.arraycopy(buffer, position, line, length, copy);
                length += copy;
            }
            if (copy < chunk) {
                truncated = true;
            }
            if (i < usable) {
                position = i + 2;
                break;
            }
            position = usable;
        }
        lineStart = start;
        consumed = bufferOffset + position;
        if (length >= 2 && (littleEndian ? line[length - 2] == '\r' && line[length - 1] == 0
                : line[length - 2] == 0 && line[length - 1] == '\r')) {
            length -= 2;
        }
        int from = 0;
        if (start == 0 && length >= 2) {
            int b0 = line[0] & 0xFF;
            int b1 = line[1] & 0xFF;
            if ((b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)) {
                from = 2; // byte order mark
            }
        }
        String text = new String(line, from, length - from, encoding.charset());
        text = TextSanitizer.clean(text);
        return truncated ? text + TRUNCATION_MARK : text;
    }

    private String readByteLine(boolean includePartial) throws IOException {
        long start = bufferOffset + position;
        int length = 0;
        boolean any = false;
        boolean truncated = false;
        while (true) {
            if (position == limit && !fill()) {
                if (!any || !includePartial) {
                    return null;
                }
                break;
            }
            any = true;
            int i = position;
            while (i < limit && buffer[i] != '\n') {
                i++;
            }
            int chunk = i - position;
            int copy = Math.min(chunk, MAX_LINE_BYTES - length);
            if (copy > 0) {
                if (length + copy > line.length) {
                    line = Arrays.copyOf(line, Math.max(line.length * 2, length + copy));
                }
                System.arraycopy(buffer, position, line, length, copy);
                length += copy;
            }
            if (copy < chunk) {
                truncated = true;
            }
            if (i < limit) {
                position = i + 1;
                break;
            }
            position = limit;
        }
        lineStart = start;
        consumed = bufferOffset + position;
        if (length > 0 && line[length - 1] == '\r') {
            length--;
        }
        int from = 0;
        if (start == 0 && length >= 3 && (line[0] & 0xFF) == 0xEF && (line[1] & 0xFF) == 0xBB && (line[2] & 0xFF) == 0xBF) {
            from = 3; // UTF-8 byte order mark
        }
        String text = new String(line, from, length - from, StandardCharsets.UTF_8);
        text = TextSanitizer.clean(text);
        return truncated ? text + TRUNCATION_MARK : text;
    }

    /** Byte offset where the most recently returned line starts. */
    public long lineStartOffset() {
        return lineStart;
    }

    /** Byte offset just past the most recently returned line. */
    public long consumedOffset() {
        return consumed;
    }

    private boolean fill() throws IOException {
        bufferOffset += limit;
        position = 0;
        limit = 0;
        int read = in.readNBytes(buffer, 0, buffer.length); // full buffers keep UTF-16 pairs aligned
        if (read <= 0) {
            return false;
        }
        limit = read;
        return true;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
