package io.logreader.gui;

import io.logreader.explain.ErrorExplainer;
import io.logreader.model.ErrorGroup;
import io.logreader.model.Severity;

import javax.swing.table.AbstractTableModel;
import java.time.LocalDateTime;
import java.util.List;

/** Table model of the error navigator: one row per deduplicated error. */
final class ErrorTableModel extends AbstractTableModel {

    static final int SEVERITY = 0;
    static final int TYPE = 1;
    static final int MEANING = 2;
    static final int LOCATION = 3;
    static final int COUNT = 4;
    static final int FIRST_SEEN = 5;
    static final int LAST_SEEN = 6;
    static final int MESSAGE = 7;

    private static final String[] COLUMNS =
            {"Severity", "Error Type", "What It Means", "Location (File:Line)", "Count", "First Seen", "Last Seen", "Message"};
    private static final Class<?>[] TYPES =
            {Severity.class, String.class, String.class, String.class, Long.class, LocalDateTime.class, LocalDateTime.class, String.class};

    private List<ErrorGroup> groups = List.of();
    /** Explanation titles by group id, filled on demand: explaining thousands of rows up front would stall the window. */
    private final java.util.Map<String, String> meanings = new java.util.HashMap<>();
    private long maxCount = 1;

    void setGroups(List<ErrorGroup> groups) {
        this.groups = List.copyOf(groups);
        if (meanings.size() > 4 * Math.max(1000, groups.size())) {
            meanings.clear();
        }
        this.maxCount = groups.stream().mapToLong(ErrorGroup::count).max().orElse(1);
        fireTableDataChanged();
    }

    /** Highest occurrence count among the rows, the scale of the count bars. */
    long maxCount() {
        return maxCount;
    }

    List<ErrorGroup> groups() {
        return groups;
    }

    ErrorGroup groupAt(int modelRow) {
        return groups.get(modelRow);
    }

    int indexOf(String groupId) {
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).id().equals(groupId)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public int getRowCount() {
        return groups.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public Class<?> getColumnClass(int column) {
        return TYPES[column];
    }

    @Override
    public Object getValueAt(int row, int column) {
        ErrorGroup g = groups.get(row);
        return switch (column) {
            case SEVERITY -> g.severity();
            case TYPE -> g.displayType();
            case MEANING -> meanings.computeIfAbsent(g.id() + "#" + g.count(),
                    key -> ErrorExplainer.standard().explain(g).title().replace("`", ""));
            case LOCATION -> g.location();
            case COUNT -> g.count();
            case FIRST_SEEN -> g.firstSeen();
            case LAST_SEEN -> g.lastSeen();
            case MESSAGE -> g.message();
            default -> null;
        };
    }
}
