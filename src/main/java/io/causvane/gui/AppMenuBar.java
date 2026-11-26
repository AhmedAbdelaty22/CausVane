package io.causvane.gui;

import io.causvane.Main;

import javax.swing.ButtonGroup;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.KeyStroke;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The menu bar: File, Edit, View and Help, the way desktop applications usually arrange them. Every item
 * runs an action the window already has, so the menus, the toolbar and Quick actions always agree. On
 * macOS it becomes the system menu bar at the top of the screen, and About and Quit live in the
 * application menu there.
 */
final class AppMenuBar {

    /** What the menus need from the window. */
    record Actions(
            Runnable open,
            Runnable paste,
            Supplier<List<Path>> recentFiles,
            Consumer<Path> openRecent,
            Runnable clearRecent,
            BooleanSupplier canWatch,
            BooleanSupplier watching,
            Runnable toggleWatch,
            Runnable saveMarkdown,
            Runnable saveJson,
            Runnable copyForTicket,
            Runnable find,
            Runnable showEverything,
            Runnable overview,
            IntSupplier severity,
            IntConsumer showSeverity,
            Supplier<String> logView,
            Consumer<String> showLogView,
            Supplier<Detail> detail,
            Consumer<Detail> showDetail,
            BooleanSupplier dark,
            Runnable switchTheme,
            Runnable quickActions,
            Runnable meeting,
            Runnable meetingPdf,
            Runnable glossary,
            Runnable searchWeb,
            BooleanSupplier problemSelected,
            Runnable shortcuts,
            Runnable about,
            Runnable quit,
            BooleanSupplier fileOpen) {}

    static final String[] SEVERITIES = {"All Problems", "Fatal Only", "Errors Only", "Warnings Only"};

    private AppMenuBar() {}

