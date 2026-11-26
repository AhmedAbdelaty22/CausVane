package io.causvane.explain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Safe web search")
class WebSearchTest {

    @Test
    void takesOutEverythingThatIdentifiesASystemOrPerson() {
        WebSearch.Query q = WebSearch.clean("Connection to db-primary.prod.acme.internal:5432 refused for user 'billing_rw' "
                + "from 10.20.30.40 (request 3f2a9c1e-7b4d-4e8a-9f00-1234567890ab, order ORD99812) "
                + "see https://wiki.acme.com/x?id=7 or mail ops@acme.com, file /srv/acme/billing/conf.yml token=eyJhbGciOiJIUzI1NiJ9abcdefghijkl");
        String text = q.text();
        for (String secret : new String[] {"acme", "5432", "billing", "10.20", "3f2a9c1e", "ORD99812", "wiki", "ops@", "/srv", "eyJ", "token"}) {
            assertFalse(text.contains(secret), secret + " was left in: " + text);
        }
        assertTrue(text.startsWith("Connection to refused for user"), text);
        assertTrue(q.removed().contains("quoted values"));
        assertTrue(q.removed().contains("IP addresses"));
    }

    @Test
    void keepsTheGeneralWords() {
        assertEquals("Java heap space", WebSearch.clean("Java heap space").text());
        assertEquals("Nothing needed removing.", WebSearch.describeRemoved(WebSearch.clean("Java heap space").removed()));
    }

    @Test
    void buildsTheAddressForEachSite() {
        assertEquals("https://duckduckgo.com/?q=NullPointerException+Cannot+invoke",
                WebSearch.Engine.DUCKDUCKGO.url("NullPointerException Cannot invoke"));
        assertEquals(WebSearch.Engine.GOOGLE, WebSearch.engine("nonsense"));
        assertEquals(WebSearch.Engine.STACK_OVERFLOW, WebSearch.engine("stack_overflow"));
    }
}
