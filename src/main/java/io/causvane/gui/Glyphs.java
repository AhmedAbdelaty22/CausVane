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

    /** Solid triangle pointing left, right or down, in the button's text color. */
    record Triangle(int direction) implements Icon {
        static final int LEFT = 0;
        static final int RIGHT = 1;
        static final int DOWN = 2;
        private static final int SIZE = 10;

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color color = c.isEnabled() ? c.getForeground() : UIManager.getColor("Label.disabledForeground");
            g2.setColor(color == null ? Theme.DIM : color);
            Path2D.Double path = new Path2D.Double();
            switch (direction) {
                case LEFT -> { path.moveTo(x + 7.5, y + 1); path.lineTo(x + 7.5, y + 9); path.lineTo(x + 1.5, y + 5); }
                case RIGHT -> { path.moveTo(x + 2.5, y + 1); path.lineTo(x + 2.5, y + 9); path.lineTo(x + 8.5, y + 5); }
                default -> { path.moveTo(x + 1.5, y + 3); path.lineTo(x + 8.5, y + 3); path.lineTo(x + 5, y + 7.5); }
            }
            path.closePath();
            g2.fill(path);
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

    /** Lightning bolt: "quick actions". Drawn in the button's text colour. */
    record Bolt() implements Icon {
        private static final int SIZE = 14;

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(c.isEnabled() ? c.getForeground() : UIManager.getColor("Label.disabledForeground"));
            Path2D.Double path = new Path2D.Double();
            path.moveTo(x + 8.5, y + 0.5);
            path.lineTo(x + 2.0, y + 8.0);
            path.lineTo(x + 6.5, y + 8.0);
            path.lineTo(x + 5.0, y + 13.5);
            path.lineTo(x + 12.0, y + 5.5);
            path.lineTo(x + 7.5, y + 5.5);
            path.closePath();
            g2.fill(path);
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
}
