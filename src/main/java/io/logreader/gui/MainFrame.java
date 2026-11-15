package io.logreader.gui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.icons.FlatSearchIcon;
import io.logreader.model.LogEvent;
import io.logreader.analysis.ErrorFilter;
import io.logreader.analysis.ErrorFilter.TextMatcher;
import io.logreader.io.ContextBuilder;
import io.logreader.io.LogSession;
import io.logreader.model.AnalysisResult;
import io.logreader.model.ErrorGroup;
import io.logreader.model.Severity;
import io.logreader.report.Formats;
import io.logreader.report.ReportWriter;
import io.logreader.report.SummaryFormatter;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.TableColumnModel;
import javax.swing.table.TableRowSorter;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Font;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetAdapter;
import java.awt.dnd.DropTargetDropEvent;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.regex.PatternSyntaxException;

/** The support engineer workspace: toolbar, metric cards, error navigator, spotlight and context viewer. */
public final class MainFrame extends JFrame {

    private static final String TITLE = "LogReader";
    private static final int TAIL_INTERVAL_MS = 1000;

    private final ErrorTableModel tableModel = new ErrorTableModel();
    private final JTable table = new JTable(tableModel);
    private final JTextField searchField = new JTextField(26);
    private final JToggleButton regexToggle = new JToggleButton(".*");
    private static final Severity[] CHIP_LEVELS = {null, Severity.FATAL, Severity.ERROR, Severity.WARN};
    private static final String[] CHIP_NAMES = {"All", "Fatal", "Error", "Warn"};
    private final JToggleButton[] severityChips = new JToggleButton[CHIP_LEVELS.length];
    private final JToggleButton tailToggle = new JToggleButton("Live Tail (-f)");
    private final JSpinner contextSpinner = new JSpinner(new SpinnerNumberModel(8, 0, 500, 1));
    private final JButton exportButton = new JButton("Export Report", new Glyphs.Triangle(Glyphs.Triangle.DOWN));
    private final JButton copyButton = new JButton("Copy Error Summary");