    static boolean mac() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
    }

    static JMenuBar build(Actions a) {
        int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        JMenuBar bar = new JMenuBar();

        // ---------------------------------------------------------------- File
        JMenu file = menu("File", KeyEvent.VK_F);
        file.add(item("Open…", KeyStroke.getKeyStroke(KeyEvent.VK_O, menu), a.open()));
        file.add(item("Paste Log Text", KeyStroke.getKeyStroke(KeyEvent.VK_V, menu | InputEvent.SHIFT_DOWN_MASK), a.paste()));
        JMenu recent = menu("Open Recent", KeyEvent.VK_R);
        file.add(recent);
        file.addSeparator();
        JCheckBoxMenuItem watch = new JCheckBoxMenuItem("Watch Live");
        watch.setMnemonic(KeyEvent.VK_W);
        watch.addActionListener(e -> a.toggleWatch().run());
        file.add(watch);
        file.addSeparator();
        JMenuItem saveMd = item("Save Report as Markdown…", KeyStroke.getKeyStroke(KeyEvent.VK_S, menu), a.saveMarkdown());
        JMenuItem saveJson = item("Save Report as JSON…", KeyStroke.getKeyStroke(KeyEvent.VK_S, menu | InputEvent.SHIFT_DOWN_MASK), a.saveJson());
        file.add(saveMd);
        file.add(saveJson);
        if (!mac()) {
            file.addSeparator();
            file.add(item("Exit", KeyStroke.getKeyStroke(KeyEvent.VK_Q, menu), a.quit()));
        }
        onOpen(file, () -> {
            boolean loaded = a.fileOpen().getAsBoolean();
            watch.setEnabled(a.canWatch().getAsBoolean());
            watch.setSelected(a.watching().getAsBoolean());
            saveMd.setEnabled(loaded);
            saveJson.setEnabled(loaded);
            recent.removeAll();
            List<Path> files = a.recentFiles().get();
            for (Path path : files) {
                JMenuItem entry = new JMenuItem(String.valueOf(path.getFileName()));
                entry.putClientProperty(Theme.HTML_DISABLE, Boolean.TRUE);
                entry.setToolTipText(Theme.literal(path.toAbsolutePath().toString()));
                entry.addActionListener(e -> a.openRecent().accept(path));
                recent.add(entry);
            }
            if (files.isEmpty()) {
                JMenuItem none = new JMenuItem("No recent files");
                none.setEnabled(false);
                recent.add(none);
            } else {
                recent.addSeparator();
                recent.add(item("Clear Recent Files", null, a.clearRecent()));
            }
        });
        bar.add(file);

        // ---------------------------------------------------------------- Edit
        JMenu edit = menu("Edit", KeyEvent.VK_E);
        JMenuItem copy = item("Copy for a Ticket", KeyStroke.getKeyStroke(KeyEvent.VK_C, menu | InputEvent.SHIFT_DOWN_MASK), a.copyForTicket());
        JMenuItem find = item("Find a Problem", KeyStroke.getKeyStroke(KeyEvent.VK_F, menu), a.find());
        JMenuItem everything = item("Show Every Problem Again", null, a.showEverything());
        edit.add(copy);
        edit.addSeparator();
        edit.add(find);
        edit.add(everything);
        onOpen(edit, () -> {
            boolean loaded = a.fileOpen().getAsBoolean();
            copy.setEnabled(loaded);
            find.setEnabled(loaded);
            everything.setEnabled(loaded);
        });
        bar.add(edit);

        // ---------------------------------------------------------------- View
        JMenu view = menu("View", KeyEvent.VK_V);
        JMenuItem overview = item("Overview of the Whole Log", KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), a.overview());
        view.add(overview);
        view.addSeparator();
        ButtonGroup severityGroup = new ButtonGroup();
        JRadioButtonMenuItem[] severities = new JRadioButtonMenuItem[SEVERITIES.length];
        for (int i = 0; i < SEVERITIES.length; i++) {
            int index = i;
            severities[i] = new JRadioButtonMenuItem(SEVERITIES[i]);
            severities[i].setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_1 + i, menu));
            severities[i].addActionListener(e -> a.showSeverity().accept(index));
            severityGroup.add(severities[i]);
            view.add(severities[i]);
        }
        view.addSeparator();
        ButtonGroup logGroup = new ButtonGroup();
        JRadioButtonMenuItem plain = new JRadioButtonMenuItem("Log in Plain Words");
        JRadioButtonMenuItem raw = new JRadioButtonMenuItem("Log as Written (All Lines)");
        plain.addActionListener(e -> a.showLogView().accept("plain"));
        raw.addActionListener(e -> a.showLogView().accept("raw"));
        logGroup.add(plain);
        logGroup.add(raw);
        view.add(plain);
        view.add(raw);
        view.addSeparator();
        JMenu detailMenu = menu("Detail", KeyEvent.VK_D);
        ButtonGroup detailGroup = new ButtonGroup();
        JRadioButtonMenuItem[] details = new JRadioButtonMenuItem[Detail.values().length];
        for (Detail level : Detail.values()) {
            JRadioButtonMenuItem entry = new JRadioButtonMenuItem(level.label());
            entry.setToolTipText(level.hint());
            entry.addActionListener(e -> a.showDetail().accept(level));
            detailGroup.add(entry);
            detailMenu.add(entry);
            details[level.ordinal()] = entry;
        }
        view.add(detailMenu);
        view.addSeparator();
        JMenuItem theme = item("", KeyStroke.getKeyStroke(KeyEvent.VK_T, menu | InputEvent.SHIFT_DOWN_MASK), a.switchTheme());
        view.add(theme);
        view.add(item("Quick Actions…", KeyStroke.getKeyStroke(KeyEvent.VK_K, menu), a.quickActions()));
        view.addSeparator();
        JMenuItem meeting = item("Meeting Mode", KeyStroke.getKeyStroke(KeyEvent.VK_M, menu | InputEvent.SHIFT_DOWN_MASK), a.meeting());
        JMenuItem meetingPdf = item("Save Meeting Slides as PDF…", null, a.meetingPdf());
        view.add(meeting);
        view.add(meetingPdf);
        onOpen(view, () -> {
            boolean loaded = a.fileOpen().getAsBoolean();
            overview.setEnabled(loaded);
            meeting.setEnabled(loaded);
            meetingPdf.setEnabled(loaded);
            int chosen = a.severity().getAsInt();
            for (int i = 0; i < severities.length; i++) {
                severities[i].setEnabled(loaded);
                severities[i].setSelected(i == chosen);
            }
            ("raw".equals(a.logView().get()) ? raw : plain).setSelected(true);
            details[a.detail().get().ordinal()].setSelected(true);
            theme.setText(a.dark().getAsBoolean() ? "Switch to Light Theme" : "Switch to Dark Theme");
        });
        theme.setText(a.dark().getAsBoolean() ? "Switch to Light Theme" : "Switch to Dark Theme");
        bar.add(view);

        // ---------------------------------------------------------------- Help
        JMenu help = menu("Help", KeyEvent.VK_H);
        help.add(item("Explain the Words Used Here", null, a.glossary()));
        JMenuItem searchWeb = item("Search the Web for This Problem…", null, a.searchWeb());
        help.add(searchWeb);
        onOpen(help, () -> searchWeb.setEnabled(a.problemSelected().getAsBoolean()));
        help.add(item("Keyboard Shortcuts", KeyStroke.getKeyStroke(KeyEvent.VK_SLASH, menu), a.shortcuts()));
        help.addSeparator();
        help.add(item("Documentation", null, () -> browse(Main.REPOSITORY + "/blob/main/docs/CausVane-Documentation.pdf")));
        help.add(item("What's New in This Version", null, () -> browse(Main.REPOSITORY + "/releases/tag/v" + Main.VERSION)));
        help.add(item("Check for Updates", null, () -> browse(Main.REPOSITORY + "/releases/latest")));
        help.add(item("Report a Problem", null, () -> browse(Main.REPOSITORY + "/issues/new")));
        if (!mac()) {
            help.addSeparator();
            help.add(item("About CausVane", null, a.about()));
        }
        bar.add(help);
        return bar;
    }

    private static JMenu menu(String text, int mnemonic) {
        JMenu menu = new JMenu(text);
        if (!mac()) {
            menu.setMnemonic(mnemonic); // Alt+letter on Windows and Linux
        }
        return menu;
    }

    private static JMenuItem item(String text, KeyStroke key, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        if (key != null) {
            item.setAccelerator(key);
        }
        item.addActionListener(e -> action.run());
        return item;
    }

    /** Refreshes a menu's items (enabled, ticked, recent files) each time it is opened. */
    private static void onOpen(JMenu menu, Runnable refresh) {
        menu.addMenuListener(new MenuListener() {
            @Override public void menuSelected(MenuEvent e) { refresh.run(); }
            @Override public void menuDeselected(MenuEvent e) { }
            @Override public void menuCanceled(MenuEvent e) { }
        });
    }

    /** Opens a web page in the system's browser. */
    static void browse(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(java.net.URI.create(url));
            }
        } catch (java.io.IOException | RuntimeException e) {
            // no browser available: nothing else to do
        }
    }
}
