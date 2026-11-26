package io.causvane.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Log text pasted into the window, as an alternative to opening a log file (both ways are offered).
 * The analyser reads logs from files, so the text is kept in a private temporary folder that only the
 * current user can read, named "Pasted log.log" so the window and reports show a sensible name. It is
 * deleted when another log is opened and, at the latest, when CausVane quits.
 */
public final class PastedLog {

    /** Pasted text larger than this is refused: it is meant for logs copied from a chat or ticket. */
    public static final int MAX_CHARS = 50_000_000;
    public static final String NAME = "Pasted log.log";

    private PastedLog() {}

    /** Writes the text to a new private temporary file and returns it. */
    public static Path write(String text) throws IOException {
        if (text.length() > MAX_CHARS) {
            throw new IOException("The pasted text is too large; save it as a file and open that instead.");
        }
        boolean posix = FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
        Path folder = posix
                ? Files.createTempDirectory("causvane-paste-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
                : Files.createTempDirectory("causvane-paste-");
        Path file = folder.resolve(NAME);
        if (posix) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        Files.writeString(file, text.endsWith("\n") ? text : text + "\n", StandardCharsets.UTF_8);
        // removed in the reverse order of registering: the file first, then its now empty folder
        folder.toFile().deleteOnExit();
        file.toFile().deleteOnExit();
        return file;
    }

    /** True for a file this class created. */
    public static boolean isPasted(Path file) {
        Path parent = file == null ? null : file.getParent();
        return parent != null && NAME.equals(String.valueOf(file.getFileName()))
                && String.valueOf(parent.getFileName()).startsWith("causvane-paste-");
    }

    /** Deletes a pasted log and its private folder; does nothing for an ordinary file. */
    public static void delete(Path file) {
        if (!isPasted(file)) {
            return;
        }
        try (Stream<Path> inside = Files.walk(file.getParent())) {
            inside.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            // already gone, or removed when CausVane quits
        }
    }
}
