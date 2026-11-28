package io.causvane.gui;

import javax.swing.Icon;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

/**
 * Small icons drawn as shapes. Arrow characters such as ◀ or ⤓ are missing from the default UI fonts of
 * some systems and would show as empty boxes there; shapes look the same everywhere.
 */
final class Glyphs {

    private Glyphs() {}

    /** Sun (shown in the dark theme: "switch to light") or crescent moon ("switch to dark"). */
    record ThemeSwitch(boolean sun) implements Icon {
        private static final int SIZE = 16;

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.setColor(c.getForeground());
            g2.translate(x, y);
            if (sun) {
                g2.fill(new java.awt.geom.Ellipse2D.Double(5, 5, 6, 6));
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (int i = 0; i < 8; i++) {
                    double angle = Math.PI * i / 4;
                    g2.draw(new java.awt.geom.Line2D.Double(8 + Math.cos(angle) * 5.2, 8 + Math.sin(angle) * 5.2,
                            8 + Math.cos(angle) * 7, 8 + Math.sin(angle) * 7));
                }
            } else {
                java.awt.geom.Area moon = new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Double(2, 2, 12, 12));
                moon.subtract(new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Double(6, 0.5, 11, 11)));
                g2.fill(moon);
            }
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }
    }

    /** Arrow pointing down onto a line: "drop a file here". */
    record DropArrow(int size, Color color) implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            float stroke = size / 12f;
            g2.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double mid = x + size / 2.0;
            double top = y + size * 0.10;
            double tip = y + size * 0.70;
            double head = size * 0.20;
            Path2D.Double path = new Path2D.Double();
            path.moveTo(mid, top);
            path.lineTo(mid, tip);
            path.moveTo(mid - head, tip - head);
            path.lineTo(mid, tip);
            path.lineTo(mid + head, tip - head);
            path.moveTo(x + size * 0.22, y + size * 0.90);
            path.lineTo(x + size * 0.78, y + size * 0.90);
            g2.draw(path);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }

    /** A small filled circle, or a ring around a dot for the most serious level. */
    static final class Dot implements javax.swing.Icon {
        private final java.awt.Color color;
        private final boolean strong;

        Dot(java.awt.Color color, boolean strong) {
            this.color = color;
            this.strong = strong;
        }

        @Override
        public void paintIcon(java.awt.Component c, java.awt.Graphics g, int x, int y) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            if (strong) {
                g2.setStroke(new java.awt.BasicStroke(1.4f));
                g2.drawOval(x, y, 8, 8);
                g2.fillOval(x + 2, y + 2, 5, 5);
            } else {
                g2.fillOval(x + 1, y + 1, 7, 7);
            }
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 9;
        }

        @Override
        public int getIconHeight() {
            return 9;
        }
    }

    /**
     * One family of line icons for buttons: the same 16-point size, the same rounded stroke, drawn in the
     * button's own text colour (dimmed when the button is disabled), so every icon in the window matches.
     */
    record Line(Kind kind) implements Icon {
        enum Kind { OPEN, PASTE, CLOSE, LIVE, BOLT, MORE, COPY, WEB, BACK, PREVIOUS, NEXT, DOWN, DETAIL, SLIDES, SAVE }

        private static final int SIZE = 16;

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            Color color = c == null || c.isEnabled() ? (c == null ? Theme.TEXT : c.getForeground())
                    : UIManager.getColor("Label.disabledForeground");
            g2.setColor(color == null ? Theme.DIM : color);
            g2.translate(x, y);
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D.Double p = new Path2D.Double();
            switch (kind) {
                case OPEN -> { // a folder
                    p.moveTo(2, 4.5); p.lineTo(6, 4.5); p.lineTo(7.5, 6); p.lineTo(14, 6); p.lineTo(14, 12.5); p.lineTo(2, 12.5); p.closePath();
                }
                case PASTE -> { // a clipboard
                    p.append(new java.awt.geom.RoundRectangle2D.Double(3, 3.5, 10, 10.5, 2, 2), false);
                    p.append(new java.awt.geom.RoundRectangle2D.Double(5.75, 2, 4.5, 2.6, 1.2, 1.2), false);
                    p.moveTo(5.5, 8); p.lineTo(10.5, 8); p.moveTo(5.5, 10.8); p.lineTo(9, 10.8);
                }
                case CLOSE -> { p.moveTo(4.5, 4.5); p.lineTo(11.5, 11.5); p.moveTo(11.5, 4.5); p.lineTo(4.5, 11.5); }
                case LIVE -> { // a pulse line
                    p.moveTo(1.5, 8.5); p.lineTo(4.5, 8.5); p.lineTo(6.5, 3.5); p.lineTo(9.5, 12.5); p.lineTo(11.5, 8.5); p.lineTo(14.5, 8.5);
                }
                case BOLT -> {
                    p.moveTo(9, 1.8); p.lineTo(3.5, 9); p.lineTo(7.5, 9); p.lineTo(6.5, 14.2); p.lineTo(12.5, 7); p.lineTo(8.5, 7); p.closePath();
                }
                case MORE -> {
                    for (double cx : new double[] {3.5, 8, 12.5}) {
                        g2.fill(new java.awt.geom.Ellipse2D.Double(cx - 1.3, 6.7, 2.6, 2.6));
                    }
                }
                case COPY -> {
                    p.append(new java.awt.geom.RoundRectangle2D.Double(5.5, 5.5, 8.5, 8.5, 2, 2), false);
                    p.moveTo(10.5, 3.5); p.lineTo(10.5, 3); p.quadTo(10.5, 2, 9.5, 2); p.lineTo(3, 2); p.quadTo(2, 2, 2, 3); p.lineTo(2, 9.5); p.quadTo(2, 10.5, 3, 10.5); p.lineTo(3.5, 10.5);
                }
                case WEB -> { // a globe
                    p.append(new java.awt.geom.Ellipse2D.Double(2, 2, 12, 12), false);
                    p.append(new java.awt.geom.Ellipse2D.Double(5.2, 2, 5.6, 12), false);
                    p.moveTo(2.3, 8); p.lineTo(13.7, 8);
                }
                case BACK -> { p.moveTo(13, 8); p.lineTo(3, 8); p.moveTo(7, 4); p.lineTo(3, 8); p.lineTo(7, 12); }
                case PREVIOUS -> { p.moveTo(10, 3.5); p.lineTo(5.5, 8); p.lineTo(10, 12.5); }
                case NEXT -> { p.moveTo(6, 3.5); p.lineTo(10.5, 8); p.lineTo(6, 12.5); }
                case DOWN -> { p.moveTo(4.5, 6.5); p.lineTo(8, 10); p.lineTo(11.5, 6.5); }
                case DETAIL -> { // three lines of different length: "how much to show"
                    p.moveTo(2.5, 4.5); p.lineTo(13.5, 4.5); p.moveTo(2.5, 8); p.lineTo(10.5, 8); p.moveTo(2.5, 11.5); p.lineTo(7.5, 11.5);
                }
                case SLIDES -> { // a screen on a stand
                    p.append(new java.awt.geom.RoundRectangle2D.Double(2, 2.5, 12, 8.5, 1.6, 1.6), false);
                    p.moveTo(8, 11); p.lineTo(8, 14); p.moveTo(5.5, 14); p.lineTo(10.5, 14);
                }
                case SAVE -> { // arrow into a tray
                    p.moveTo(8, 2.5); p.lineTo(8, 10); p.moveTo(5, 7); p.lineTo(8, 10); p.lineTo(11, 7);
                    p.moveTo(2.5, 10.5); p.lineTo(2.5, 13.5); p.lineTo(13.5, 13.5); p.lineTo(13.5, 10.5);
                }
            }
            g2.draw(p);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }
    }
}
