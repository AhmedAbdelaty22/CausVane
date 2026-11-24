package io.causvane.parser;

import io.causvane.model.Language;
import io.causvane.parser.FrameExtractor.RawFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Polyglot stack frame extraction")
class FrameExtractorTest {

    private final StackTraceParser parser = new StackTraceParser();
    private final FrameClassifier classifier = new FrameClassifier();

    private RawFrame frame(String line) {
        return frame(line, null);
    }

    private RawFrame frame(String line, String previous) {
        return parser.extractFrame(line, previous)
                .orElseThrow(() -> new AssertionError("no frame extracted from: " + line));
    }

    @Test
    void javaFrame() {
        RawFrame f = frame("\tat com.example.MyClass.myMethod(MyClass.java:42)");
        assertEquals(Language.JAVA, f.language());
        assertEquals("MyClass.java", f.file());
        assertEquals("com.example.MyClass", f.module());
        assertEquals("myMethod", f.function());
        assertEquals(42, f.line());
        assertTrue(classifier.isApplication(f));
    }

    @Test
    void javaFrameWithModulePrefixIsFrameworkCode() {
        RawFrame f = frame("\tat java.base/java.util.ArrayList.get(ArrayList.java:427)");
        assertEquals("java.util.ArrayList", f.module());
        assertEquals("get", f.function());
        assertEquals(427, f.line());
        assertFalse(classifier.isApplication(f));
    }

    @Test
    void javaSpecialFrames() {
        RawFrame nativeFrame = frame("\tat java.base/jdk.internal.misc.Unsafe.park(Native Method)");
        assertEquals("park", nativeFrame.function());
        assertEquals(-1, nativeFrame.line());

        RawFrame lambda = frame("\tat com.acme.Job.lambda$run$0(Job.java:18) ~[app.jar:1.0]");
        assertEquals("lambda$run$0", lambda.function());
        assertEquals(18, lambda.line());

        RawFrame constructor = frame("\tat com.acme.Config.<init>(Config.java:9)");
        assertEquals("<init>", constructor.function());
    }

    @Test
    void kotlinAndScalaFrames() {
        RawFrame kotlin = frame("\tat com.acme.jobs.LedgerVerifier.verify(LedgerVerifier.kt:51)");
        assertEquals("LedgerVerifier.kt", kotlin.file());
        assertEquals(51, kotlin.line());

        RawFrame scala = frame("\tat com.acme.stream.Pipeline$.run(Pipeline.scala:77)");
        assertEquals("com.acme.stream.Pipeline$", scala.module());
        assertEquals(77, scala.line());
    }

    @Test
    void springFrameIsFrameworkCode() {
        assertFalse(classifier.isApplication(frame(
                "\tat org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1089)")));
    }

    @Test
    void dotNetFrameWithFileAndLine() {
        RawFrame f = frame("   at Acme.Billing.Services.InvoiceService.CreateAsync(CreateInvoiceRequest request, CancellationToken ct)"
                + " in C:\\src\\Acme.Billing\\Services\\InvoiceService.cs:line 118");
        assertEquals(Language.DOTNET, f.language());
        assertEquals("C:\\src\\Acme.Billing\\Services\\InvoiceService.cs", f.file());
        assertEquals("Acme.Billing.Services.InvoiceService", f.module());
        assertEquals("CreateAsync", f.function());
        assertEquals(118, f.line());
        assertTrue(classifier.isApplication(f));
    }

    @Test
    void dotNetFrameWithoutSourceIsFrameworkCode() {
        RawFrame f = frame("   at System.Collections.Generic.Dictionary`2.get_Item(TKey key)");
        assertEquals(Language.DOTNET, f.language());
        assertNull(f.file());
        assertEquals("get_Item", f.function());
        assertFalse(classifier.isApplication(f));
    }

    @Test
    void dotNetConstructorFrame() {
        RawFrame f = frame("   at Acme.Export.TemplateLoader..ctor(String path) in /src/Acme.Export/TemplateLoader.cs:line 12");
        assertEquals("Acme.Export.TemplateLoader", f.module());
        assertEquals(".ctor", f.function());
        assertEquals(12, f.line());
    }

    @Test
    void pythonFrame() {
        RawFrame f = frame("  File \"/path/to/script.py\", line 42, in function_name");
        assertEquals(Language.PYTHON, f.language());
        assertEquals("/path/to/script.py", f.file());
        assertEquals("function_name", f.function());
        assertEquals(42, f.line());
        assertTrue(classifier.isApplication(f));
    }

    @Test
    void pythonSitePackagesIsLibraryCode() {
        assertFalse(classifier.isApplication(frame(
                "  File \"/srv/venv/lib/python3.12/site-packages/django/core/handlers/base.py\", line 197, in _get_response")));
    }

    @Test
    void nodeFrameWithFunction() {
        RawFrame f = frame("    at functionName (/path/to/file.js:42:15)");
        assertEquals(Language.NODE, f.language());
        assertEquals("/path/to/file.js", f.file());
        assertEquals("functionName", f.function());
        assertEquals(42, f.line());
        assertEquals(15, f.column());
        assertTrue(classifier.isApplication(f));
    }

    @Test
    void nodeFrameVariants() {
        RawFrame anonymous = frame("    at /app/src/routes/index.js:284:15");
        assertNull(anonymous.function());
        assertEquals(284, anonymous.line());

        RawFrame async = frame("    at async UserController.updateAvatar (/app/src/controllers/userController.ts:61:5)");
        assertEquals("UserController.updateAvatar", async.function());
        assertEquals("/app/src/controllers/userController.ts", async.file());

        RawFrame windows = frame("    at Object.<anonymous> (C:\\app\\src\\server.js:10:3)");
        assertEquals("C:\\app\\src\\server.js", windows.file());
        assertEquals(10, windows.line());

        RawFrame esm = frame("    at file:///app/src/main.mjs:7:9");
        assertEquals(7, esm.line());
    }

