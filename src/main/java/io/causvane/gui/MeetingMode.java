package io.causvane.gui;

import io.causvane.explain.Briefing;
import io.causvane.report.ImagePdf;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Meeting mode: the log as six full-screen slides with large text, for showing on a shared screen. The
 * arrow keys move between slides and Esc leaves; the slides follow the theme and the detail level, and can
 * be saved as a PDF to send round afterwards.
 */
final class MeetingMode {

    private static final Color[] ACCENTS = new Color[6];

    private final JFrame owner;
    private final List<Briefing.Slide> slides;
    private final String source;
    private final Path folder;
    private final String baseName;
    private JDialog window;
    private int index;
    /** Where the buttons along the bottom were last drawn, for clicks. */
    private final Rectangle previousButton = new Rectangle();
    private final Rectangle nextButton = new Rectangle();
    private final Rectangle saveButton = new Rectangle();
    private final Rectangle closeButton = new Rectangle();

    private MeetingMode(JFrame owner, List<Briefing.Slide> slides, String source, Path folder) {
        this.owner = owner;
        this.slides = slides;
        this.source = source;
        this.folder = folder;
        this.baseName = source.replaceFirst("\\.[^.]+$", "").replaceAll("[\\\\/:*?\"<>|]", "-");
    }

    static Briefing.Level level() {
        return switch (Detail.level()) {
            case SIMPLE -> Briefing.Level.SIMPLE;
            case NORMAL -> Briefing.Level.NORMAL;
            case EXPERT -> Briefing.Level.EXPERT;
        };
    }

    /** Opens the slides over the whole screen the window is on. */
    static void show(JFrame owner, List<Briefing.Slide> slides, String source, Path folder) {
        new MeetingMode(owner, slides, source, folder).open();
    }

    /** Asks where to save, then writes the slides as a PDF. */
    static void savePdf(JFrame owner, List<Briefing.Slide> slides, String source, Path folder) {
        new MeetingMode(owner, slides, source, folder).save(owner);
    }

