package io.causvane.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Text encoding of a log file. UTF-8 is the norm (and covers ASCII); UTF-16 matters on Windows, where
 * PowerShell's {@code >} redirection and some .NET loggers write UTF-16 files.
 */
public enum LogEncoding {
    UTF_8(StandardCharsets.UTF_8),
    UTF_16LE(StandardCharsets.UTF_16LE),
    UTF_16BE(StandardCharsets.UTF_16BE);

    private final Charset charset;

    LogEncoding(Charset charset) {
        this.charset = charset;
    }

    public Charset charset() {
        return charset;
    }

    public boolean isWide() {
        return this != UTF_8;
    }

    /** Detects the encoding from the byte order mark, or from the zero-byte pattern of BOM-less UTF-16. */
    public static LogEncoding detect(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(4);
        }
        if (head.length >= 2) {
            int b0 = head[0] & 0xFF;
            int b1 = head[1] & 0xFF;
            if (b0 == 0xFF && b1 == 0xFE) {
                return UTF_16LE;
            }
            if (b0 == 0xFE && b1 == 0xFF) {
                return UTF_16BE;
            }
        }
        if (head.length == 4) {
            // plain ASCII text stored as UTF-16 has a zero in every other byte
            if (head[0] != 0 && head[1] == 0 && head[2] != 0 && head[3] == 0) {
                return UTF_16LE;
            }
            if (head[0] == 0 && head[1] != 0 && head[2] == 0 && head[3] != 0) {
                return UTF_16BE;
            }
        }
        return UTF_8;
    }
}
