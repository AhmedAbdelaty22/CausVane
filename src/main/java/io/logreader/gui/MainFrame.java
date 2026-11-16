package io.logreader.gui;

import com.formdev.flatlaf.FlatClientProperties;
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
import javax.swing.Icon;
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
    private final JToggleButton tailToggle = new JToggleButton("Live tail");
    private final JSpinner contextSpinner = new JSpinner(new SpinnerNumberModel(8, 0, 500, 1));
    private final JButton exportButton = new JButton("Export", new Glyphs.Triangle(Glyphs.Triangle.DOWN));
    private final JButton copyButton = new JButton("Copy summary");

    private final MetricCard linesCard = new MetricCard("Lines read", Theme.TEXT);
    private final MetricCard errorsCard = new MetricCard("Errors found", Theme.RED);
    private final MetricCard uniqueCard = new MetricCard("Different problems", Theme.ORANGE);
    private final MetricCard spanCard = new MetricCard("Period covered", Theme.BLUE);
    private final TimelineChart timelineChart = new TimelineChart();
    private final JLabel contextTitle = new JLabel("THE LOG LINES, EXPLAINED");
    /** Plain-language overview of the whole file, shown above the list. */
    private final javax.swing.JTextArea overview = new javax.swing.JTextArea();
    /** What the clicked log line means. */
    private final javax.swing.JTextArea aboutLine = new javax.swing.JTextArea();
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

    /** Installs the look and feel and shows the window, optionally opening a file. */
    public static void launch(Path fileToOpen) {
        System.setProperty("apple.awt.application.name", TITLE);
        SwingUtilities.invokeLater(() -> {
            Theme.apply(Theme.initialMode());
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
            if (fileToOpen != null) {
                frame.openFile(fileToOpen);
            }
        });
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
                Theme.apply(Theme.initialMode());
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
        // macOS: make the title bar match the theme of the content
        getRootPane().putClientProperty("apple.awt.windowAppearance",
                Theme.mode() == Theme.Mode.DARK ? "NSAppearanceNameDarkAqua" : "NSAppearanceNameAqua");
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
        bar.setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 12));

        JLabel brand = new JLabel("LogReader", new Icon() {
            private final java.awt.Image image = AppIcon.render(88, 0);

            @Override
            public void paintIcon(Component c, java.awt.Graphics g, int x, int y) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2.drawImage(image, x, y, 22, 22, null);
                g2.dispose();
            }

            @Override public int getIconWidth() { return 22; }
            @Override public int getIconHeight() { return 22; }
        }, SwingConstants.LEADING);
        brand.setIconTextGap(8);
        brand.setFont(brand.getFont().deriveFont(Font.BOLD, 15f));
        brand.setForeground(Theme.TEXT);
        brand.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 16));

        boolean mac = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
        JButton paletteButton = new JButton(mac ? "\u2318K" : "Ctrl K");
        paletteButton.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
        paletteButton.setToolTipText("Command palette: search every action and every error");
        paletteButton.addActionListener(e -> openPalette());

        JButton themeButton = new JButton(new Glyphs.ThemeSwitch(Theme.mode() == Theme.Mode.DARK));
        themeButton.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
        themeButton.setToolTipText(Theme.mode() == Theme.Mode.DARK ? "Switch to the light theme" : "Switch to the dark theme");
        themeButton.addActionListener(e -> switchTheme());

        JButton open = new JButton("Open log file");
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

        contextSpinner.setToolTipText("How many log lines to show before and after each error");
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
        bar.add(new JLabel("Lines around  "));
        bar.add(contextSpinner);
        bar.add(Box.createHorizontalGlue());
        bar.add(exportButton);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(copyButton);
        bar.add(Box.createHorizontalStrut(10));
        JButton helpButton = new JButton("?");
        helpButton.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
        helpButton.setToolTipText("What the words in this window mean");
        helpButton.addActionListener(e -> showGlossary());
        bar.add(helpButton);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(paletteButton);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(themeButton);
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
        table.setRowHeight(50);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setFillsViewportHeight(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF); // keep File:Line readable; scroll sideways for the message
        table.setShowVerticalLines(false);
        TableRowSorter<ErrorTableModel> sorter = new TableRowSorter<>(tableModel);
        sorter.setSortKeys(List.of(new RowSorter.SortKey(ErrorTableModel.COUNT, SortOrder.DESCENDING)));
        table.setRowSorter(sorter);

        TableColumnModel columns = table.getColumnModel();
        columns.getColumn(ErrorTableModel.SEVERITY).setCellRenderer(new TableRenderers.SeverityBadge());
        columns.getColumn(ErrorTableModel.TYPE).setCellRenderer(new TableRenderers.IssueCell());
        columns.getColumn(ErrorTableModel.LOCATION).setCellRenderer(new TableRenderers.Text(Theme.ORANGE, true, false, SwingConstants.LEADING));
        columns.getColumn(ErrorTableModel.COUNT).setCellRenderer(new TableRenderers.CountBar());
        columns.getColumn(ErrorTableModel.FIRST_SEEN).setCellRenderer(new TableRenderers.Text(Theme.DIM, false, false, SwingConstants.LEADING));
        columns.getColumn(ErrorTableModel.LAST_SEEN).setCellRenderer(new TableRenderers.Text(Theme.DIM, false, false, SwingConstants.LEADING));
        columns.getColumn(ErrorTableModel.MESSAGE).setCellRenderer(TableRenderers.Text.plain());
        int[] widths = {92, 318, 196, 78, 172, 172, 520};
        for (int i = 0; i < widths.length; i++) {
            columns.getColumn(i).setPreferredWidth(widths[i]);
        }
        // highlight the row under the pointer
        java.awt.event.MouseAdapter hover = new java.awt.event.MouseAdapter() {
            private void set(Integer row) {
                Object previous = table.getClientProperty(TableRenderers.HOVER_ROW);
                if (!java.util.Objects.equals(previous, row)) {
                    table.putClientProperty(TableRenderers.HOVER_ROW, row);
                    table.repaint();
                }
            }

            @Override public void mouseMoved(java.awt.event.MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                set(row < 0 ? null : row);
            }

            @Override public void mouseExited(java.awt.event.MouseEvent e) { set(null); }
        };
        table.addMouseMotionListener(hover);
        table.addMouseListener(hover);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !updatingTable) {
                onSelectionChanged();
            }
        });

        JScrollPane tableScroll = new JScrollPane(table);
        tableScroll.setBorder(BorderFactory.createEmptyBorder());
        tableScroll.getViewport().setBackground(Theme.CARD);

        overview.setEditable(false);
        overview.setLineWrap(true);
        overview.setWrapStyleWord(true);
        overview.setOpaque(true);
        overview.setBackground(Theme.CALLOUT);
        overview.setForeground(Theme.TEXT);
        overview.setFont(overview.getFont().deriveFont(13f));
        overview.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.CARD_BORDER),
                BorderFactory.createEmptyBorder(12, 16, 12, 16)));
        JPanel listPanel = new JPanel(new BorderLayout());
        listPanel.add(overview, BorderLayout.NORTH);
        listPanel.add(tableScroll, BorderLayout.CENTER);

        contextTitle.setForeground(Theme.DIM);
        contextTitle.setFont(contextTitle.getFont().deriveFont(Font.BOLD, 11f));
        contextTitle.setBorder(BorderFactory.createEmptyBorder(8, 14, 2, 14));
        contextTitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        // legend: what the labels beside the lines mean
        JPanel legend = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        legend.setOpaque(false);
        legend.setBorder(BorderFactory.createEmptyBorder(2, 8, 6, 8));
        legend.setAlignmentX(Component.LEFT_ALIGNMENT);
        legend.add(new Pill("STARTS HERE", Theme.RED, false));
        legend.add(new Pill("ROOT CAUSE", Theme.RED, false));
        legend.add(new Pill("CAUSED BY", Theme.ORANGE, false));
        legend.add(new Pill("YOUR CODE", Theme.GREEN, false));
        legend.add(new Pill("LIBRARY", Theme.DIM, false));
        legend.add(new Pill("BEFORE / AFTER", Theme.DIM, false));
        legend.setToolTipText("The label beside each line says what part it plays in the error");
        JPanel contextHeader = new JPanel();
        contextHeader.setOpaque(false);
        contextHeader.setLayout(new javax.swing.BoxLayout(contextHeader, javax.swing.BoxLayout.Y_AXIS));
        contextHeader.add(contextTitle);
        contextHeader.add(legend);

        aboutLine.setEditable(false);
        aboutLine.setLineWrap(true);
        aboutLine.setWrapStyleWord(true);
        aboutLine.setRows(3);
        aboutLine.setBackground(Theme.CALLOUT);
        aboutLine.setForeground(Theme.TEXT);
        aboutLine.setFont(aboutLine.getFont().deriveFont(13f));
        aboutLine.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.CARD_BORDER),
                BorderFactory.createEmptyBorder(9, 16, 9, 16)));
        aboutLine.setText("Click any line above to see what it means.");
        contextViewer.onLineSelected(line -> aboutLine.setText(
                (line.lineNumber() > 0 ? "Line " + Formats.count(line.lineNumber()) : "Decoded line")
                        + (line.role().tag().isEmpty() ? "" : "  ·  " + line.role().tag())
                        + "\n" + line.explanation().replace("`", "")));

        JPanel contextPanel = new JPanel(new BorderLayout());
        contextPanel.setBackground(Theme.EDITOR);
        contextPanel.add(contextHeader, BorderLayout.NORTH);
        contextPanel.add(contextViewer, BorderLayout.CENTER);
        contextPanel.add(aboutLine, BorderLayout.SOUTH);

        JSplitPane detail = new JSplitPane(JSplitPane.VERTICAL_SPLIT, spotlight, contextPanel);
        detail.setResizeWeight(0.45);
        detail.setDividerLocation(330);
        detail.setBorder(BorderFactory.createEmptyBorder());

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listPanel, detail);
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
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(KeyEvent.VK_K, menuMask), "palette");
        root.getActionMap().put("palette", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { openPalette(); }
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
        contextTitle.setText("THE LOG LINES, EXPLAINED");
        overview.setText("Reading " + file.getFileName() + "…");
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
            Toast.show(this, "Live tail on. New errors appear as they are written.");
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
        overview.setText(describe(fresh));
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
            contextTitle.setText("THE LOG LINES, EXPLAINED  ·  lines " + Formats.count(Math.max(1, event.lineNumber() - lines)) + "–"
                    + Formats.count(event.lastLineNumber() + lines) + "  ·  click a line for what it means");
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
            Toast.show(this, "Report exported to " + target.getFileName());
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
        String confirmation = selected != null
                ? "Copied summary of " + selected.displayType() + " to the clipboard"
                : "Copied analysis overview to the clipboard";
        status.setText(confirmation + ".");
        Toast.show(this, confirmation);
    }

    /** The whole file in two or three plain sentences, for someone who has not read it. */
    private static String describe(AnalysisResult r) {
        if (r.groups().isEmpty()) {
            return "LogReader read " + Formats.count(r.totalLines()) + " lines and found no errors or warnings. "
                    + "As far as this file shows, the application was running normally.";
        }
        StringBuilder text = new StringBuilder("This log");
        if (r.timeSpan() != null) {
            text.append(" covers ").append(Formats.duration(r.timeSpan()));
        } else {
            text.append(" has ").append(Formats.count(r.totalLines())).append(" lines");
        }
        long problems = r.groups().size();
        text.append(". It contains ").append(Formats.count(r.errorCount())).append(r.errorCount() == 1 ? " error" : " errors")
                .append(" and ").append(Formats.count(r.warningCount())).append(r.warningCount() == 1 ? " warning" : " warnings")
                .append(", which come down to ").append(problems).append(problems == 1 ? " problem" : " different problems")
                .append(", listed below with the most frequent first.");
        ErrorGroup top = r.groups().get(0);
        String meaning = io.logreader.explain.ErrorExplainer.standard().explain(top).title().replace("`", "");
        text.append("\n\nThe most frequent: ").append(Character.toLowerCase(meaning.charAt(0))).append(meaning.substring(1))
                .append(" (").append(Formats.count(top.count())).append(top.count() == 1 ? " time" : " times");
        if (top.firstSeen() != null) {
            text.append(", first at ").append(Formats.timestamp(top.firstSeen()));
        }
        text.append("). Select a row to see what it means, where it started and the log lines behind it.");
        if (r.groups().stream().anyMatch(g -> g.firstSeen() != null)) {
            ErrorGroup earliest = r.groups().stream().filter(g -> g.firstSeen() != null && g.severity().isAtLeast(Severity.ERROR))
                    .min(java.util.Comparator.comparing(ErrorGroup::firstSeen)).orElse(null);
            if (earliest != null && earliest != top) {
                String first = io.logreader.explain.ErrorExplainer.standard().explain(earliest).title().replace("`", "");
                text.append(" The earliest error was: ").append(Character.toLowerCase(first.charAt(0))).append(first.substring(1))
                        .append(" (").append(Formats.timestamp(earliest.firstSeen())).append(").");
            }
        }
        return text.toString();
    }

    /** The handful of terms a newcomer meets in this window, in plain words. */
    private void showGlossary() {
        String[][] terms = {
                {"Log", "A file in which an application writes down what it is doing, one entry after another, as it runs."},
                {"Error / warning", "An error means something failed. A warning means something looked wrong but the application carried on."},
                {"Stack trace", "The list of code locations that were active at the moment of a failure. It is the block of indented lines under an error."},
                {"Your code / library", "Each location is either the application's own code or code of a library it uses. Faults are nearly always in the application's own code or in what it was given."},
                {"Chain of errors", "Errors are often wrapped: a low-level failure causes a higher-level one, which causes the one finally reported."},
                {"Root cause", "The innermost error of the chain, the one that set off the others."},
                {"Starts here", "The first line of the application's own code in the root cause. The place to look first."},
                {"Occurrence", "One time the problem happened. The same problem at the same place is shown as one row with a count."},
                {"Trace ID", "An identifier attached to every log entry of one request. Searching for it shows everything that request did."},
                {"Live tail", "Watching a log that is still being written, so new problems appear as they happen."},
        };
        StringBuilder html = new StringBuilder("<html><body style='width: 430px; font-size: 12px;'>");
        for (String[] term : terms) {
            html.append("<p style='margin: 0 0 8px 0;'><b>").append(term[0]).append("</b><br>").append(term[1]).append("</p>");
        }
        JOptionPane.showMessageDialog(this, new JLabel(html.append("</body></html>").toString()),
                "Words used in LogReader", JOptionPane.PLAIN_MESSAGE);
    }

    // ---------------------------------------------------------------- command palette and theme

    /** Every action of the window plus one "go to" entry per listed error. */
    private void openPalette() {
        List<CommandPalette.Command> commands = new java.util.ArrayList<>();
        boolean mac = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
        String key = mac ? "\u2318" : "Ctrl+";
        commands.add(new CommandPalette.Command("Open log file…", key + "O", this::chooseFile));
        commands.add(new CommandPalette.Command("Filter errors", key + "F", () -> {
            searchField.requestFocusInWindow();
            searchField.selectAll();
        }));
        commands.add(new CommandPalette.Command("Explain the words used here", "Help", this::showGlossary));
        commands.add(new CommandPalette.Command(
                Theme.mode() == Theme.Mode.DARK ? "Switch to light theme" : "Switch to dark theme", "Appearance", this::switchTheme));
        if (session != null) {
            commands.add(new CommandPalette.Command(tailToggle.isSelected() ? "Turn live tail off" : "Turn live tail on",
                    "Live", tailToggle::doClick));
        }
        if (result != null) {
            commands.add(new CommandPalette.Command("Copy summary to clipboard", "Share", this::copySummary));
            for (ReportWriter.Format format : ReportWriter.Format.values()) {
                commands.add(new CommandPalette.Command("Export report as " + format.label() + "…", "Share",
                        () -> exportReport(format)));
            }
            for (int i = 0; i < CHIP_LEVELS.length; i++) {
                JToggleButton chip = severityChips[i];
                commands.add(new CommandPalette.Command(
                        CHIP_LEVELS[i] == null ? "Show all severities" : "Show only " + CHIP_NAMES[i].toLowerCase(java.util.Locale.ROOT) + " entries",
                        "Filter", chip::doClick));
            }
            commands.add(new CommandPalette.Command("Clear filter", "Filter", () -> {
                searchField.setText("");
                severityChips[0].doClick();
            }));
            for (ErrorGroup group : tableModel.groups()) {
                commands.add(new CommandPalette.Command(
                        "Go to  " + group.displayType() + (group.pinpoint() != null ? "  ·  " + group.location() : ""),
                        "×" + Formats.count(group.count()), () -> reveal(group.id())));
            }
        }
        CommandPalette.open(this, commands);
    }

    private void reveal(String groupId) {
        int modelRow = tableModel.indexOf(groupId);
        if (modelRow >= 0) {
            int viewRow = table.convertRowIndexToView(modelRow);
            table.getSelectionModel().setSelectionInterval(viewRow, viewRow);
            table.scrollRectToVisible(table.getCellRect(viewRow, 0, true));
        }
    }

    /**
     * Switches between the dark and the light theme. Colors are read when components are built, so the
     * window is rebuilt and the open file, filter and selection are carried over.
     */
    private void switchTheme() {
        if (loader != null && !loader.isDone()) {
            Toast.show(this, "The theme can be switched once the file has finished loading");
            return;
        }
        Theme.apply(Theme.mode() == Theme.Mode.DARK ? Theme.Mode.LIGHT : Theme.Mode.DARK);
        MainFrame next = new MainFrame();
        next.setBounds(getBounds());
        next.setExtendedState(getExtendedState());
        next.adopt(this);
        tailTimer.stop();
        next.setVisible(true);
        dispose();
    }

    private void adopt(MainFrame previous) {
        searchField.setText(previous.searchField.getText());
        regexToggle.setSelected(previous.regexToggle.isSelected());
        contextSpinner.setValue(previous.contextSpinner.getValue());
        for (int i = 0; i < severityChips.length; i++) {
            severityChips[i].setSelected(previous.severityChips[i].isSelected());
        }
        session = previous.session;
        selected = previous.selected;
        occurrence = previous.occurrence;
        if (previous.result != null) {
            setTitle(previous.getTitle());
            cards.show(center, "workspace");
            applyResult(previous.result);
            status.setText(previous.status.getText());
        }
        if (previous.tailToggle.isSelected()) {
            tailToggle.setSelected(true);
            tailTimer.start();
        }
        updateActions();
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
