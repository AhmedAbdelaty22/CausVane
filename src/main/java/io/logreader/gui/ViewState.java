package io.logreader.gui;

import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the reader was in the window: how far each panel was scrolled and where each divider was. Taken
 * before the theme changes and put back on the window built in the new colours, which has the same parts
 * in the same order, so the switch changes nothing but the colours.
 */
final class ViewState {

    static final String RATIO = "logreader.ratio";

    private final List<Point> scrolls = new ArrayList<>();
    private final List<int[]> dividers = new ArrayList<>();
    private final List<Object> ratios = new ArrayList<>();

    static ViewState capture(Container root) {
        ViewState state = new ViewState();
        state.walk(root, true);
        return state;
    }

    void restore(Container root) {
        scrollIndex = 0;
        dividerIndex = 0;
        walk(root, false);
    }

    private int scrollIndex;
    private int dividerIndex;

    private void walk(Component component, boolean capture) {
        if (component instanceof JSplitPane split) {
            if (capture) {
                dividers.add(new int[] {split.getOrientation(), split.getDividerLocation()});
                int size = split.getOrientation() == JSplitPane.HORIZONTAL_SPLIT ? split.getWidth() : split.getHeight();
                // the proportion the divider is really at, so later resizing keeps it where it is now
                int room = size - split.getDividerSize(); // JSplitPane measures proportions without the divider itself
                ratios.add(room > 0 ? Double.valueOf(split.getDividerLocation() / (double) room) : split.getClientProperty(RATIO));
            } else if (dividerIndex < dividers.size()) {
                int[] saved = dividers.get(dividerIndex);
                Object ratio = ratios.get(dividerIndex++);
                if (ratio != null) {
                    split.putClientProperty(RATIO, ratio);
                }
                if (saved[0] == split.getOrientation()) {
                    split.setDividerLocation(saved[1]);
                }
            }
        }
        if (component instanceof JScrollPane scroll) {
            if (capture) {
                scrolls.add(scroll.getViewport().getViewPosition());
            } else if (scrollIndex < scrolls.size()) {
                Point position = scrolls.get(scrollIndex++);
                scroll.getViewport().validate();
                scroll.getViewport().setViewPosition(position);
            }
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                walk(child, capture);
            }
        }
    }
}
