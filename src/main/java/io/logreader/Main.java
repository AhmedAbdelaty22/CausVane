package io.logreader;

import io.logreader.cli.CliRunner;
import io.logreader.gui.MainFrame;

import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Entry point: launches the GUI by default, or the CLI for {@code analyze}. */
public final class Main {

    public static final String APP_NAME = "LogReader";
    public static final String VERSION = "1.0.1";

    private Main() {}

    public static void main(String[] args) {
        // must be set before any AWT class loads: the application name shown in the macOS menu bar
        System.setProperty("apple.awt.application.name", APP_NAME);
        System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua");
        if (args.length == 0) {
            launchGui(null);
            return;
        }
        switch (args[0]) {
            case "-h", "--help", "help" -> System.out.print(CliRunner.USAGE);
            case "-V", "--version" -> System.out.println("LogReader " + VERSION);
            case "analyze" -> System.exit(new CliRunner().run(Arrays.copyOfRange(args, 1, args.length)));
            case "self-test" -> {
                if (GraphicsEnvironment.isHeadless() || args.length < 2) {
                    System.err.println("LogReader: self-test needs a display and a log file: LogReader self-test <file>");
                    System.exit(2);
                }
                configureNativeAccess();
                System.exit(MainFrame.selfTest(Path.of(args[1])));
            }
            case "gui" -> launchGui(args.length > 1 ? Path.of(args[1]) : null);
            default -> {
                Path file = Path.of(args[0]);
                if (args[0].startsWith("-") || !Files.isRegularFile(file)) {
                    System.err.println("LogReader: unknown command or missing file: " + args[0]);
                    System.err.print(CliRunner.USAGE);
                    System.exit(2);
                }
                launchGui(file);
            }
        }
    }

    private static void launchGui(Path file) {
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("LogReader: no display available. Use: LogReader analyze <file>");
            System.exit(2);
        }
        configureNativeAccess();
        MainFrame.launch(file);
    }

    /**
     * FlatLaf loads a small native library for window polish. JDK 24+ prints warnings for native calls unless
     * the JVM was started with native access enabled (the packaged JAR's manifest does that). When it was
     * not, e.g. when run straight from an IDE, skip the native library instead of triggering the warnings.
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
