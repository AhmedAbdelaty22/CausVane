package io.easylog.gui;

import io.easylog.io.ContextBuilder.ContextLine;

import javax.swing.BorderFactory;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Log Context Viewer: the lines around an error with line numbers and syntax highlighting; the exact
 * failing line gets a bright red background and a {@code >>>} marker.
 */
final class ContextViewer extends JScrollPane {

    private static final int MAX_LINE_CHARS = 2000;

    private record Rule(Pattern pattern, SimpleAttributeSet style) {}

    private final List<Rule> rules = List.of(
            new Rule(Pattern.compile("\\d{4}[-/]\\d{2}[-/]\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?(?:Z|[+-]\\d{2}:?\\d{2})?"
                    + "|\\b\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d+)?\\b"), fg(Theme.DIM, false)),
            new Rule(Pattern.compile("\"(?:[^\"\\\\]|\\\\.){0,300}\"|'(?:[^'\\\\]|\\\\.){0,300}'"), fg(Theme.GREEN, false)),
            new Rule(Pattern.compile("^\\s*(?:at|File|from|in)\\b|Caused by:|Traceback \\(most recent call last\\):|--->"
                    + "|\\[cause\\]:|goroutine \\d+"), fg(Theme.PURPLE, false)),
            new Rule(Pattern.compile("\\b(?:INFO|INF|DEBUG|DBG|TRACE|NOTICE|info|debug)\\b"), fg(Theme.BLUE, false)),
            new Rule(Pattern.compile("\\b(?:WARN|WARNING|WRN|warn|warning)\\b"), fg(Theme.YELLOW, true)),
            new Rule(Pattern.compile("\\b(?:ERROR|ERR|SEVERE|FATAL|FTL|CRITICAL|CRIT|PANIC|error|fatal|fail|panic)\\b"), fg(Theme.RED, true)),
            new Rule(Pattern.compile("(?<![\\w.$])(?:[A-Za-z_$][\\w$]*(?:\\.|::|\\\\))*[A-Za-z_$][\\w$]*(?:Exception|Error|Throwable)\\b"),
                    fg(Theme.RED, true)),
            new Rule(Pattern.compile("[\\w$@~+./\\\\-]+\\.[A-Za-z]{1,6}(?::line \\d+|:\\d+(?::\\d+)?|\", line \\d+|\\(\\d+\\))"),
                    fg(Theme.ORANGE, false)));

    private final SimpleAttributeSet GUTTER = fg(Theme.GUTTER, false);
    private final SimpleAttributeSet PLAIN = fg(Theme.TEXT, false);
    private final SimpleAttributeSet CONTEXT = fg(Theme.CONTEXT_TEXT, false);
    private final SimpleAttributeSet NOTE = fg(Theme.DIM, false);
    private final SimpleAttributeSet PIN = new SimpleAttributeSet();
    private final SimpleAttributeSet EVENT_BG = new SimpleAttributeSet();

    {
        StyleConstants.setItalic(NOTE, true);
        StyleConstants.setForeground(PIN, Color.WHITE);
        StyleConstants.setBackground(PIN, Theme.PIN_BACKGROUND);
        StyleConstants.setBold(PIN, true);
        StyleConstants.setBackground(EVENT_BG, Theme.EVENT_BACKGROUND);
    }

    private final JTextPane pane = new JTextPane() {
        @Override
        public boolean getScrollableTracksViewportWidth() {
            // no soft wrapping: log lines stay on one row and scroll horizontally
            return getUI().getPreferredSize(this).width <= getParent().getSize().width;
        }

        @Override
        public Dimension getPreferredSize() {
            return getUI().getPreferredSize(this);
        }
    };

    private java.util.function.Consumer<ContextLine> listener;
    private List<ContextLine> shown = List.of();
    private int[] lineStarts = new int[0];

