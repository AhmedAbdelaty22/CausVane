package io.causvane.gui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.icons.FlatSearchIcon;
import io.causvane.model.LogEvent;
import io.causvane.analysis.ErrorFilter;
import io.causvane.analysis.ErrorFilter.TextMatcher;
import io.causvane.io.ContextBuilder;
import io.causvane.io.LogSession;
import io.causvane.io.PastedLog;
import io.causvane.model.AnalysisResult;
import io.causvane.model.ErrorGroup;
import io.causvane.model.Severity;
import io.causvane.report.Formats;
import io.causvane.report.ReportWriter;
import io.causvane.report.SummaryFormatter;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Box;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JRootPane;
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
public final class MainFrame {

    /**
     * The window. It stays the same when the theme changes: only its contents are built again in the new
     * colours, so switching looks like one smooth change rather than a new window opening.
     */
    private final JFrame frame;
    /** Window listeners added by this instance, removed when a theme change hands the window on. */
    private final List<java.util.EventListener> windowHooks = new java.util.ArrayList<>();

    private static final String TITLE = "CausVane";
    private static final int TAIL_INTERVAL_MS = 1000;

    private final ErrorTableModel tableModel = new ErrorTableModel();
    private final JTable table = new JTable(tableModel);
    private final JTextField searchField = new JTextField(26);
    private static final Severity[] CHIP_LEVELS = {null, Severity.FATAL, Severity.ERROR, Severity.WARN};
    private static final String[] CHIP_NAMES = {"All", "Fatal", "Errors", "Warnings"};
    private final WelcomePanel welcome = new WelcomePanel(this::chooseFile, this::pasteLog, Settings.get().recentFiles(), this::openFile);
    private JLabel brandLabel;
    private JButton quickButton;
    private JSplitPane mainSplit;
    private final long[] chipCounts = new long[CHIP_LEVELS.length];
    /** 0 wide, 1 medium, 2 narrow; -1 until first fitted. */
    private int widthLevel = -1;
    private static final String SEARCH_TIP = "Type words or a request / trace ID; every word must appear. "
            + "Advanced: text between slashes, such as /timeout|refused/, is a regular expression.";
    private static final String[] CHIP_HINTS = {"", "the application could not go on", "something failed",
            "something looked wrong but work went on"};
    private JPanel severitySegments;
    private final JToggleButton[] severityChips = new JToggleButton[CHIP_LEVELS.length];
    private final JToggleButton tailToggle = new JToggleButton("Watch live");
    private final JSpinner contextSpinner = new JSpinner(new SpinnerNumberModel(Settings.get().contextLinesOr(8), 0, 500, 1));
    private final JButton moreButton = new JButton("More", new Glyphs.Triangle(Glyphs.Triangle.DOWN));
    private final TimelineChart timelineChart = new TimelineChart();
    private final SummaryStrip summary = new SummaryStrip(timelineChart, this::reveal);
    private final JLabel contextTitle = new JLabel("WHAT THE LOG SAYS");
    /** Plain-language overview of the whole file: the summary strip's tooltip. */
    private String overview = "";
    private final StoryView storyView = new StoryView();
    /** Right-hand side: the whole-log overview, or the selected problem. */
    private final OverviewPanel overviewPanel = new OverviewPanel(this::reveal);
    private final CardLayout rightCards = new CardLayout();
    private final JPanel right = new JPanel(rightCards);
    private boolean overviewMode = true;
    private final CardLayout logCards = new CardLayout();
    private final JPanel logArea = new JPanel(logCards);
    private final JToggleButton plainToggle = new JToggleButton("In plain words");
    private final JToggleButton rawToggle = new JToggleButton("All lines");
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
    /** Period picked on the chart, or null for the whole log. */
    private java.time.LocalDateTime rangeFrom;
    private java.time.LocalDateTime rangeTo;
    private final JButton rangeChip = new JButton();
    private List<io.causvane.explain.IncidentLinker.Incident> incidentList = List.of();
    private Map<String, io.causvane.explain.IncidentLinker.Membership> incidents = Map.of();
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

    public MainFrame() {
        this(null);
    }

