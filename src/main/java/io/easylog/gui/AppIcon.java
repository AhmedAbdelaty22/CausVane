package io.easylog.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Taskbar;
import java.awt.Window;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * The EasyLog application icon, drawn as vector shapes so it is sharp at every size: a dark tile showing
 * log lines, one of them highlighted in red, under an orange magnifying glass.
 */
public final class AppIcon {

    private static final int[] WINDOW_SIZES = {16, 20, 24, 32, 40, 48, 64, 128, 256};

    private static final Color TILE_TOP = new Color(0x34373E);
    private static final Color TILE_BOTTOM = new Color(0x17181C);
    private static final Color TILE_EDGE = new Color(255, 255, 255, 38);
    private static final Color LINE = new Color(0x6B717D);
    private static final Color ERROR = new Color(0xF0524B);
    private static final Color ACCENT = new Color(0xFFA53C);

    private AppIcon() {}

    /**
     * Renders the icon.
     *
     * @param size   width and height in pixels
     * @param margin transparent border as a fraction of the size (macOS icons use about 0.09, others 0)
     */
    public static BufferedImage render(int size, double margin) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        double inset = size * margin;
        double s = size - 2 * inset;               // side of the tile
        g.translate(inset, inset);

        RoundRectangle2D tile = new RoundRectangle2D.Double(0, 0, s, s, s * 0.45, s * 0.45);
        g.setPaint(new GradientPaint(0, 0, TILE_TOP, 0, (float) s, TILE_BOTTOM));
        g.fill(tile);
        if (size >= 48) {
            g.setColor(TILE_EDGE);
            g.setStroke(new BasicStroke((float) Math.max(1, s * 0.008)));
            g.draw(new RoundRectangle2D.Double(s * 0.004, s * 0.004, s * 0.992, s * 0.992, s * 0.45, s * 0.45));
        }

        // log lines; the third one is the error
        double left = s * 0.20;
        double barHeight = s * 0.085;
        double[] tops = {0.235, 0.385, 0.535, 0.685};
        double[] widths = {0.60, 0.42, 0.60, 0.34};
        for (int i = 0; i < tops.length; i++) {
            boolean error = i == 2;
            g.setColor(error ? ERROR : LINE);
            g.fill(new RoundRectangle2D.Double(left, s * tops[i], s * widths[i], barHeight, barHeight, barHeight));
        }

        // magnifying glass over the lower right, drawn only where it stays legible
        if (size >= 24) {
            double cx = s * 0.655;
            double cy = s * 0.640;
            double radius = s * 0.165;
            float stroke = (float) (s * 0.062);
            Ellipse2D lens = new Ellipse2D.Double(cx - radius, cy - radius, radius * 2, radius * 2);
            g.setColor(new Color(23, 24, 28, 215));
            g.fill(lens);
            g.setClip(lens);
            g.setColor(ERROR);                       // the error line seen through the lens, enlarged
            double zoomed = barHeight * 1.5;
            g.fill(new RoundRectangle2D.Double(cx - radius * 1.4, cy - zoomed / 2 - s * 0.01, radius * 2.8, zoomed, zoomed, zoomed));
            g.setClip(null);
            g.setColor(ACCENT);
            g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(lens);
            double start = radius + stroke * 0.2;
            double diagonal = Math.sqrt(0.5);
            g.setStroke(new BasicStroke(stroke * 1.25f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(cx + start * diagonal, cy + start * diagonal, s * 0.875, s * 0.860));
        }
        g.dispose();
        return image;
    }

    /** Icon images in every size a window manager may ask for (title bar, task switcher, taskbar). */
    public static List<Image> windowIcons() {
        List<Image> icons = new ArrayList<>(WINDOW_SIZES.length);
        for (int size : WINDOW_SIZES) {
            icons.add(render(size, 0));
        }
        return icons;
    }

    /** Uses the icon for the window and, where the platform supports it, for the Dock / taskbar entry. */
    public static void install(Window window) {
        window.setIconImages(windowIcons());
        try {
            if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
                Taskbar.getTaskbar().setIconImage(render(512, 0.09));
            }
        } catch (UnsupportedOperationException | SecurityException e) {
            // the window icon set above still applies
        }
    }
}
