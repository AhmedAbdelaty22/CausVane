package io.logreader.io;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.zip.GZIPInputStream;

/** Gzip support for rotated logs such as {@code app.log.1.gz}. */
final class Decompressor {

    private static final long PROGRESS_INTERVAL_NANOS = 100_000_000L;

    private Decompressor() {}

    /** True when the file starts with the gzip signature, whatever its name. */
    static boolean isGzip(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] head = in.readNBytes(2);
            return head.length == 2 && (head[0] & 0xFF) == 0x1F && (head[1] & 0xFF) == 0x8B;
        }
    }

    /**
     * Decompresses into a new temporary file readable only by the current user. Multi-member archives
     * (several gzip files joined together) are read in full.
     *
     * @throws IOException when the archive is damaged or decompresses to more than {@code maxBytes}
     */
    static Path toTempFile(Path gzip, long maxBytes, LogSession.ProgressListener listener) throws IOException {
        Path temp = FileSystems.getDefault().supportedFileAttributeViews().contains("posix")
                ? Files.createTempFile("logreader-", ".log", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                : Files.createTempFile("logreader-", ".log");
        temp.toFile().deleteOnExit();
        long compressedSize = Files.size(gzip);
        boolean done = false;
        try (CountingInputStream counted = new CountingInputStream(Files.newInputStream(gzip));
             InputStream in = new GZIPInputStream(counted, 1 << 16);
             OutputStream out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[1 << 16];
            long written = 0;
            long lastReport = System.nanoTime();
            int read;
            while ((read = in.read(buffer)) > 0) {
                written += read;
                if (written > maxBytes) {
                    throw new IOException("This compressed file expands to more than "
                            + (maxBytes >> 30) + " GB, which LogReader does not open. Decompress part of it first.");
                }
                out.write(buffer, 0, read);
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Loading cancelled");
                }
                long now = System.nanoTime();
                if (listener != null && now - lastReport > PROGRESS_INTERVAL_NANOS) {
                    lastReport = now;
                    listener.onProgress(counted.count, compressedSize);
                }
            }
            done = true;
        } catch (java.util.zip.ZipException e) {
            throw new IOException("The file looks compressed (gzip) but is damaged: " + e.getMessage(), e);
        } finally {
            if (!done) {
                Files.deleteIfExists(temp);
            }
        }
        return temp;
    }

    private static final class CountingInputStream extends java.io.FilterInputStream {
        long count;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
            }
            return n;
        }
    }
}
