package io.logreader.gui;

import io.logreader.io.ContextBuilder.ContextLine;

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

    private static final List<Rule> RULES = List.of(
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

    private static final SimpleAttributeSet GUTTER = fg(new Color(0x5F646C), false);
    private static final SimpleAttributeSet PLAIN = fg(Theme.TEXT, false);
    private static final SimpleAttributeSet CONTEXT = fg(new Color(0xA9ADB5), false);
    private static final SimpleAttributeSet NOTE = fg(Theme.DIM, false);
    private static final SimpleAttributeSet PIN = new SimpleAttributeSet();
    private static final SimpleAttributeSet EVENT_BG = new SimpleAttributeSet();

    static {
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

    ContextViewer() {
        pane.setEditable(false);
        pane.setBackground(Theme.EDITOR);
        pane.setFont(Theme.mono(12.5f));
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
        pane.setText("");
        try {
            pane.getStyledDocument().insertString(0, message, NOTE);
        } catch (BadLocationException ignored) {
            // inserting at 0 into an empty document cannot fail
        }
    }

    void showLines(List<ContextLine> lines) {
        pane.setText("");
        StyledDocument doc = pane.getStyledDocument();
        int width = 0;
        for (ContextLine line : lines) {
            width = Math.max(width, Math.min(line.text().length(), 160));
        }
        int pinOffset = -1;
        try {
            for (ContextLine line : lines) {
                String text = line.text().length() > MAX_LINE_CHARS
                        ? line.text().substring(0, MAX_LINE_CHARS) + " …" : line.text();
                text = text.replace("\t", "    ");
                String number = line.lineNumber() > 0 ? String.valueOf(line.lineNumber()) : "+";
                boolean pin = line.kind() == ContextLine.Kind.PINPOINT;
                String gutter = String.format(java.util.Locale.ROOT, "%s %8s │ ", pin ? ">>>" : "   ", number);
                int start = doc.getLength();
                switch (line.kind()) {
                    case PINPOINT -> {
                        pinOffset = start;
                        String padded = text.length() < width ? text + " ".repeat(width - text.length()) : text;
                        doc.insertString(start, gutter + padded + " \n", PIN);
                    }
                    case NOTE -> {
                        doc.insertString(start, gutter, GUTTER);
                        doc.insertString(doc.getLength(), text + "\n", NOTE);
                    }
                    default -> {
                        boolean event = line.kind() == ContextLine.Kind.EVENT;
                        doc.insertString(start, gutter, GUTTER);
                        int textStart = doc.getLength();
                        doc.insertString(textStart, text + "\n", event ? PLAIN : CONTEXT);
                        highlight(doc, textStart, text);
                        if (event) {
                            doc.setCharacterAttributes(start, doc.getLength() - start, EVENT_BG, false);
                        }
                    }
                }
            }
        } catch (BadLocationException e) {
            throw new IllegalStateException(e);
        }
        scrollTo(pinOffset);
    }

    private static void highlight(StyledDocument doc, int offset, String text) {
        for (Rule rule : RULES) {
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