    ContextViewer() {
        pane.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                lineClicked(pane.viewToModel2D(e.getPoint()));
            }
        });
        pane.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        pane.setToolTipText("Click a line to see what it means");
        pane.setEditable(false);
        pane.setBackground(Theme.EDITOR);
        pane.setFont(Theme.mono(12.5f));
        pane.setForeground(Theme.TEXT);
        pane.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        setViewportView(pane);
        setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.CARD_BORDER));
        getVerticalScrollBar().setUnitIncrement(18);
        getHorizontalScrollBar().setUnitIncrement(18);
    }

    JTextPane textPane() {
        return pane;
    }

    void showMessage(String message) {
        shown = List.of();
        pane.setText("");
        try {
            pane.getStyledDocument().insertString(0, message, NOTE);
        } catch (BadLocationException ignored) {
            // inserting at 0 into an empty document cannot fail
        }
    }

    /** Called with the line the user clicked (or the failing line when a new error is shown). */
    void onLineSelected(java.util.function.Consumer<ContextLine> listener) {
        this.listener = listener;
    }

    void showLines(List<ContextLine> lines) {
        shown = List.of();
        pane.setText("");
        StyledDocument doc = pane.getStyledDocument();
        int width = 0;
        for (ContextLine line : lines) {
            width = Math.max(width, Math.min(line.text().length(), 160));
        }
        int pinOffset = -1;
        ContextLine focus = null;
        int[] starts = new int[lines.size()];
        try {
            for (int i = 0; i < lines.size(); i++) {
                ContextLine line = lines.get(i);
                String text = line.text().length() > MAX_LINE_CHARS
                        ? line.text().substring(0, MAX_LINE_CHARS) + " …" : line.text();
                text = text.replace("\t", "    ");
                String number = line.lineNumber() > 0 ? String.valueOf(line.lineNumber()) : "+";
                boolean pin = line.kind() == ContextLine.Kind.PINPOINT;
                String gutter = String.format(java.util.Locale.ROOT, "%s %7s │ ", pin ? ">>>" : "   ", number);
                String tag = String.format(java.util.Locale.ROOT, "%-11s │ ", line.role().tag());
                int start = doc.getLength();
                starts[i] = start;
                switch (line.kind()) {
                    case PINPOINT -> {
                        pinOffset = start;
                        focus = line;
                        String padded = text.length() < width ? text + " ".repeat(width - text.length()) : text;
                        doc.insertString(start, gutter + tag + padded + " \n", PIN);
                    }
                    case NOTE -> {
                        doc.insertString(start, gutter + tag, GUTTER);
                        doc.insertString(doc.getLength(), text + "\n", NOTE);
                    }
                    default -> {
                        boolean event = line.kind() == ContextLine.Kind.EVENT;
                        doc.insertString(start, gutter, GUTTER);
                        doc.insertString(doc.getLength(), tag, tagStyle(line.role()));
                        int textStart = doc.getLength();
                        doc.insertString(textStart, text + "\n", event ? PLAIN : CONTEXT);
                        highlight(doc, textStart, text);
                        if (event) {
                            doc.setCharacterAttributes(start, doc.getLength() - start, EVENT_BG, false);
                            if (focus == null) {
                                focus = line;
                            }
                        }
                    }
                }
            }
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
        shown = List.copyOf(lines);
        lineStarts = starts;
        scrollTo(pinOffset);
        if (listener != null && focus != null) {
            listener.accept(focus);
        }
    }

    /** Colour of the role label beside each line. */
    private SimpleAttributeSet tagStyle(io.easylog.explain.LineExplainer.Role role) {
        return switch (role) {
            case LOGGED -> fg(Theme.BLUE, true);
            case ERROR_TEXT, ROOT_CAUSE -> fg(Theme.RED, true);
            case CAUSE -> fg(Theme.ORANGE, true);
            case YOUR_CODE -> fg(Theme.GREEN, true);
            case STARTS_HERE -> fg(Theme.RED, true);
            default -> fg(Theme.GUTTER, false);
        };
    }

    private void lineClicked(int offset) {
        if (listener == null || shown.isEmpty()) {
            return;
        }
        int index = java.util.Arrays.binarySearch(lineStarts, offset);
        if (index < 0) {
            index = -index - 2;
        }
        if (index >= 0 && index < shown.size()) {
            listener.accept(shown.get(index));
        }
    }

    private void highlight(StyledDocument doc, int offset, String text) {
        for (Rule rule : rules) {
            Matcher m = rule.pattern().matcher(text);
            while (m.find()) {
                doc.setCharacterAttributes(offset + m.start(), m.end() - m.start(), rule.style(), false);
            }
        }
    }

    /** Scrolls so the pinpointed line sits about a third of the way down the viewport. */
    private void scrollTo(int offset) {
        pane.setCaretPosition(Math.max(offset, 0));
        if (offset < 0) {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                Rectangle2D r = pane.modelToView2D(offset);
                if (r != null) {
                    int viewHeight = getViewport().getExtentSize().height;
                    int y = Math.max(0, (int) r.getY() - viewHeight / 3);
                    pane.scrollRectToVisible(new Rectangle(0, y, 1, viewHeight));
                }
            } catch (BadLocationException ignored) {
                // document changed before the scroll ran; nothing to scroll to
            }
        });
    }

    private static SimpleAttributeSet fg(Color color, boolean bold) {
        SimpleAttributeSet set = new SimpleAttributeSet();
        StyleConstants.setForeground(set, color);
        StyleConstants.setBold(set, bold);
        return set;
    }
}
