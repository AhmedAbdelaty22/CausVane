package io.logreader.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ThemeTest {

    @Test
    void logTextThatLooksLikeHtmlIsNeutralised() {
        String hostile = "<html><img src='http://attacker.example/track.png'>";
        assertFalse(Theme.literal(hostile).startsWith("<html"));
        assertFalse(Theme.literal("  <HTML>x").stripLeading().toLowerCase().startsWith("<html"));
        assertEquals("plain <html> later", Theme.literal("plain <html> later"));
        assertNull(Theme.literal(null));
    }
}
