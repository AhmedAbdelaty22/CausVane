package io.logreader.gui;

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
}
