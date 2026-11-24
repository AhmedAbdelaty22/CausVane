package io.causvane.explain;

import io.causvane.io.LogSession;
import io.causvane.model.AnalysisResult;
import io.causvane.model.ErrorGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Plain-language error explanations")
class ErrorExplainerTest {

    private final ErrorExplainer explainer = ErrorExplainer.standard();

    private Explanation explain(String sample, String location) throws IOException {
        AnalysisResult result = new LogSession(Path.of("src", "test", "resources", "fixtures", sample)).load(null);
        ErrorGroup group = result.groups().stream().filter(g -> g.location().equals(location)).findFirst()
                .orElseThrow(() -> new AssertionError("no error at " + location));
        return explainer.explain(group);
    }

    @Test
    void wrappedErrorIsExplainedByItsRootCause() throws IOException {
        Explanation e = explain("java-spring-app.log", "ConnectionProvider.java:58");
        assertEquals("Database", e.category());
        assertEquals("The database connection pool ran out of connections", e.title());
        assertEquals("The request waited 30000 ms before giving up.", e.detail());
        assertTrue(e.recognised());
        assertTrue(e.context().get(0).contains("`ConnectionProvider.java:58`"));
        assertTrue(e.context().get(0).contains("application's own code"));
        assertTrue(e.context().get(1).contains("only the outer wrapper"));
        assertTrue(e.context().get(1).contains("`SQLTransientConnectionException`"));
        assertTrue(e.context().get(2).startsWith("It happened 3 times"));
        assertFalse(e.likelyCauses().isEmpty());
        assertFalse(e.nextSteps().isEmpty());
    }

    @Test
    void detailsAreTakenFromTheErrorMessage() throws IOException {
        assertEquals("The empty value was `com.acme.orders.model.Customer.getBillingAddress()`.",
                explain("java-spring-app.log", "TaxCalculator.java:64").detail());
        assertEquals("It tried to read `preferences` from a value that was undefined.",
                explain("node-express.log", "settingsService.js:42").detail());
        assertEquals("The missing key was `XK`.", explain("python-django.log", "shipping.py:37").detail());
        assertEquals("The constraint that was violated is `UQ_Invoices_Number`.",
                explain("dotnet-api.log", "InvoiceRepository.cs:74").detail());
        assertEquals("It asked for position 12 in a list of only 12 items.",
                explain("go-polyglot-crashes.log", "assign.go:47").detail());
        assertEquals("The limit was 2000 ms.", explain("json-structured.log", "IndexClient.java:121").detail());
        assertEquals("The target was `10.0.4.17:9000`.", explain("node-express.log", "s3Client.ts:34").detail());
        assertEquals("The path was `/data/export/templates/order.xslt`.",
                explain("json-structured.log", "TemplateLoader.cs:31").detail());
    }

    @Test
    void sameKindOfProblemIsRecognisedAcrossLanguages() throws IOException {
        assertEquals("Null reference", explain("java-spring-app.log", "TaxCalculator.java:64").category());
        assertEquals("Null reference", explain("dotnet-api.log", "DiscountCalculator.cs:39").category());
        assertEquals("Null reference", explain("node-express.log", "settingsService.js:42").category());
        assertEquals("Null reference", explain("go-polyglot-crashes.log", "batch.go:91").category());
        assertEquals("Null reference", explain("go-polyglot-crashes.log", "revenue_report.rb:64").category());

        assertEquals("Memory", explain("java-spring-app.log", "CsvExporter.java:73").category());
        assertEquals("State", explain("java-spring-app.log", "LedgerVerifier.kt:51").category());
        assertEquals("Network", explain("java-spring-app.log", "PaymentClient.java:91").category());
        assertEquals("Network", explain("dotnet-api.log", "PdfClient.cs:52").category());
        assertEquals("Configuration", explain("dotnet-api.log", "Program.cs:22").category());
        assertEquals("Code defect", explain("node-express.log", "tree.js:14").category());
        assertEquals("Data", explain("node-express.log", "json.js:92").category());
        assertEquals("Data", explain("python-django.log", "client.py:41").category());
        assertEquals("Database", explain("python-django.log", "product.py:67").category());
        assertEquals("E-mail", explain("python-django.log", "smtp.py:71").category());
        assertEquals("Validation", explain("json-structured.log", "product.js:27").category());
        assertEquals("Data", explain("json-structured.log", "webhooks.py:58").category());
        assertEquals("Crash", explain("go-polyglot-crashes.log", "transform.rs:118").category());
        assertEquals("Crash", explain("go-polyglot-crashes.log", "image_cache.cpp:203").category());
    }

    @Test
    void libraryOnlyTraceSaysNoApplicationCodeIsInvolved() throws IOException {
        Explanation e = explain("node-express.log", "json.js:92");
        assertTrue(e.context().get(0).contains("framework or library code"));
    }

    @Test
    void unknownErrorsGetHonestGenericGuidance() throws IOException {
        Explanation e = explain("go-polyglot-crashes.log", "CheckoutService.php:142");
        assertFalse(e.recognised());
        assertEquals("Unclassified", e.category());
        assertEquals("The application raised `CartException`", e.title());
        assertTrue(e.meaning().contains("not in CausVane's knowledge base"));
        assertNotNull(e.detail());
    }

    @Test
    void messagesWithoutExceptionsAreExplainedFromTheirText() throws IOException {
        AnalysisResult result = new LogSession(Path.of("src", "test", "resources", "fixtures", "java-spring-app.log")).load(null);
        ErrorGroup slow = result.groups().stream().filter(g -> g.displayType().equals("Warning")).findFirst().orElseThrow();
        Explanation e = explainer.explain(slow);
        assertEquals("Performance", e.category());
        assertTrue(e.context().get(0).startsWith("No stack trace was logged"));
        assertNull(e.detail());
    }
}
