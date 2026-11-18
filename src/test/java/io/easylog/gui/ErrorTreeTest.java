package io.easylog.gui;

import io.easylog.io.LogSession;
import io.easylog.model.AnalysisResult;
import io.easylog.model.ErrorGroup;
import io.easylog.model.LogEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("The error, step by step")
class ErrorTreeTest {

    private static ErrorGroup group(String sample, String type) throws IOException {
        AnalysisResult r = new LogSession(Path.of("samples", sample)).load(null);
        return r.groups().stream().filter(g -> type.equals(g.displayType())).findFirst().orElseThrow();
    }

    private static String tree(ErrorGroup g) {
        ErrorTree tree = new ErrorTree();
        LogEvent event = g.samples().get(0);
        tree.prepare(g, event);
        return tree.html(event);
    }

    @Test
    void aJavaChainEndsAtTheRootCauseAndThePlaceItStarted() throws IOException {
        String html = tree(group("checkout-db-failover.log", "CannotCreateTransactionException"));
        assertTrue(html.contains("THE ERROR, STEP BY STEP"));
        assertTrue(html.contains("CAUSED BY") && html.contains("ROOT CAUSE"));
        assertTrue(html.contains("ConnectException"), "the deepest cause is in the tree");
        assertTrue(html.contains("STARTED HERE"));
        assertTrue(html.contains("steps inside libraries"), "library code is folded into runs");
    }

    @Test
    void otherLanguagesWorkTooAndLibrariesAreNamed() throws IOException {
        String python = tree(group("payments-worker-deadlocks.log", "OperationalError"));
        assertTrue(python.contains("DeadlockDetected") && python.contains("STARTED HERE"));
        assertTrue(python.contains("celery") || python.contains("django"), python);
        String go = tree(group("ingest-crash-loop.log", "panic"));
        assertTrue(go.contains("STARTED HERE") && go.contains("tagger.go"));
    }

    @Test
    void anErrorWithoutAStackTraceHasNoTree() throws IOException {
        AnalysisResult r = new LogSession(Path.of("samples", "checkout-db-failover.log")).load(null);
        ErrorGroup single = r.groups().stream().filter(g -> g.samples().get(0).trace() == null
                || !g.samples().get(0).trace().hasFrames()).findFirst().orElseThrow();
        assertEquals("", tree(single), "a single-line error keeps the panel as it was");
    }

    @Test
    void libraryRunsFoldAndOpen() throws IOException {
        ErrorGroup g = group("checkout-db-failover.log", "CannotCreateTransactionException");
        ErrorTree tree = new ErrorTree();
        LogEvent event = g.samples().get(0);
        tree.prepare(g, event);
        String folded = tree.html(event);
        assertTrue(tree.click("et:lib:0"));
        String opened = tree.html(event);
        assertTrue(opened.length() > folded.length(), "opening a library run lists its steps");
        assertFalse(tree.click("group:x"));
    }
}
