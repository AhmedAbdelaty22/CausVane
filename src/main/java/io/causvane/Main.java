package io.causvane;

import io.causvane.gui.MainFrame;

import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Entry point: CausVane is a desktop application and always opens its window. When the system starts it
 * with a log file (for example "Open With → CausVane"), the window opens on that file.
 */
public final class Main {

    public static final String APP_NAME = "CausVane";
    public static final String VERSION = "3.0.0";
    /** Where CausVane is published: releases, documentation and problem reports. */
    public static final String REPOSITORY = "https://github.com/AhmedAbdelaty22/CausVane";

    private Main() {}

    public static void main(String[] args) {
        // must be set before any AWT class loads: the application name shown in the macOS menu bar
        System.setProperty("apple.awt.application.name", APP_NAME);
        System.setProperty("apple.awt.application.appearance", "system");
        System.setProperty("apple.laf.useScreenMenuBar", "true"); // macOS: the menus go in the bar at the top of the screen
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("CausVane is a desktop application and needs a screen to open its window.");
            System.exit(2);
        }
        Path file = null;
        if (args.length > 0 && !args[0].startsWith("-")) {
            Path candidate = Path.of(args[0]);
            if (Files.isRegularFile(candidate)) {
                file = candidate; // opened with a log file: show it straight away
            }
        }
        configureNativeAccess();
        MainFrame.launch(file);
    }

    /**
     * FlatLaf loads a small native library for window polish. JDK 24+ prints warnings for native calls unless
     * the JVM was started with native access enabled (the packaged app does that). When it was not, e.g.
     * when run straight from an IDE, skip the native library instead of triggering the warnings.
     */
    private static void configureNativeAccess() {
        try {
            Object enabled = Module.class.getMethod("isNativeAccessEnabled").invoke(Main.class.getModule());
            if (Boolean.FALSE.equals(enabled)) {
                System.setProperty("flatlaf.useNativeLibrary", "false");
            }
        } catch (ReflectiveOperationException e) {
            // JDK 21: the check does not exist and native calls are not restricted
        }
    }
}