    private final MetricCard linesCard = new MetricCard("Total lines scanned", Theme.TEXT);
    private final MetricCard errorsCard = new MetricCard("Total errors", Theme.RED);
    private final MetricCard uniqueCard = new MetricCard("Unique error types", Theme.ORANGE);
    private final MetricCard spanCard = new MetricCard("Time span", Theme.BLUE);
    private final TimelineChart timelineChart = new TimelineChart();
    private final JLabel contextTitle = new JLabel("LOG CONTEXT");
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);

    private final SpotlightPanel spotlight = new SpotlightPanel(this::showOccurrence);
    private final ContextViewer contextViewer = new ContextViewer();
    private final JLabel status = new JLabel("Open a log file or drop one anywhere on this window.");
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final Timer tailTimer = new Timer(TAIL_INTERVAL_MS, e -> pollTail());

    private LogSession session;
    private AnalysisResult result;
    private SwingWorker<AnalysisResult, long[]> loader;
    private volatile boolean polling;
    private ErrorGroup selected;
    private int occurrence;
    /** Guards against selection events fired while the table is being repopulated. */
    private boolean updatingTable;

    /** Installs the dark look and feel and shows the window, optionally opening a file. */
    public static void launch(Path fileToOpen) {
        System.setProperty("apple.awt.application.name", TITLE);
        System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua");
        SwingUtilities.invokeLater(() -> {
            installLookAndFeel();
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
            if (fileToOpen != null) {
                frame.openFile(fileToOpen);
            }
        });
    }

    private static void installLookAndFeel() {
        FlatLaf.setGlobalExtraDefaults(Map.of("@background", "#222327", "@accentColor", "#4C8DF6"));
        FlatDarkLaf.setup();
        UIManager.put("Component.arc", 8);
        UIManager.put("Button.arc", 8);
        UIManager.put("TextComponent.arc", 8);
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("Table.showHorizontalLines", true);
        UIManager.put("Table.selectionBackground", new java.awt.Color(0x2E4366));
        UIManager.put("Table.selectionInactiveBackground", new java.awt.Color(0x2E4366));
        UIManager.put("TableHeader.height", 30);
        UIManager.put("SplitPaneDivider.gripDotCount", 0);
        UIManager.put("ToolTip.background", new java.awt.Color(0x33353A));
    }

    /**
     * Start-up check for a new machine or operating system: opens the real window, loads the file, and
     * verifies that errors were found, one is selected and the window actually painted.
     *
     * @return 0 when everything worked, 1 otherwise (details are printed)
     */
    public static int selfTest(Path file) {
        String platform = System.getProperty("os.name") + " " + System.getProperty("os.arch")
                + ", Java " + System.getProperty("java.version");
        try {
            MainFrame[] holder = new MainFrame[1];
            SwingUtilities.invokeAndWait(() -> {
                installLookAndFeel();
                holder[0] = new MainFrame();
                holder[0].setVisible(true);
                holder[0].openFile(file);
            });
            MainFrame frame = holder[0];
            boolean[] loaded = new boolean[1];
            for (int i = 0; i < 150 && !loaded[0]; i++) {
                Thread.sleep(200);
                SwingUtilities.invokeAndWait(() -> loaded[0] = frame.result != null);
            }
            String[] failure = new String[1];
            int[] rows = new int[1];
            SwingUtilities.invokeAndWait(() -> {
                rows[0] = frame.tableModel.getRowCount();
                if (!loaded[0]) {
                    failure[0] = "the file was not analyzed within 30 seconds";
                } else if (rows[0] == 0) {
                    failure[0] = "no errors were listed for " + file;
                } else if (frame.selected == null) {
                    failure[0] = "no error was selected after loading";
                } else {
                    Container content = frame.getContentPane();
                    java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                            Math.max(1, content.getWidth()), Math.max(1, content.getHeight()),
                            java.awt.image.BufferedImage.TYPE_INT_RGB);
                    java.awt.Graphics2D g = image.createGraphics();
                    content.paint(g);
                    g.dispose();
                    java.util.Set<Integer> colors = new java.util.HashSet<>();
                    for (int x = 0; x < image.getWidth(); x += 7) {
                        for (int y = 0; y < image.getHeight(); y += 7) {
                            colors.add(image.getRGB(x, y));
                        }
                    }
                    if (colors.size() < 20) {
                        failure[0] = "the window painted only " + colors.size() + " distinct colors";
                    }
                }
                frame.dispose();
            });
            if (failure[0] != null) {
                System.err.println("LogReader self-test FAILED on " + platform + ": " + failure[0]);
                return 1;
            }
            System.out.println("LogReader self-test passed on " + platform + ": window opened, "
                    + rows[0] + " errors listed, details and context rendered.");
            return 0;
        } catch (Exception e) {
            System.err.println("LogReader self-test FAILED on " + platform + ": " + e);
            return 1;
        }
    }

    public MainFrame() {
        super(TITLE);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        AppIcon.install(this);
        // fit smaller laptop screens: never open larger than the usable desktop area
        Rectangle desktop = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(1440, desktop.width), Math.min(880, desktop.height));
        setMinimumSize(new Dimension(Math.min(980, desktop.width), Math.min(620, desktop.height)));
        setLocationRelativeTo(null);

        JPanel north = new JPanel(new BorderLayout());
        north.add(buildToolbar(), BorderLayout.NORTH);
        north.add(buildMetrics(), BorderLayout.CENTER);

        JPanel content = new JPanel(new BorderLayout());
        content.add(north, BorderLayout.NORTH);
        center.add(new WelcomePanel(this::chooseFile), "welcome");
        center.add(buildWorkspace(), "workspace");
        content.add(center, BorderLayout.CENTER);
        content.add(buildStatusBar(), BorderLayout.SOUTH);
        setContentPane(content);

        installShortcuts();
        installFileDrop(getRootPane());
        cards.show(center, "welcome");
        updateActions();
    }

    // ---------------------------------------------------------------- layout

    private JToolBar buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(BorderFactory.createEmptyBorder(8, 10, 6, 10));

        JLabel brand = new JLabel("LogReader");
        brand.setFont(brand.getFont().deriveFont(Font.BOLD, 16f));
        brand.setForeground(Theme.ORANGE);
        brand.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 14));

        JButton open = new JButton("Open Log File");
        open.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
        open.setToolTipText("Open a log file (or drag and drop one anywhere on the window)");
        open.addActionListener(e -> chooseFile());

        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Filter: keyword, trace ID or regex…");
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, new FlatSearchIcon());
        searchField.setMaximumSize(new Dimension(420, 30));
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });
        regexToggle.setToolTipText("Treat the filter as a regular expression");
        regexToggle.setFont(Theme.mono(12f));
        regexToggle.addActionListener(e -> applyFilter());
        ButtonGroup levels = new ButtonGroup();
        for (int i = 0; i < CHIP_LEVELS.length; i++) {
            JToggleButton chip = new JToggleButton(CHIP_NAMES[i]);
            chip.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
            chip.setToolTipText(CHIP_LEVELS[i] == null ? "Show every severity" : "Show only " + CHIP_LEVELS[i] + " entries");
            if (CHIP_LEVELS[i] != null) {
                chip.setForeground(Theme.severity(CHIP_LEVELS[i]));
            }
            chip.addActionListener(e -> applyFilter());
            levels.add(chip);
            severityChips[i] = chip;
        }
        severityChips[0].setSelected(true);

        tailToggle.setToolTipText("Watch the file and analyze new lines as they are written");
        tailToggle.addActionListener(e -> toggleTail());

        contextSpinner.setToolTipText("Log lines shown before and after the error");
        contextSpinner.setMaximumSize(new Dimension(70, 30));
        contextSpinner.addChangeListener(e -> refreshContext());

        JPopupMenu exportMenu = new JPopupMenu();
        for (ReportWriter.Format format : ReportWriter.Format.values()) {
            JMenuItem item = new JMenuItem("Export as " + format.label() + "…");
            item.addActionListener(e -> exportReport(format));
            exportMenu.add(item);
        }
        exportButton.setHorizontalTextPosition(SwingConstants.LEADING);
        exportButton.setIconTextGap(8);
        exportButton.setToolTipText("Export the filtered errors as a Markdown or JSON report");
        exportButton.addActionListener(e -> exportMenu.show(exportButton, 0, exportButton.getHeight()));
        copyButton.setToolTipText("Copy a ticket-ready summary of the selected error to the clipboard");
        copyButton.addActionListener(e -> copySummary());

        bar.add(brand);
        bar.add(open);
        bar.addSeparator();
        bar.add(searchField);
        bar.add(Box.createHorizontalStrut(4));
        bar.add(regexToggle);
        bar.add(Box.createHorizontalStrut(6));
        for (JToggleButton chip : severityChips) {
            bar.add(chip);
            bar.add(Box.createHorizontalStrut(4));
        }
        bar.addSeparator();
        bar.add(tailToggle);
        bar.addSeparator();
        bar.add(new JLabel("Context ± "));
        bar.add(contextSpinner);
        bar.add(Box.createHorizontalGlue());
        bar.add(exportButton);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(copyButton);
        return bar;
    }

    private JPanel buildMetrics() {
        JPanel cardRow = new JPanel(new GridLayout(1, 4, 10, 0));
        cardRow.add(linesCard);
        cardRow.add(errorsCard);
        cardRow.add(uniqueCard);
        cardRow.add(spanCard);
        timelineChart.setPreferredSize(new Dimension(420, 84));
        JPanel panel = new JPanel(new BorderLayout(10, 0));
        panel.setBorder(BorderFactory.createEmptyBorder(2, 10, 10, 10));
        panel.add(cardRow, BorderLayout.CENTER);
        panel.add(timelineChart, BorderLayout.EAST);
        return panel;
    }

    private JSplitPane buildWorkspace() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(34);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setFillsViewportHeight(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF); // keep File:Line readable; scroll sideways for the message
        table.setShowVerticalLines(false);
        TableRowSorter<ErrorTableModel> sorter = new TableRowSorter<>(tableModel);
        sorter.setSortKeys(List.of(new RowSorter.SortKey(ErrorTableModel.COUNT, SortOrder.DESCENDING)));
        table.setRowSorter(sorter);

        TableColumnModel columns = table.getColumnModel();
        columns.getColumn(ErrorTableModel.SEVERITY).setCellRenderer(new TableRenderers.SeverityBadge());
        columns.getColumn(ErrorTableModel.TYPE).setCellRenderer(new TableRenderers.Text(null, false, true, SwingConstants.LEADING));
        columns.getColumn(ErrorTableModel.LOCATION).setCellRenderer(new TableRenderers.Text(Theme.ORANGE, true, false, SwingConstants.LEADING));
        columns.getColumn(ErrorTableModel.COUNT).setCellRenderer(new TableRenderers.CountBar());
        columns.getColumn(ErrorTableModel.MEANING).setCellRenderer(TableRenderers.Text.plain());
        columns.getColumn(ErrorTableModel.FIRST_SEEN).setCellRenderer(new TableRenderers.Text(Theme.DIM, false, false, SwingConstants.LEADING));
        columns.getColumn(ErrorTableModel.LAST_SEEN).setCellRenderer(new TableRenderers.Text(Theme.DIM, false, false, SwingConstants.LEADING));
        columns.getColumn(ErrorTableModel.MESSAGE).setCellRenderer(TableRenderers.Text.plain());
        int[] widths = {80, 180, 330, 190, 70, 168, 168, 520};
        for (int i = 0; i < widths.length; i++) {
            columns.getColumn(i).setPreferredWidth(widths[i]);
        }
        // show location and count before the longer explanation text
        table.moveColumn(ErrorTableModel.MEANING, ErrorTableModel.COUNT);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !updatingTable) {
                onSelectionChanged();
            }
        });

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createEmptyBorder());

        contextTitle.setForeground(Theme.DIM);
        contextTitle.setFont(contextTitle.getFont().deriveFont(Font.BOLD, 11f));
        contextTitle.setBorder(BorderFactory.createEmptyBorder(7, 14, 6, 14));
        JPanel contextPanel = new JPanel(new BorderLayout());
        contextPanel.setBackground(Theme.EDITOR);
        contextPanel.add(contextTitle, BorderLayout.NORTH);
        contextPanel.add(contextViewer, BorderLayout.CENTER);

        JSplitPane detail = new JSplitPane(JSplitPane.VERTICAL_SPLIT, spotlight, contextPanel);
        detail.setResizeWeight(0.55);
        detail.setDividerLocation(400);
        detail.setBorder(BorderFactory.createEmptyBorder());

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tableScroll, detail);
        split.setResizeWeight(0.45);
        split.setDividerLocation(700);
        split.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.CARD_BORDER));
        return split;
    }

    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new BorderLayout(10, 0));
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.CARD_BORDER),
                BorderFactory.createEmptyBorder(4, 12, 4, 12)));
        status.setForeground(Theme.DIM);
        status.putClientProperty(Theme.HTML_DISABLE, Boolean.TRUE); // shows file names and error text
        progress.setPreferredSize(new Dimension(220, 6));
        progress.setVisible(false);
        bar.add(status, BorderLayout.CENTER);
        bar.add(progress, BorderLayout.EAST);
        return bar;
    }

    private void installShortcuts() {
        int menuMask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        JComponent root = getRootPane();
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_O, menuMask), "open");
        root.getActionMap().put("open", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { chooseFile(); }
        });
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_F, menuMask), "find");
        root.getActionMap().put("find", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                searchField.requestFocusInWindow();
                searchField.selectAll();
            }
        });
    }

    /** Accepts file drops on every component of the window. */
    private void installFileDrop(Component component) {
        new DropTarget(component, DnDConstants.ACTION_COPY, new DropTargetAdapter() {
            @Override
            public void drop(DropTargetDropEvent event) {
                if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    event.rejectDrop();
                    return;
                }
                event.acceptDrop(DnDConstants.ACTION_COPY);
                try {
                    List<?> files = (List<?>) event.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    event.dropComplete(true);
                    if (!files.isEmpty() && files.get(0) instanceof File file) {
                        SwingUtilities.invokeLater(() -> openFile(file.toPath()));
                    }
                } catch (Exception e) {
                    event.dropComplete(false);
                    showError("Could not read the dropped file", e);
                }
            }
        }, true);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                installFileDrop(child);
            }
        }
    }

    // ---------------------------------------------------------------- loading and tailing

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser(session == null ? null : session.file().getParent().toFile());
        chooser.setDialogTitle("Open Log File");
        chooser.addChoosableFileFilter(new FileNameExtensionFilter("Log files (*.log, *.txt, *.json, *.out)", "log", "txt", "json", "out", "err"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            openFile(chooser.getSelectedFile().toPath());
        }
    }

    /** Parses the file on a background worker; the window stays responsive whatever the file size. */
    public void openFile(Path file) {
        if (!Files.isRegularFile(file)) {
            showError("Not a readable file: " + file, null);
            return;
        }
        if (loader != null) {
            loader.cancel(true);
        }
        LogSession newSession = new LogSession(file);
        session = newSession;
        result = null;
        selected = null;
        tableModel.setGroups(List.of());
        spotlight.clear();
        contextViewer.showMessage("Analyzing " + file.getFileName() + "…");
        contextTitle.setText("LOG CONTEXT");
        cards.show(center, "workspace");
        setTitle(TITLE + " — " + file.getFileName());
        status.setText("Analyzing " + file + "…");
        progress.setValue(0);
        progress.setVisible(true);
        updateActions();

        long started = System.nanoTime();
        loader = new SwingWorker<>() {
            @Override
            protected AnalysisResult doInBackground() throws IOException {
                return newSession.load((read, total) -> publish(new long[] {read, total}));
            }

            @Override
            protected void process(List<long[]> chunks) {
                long[] last = chunks.get(chunks.size() - 1);
                if (session == newSession && last[1] > 0) {
                    progress.setValue((int) (last[0] * 1000 / last[1]));
                    status.setText("Analyzing " + file.getFileName() + "… " + Formats.bytes(last[0]) + " of " + Formats.bytes(last[1]));
                }
            }

            @Override
            protected void done() {
                if (session != newSession) {
                    return; // superseded by another file
                }
                progress.setVisible(false);
                try {
                    AnalysisResult loaded = get();
                    long millis = (System.nanoTime() - started) / 1_000_000;
                    applyResult(loaded);
                    status.setText(file + "  ·  " + Formats.bytes(loaded.fileSize()) + "  ·  analyzed in " + millis + " ms");
                } catch (CancellationException e) {
                    status.setText("Cancelled.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    cards.show(center, "welcome");
                    showError("Could not read " + file.getFileName(), e.getCause());
                }
            }
        };
        loader.execute();
    }

    private void toggleTail() {
        if (tailToggle.isSelected()) {
            tailTimer.start();
            status.setText("Live tail on: watching " + (session == null ? "" : session.file().getFileName()) + "…");
        } else {
            tailTimer.stop();
            status.setText("Live tail off.");
        }
    }

    /** One live-tail step, run on a virtual thread so file I/O never touches the event thread. */
    private void pollTail() {
        LogSession current = session;
        if (current == null || polling || (loader != null && !loader.isDone())) {
            return;
        }
        polling = true;
        Thread.ofVirtual().name("log-tail").start(() -> {
            try {
                Optional<AnalysisResult> update = current.poll();
                update.ifPresent(fresh -> SwingUtilities.invokeLater(() -> {
                    if (session == current) {
                        applyResult(fresh);
                        status.setText("Live tail: " + Formats.count(fresh.totalLines()) + " lines, updated "
                                + Formats.timestamp(java.time.LocalDateTime.now()));
                    }
                }));
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> {
                    tailToggle.setSelected(false);
                    tailTimer.stop();
                    showError("Live tail stopped", e);
                });
            } finally {
                polling = false;
            }
        });
    }

    // ---------------------------------------------------------------- results, filtering, selection

    private void applyResult(AnalysisResult fresh) {
        result = fresh;
        linesCard.set(Formats.count(fresh.totalLines()), Formats.count(fresh.totalEvents()) + " log events · " + Formats.bytes(fresh.fileSize()));
        errorsCard.set(Formats.count(fresh.errorCount()), Formats.count(fresh.warningCount()) + " warnings");
        uniqueCard.set(Formats.count(fresh.uniqueErrorTypes()), fresh.unlistedOccurrences() > 0
                ? "listing limit reached: " + Formats.count(fresh.unlistedOccurrences()) + " occurrences not listed"
                : fresh.groups().size() + " deduplicated rows incl. warnings");
        spanCard.set(Formats.duration(fresh.timeSpan()),
                fresh.firstTimestamp() == null ? "no timestamps detected"
                        : Formats.timestamp(fresh.firstTimestamp()) + " → " + Formats.timestamp(fresh.lastTimestamp()));
        timelineChart.setBuckets(fresh.timeline());
        for (int i = 0; i < CHIP_LEVELS.length; i++) {
            Severity level = CHIP_LEVELS[i];
            long rows = level == null ? fresh.groups().size()
                    : fresh.groups().stream().filter(g -> g.severity() == level).count();
            severityChips[i].setText(CHIP_NAMES[i] + "  " + rows);
        }
        applyFilter();
        updateActions();
    }

    private ErrorFilter currentFilter() {
        Severity severity = null;
        for (int i = 0; i < CHIP_LEVELS.length; i++) {
            if (severityChips[i].isSelected()) {
                severity = CHIP_LEVELS[i];
            }
        }
        try {
            TextMatcher matcher = TextMatcher.of(searchField.getText(), regexToggle.isSelected());
            searchField.putClientProperty(FlatClientProperties.OUTLINE, null);
            searchField.setToolTipText(null);
            return new ErrorFilter(matcher, severity);
        } catch (PatternSyntaxException e) {
            searchField.putClientProperty(FlatClientProperties.OUTLINE, FlatClientProperties.OUTLINE_ERROR);
            searchField.setToolTipText("Invalid regular expression: " + e.getDescription());
            return null;
        }
    }

    private void applyFilter() {
        if (result == null) {
            return;
        }
        ErrorFilter filter = currentFilter();
        if (filter == null) {
            return; // keep the current rows while the regex is being typed
        }
        String keepId = selected == null ? null : selected.id();
        List<ErrorGroup> rows = result.groups().stream().filter(filter).toList();

        updatingTable = true;
        tableModel.setGroups(rows);
        int modelRow = keepId == null ? -1 : tableModel.indexOf(keepId);
        if (modelRow < 0 && !rows.isEmpty()) {
            modelRow = table.convertRowIndexToModel(0);
        }
        if (modelRow >= 0) {
            int viewRow = table.convertRowIndexToView(modelRow);
            table.getSelectionModel().setSelectionInterval(viewRow, viewRow);
        }
        updatingTable = false;

        ErrorGroup now = modelRow >= 0 ? tableModel.groupAt(modelRow) : null;
        boolean sameError = now != null && selected != null && now.id().equals(selected.id());
        boolean unchanged = sameError && now.count() == selected.count();
        if (!unchanged) {
            select(now, sameError ? Math.min(occurrence, now.samples().size() - 1) : 0);
        }
        if (rows.isEmpty() && !result.groups().isEmpty()) {
            contextViewer.showMessage("No errors match the current filter.");
        } else if (result.groups().isEmpty()) {
            contextViewer.showMessage("No errors or warnings found in " + result.file().getFileName() + ".");
        }
    }

    private void onSelectionChanged() {
        int viewRow = table.getSelectedRow();
        select(viewRow < 0 ? null : tableModel.groupAt(table.convertRowIndexToModel(viewRow)), 0);
    }

    private void select(ErrorGroup group, int occurrenceIndex) {
        selected = group;
        if (group == null) {
            spotlight.clear();
            contextViewer.showMessage("");
        } else {
            showOccurrence(occurrenceIndex);
        }
        updateActions();
    }

    private void showOccurrence(int index) {
        if (selected == null || index < 0 || index >= selected.samples().size()) {
            return;
        }
        occurrence = index;
        spotlight.show(selected, index);
        refreshContext();
    }

    private void refreshContext() {
        if (selected == null || session == null) {
            return;
        }
        int lines = (Integer) contextSpinner.getValue();
        try {
            LogEvent event = selected.samples().get(occurrence);
            contextViewer.showLines(ContextBuilder.build(session, event, lines, lines));
            contextTitle.setText("LOG CONTEXT  ·  lines " + Formats.count(Math.max(1, event.lineNumber() - lines)) + "–"
                    + Formats.count(event.lastLineNumber() + lines) + "  ·  the failing line is marked >>>");
        } catch (IOException e) {
            contextViewer.showMessage("Could not read log context: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- export and clipboard

    private void exportReport(ReportWriter.Format format) {
        if (result == null) {
            return;
        }
        String base = result.file().getFileName().toString().replaceFirst("\\.[^.]+$", "");
        JFileChooser chooser = new JFileChooser(result.file().getParent().toFile());
        chooser.setDialogTitle("Export " + format.label() + " Report");
        chooser.setSelectedFile(new File(base + "-report." + format.extension()));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = chooser.getSelectedFile().toPath();
        if (Files.exists(target) && JOptionPane.showConfirmDialog(this, target.getFileName() + " already exists. Replace it?",
                "Export Report", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try (Writer writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            format.writer().write(result, tableModel.groups(), writer);
            status.setText("Report exported to " + target);
        } catch (IOException e) {
            showError("Could not export the report", e);
        }
    }

    private void copySummary() {
        if (result == null) {
            return;
        }
        String text = selected != null
                ? SummaryFormatter.errorSummary(result, selected)
                : SummaryFormatter.overview(result, tableModel.groups(), 10);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
        status.setText(selected != null
                ? "Copied summary of " + selected.displayType() + " to the clipboard."
                : "Copied analysis overview to the clipboard.");
    }

    // ---------------------------------------------------------------- helpers

    private void updateActions() {
        boolean loaded = result != null;
        exportButton.setEnabled(loaded);
        copyButton.setEnabled(loaded);
        tailToggle.setEnabled(session != null);
    }

    private void showError(String message, Throwable cause) {
        String detail = cause == null || cause.getMessage() == null ? "" : "\n" + cause.getMessage();
        status.setText(message);
        JOptionPane.showMessageDialog(this, message + detail, TITLE, JOptionPane.ERROR_MESSAGE);
    }
}