    @Test
    void nodeModulesAndInternalsAreLibraryCode() {
        assertFalse(classifier.isApplication(frame(
                "    at Layer.handle [as handle_request] (/app/node_modules/express/lib/router/layer.js:95:5)")));
        assertFalse(classifier.isApplication(frame(
                "    at processTicksAndRejections (node:internal/process/task_queues:95:5)")));
    }

    @Test
    void goFrameTakesFunctionFromPreviousLine() {
        RawFrame f = frame("\t/build/internal/handlers/batch.go:91 +0x2c5",
                "github.com/acme/ingest/internal/handlers.(*BatchHandler).Submit(0xc0001a2000, {0x8f1a60, 0xc0002d40e0})");
        assertEquals(Language.GO, f.language());
        assertEquals("/build/internal/handlers/batch.go", f.file());
        assertEquals("github.com/acme/ingest/internal/handlers", f.module());
        assertEquals("(*BatchHandler).Submit", f.function());
        assertEquals(91, f.line());
        assertTrue(classifier.isApplication(f));
    }

    @Test
    void goRuntimeAndStandardLibraryAreLibraryCode() {
        assertFalse(classifier.isApplication(frame("\t/usr/local/go/src/runtime/panic.go:770 +0x132", "panic({0x7a1c20?, 0xb5d3f0?})")));
        assertFalse(classifier.isApplication(frame("\t/usr/local/go/src/net/http/server.go:2039 +0x5e8", "net/http.(*conn).serve(0xc0002a6000)")));
        assertTrue(classifier.isApplication(frame("\t/build/cmd/ingest-worker/main.go:58 +0x3d1", "main.main()")));
    }

    @Test
    void rubyFrame() {
        RawFrame f = frame("\tfrom /srv/reports/app/services/revenue_report.rb:31:in `block in build'");
        assertEquals(Language.RUBY, f.language());
        assertEquals("/srv/reports/app/services/revenue_report.rb", f.file());
        assertEquals("block in build", f.function());
        assertEquals(31, f.line());
    }

    @Test
    void phpFrame() {
        RawFrame f = frame("#0 /var/www/shop/app/Http/Controllers/CheckoutController.php(57): "
                + "App\\Services\\CheckoutService->finalize(Object(App\\Models\\Cart))");
        assertEquals(Language.PHP, f.language());
        assertEquals("/var/www/shop/app/Http/Controllers/CheckoutController.php", f.file());
        assertEquals("App\\Services\\CheckoutService->finalize", f.function());
        assertEquals(57, f.line());
        assertFalse(classifier.isApplication(frame(
                "#2 /var/www/shop/vendor/laravel/framework/src/Illuminate/Routing/Router.php(799): Illuminate\\Routing\\Route->run()")));
    }

    @Test
    void rustFrameTakesSymbolFromPreviousLine() {
        RawFrame f = frame("             at ./src/pipeline/transform.rs:118:27", "   2: etl::pipeline::transform::normalize_row");
        assertEquals(Language.RUST, f.language());
        assertEquals("./src/pipeline/transform.rs", f.file());
        assertEquals("etl::pipeline::transform::normalize_row", f.function());
        assertEquals(118, f.line());
        assertEquals(27, f.column());
        assertFalse(classifier.isApplication(frame(
                "             at /rustc/9b00956e/library/core/src/panicking.rs:72:14", "   1: core::panicking::panic_fmt")));
    }

    @Test
    void gdbFrame() {
        RawFrame f = frame("#0  0x000055555555a1c4 in ImageCache::evict (this=0x0, key=...) at src/cache/image_cache.cpp:203");
        assertEquals(Language.CPP, f.language());
        assertEquals("ImageCache::evict", f.function());
        assertEquals("src/cache/image_cache.cpp", f.file());
        assertEquals(203, f.line());
    }

    @Test
    void genericFileLineColumnFallback() {
        RawFrame rust = frame("thread 'main' panicked at src/main.rs:10:5:");
        assertEquals(Language.RUST, rust.language());
        assertEquals("src/main.rs", rust.file());
        assertEquals(10, rust.line());
        assertEquals(5, rust.column());

        RawFrame cpp = frame("Assertion failed: (index < size), function at, file /src/lib/vector.hpp:88");
        assertEquals(Language.CPP, cpp.language());
        assertEquals(88, cpp.line());

        RawFrame elixir = frame("    (shop 0.1.0) lib/shop/cart.ex:42: Shop.Cart.total/1");
        assertEquals("lib/shop/cart.ex", elixir.file());
        assertEquals(42, elixir.line());
    }

    @Test
    void phpOnLineFallback() {
        RawFrame f = frame("PHP Warning:  Undefined variable $total in /var/www/shop/cart.php on line 27");
        assertEquals(Language.PHP, f.language());
        assertEquals("/var/www/shop/cart.php", f.file());
        assertEquals(27, f.line());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2026-03-14 09:12:03.557  INFO 4821 --- [main] o.s.b.w.e.tomcat.TomcatWebServer : Tomcat started on port 8080",
            "Connected to db.internal:5432 in 12ms",
            "    at startup (phase 2)",
            "GET http://localhost:3000/health 200",
            "\t... 7 more",
            "user logged in at 10:45:12",
    })
    void ordinaryLogLinesAreNotFrames(String line) {
        assertTrue(parser.extractFrame(line, null).isEmpty(), "unexpected frame in: " + line);
    }
}
