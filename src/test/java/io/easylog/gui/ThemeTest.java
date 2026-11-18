package io.easylog.gui;

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

    @Test
    void codeNamesAreToldApartFromWords() {
        org.junit.jupiter.api.Assertions.assertTrue(Theme.looksLikeCode("OrderService.java"));
        org.junit.jupiter.api.Assertions.assertTrue(Theme.looksLikeCode("OrderService.placeOrder"));
        org.junit.jupiter.api.Assertions.assertTrue(Theme.looksLikeCode("decoder/envelope.go:63"));
        org.junit.jupiter.api.Assertions.assertTrue(Theme.looksLikeCode("std::vector::at"));
        assertFalse(Theme.looksLikeCode("orders-db"));
        assertFalse(Theme.looksLikeCode("connection refused"));
        assertFalse(Theme.looksLikeCode("1.5"));
        assertEquals("failed in&nbsp;<span class='code'>&nbsp;OrderService.java:131&nbsp;</span> today",
                Theme.markFiles("failed in OrderService.java:131 today", "code"));
        assertEquals("version 2.1 of the api", Theme.markFiles("version 2.1 of the api", "code"));
    }
}