    private void open() {
        accents();
        window = new JDialog(owner);
        window.setUndecorated(true);
        window.setTitle("Meeting mode");
        JComponent view = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                MeetingMode.this.paint(g2, getWidth(), getHeight(), true);
                g2.dispose();
            }
        };
        view.setFocusable(true);
        view.setOpaque(true);
        view.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (previousButton.contains(e.getPoint())) {
                    move(-1);
                } else if (nextButton.contains(e.getPoint())) {
                    move(1);
                } else if (saveButton.contains(e.getPoint())) {
                    saveFromWindow();
                } else if (closeButton.contains(e.getPoint())) {
                    close();
                }
            }
        });
        int menu = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        key(view, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), () -> move(1));
        key(view, KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), () -> move(1));
        key(view, KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), () -> move(1));
        key(view, KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_DOWN, 0), () -> move(1));
        key(view, KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), () -> move(-1));
        key(view, KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), () -> move(-1));
        key(view, KeyStroke.getKeyStroke(KeyEvent.VK_PAGE_UP, 0), () -> move(-1));
        key(view, KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0), () -> move(-slides.size()));
        key(view, KeyStroke.getKeyStroke(KeyEvent.VK_END, 0), () -> move(slides.size()));
        key(view, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), this::close);
        key(view, KeyStroke.getKeyStroke(KeyEvent.VK_S, menu), this::saveFromWindow);
        key(view, KeyStroke.getKeyStroke(KeyEvent.VK_M, menu | InputEvent.SHIFT_DOWN_MASK), this::close);
        window.setContentPane(view);
        enter();
        view.requestFocusInWindow();
    }

    private void enter() {
        GraphicsDevice screen = owner.getGraphicsConfiguration().getDevice();
        window.setBounds(screen.getDefaultConfiguration().getBounds());
        if (screen.isFullScreenSupported()) {
            screen.setFullScreenWindow(window);
        } else {
            window.setVisible(true);
        }
        window.toFront();
        window.getContentPane().requestFocusInWindow();
    }

    private void leave() {
        GraphicsDevice screen = owner.getGraphicsConfiguration().getDevice();
        if (screen.getFullScreenWindow() == window) {
            screen.setFullScreenWindow(null);
        }
        window.setVisible(false);
    }

    private void close() {
        leave();
        window.dispose();
        owner.toFront();
    }

    private void move(int by) {
        int next = Math.max(0, Math.min(slides.size() - 1, index + by));
        if (next != index) {
            index = next;
            window.repaint();
        }
    }

    /** A save dialog cannot sit over a full-screen window everywhere, so the slides step aside for it. */
    private void saveFromWindow() {
        leave();
        save(owner);
        enter();
    }

    private void save(JFrame parent) {
        accents();
        JFileChooser chooser = new JFileChooser(folder == null ? null : folder.toFile());
        chooser.setDialogTitle("Save the Meeting Slides as PDF");
        chooser.setSelectedFile(new File(baseName + "-meeting.pdf"));
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = chooser.getSelectedFile().toPath();
        if (!target.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) {
            target = target.resolveSibling(target.getFileName() + ".pdf");
        }
        if (Files.exists(target) && JOptionPane.showConfirmDialog(parent, target.getFileName() + " already exists. Replace it?",
                "Save as PDF", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            List<BufferedImage> pages = new ArrayList<>();
            int shown = index;
            for (int i = 0; i < slides.size(); i++) {
                index = i;
                pages.add(render(1920, 1080));
            }
            index = shown;
            ImagePdf.write(pages, 842f, target); // A4 landscape width
            Toast.show(owner, "Meeting slides saved to " + target.getFileName());
        } catch (IOException | RuntimeException e) {
            JOptionPane.showMessageDialog(parent, "Could not save the slides: " + e.getMessage(), "Save as PDF", JOptionPane.ERROR_MESSAGE);
        }
    }

    BufferedImage render(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        paint(g, width, height, false);
        g.dispose();
        return image;
    }

    private static void key(JComponent view, KeyStroke stroke, Runnable action) {
        String name = "meeting-" + stroke;
        view.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(stroke, name);
        view.getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { action.run(); }
        });
    }

    private static void accents() {
        Color[] colours = {Theme.BLUE, Theme.PURPLE, Theme.ORANGE, Theme.RED, Theme.YELLOW, Theme.GREEN};
        System.arraycopy(colours, 0, ACCENTS, 0, ACCENTS.length);
    }

    // ------------------------------------------------------------------ drawing

    private void paint(Graphics2D g, int w, int h, boolean onScreen) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        Briefing.Slide slide = slides.get(index);
        Color accent = ACCENTS[index % ACCENTS.length];
        float s = Math.min(w / 1920f, h / 1080f);
        g.setColor(Theme.EDITOR);
        g.fillRect(0, 0, w, h);
        int margin = Math.round(140 * s);
        int textWidth = w - 2 * margin;

        Font base = UIManager.getFont("defaultFont") != null ? UIManager.getFont("defaultFont") : new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        Font kicker = base.deriveFont(Font.BOLD, 30 * s);
        Font title = base.deriveFont(Font.BOLD, 60 * s);
        Font point = base.deriveFont(Font.PLAIN, 36 * s);
        Font tech = Theme.mono(26 * s);
        Font small = base.deriveFont(Font.PLAIN, 22 * s);

        // a band of the slide's colour down the left edge
        g.setColor(accent);
        g.fillRect(0, 0, Math.round(16 * s), h);

        int y = Math.round(120 * s);
        g.setFont(kicker);
        g.setColor(accent);
        g.drawString(slide.kicker(), margin, y);
        y += Math.round(40 * s);

        g.setFont(title);
        g.setColor(Theme.TEXT);
        FontMetrics tm = g.getFontMetrics();
        for (String line : wrap(slide.title(), tm, textWidth, 3)) {
            y += tm.getAscent();
            g.drawString(line, margin, y);
            y += tm.getDescent() + Math.round(8 * s);
        }
        y += Math.round(40 * s);

        int bottom = h - Math.round(170 * s);
        g.setFont(point);
        FontMetrics pm = g.getFontMetrics();
        int bullet = Math.round(14 * s);
        int indent = Math.round(44 * s);
        for (String text : slide.points()) {
            List<String> lines = wrap(text, pm, textWidth - indent, 3);
            if (y + pm.getHeight() > bottom) {
                break;
            }
            g.setColor(accent);
            g.fillOval(margin, y + (pm.getAscent() - bullet) / 2 + Math.round(4 * s), bullet, bullet);
            g.setColor(Theme.TEXT);
            for (String line : lines) {
                if (y + pm.getHeight() > bottom) {
                    break;
                }
                y += pm.getAscent();
                g.drawString(line, margin + indent, y);
                y += pm.getDescent() + Math.round(4 * s);
            }
            y += Math.round(22 * s);
        }

        if (slide.technical() != null) {
            g.setFont(tech);
            g.setColor(Theme.CODE);
            FontMetrics cm = g.getFontMetrics();
            List<String> lines = wrap(slide.technical(), cm, textWidth, 2);
            int ty = bottom + Math.round(10 * s);
            for (String line : lines) {
                ty += cm.getAscent();
                g.drawString(line, margin, ty);
                ty += cm.getDescent();
            }
        }

        // the footer: where the slides come from, the progress, and on screen the controls
        int footer = h - Math.round(60 * s);
        g.setFont(small);
        g.setColor(Theme.DIM);
        g.drawString("CausVane  ·  " + source, margin, footer);
        int dot = Math.round(12 * s);
        int gap = Math.round(14 * s);
        int dotsWidth = slides.size() * dot + (slides.size() - 1) * gap;
        int dx = (w - dotsWidth) / 2;
        for (int i = 0; i < slides.size(); i++) {
            g.setColor(i == index ? ACCENTS[i % ACCENTS.length] : Theme.TRACK);
            g.fillOval(dx + i * (dot + gap), footer - dot, dot, dot);
        }
        String count = (index + 1) + " / " + slides.size();
        FontMetrics sm = g.getFontMetrics();
        if (!onScreen) {
            g.setColor(Theme.DIM);
            g.drawString(count, w - margin - sm.stringWidth(count), footer);
            return;
        }
        // on screen: Previous, Next, Save as PDF and Close, along the bottom right
        String[] labels = {"Previous", "Next", "Save as PDF", "Close (Esc)"};
        Rectangle[] areas = {previousButton, nextButton, saveButton, closeButton};
        int x = w - margin;
        int pad = Math.round(14 * s);
        int height = sm.getHeight() + Math.round(12 * s);
        for (int i = labels.length - 1; i >= 0; i--) {
            int width = sm.stringWidth(labels[i]) + 2 * pad;
            x -= width;
            areas[i].setBounds(x, footer - sm.getAscent() - Math.round(6 * s), width, height);
            boolean enabled = !(i == 0 && index == 0) && !(i == 1 && index == slides.size() - 1);
            Buttons.Role role = i == 2 ? Buttons.Role.SHARE : i == 3 ? Buttons.Role.CLOSE : Buttons.Role.NEUTRAL;
            boolean neutral = role == Buttons.Role.NEUTRAL;
            boolean dark = Theme.mode() == Theme.Mode.DARK;
            Color tone = Buttons.color(role);
            g.setColor(neutral ? Theme.CARD : Buttons.mix(Theme.CARD, tone, dark ? 0.16f : 0.10f));
            g.fillRoundRect(areas[i].x, areas[i].y, areas[i].width, areas[i].height, height, height);
            g.setColor(neutral ? Theme.CARD_BORDER : Buttons.mix(Theme.CARD, tone, dark ? 0.55f : 0.45f));
            g.drawRoundRect(areas[i].x, areas[i].y, areas[i].width, areas[i].height, height, height);
            g.setColor(!enabled ? Theme.DIM : neutral ? Theme.TEXT : tone);
            g.drawString(labels[i], x + pad, footer);
            x -= Math.round(10 * s);
        }
        g.setColor(Theme.DIM);
        String hint = count + "  ·  arrow keys to move";
        g.drawString(hint, x - sm.stringWidth(hint) - Math.round(14 * s), footer);
    }

    /** Splits text into lines that fit, ending the last one with "..." when it does not all fit. */
    static List<String> wrap(String text, FontMetrics metrics, int width, int maxLines) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        String[] words = text.split(" ");
        for (int i = 0; i < words.length; i++) {
            String word = words[i];
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (metrics.stringWidth(candidate) <= width || line.isEmpty()) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            lines.add(line.toString());
            line.setLength(0);
            line.append(word);
            if (lines.size() == maxLines) {
                String last = lines.remove(maxLines - 1);
                while (!last.isEmpty() && metrics.stringWidth(last + "…") > width) {
                    last = last.substring(0, last.length() - 1);
                }
                lines.add(last.strip() + "…");
                return lines;
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }
}