    private MainFrame(JFrame reuse) {
        frame = reuse != null ? reuse : new JFrame(TITLE);
        boolean fresh = reuse == null;
        if (fresh) {
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            AppIcon.install(frame);
            if (java.awt.Desktop.isDesktopSupported()
                    && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.APP_ABOUT)) {
                // macOS: "About CausVane" in the application menu at the top of the screen
                java.awt.Desktop.getDesktop().setAboutHandler(e -> AboutDialog.showAbout(frame));
            }
        }
        // macOS: make the title bar match the theme of the content
        getRootPane().putClientProperty("apple.awt.windowAppearance",
                Theme.mode() == Theme.Mode.DARK ? "NSAppearanceNameDarkAqua" : "NSAppearanceNameAqua");
        if (fresh) {
            // fit smaller laptop screens: never open larger than the usable desktop area
            Rectangle desktop = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            frame.setSize(Math.min(1440, desktop.width), Math.min(880, desktop.height));
            frame.setMinimumSize(new Dimension(Math.min(720, desktop.width), Math.min(520, desktop.height)));
            frame.setLocationRelativeTo(null);
            Rectangle saved = Settings.get().windowBounds();
            if (saved != null) {
                frame.setBounds(saved);
            }
            frame.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosing(java.awt.event.WindowEvent e) {
                    if ((frame.getExtendedState() & JFrame.MAXIMIZED_BOTH) == 0) {
                        Settings.get().windowBounds(frame.getBounds());
                    }
                }
            });
        }
        java.awt.event.ComponentAdapter resized = new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                fitLayout(); // the layout follows the window while it is dragged larger or smaller
            }
        };
        frame.addComponentListener(resized);
        windowHooks.add(resized);

        JPanel north = new JPanel(new BorderLayout());
        north.add(buildToolbar(), BorderLayout.NORTH);
        north.add(buildMetrics(), BorderLayout.CENTER);
        summary.setVisible(false); // shown once a file is open

        JPanel content = new JPanel(new BorderLayout());
        content.add(north, BorderLayout.NORTH);
        center.add(welcome, "welcome");
        center.add(buildWorkspace(), "workspace");
        content.add(center, BorderLayout.CENTER);
        content.add(buildStatusBar(), BorderLayout.SOUTH);
        setContentPane(content);

        frame.setJMenuBar(buildMenuBar());
        installFileDrop(getRootPane());
        cards.show(center, "welcome");
        updateActions();
    }

    // ---------------------------------------------------------------- layout

    private JToolBar buildToolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        bar.setBorder(BorderFactory.createEmptyBorder(10, 12, 8, 12));

        JLabel brand = new JLabel("CausVane", new Icon() {
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
        brandLabel = brand;

        boolean mac = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
        JButton paletteButton = new JButton("Quick actions", new Glyphs.Bolt());
        paletteButton.setIconTextGap(6);
        paletteButton.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
        paletteButton.setToolTipText("Find any action or any error by typing a few letters ("
                + (mac ? "\u2318K" : "Ctrl+K") + ")");
        paletteButton.addActionListener(e -> openPalette());
        quickButton = paletteButton;

        JButton themeButton = new JButton(new Glyphs.ThemeSwitch(Theme.mode() == Theme.Mode.DARK));
        themeButton.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
        themeButton.setToolTipText(Theme.mode() == Theme.Mode.DARK ? "Switch to the light theme" : "Switch to the dark theme");
        themeButton.addActionListener(e -> switchTheme());

        JButton open = new JButton("Open");
        open.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
        open.setToolTipText("Open a log file (or drop one anywhere on the window)");
        open.addActionListener(e -> chooseFile());

        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Find a problem…");
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_SHOW_CLEAR_BUTTON, true);
        searchField.putClientProperty(FlatClientProperties.TEXT_FIELD_LEADING_ICON, new FlatSearchIcon());
        searchField.setMaximumSize(new Dimension(300, 30));
        searchField.setPreferredSize(new Dimension(300, 30));
        searchField.setMinimumSize(new Dimension(120, 30));
        searchField.setToolTipText(SEARCH_TIP);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });
        ButtonGroup levels = new ButtonGroup();
        for (int i = 0; i < CHIP_LEVELS.length; i++) {
            JToggleButton chip = new JToggleButton(CHIP_NAMES[i]);
            // one segmented control: neutral text, a small dot for the level's colour, the chosen one filled
            chip.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_BORDERLESS);
            chip.putClientProperty(FlatClientProperties.STYLE, "arc: 8; margin: 3,10,3,10; selectedBackground: "
                    + Theme.hex(Theme.CARD) + "; selectedForeground: " + Theme.hex(Theme.TEXT) + "; foreground: "
                    + Theme.hex(Theme.DIM) + "; hoverBackground: " + Theme.hex(Theme.HOVER_STRONG));
            chip.setFocusable(false);
            chip.setToolTipText(CHIP_LEVELS[i] == null ? "List problems of every severity"
                    : "List only " + CHIP_NAMES[i].toLowerCase(java.util.Locale.ROOT) + ": " + CHIP_HINTS[i]);
            if (CHIP_LEVELS[i] != null) {
                chip.setIcon(new Glyphs.Dot(Theme.severity(CHIP_LEVELS[i]),
                        CHIP_LEVELS[i] == Severity.FATAL));
                chip.setIconTextGap(6);
            }
            chip.addActionListener(e -> applyFilter());
            levels.add(chip);
            severityChips[i] = chip;
        }
        severityChips[0].setSelected(true);

        tailToggle.setToolTipText("Watch the file and analyze new lines as they are written");
        tailToggle.addActionListener(e -> toggleTail());

        contextSpinner.setToolTipText("How many log lines to read before and after each error");
        contextSpinner.setMaximumSize(new Dimension(64, 26));
        contextSpinner.setPreferredSize(new Dimension(64, 26));
        contextSpinner.addChangeListener(e -> {
            Settings.get().contextLines((Integer) contextSpinner.getValue());
            refreshContext();
        });

        JPopupMenu moreMenu = new JPopupMenu();
        JMenuItem copyItem = new JMenuItem("Copy a summary for a ticket");
        copyItem.addActionListener(e -> copySummary());
        moreMenu.add(copyItem);
        for (ReportWriter.Format format : ReportWriter.Format.values()) {
            JMenuItem item = new JMenuItem("Save a report as " + format.label() + "…");
            item.addActionListener(e -> exportReport(format));
            moreMenu.add(item);
        }
        moreMenu.addSeparator();
        JMenuItem words = new JMenuItem("Explain the words used here");
        words.addActionListener(e -> showGlossary());
        moreMenu.add(words);
        moreMenu.addSeparator();
        JMenuItem clearRecent = new JMenuItem("Clear recent files");
        clearRecent.addActionListener(e -> clearRecentFiles());
        moreMenu.add(clearRecent);
        moreMenu.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                clearRecent.setEnabled(!Settings.get().recentFiles().isEmpty());
            }
            @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) { }
            @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) { }
        });
        moreButton.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
        moreButton.setHorizontalTextPosition(SwingConstants.LEADING);
        moreButton.setIconTextGap(6);
        moreButton.setToolTipText("Copy, save a report, explain the words");
        moreButton.addActionListener(e -> moreMenu.show(moreButton, 0, moreButton.getHeight()));

        bar.add(brand);
        bar.add(open);
        bar.addSeparator();
        bar.add(searchField);
        bar.add(Box.createHorizontalStrut(4));
        bar.add(Box.createHorizontalStrut(10));
        JPanel segments = new JPanel() {
            @Override
            protected void paintComponent(java.awt.Graphics g) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Theme.mode() == Theme.Mode.DARK ? Theme.EDITOR : new java.awt.Color(0xEDEEF0));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.setColor(Theme.CARD_BORDER);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                g2.dispose();
            }
        };
        segments.setOpaque(false);
        segments.setLayout(new BoxLayout(segments, BoxLayout.X_AXIS));
        segments.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
        for (JToggleButton chip : severityChips) {
            segments.add(chip);
        }
        segments.setMaximumSize(segments.getPreferredSize());
        bar.add(segments);
        severitySegments = segments;
        rangeChip.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
        rangeChip.setToolTipText("Showing only problems from this period. Click to show the whole log again.");
        rangeChip.setVisible(false);
        rangeChip.addActionListener(e -> clearTimeRange());
        bar.add(Box.createHorizontalStrut(6));
        bar.add(rangeChip);
        bar.add(Box.createHorizontalGlue());
        bar.add(tailToggle);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(paletteButton);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(moreButton);
        bar.add(Box.createHorizontalStrut(6));
        bar.add(themeButton);
        return bar;
    }

    /** The name the open log is shown under: its file name, or "Pasted log". */
    private String shownName() {
        return result == null ? "" : PastedLog.isPasted(result.file()) ? "Pasted log" : String.valueOf(result.file().getFileName());
    }

    /** Where to offer saving reports: next to the log, or for pasted text the last folder used. */
    private Path saveFolder() {
        if (result == null) {
            return null;
        }
        if (PastedLog.isPasted(result.file())) {
            Path last = Settings.get().lastFolder();
            return last != null ? last : Path.of(System.getProperty("user.home"));
        }
        return result.file().toAbsolutePath().getParent();
    }

    private JPanel buildMetrics() {
        timelineChart.onRangeSelected(this::setTimeRange);
        return summary;
    }

    private JSplitPane buildWorkspace() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(52);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setFillsViewportHeight(true);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        table.setShowVerticalLines(false);
        TableRowSorter<ErrorTableModel> sorter = new TableRowSorter<>(tableModel);
        sorter.setSortKeys(List.of(new RowSorter.SortKey(ErrorTableModel.COUNT, SortOrder.DESCENDING)));
        table.setRowSorter(sorter);

        TableColumnModel columns = table.getColumnModel();
        columns.getColumn(ErrorTableModel.SEVERITY).setCellRenderer(new TableRenderers.SeverityBadge());
        columns.getColumn(ErrorTableModel.TYPE).setCellRenderer(new TableRenderers.IssueCell());
        columns.getColumn(ErrorTableModel.LOCATION).setCellRenderer(new TableRenderers.Text(Theme.TEXT, true, false, SwingConstants.LEADING));
        columns.getColumn(ErrorTableModel.COUNT).setCellRenderer(new TableRenderers.CountBar());
        columns.getColumn(ErrorTableModel.FIRST_SEEN).setCellRenderer(new TableRenderers.Text(Theme.DIM, false, false, SwingConstants.LEADING));
        columns.getColumn(ErrorTableModel.LAST_SEEN).setCellRenderer(new TableRenderers.Text(Theme.DIM, false, false, SwingConstants.LEADING));
        columns.getColumn(ErrorTableModel.MESSAGE).setCellRenderer(TableRenderers.Text.plain());
        // only what a reader needs to choose a problem; the rest is in the detail panel
        for (int hidden : new int[] {ErrorTableModel.MESSAGE, ErrorTableModel.FIRST_SEEN, ErrorTableModel.LAST_SEEN, ErrorTableModel.LOCATION}) {
            table.removeColumn(columns.getColumn(hidden));
        }
        columns.getColumn(0).setMinWidth(96);
        columns.getColumn(0).setMaxWidth(96);
        columns.getColumn(2).setMinWidth(78);
        columns.getColumn(2).setMaxWidth(90);
        columns.getColumn(1).setPreferredWidth(420);
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
        spotlight.onReveal(this::reveal);
        spotlight.onBack(this::showOverview);
        spotlight.onCopy(this::copySummary);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && !updatingTable) {
                onSelectionChanged();
            }
        });

        JScrollPane tableScroll = new JScrollPane(table, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        tableScroll.setBorder(BorderFactory.createEmptyBorder());
        tableScroll.getViewport().setBackground(Theme.CARD);

        JPanel listPanel = new JPanel(new BorderLayout());
        listPanel.add(tableScroll, BorderLayout.CENTER);

        contextTitle.setForeground(Theme.DIM);
        contextTitle.setFont(contextTitle.getFont().deriveFont(Font.BOLD, 11f));


        ButtonGroup views = new ButtonGroup();
        for (JToggleButton toggle : new JToggleButton[] {plainToggle, rawToggle}) {
            toggle.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_ROUND_RECT);
            toggle.setFont(toggle.getFont().deriveFont(12f));
            views.add(toggle);
        }
        plainToggle.setToolTipText("The error and what happened around it, told in plain sentences");
        rawToggle.setToolTipText("Every log line as written, each labelled with the part it plays");
        plainToggle.addActionListener(e -> showLogView("plain"));
        rawToggle.addActionListener(e -> showLogView("raw"));
        JLabel around = new JLabel("lines around");
        around.setForeground(Theme.DIM);
        around.setFont(around.getFont().deriveFont(11.5f));
        JPanel contextHeader = new JPanel();
        contextHeader.setOpaque(false);
        contextHeader.setLayout(new javax.swing.BoxLayout(contextHeader, javax.swing.BoxLayout.X_AXIS));
        contextHeader.setBorder(BorderFactory.createEmptyBorder(8, 14, 6, 12));
        contextHeader.add(contextTitle);
        contextHeader.add(Box.createHorizontalGlue());
        contextHeader.add(plainToggle);
        contextHeader.add(Box.createHorizontalStrut(4));
        contextHeader.add(rawToggle);
        contextHeader.add(Box.createHorizontalStrut(12));
        contextHeader.add(around);
        contextHeader.add(Box.createHorizontalStrut(6));
        contextHeader.add(contextSpinner);

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

        JPanel rawPanel = new JPanel(new BorderLayout());
        rawPanel.add(contextViewer, BorderLayout.CENTER);
        rawPanel.add(aboutLine, BorderLayout.SOUTH);
        logArea.add(storyView, "plain");
        logArea.add(rawPanel, "raw");

        JPanel contextPanel = new JPanel(new BorderLayout());
        contextPanel.setBackground(Theme.EDITOR);
        contextPanel.add(contextHeader, BorderLayout.NORTH);
        contextPanel.add(logArea, BorderLayout.CENTER);
        showLogView(Settings.get().logView());

        JSplitPane detail = new JSplitPane(JSplitPane.VERTICAL_SPLIT, spotlight, contextPanel);
        detail.setResizeWeight(0.42);
        detail.setDividerLocation(330);
        detail.setBorder(BorderFactory.createEmptyBorder());

        right.add(overviewPanel, "overview");
        right.add(detail, "problem");
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listPanel, right);
        split.setResizeWeight(0.42);
        split.setDividerLocation(600);
        split.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.CARD_BORDER));
        mainSplit = split;
        keepProportion(split, 0.42);
        keepProportion(detail, 0.42);
        return split;
    }

    /**
     * Keeps a split at the same proportion while the window is resized, and remembers a new proportion
     * whenever the user drags the divider, so neither side collapses when the window gets smaller.
     */
    private static void keepProportion(JSplitPane split, double initial) {
        split.putClientProperty(ViewState.RATIO, initial);
        // remember the proportion only when the user drags the divider, not when the window resizes it
        if (split.getUI() instanceof javax.swing.plaf.basic.BasicSplitPaneUI ui) {
            ui.getDivider().addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseReleased(java.awt.event.MouseEvent e) {
                    int size = split.getOrientation() == JSplitPane.HORIZONTAL_SPLIT ? split.getWidth() : split.getHeight();
                    int room = size - split.getDividerSize();
                    if (room > 0) {
                        split.putClientProperty(ViewState.RATIO,
                                Math.max(0.15, Math.min(0.85, split.getDividerLocation() / (double) room)));
                    }
                }
            });
        }
        split.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                if (split.getClientProperty(ViewState.RATIO) instanceof Double ratio) {
                    split.setDividerLocation(ratio);
                }
            }
        });
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

    /** The File, Edit, View and Help menus; they also carry the window's keyboard shortcuts. */
    private javax.swing.JMenuBar buildMenuBar() {
        return AppMenuBar.build(new AppMenuBar.Actions(
                this::chooseFile,
                this::pasteLog,
                () -> Settings.get().recentFiles(),
                this::openFile,
                this::clearRecentFiles,
                () -> tailToggle.isEnabled(),
                () -> tailToggle.isSelected(),
                () -> tailToggle.doClick(),
                () -> exportReport(ReportWriter.Format.MARKDOWN),
                () -> exportReport(ReportWriter.Format.JSON),
                this::copySummary,
                () -> {
                    searchField.requestFocusInWindow();
                    searchField.selectAll();
                },
                () -> {
                    searchField.setText("");
                    severityChips[0].doClick();
                    clearTimeRange();
                },
                this::showOverview,
                () -> {
                    for (int i = 0; i < severityChips.length; i++) {
                        if (severityChips[i].isSelected()) {
                            return i;
                        }
                    }
                    return 0;
                },
                index -> severityChips[index].doClick(),
                () -> rawToggle.isSelected() ? "raw" : "plain",
                this::showLogView,
                () -> Theme.mode() == Theme.Mode.DARK,
                this::switchTheme,
                this::openPalette,
                this::showGlossary,
                () -> AboutDialog.showShortcuts(frame),
                () -> AboutDialog.showAbout(frame),
                () -> frame.dispatchEvent(new java.awt.event.WindowEvent(frame, java.awt.event.WindowEvent.WINDOW_CLOSING)),
                () -> result != null));
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

    /** The log on screen; a pasted one is deleted when another log is opened. */
    private Path shownFile;

    /**
     * Analyses log text from the clipboard, for logs that arrive in a chat, ticket or e-mail. Opening a
     * file works as before; this is a second way in.
     */
    private void pasteLog() {
        String text;
        try {
            java.awt.datatransfer.Transferable content = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
            text = content != null && content.isDataFlavorSupported(DataFlavor.stringFlavor)
                    ? (String) content.getTransferData(DataFlavor.stringFlavor) : null;
        } catch (Exception e) {
            text = null;
        }
        if (text == null || text.isBlank()) {
            Toast.show(frame, "The clipboard has no text. Copy the log lines first, then paste.");
            return;
        }
        try {
            openFile(io.causvane.io.PastedLog.write(text));
        } catch (IOException e) {
            showError("Could not use the pasted text", e);
        }
    }

    private void chooseFile() {
        Path folder = session != null ? session.file().toAbsolutePath().getParent() : Settings.get().lastFolder();
        JFileChooser chooser = new JFileChooser(folder == null ? null : folder.toFile());
        chooser.setDialogTitle("Open Log File");
        chooser.addChoosableFileFilter(new FileNameExtensionFilter(
                "Log files (*.log, *.txt, *.json, *.out, *.gz)", "log", "txt", "json", "out", "err", "gz"));
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
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
        if (session != null) {
            session.close();
        }
        if (tailToggle.isSelected()) {
            tailToggle.setSelected(false);
            tailTimer.stop();
        }
        boolean pasted = io.causvane.io.PastedLog.isPasted(file);
        if (shownFile != null && !shownFile.equals(file)) {
            io.causvane.io.PastedLog.delete(shownFile); // a pasted log is not kept once another one is opened
        }
        shownFile = file;
        String label = pasted ? "Pasted log" : String.valueOf(file.getFileName());
        LogSession newSession = new LogSession(file);
        session = newSession;
        if (!pasted) {
            Settings.get().addRecent(file);
        }
        result = null;
        selected = null;
        tableModel.setGroups(List.of());
        spotlight.clear();
        contextViewer.showMessage("Analyzing " + label + "…");
        contextTitle.setText("THE LOG LINES, EXPLAINED");
        summary.reading(label);
        overviewMode = true;
        showCard("overview");
        overviewPanel.showMessage("Reading " + label + "…");
        summary.setVisible(true);
        storyView.showMessage("Reading " + label + "…");
        cards.show(center, "workspace");
        setTitle(TITLE + " — " + label);
        status.setText("Analyzing " + (pasted ? label : file) + "…");
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
                    status.setText("Analyzing " + label + "… " + Formats.bytes(last[0]) + " of " + Formats.bytes(last[1]));
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
                    status.setText((pasted ? label + " (" + Formats.count(loaded.totalLines()) + " lines, kept only until you open another log)" : file)
                            + "  ·  " + Formats.bytes(loaded.fileSize()) + "  ·  analyzed in " + millis + " ms");
                } catch (CancellationException e) {
                    status.setText("Cancelled.");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    cards.show(center, "welcome");
                    showError("Could not read " + label, e.getCause());
                }
            }
        };
        loader.execute();
    }

    private void toggleTail() {
        if (tailToggle.isSelected()) {
            tailTimer.start();
            status.setText("Live tail on: watching " + (session == null ? "" : session.file().getFileName()) + "…");
            Toast.show(frame, "Live tail on. New errors appear as they are written.");
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
        timelineChart.setBuckets(fresh.timeline());
        incidentList = io.causvane.explain.IncidentLinker.find(fresh.groups());
        incidents = io.causvane.explain.IncidentLinker.index(incidentList);
        tableModel.setIncidents(incidents);
        overview = describe(fresh, incidentList);
        io.causvane.explain.LogOverview.Overview whole = io.causvane.explain.LogOverview.of(fresh, incidentList);
        summary.show(fresh, incidentList, overview, whole);
        overviewPanel.show(fresh, whole, incidentList);
        if (overviewMode) {
            showCard("overview");
        }
        summary.setVisible(true);
        for (int i = 0; i < CHIP_LEVELS.length; i++) {
            Severity level = CHIP_LEVELS[i];
            long rows = level == null ? fresh.groups().size()
                    : fresh.groups().stream().filter(g -> g.severity() == level).count();
            chipCounts[i] = rows;
        }
        widthLevel = -1;
        fitLayout();
        if (severitySegments != null) {
            severitySegments.setMaximumSize(severitySegments.getPreferredSize());
            severitySegments.revalidate();
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
            // words by default; text between slashes, such as /timeout|refused/, is a regular expression
            String query = searchField.getText().strip();
            boolean regex = query.length() > 2 && query.startsWith("/") && query.endsWith("/");
            TextMatcher matcher = TextMatcher.of(regex ? query.substring(1, query.length() - 1) : query, regex);
            searchField.putClientProperty(FlatClientProperties.OUTLINE, null);
            searchField.setToolTipText(SEARCH_TIP);
            ErrorFilter filter = new ErrorFilter(matcher, severity);
            return rangeFrom == null ? filter : filter.during(rangeFrom, rangeTo);
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
        if (modelRow < 0 && !rows.isEmpty() && !overviewMode) {
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
            contextViewer.showMessage("No problems match the current filter.");
            storyView.showMessage("No problems match the current filter.");
        } else if (result.groups().isEmpty()) {
            contextViewer.showMessage("No errors or warnings found in " + result.file().getFileName() + ".");
            storyView.showMessage("No errors or warnings found in " + result.file().getFileName() + ". As far as this file shows, the application ran normally.");
        }
    }

    private void onSelectionChanged() {
        int viewRow = table.getSelectedRow();
        select(viewRow < 0 ? null : tableModel.groupAt(table.convertRowIndexToModel(viewRow)), 0);
    }

    private void select(ErrorGroup group, int occurrenceIndex) {
        selected = group;
        if (group != null) {
            overviewMode = false;
            showCard("problem");
        } else if (overviewMode) {
            showCard("overview");
        }
        if (group == null) {
            spotlight.clear();
            contextViewer.showMessage("");
            storyView.showMessage("");
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
        spotlight.show(selected, index, incidents.get(selected.id()));
        refreshContext();
    }

    private void refreshContext() {
        if (selected == null || session == null) {
            return;
        }
        int lines = (Integer) contextSpinner.getValue();
        try {
            LogEvent event = selected.samples().get(occurrence);
            List<ContextBuilder.ContextLine> context = ContextBuilder.build(session, event, lines, lines);
            contextViewer.showLines(context);
            storyView.show(io.causvane.explain.PlainStory.build(event, context));
            contextTitle.setText("WHAT THE LOG SAYS  ·  line " + Formats.count(event.lineNumber()));
        } catch (IOException e) {
            contextViewer.showMessage("Could not read log context: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- export and clipboard

    private void exportReport(ReportWriter.Format format) {
        if (result == null) {
            return;
        }
        String base = shownName().replaceFirst("\\.[^.]+$", "").replace(' ', '-');
        Path exportFolder = saveFolder();
        JFileChooser chooser = new JFileChooser(exportFolder == null ? null : exportFolder.toFile());
        chooser.setDialogTitle("Export " + format.label() + " Report");
        chooser.setSelectedFile(new File(base + "-report." + format.extension()));
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = chooser.getSelectedFile().toPath();
        if (Files.exists(target) && JOptionPane.showConfirmDialog(frame, target.getFileName() + " already exists. Replace it?",
                "Export Report", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try (Writer writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            format.writer().write(result, tableModel.groups(), writer);
            status.setText("Report exported to " + target);
            Toast.show(frame, "Report exported to " + target.getFileName());
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
        Toast.show(frame, confirmation);
    }

    /** The whole file in two or three plain sentences, for someone who has not read it. */
    private static String describe(AnalysisResult r, List<io.causvane.explain.IncidentLinker.Incident> found) {
        if (r.groups().isEmpty()) {
            return "CausVane read " + Formats.count(r.totalLines()) + " lines and found no errors or warnings. "
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
        String meaning = io.causvane.explain.ErrorExplainer.standard().explain(top).title().replace("`", "");
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
                String first = io.causvane.explain.ErrorExplainer.standard().explain(earliest).title().replace("`", "");
                text.append(" The earliest error was: ").append(Character.toLowerCase(first.charAt(0))).append(first.substring(1))
                        .append(" (").append(Formats.timestamp(earliest.firstSeen())).append(").");
            }
        }
        if (!found.isEmpty()) {
            var biggest = found.stream().max(java.util.Comparator.comparingInt(io.causvane.explain.IncidentLinker.Incident::size)).get();
            String lead = io.causvane.explain.ErrorExplainer.standard().explain(biggest.lead()).title().replace("`", "");
            text.append("\n\n").append(biggest.size()).append(" of these problems look like one incident that began at ")
                    .append(Formats.timestamp(biggest.lead().firstSeen())).append(" with: ")
                    .append(Character.toLowerCase(lead.charAt(0))).append(lead.substring(1))
                    .append(". It is marked FIRST FAILURE in the list; start there.");
            if (found.size() > 1) {
                text.append(" ").append(found.size() - 1).append(found.size() == 2 ? " smaller incident is" : " smaller incidents are")
                        .append(" marked the same way.");
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
                {"Incident", "Several problems that probably share one cause: a first failure (marked FIRST FAILURE) and the problems it set off a few minutes later (KNOCK-ON). CausVane links them by timing and kind of problem, so treat the link as a strong hint."},
                {"Warning sign", "A warning that appeared shortly before a failure and stopped when it began. Often the first visible symptom."},
                {"Live tail", "Watching a log that is still being written, so new problems appear as they happen."},
        };
        StringBuilder html = new StringBuilder("<html><body style='width: 430px; font-size: 12px;'>");
        for (String[] term : terms) {
            html.append("<p style='margin: 0 0 8px 0;'><b>").append(term[0]).append("</b><br>").append(term[1]).append("</p>");
        }
        JOptionPane.showMessageDialog(frame, new JLabel(html.append("</body></html>").toString()),
                "Words used in CausVane", JOptionPane.PLAIN_MESSAGE);
    }

    // ---------------------------------------------------------------- command palette and theme

    /** Every action of the window plus one "go to" entry per listed error. */
    private void openPalette() {
        List<CommandPalette.Command> commands = new java.util.ArrayList<>();
        boolean mac = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
        String key = mac ? "\u2318" : "Ctrl+";
        if (result != null) {
            // problems first, listed by what they mean; the technical name is still searchable
            List<ErrorGroup> problems = new java.util.ArrayList<>(tableModel.groups());
            problems.sort(java.util.Comparator.comparing((ErrorGroup g) -> {
                var member = incidents.get(g.id());
                return member != null && member.part() == io.causvane.explain.IncidentLinker.Part.LEAD ? 0 : 1;
            }));
            java.util.Map<String, Long> sameMeaning = new java.util.HashMap<>();
            for (ErrorGroup group : problems) {
                sameMeaning.merge(io.causvane.explain.ErrorExplainer.standard().explain(group).title(), 1L, Long::sum);
            }
            for (ErrorGroup group : problems) {
                String title = io.causvane.explain.ErrorExplainer.standard().explain(group).title();
                String meaning = title.replace("`", "");
                if (sameMeaning.get(title) > 1) {
                    // several problems mean the same thing: say where each one is
                    meaning += group.pinpoint() != null ? "  (" + group.location() + ")" : "  (" + group.displayType() + ")";
                }
                var member = incidents.get(group.id());
                String tag = member == null ? "" : TableRenderers.tag(member.part()).toLowerCase(java.util.Locale.ROOT) + "  ·  ";
                commands.add(new CommandPalette.Command("Problems", meaning, tag + "×" + Formats.count(group.count()),
                        group.displayType() + " " + group.errorType() + " " + group.location() + " " + group.severity(),
                        () -> reveal(group.id())));
            }
        }
        commands.add(new CommandPalette.Command("Actions", "Open a log file…", key + "O", "browse load", this::chooseFile));
        commands.add(new CommandPalette.Command("Actions", "Paste log text from the clipboard", (mac ? "\u21e7\u2318" : "Ctrl+Shift+") + "V",
                "clipboard paste text chat ticket", this::pasteLog));
        if (result != null) {
            commands.add(new CommandPalette.Command("Actions", "Show the overview of the whole log", "Esc",
                    "summary story timeline health", this::showOverview));
        }
        if (result != null) {
            commands.add(new CommandPalette.Command("Actions", "Filter the problems", key + "F", "search find", () -> {
                searchField.requestFocusInWindow();
                searchField.selectAll();
            }));
            commands.add(new CommandPalette.Command("Actions", "Show every problem again", "", "clear reset filter all", () -> {
                searchField.setText("");
                severityChips[0].doClick();
                clearTimeRange();
            }));
            commands.add(new CommandPalette.Command("Actions", "Copy a summary for a ticket", "", "clipboard share", this::copySummary));
            for (ReportWriter.Format format : ReportWriter.Format.values()) {
                commands.add(new CommandPalette.Command("Actions", "Save a report as " + format.label() + "…", "",
                        "export share file", () -> exportReport(format)));
            }
        }
        if (session != null && !session.isCompressed()) {
            commands.add(new CommandPalette.Command("Actions", tailToggle.isSelected() ? "Stop watching the file" : "Watch the file for new problems",
                    "live tail", "follow tail live", tailToggle::doClick));
        }
        commands.add(new CommandPalette.Command("Actions",
                Theme.mode() == Theme.Mode.DARK ? "Switch to the light theme" : "Switch to the dark theme", "", "appearance dark light", this::switchTheme));
        commands.add(new CommandPalette.Command("Actions", "Explain the words used here", "", "help glossary", this::showGlossary));
        if (!Settings.get().recentFiles().isEmpty()) {
            commands.add(new CommandPalette.Command("Actions", "Clear recent files", "", "forget history recent", this::clearRecentFiles));
        }
        for (Path recent : Settings.get().recentFiles()) {
            Path folder = recent.toAbsolutePath().getParent();
            commands.add(new CommandPalette.Command("Recent files", String.valueOf(recent.getFileName()),
                    folder == null ? "" : String.valueOf(folder.getFileName()), String.valueOf(folder), () -> openFile(recent)));
        }
        CommandPalette.open(frame, commands);
    }

    /**
     * Fits the window's parts to its width: the toolbar shortens its labels, the chart narrows, and in a
     * narrow window the problem list moves above the details instead of beside them.
     */
    private void fitLayout() {
        int width = getContentPane().getWidth();
        if (width <= 0 || brandLabel == null) {
            return;
        }
        summary.fitWidth(width);
        int level = width >= 1250 ? 0 : width >= 980 ? 1 : 2;
        SwingUtilities.invokeLater(() -> { // the facts wrap to the new width: measure them again once laid out
            summary.revalidate();
            summary.repaint();
        });
        if (level == widthLevel) {
            return;
        }
        widthLevel = level;
        brandLabel.setText(level == 0 ? "CausVane" : "");
        quickButton.setText(level == 0 ? "Quick actions" : "");
        moreButton.setText(level == 2 ? "" : "More");
        tailToggle.setText(level == 0 ? "Watch live" : "Live");
        for (int i = 0; i < CHIP_LEVELS.length; i++) {
            boolean counted = result != null;
            String name = level == 2 && CHIP_LEVELS[i] != null ? "" : CHIP_NAMES[i];
            severityChips[i].setText((name + (counted ? "  " + chipCounts[i] : "")).strip());
        }
        if (severitySegments != null) {
            severitySegments.setMaximumSize(severitySegments.getPreferredSize());
            severitySegments.revalidate();
        }
        int orientation = level == 2 ? JSplitPane.VERTICAL_SPLIT : JSplitPane.HORIZONTAL_SPLIT;
        if (mainSplit != null && mainSplit.getOrientation() != orientation) {
            mainSplit.setOrientation(orientation);
            SwingUtilities.invokeLater(() -> mainSplit.setDividerLocation(0.42));
        }
        getContentPane().revalidate();
        getContentPane().repaint();
    }

    /** Forgets the recently opened files; the files themselves are not touched. */
    private void clearRecentFiles() {
        Settings.get().clearRecent();
        welcome.clearRecent();
        Toast.show(frame, "Recent files cleared");
    }

    private void setTimeRange(java.time.LocalDateTime from, java.time.LocalDateTime to) {
        rangeFrom = from;
        rangeTo = to;
        boolean sameDay = from.toLocalDate().equals(to.toLocalDate());
        java.time.format.DateTimeFormatter format = java.time.format.DateTimeFormatter.ofPattern(
                sameDay ? "HH:mm:ss" : "MMM d HH:mm", java.util.Locale.ENGLISH);
        rangeChip.setText(format.format(from) + " – " + format.format(to) + "   ×");
        rangeChip.setVisible(true);
        applyFilter();
    }

    private void clearTimeRange() {
        rangeFrom = null;
        rangeTo = null;
        rangeChip.setVisible(false);
        timelineChart.clearSelection();
        applyFilter();
    }

    // ---------------------------------------------------------------- the window

    private java.awt.Container getContentPane() { return frame.getContentPane(); }
    private void setContentPane(java.awt.Container pane) { frame.setContentPane(pane); }
    private JRootPane getRootPane() { return frame.getRootPane(); }
    private void setTitle(String title) { frame.setTitle(title); }
    private String getTitle() { return frame.getTitle(); }
    private void setVisible(boolean visible) { frame.setVisible(visible); }
    private void dispose() { frame.dispose(); }
    private Rectangle getBounds() { return frame.getBounds(); }
    private void setBounds(Rectangle bounds) { frame.setBounds(bounds); }
    private void setSize(int width, int height) { frame.setSize(width, height); }

    /** Switches the right-hand panel between the overview and the selected problem. */
    private void showCard(String card) {
        rightCards.show(right, card);
    }

    /** Shows the whole-log overview on the right and clears the selection in the list. */
    private void showOverview() {
        if (result == null) {
            return;
        }
        overviewMode = true;
        updatingTable = true;
        table.clearSelection();
        updatingTable = false;
        select(null, 0);
        showCard("overview");
    }

    private void reveal(String groupId) {
        int modelRow = tableModel.indexOf(groupId);
        if (modelRow < 0 && result != null) {
            // the problem is hidden by the current filter: clear it, then select
            searchField.setText("");
            severityChips[0].setSelected(true);
            clearTimeRange();
            modelRow = tableModel.indexOf(groupId);
        }
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
    /**
     * Switches between the dark and light theme in the same window: the contents are built again in the new
     * colours, everything that was open is carried over, and a short fade hides the change.
     */
    private void switchTheme() {
        if (loader != null && !loader.isDone()) {
            Toast.show(frame, "The theme can be switched once the file has finished loading");
            return;
        }
        // everything happens inside this one event, so the window is painted once, already in the new colours
        ViewState view = ViewState.capture(getContentPane());
        boolean searching = searchField.isFocusOwner();
        int caret = searchField.getCaretPosition();
        Theme.apply(Theme.mode() == Theme.Mode.DARK ? Theme.Mode.LIGHT : Theme.Mode.DARK);
        Settings.get().theme(Theme.mode());
        retire();
        setContentPane(new JPanel());
        SwingUtilities.updateComponentTreeUI(frame); // the window's own parts in the new look
        MainFrame next = new MainFrame(frame);
        next.spotlight.technical(spotlight.technical());
        next.spotlight.incidentAll(spotlight.incidentAll());
        next.adopt(this);
        frame.validate();
        // second pass: the wrapping rows of facts measure themselves against the widths the first pass gave them
        invalidateTree(next.summary);
        frame.validate();
        view.restore(frame.getContentPane());
        Runnable focus = () -> {
            if (searching) {
                next.searchField.requestFocusInWindow();
                next.searchField.setCaretPosition(Math.min(caret, next.searchField.getText().length()));
            } else {
                next.table.requestFocusInWindow();
            }
        };
        focus.run();
        SwingUtilities.invokeLater(() -> { // after any layout still pending
            view.restore(frame.getContentPane());
            focus.run();
        });
    }

    private static void invalidateTree(Component component) {
        component.invalidate();
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                invalidateTree(child);
            }
        }
    }

    /** Stops this instance's timers and listeners once the window has been handed to a new instance. */
    private void retire() {
        tailTimer.stop();
        for (java.util.EventListener hook : windowHooks) {
            if (hook instanceof java.awt.event.ComponentListener listener) {
                frame.removeComponentListener(listener);
            }
        }
        windowHooks.clear();
    }

    private void adopt(MainFrame previous) {
        searchField.setText(previous.searchField.getText());
        contextSpinner.setValue(previous.contextSpinner.getValue());
        for (int i = 0; i < severityChips.length; i++) {
            severityChips[i].setSelected(previous.severityChips[i].isSelected());
        }
        session = previous.session;
        shownFile = previous.shownFile;
        selected = previous.selected;
        occurrence = previous.occurrence;
        overviewMode = previous.overviewMode;
        if (previous.result != null) {
            setTitle(previous.getTitle());
            cards.show(center, "workspace");
            applyResult(previous.result);
            status.setText(previous.status.getText());
            if (overviewMode) {
                showOverview();
            } else if (selected != null) {
                select(selected, occurrence); // draw the open problem again in the new colours
            }
        }
        if (previous.rangeFrom != null) {
            timelineChart.select(previous.rangeFrom, previous.rangeTo);
            setTimeRange(previous.rangeFrom, previous.rangeTo);
        }
        if (previous.tailToggle.isSelected()) {
            tailToggle.setSelected(true);
            tailTimer.start();
        }
        updateActions();
    }

    // ---------------------------------------------------------------- helpers

    /** Switches the log area between the plain story and every line, and remembers the choice. */
    private void showLogView(String view) {
        String chosen = "raw".equals(view) ? "raw" : "plain";
        logCards.show(logArea, chosen);
        (chosen.equals("raw") ? rawToggle : plainToggle).setSelected(true);
        Settings.get().logView(chosen);
    }

    private void updateActions() {
        boolean loaded = result != null;
        moreButton.setEnabled(loaded);
        boolean archive = session != null && session.isCompressed();
        boolean pasted = shownFile != null && io.causvane.io.PastedLog.isPasted(shownFile);
        tailToggle.setEnabled(session != null && !archive && !pasted);
        tailToggle.setToolTipText(pasted ? "Watching live needs a log file; pasted text does not change"
                : archive ? "Live tail is not available for a compressed (archived) log"
                : "Watch the file and analyze new lines as they are written");
    }

    private void showError(String message, Throwable cause) {
        String detail = cause == null || cause.getMessage() == null ? "" : "\n" + cause.getMessage();
        status.setText(message);
        JOptionPane.showMessageDialog(frame, message + detail, TITLE, JOptionPane.ERROR_MESSAGE);
    }
}
