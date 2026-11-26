package io.causvane.gui;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

/**
 * What the window remembers between launches: theme, lines of context, last folder, window position and
 * recently opened files. Stored with the operating system's per-user preferences (on macOS a file under
 * ~/Library/Preferences, on Windows the user's registry, on Linux a file under ~/.java).
 *
 * <p>With {@code -Dcausvane.noPersist=true} (used by automated checks) nothing is read or written.
 * Storage problems never stop the program: the setting is simply not remembered.
 */
final class Settings {

    static final int MAX_RECENT = 8;
    private static final int MAX_PATH_CHARS = 1000;

    private static final String THEME = "theme";
    private static final String CONTEXT = "contextLines";
    private static final String FOLDER = "lastFolder";
    private static final String RECENT = "recentFiles";
    private static final String BOUNDS = "windowBounds";

    private static Settings instance;

    private final Preferences node;
    private final Map<String, String> memory = new HashMap<>();

    private Settings(Preferences node) {
        this.node = node;
    }

    static synchronized Settings get() {
        if (instance == null) {
            Preferences node = null;
            if (!Boolean.getBoolean("causvane.noPersist")) {
                try {
                    node = Preferences.userRoot().node("io/causvane");
                    carryOverFromOldName(node);
                } catch (RuntimeException e) {
                    node = null; // no preference store on this system: behave as if nothing is saved
                }
            }
            instance = new Settings(node);
        }
        return instance;
    }

    /**
     * The app was called LogReader, and briefly EasyLog, before it became CausVane, and kept its settings
     * under those names. On the first start under the new name, copy the most recent ones across once, so
     * the theme, recent files and window stay as they were.
     */
    private static void carryOverFromOldName(Preferences node) {
        try {
            if (node.keys().length > 0) {
                return;
            }
            for (String oldPath : new String[] {"io/" + "easy" + "log", "io/" + "log" + "reader"}) {
                if (!Preferences.userRoot().nodeExists(oldPath)) {
                    continue;
                }
                Preferences old = Preferences.userRoot().node(oldPath);
                if (old.keys().length == 0) {
                    continue;
                }
                for (String key : old.keys()) {
                    String value = old.get(key, null);
                    if (value != null) {
                        node.put(key, value);
                    }
                }
                node.flush();
                return;
            }
        } catch (java.util.prefs.BackingStoreException | RuntimeException e) {
            // nothing to carry over, or no access: start with the defaults
        }
    }

    // ------------------------------------------------------------------ values

    Theme.Mode theme() {
        String value = read(THEME);
        if ("light".equals(value)) {
            return Theme.Mode.LIGHT;
        }
        return "dark".equals(value) ? Theme.Mode.DARK : null;
    }

    void theme(Theme.Mode mode) {
        write(THEME, mode == Theme.Mode.LIGHT ? "light" : "dark");
    }

    int contextLinesOr(int fallback) {
        try {
            int value = Integer.parseInt(read(CONTEXT));
            return value >= 0 && value <= 500 ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    void contextLines(int lines) {
        write(CONTEXT, String.valueOf(lines));
    }

    /** The folder of the last opened file, if it still exists. */
    Path lastFolder() {
        String value = read(FOLDER);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            Path folder = Path.of(value);
            return Files.isDirectory(folder) ? folder : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    void lastFolder(Path folder) {
        if (folder != null) {
            write(FOLDER, clip(folder.toAbsolutePath().toString()));
        }
    }

    /** Recently opened files that still exist, most recent first. */
    List<Path> recentFiles() {
        List<Path> files = new ArrayList<>();
        String value = read(RECENT);
        if (value == null) {
            return files;
        }
        for (String entry : value.split("\n")) {
            if (entry.isBlank()) {
                continue;
            }
            try {
                Path path = Path.of(entry);
                if (Files.isRegularFile(path) && !files.contains(path)) {
                    files.add(path);
                }
            } catch (RuntimeException e) {
                // an unreadable entry is skipped
            }
        }
        return files;
    }

    void addRecent(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        List<String> entries = new ArrayList<>();
        entries.add(clip(absolute.toString()));
        for (Path existing : recentFiles()) {
            if (!existing.toAbsolutePath().normalize().equals(absolute) && entries.size() < MAX_RECENT) {
                entries.add(clip(existing.toString()));
            }
        }
        String joined = String.join("\n", entries);
        while (joined.length() > Preferences.MAX_VALUE_LENGTH && entries.size() > 1) {
            entries.remove(entries.size() - 1);
            joined = String.join("\n", entries);
        }
        write(RECENT, joined);
        lastFolder(absolute.getParent());
    }

    void clearRecent() {
        write(RECENT, "");
    }

    /** "plain" or "raw": how the log lines were last shown. */
    String logView() {
        String value = read("logView");
        return "raw".equals(value) ? "raw" : "plain";
    }

    void logView(String view) {
        write("logView", view);
    }

    /** The search site chosen last time. */
    io.causvane.explain.WebSearch.Engine searchEngine() {
        return io.causvane.explain.WebSearch.engine(read("searchEngine"));
    }

    void searchEngine(io.causvane.explain.WebSearch.Engine engine) {
        write("searchEngine", engine.name().toLowerCase(java.util.Locale.ROOT));
    }

    /** How much technical detail to show: Normal unless another level was chosen. */
    Detail detail() {
        String value = read("detail");
        for (Detail level : Detail.values()) {
            if (level.name().equalsIgnoreCase(String.valueOf(value))) {
                return level;
            }
        }
        return Detail.NORMAL;
    }

    void detail(Detail level) {
        write("detail", level.name().toLowerCase(java.util.Locale.ROOT));
    }

    /** The last window position and size, if it is still at least partly on a connected screen. */
    Rectangle windowBounds() {
        String value = read(BOUNDS);
        if (value == null) {
            return null;
        }
        try {
            String[] parts = value.split(",");
            Rectangle bounds = new Rectangle(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
            if (bounds.width < 400 || bounds.height < 300 || GraphicsEnvironment.isHeadless()) {
                return null;
            }
            Rectangle desktop = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            for (var device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
                desktop = desktop.union(device.getDefaultConfiguration().getBounds());
            }
            Rectangle visible = desktop.intersection(bounds);
            return visible.width >= 200 && visible.height >= 150 ? bounds : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    void windowBounds(Rectangle bounds) {
        write(BOUNDS, bounds.x + "," + bounds.y + "," + bounds.width + "," + bounds.height);
    }

    // ------------------------------------------------------------------ storage

    private String read(String key) {
        if (node == null) {
            return memory.get(key);
        }
        try {
            return node.get(key, null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void write(String key, String value) {
        if (node == null) {
            memory.put(key, value);
            return;
        }
        try {
            node.put(key, value);
            node.flush();
        } catch (Exception e) {
            // not remembered; the program carries on
        }
    }

    private static String clip(String text) {
        return text.length() <= MAX_PATH_CHARS ? text : text.substring(0, MAX_PATH_CHARS);
    }
}
