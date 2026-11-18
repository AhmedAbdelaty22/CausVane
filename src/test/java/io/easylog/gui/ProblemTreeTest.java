package io.easylog.gui;

import io.easylog.explain.IncidentLinker;
import io.easylog.io.LogSession;
import io.easylog.model.AnalysisResult;
import io.easylog.model.ErrorGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Map of all problems")
class ProblemTreeTest {

    @Test
    void everyProblemIsInTheTreeAndBranchesFold() throws IOException {
        AnalysisResult r = new LogSession(Path.of("samples", "checkout-db-failover.log")).load(null);
        List<IncidentLinker.Incident> incidents = IncidentLinker.find(r.groups());
        ProblemTree tree = new ProblemTree();
        tree.prepare(r, incidents);

        String start = tree.html(r, incidents);
        assertTrue(start.contains("Incident: The database connection was cut while in use"), "the incident is a branch");
        assertTrue(start.contains("First failure: "), "the first incident starts open");
        assertTrue(start.contains("Knock-on effects after it") && start.contains("Warning signs before it"));
        assertFalse(start.contains("group:" + r.groups().get(0).id()), "knock-on effects start folded");

        assertTrue(tree.click("tree-all", r, incidents));
        String all = tree.html(r, incidents);
        for (ErrorGroup g : r.groups()) {
            assertTrue(all.contains("group:" + g.id()), "every problem appears once opened: " + g.displayType());
        }
        assertTrue(all.contains("Null reference"), "problems outside the incident are grouped by kind");

        assertTrue(tree.click("tree-none", r, incidents));
        assertFalse(tree.html(r, incidents).contains("First failure: "), "close all folds everything");
        assertFalse(tree.click("group:x", r, incidents), "problem links are not the tree's own");
    }
}
