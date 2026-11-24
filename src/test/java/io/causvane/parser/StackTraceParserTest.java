package io.causvane.parser;

import io.causvane.model.Language;
import io.causvane.model.ParsedTrace;
import io.causvane.model.StackFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Root cause and pinpoint detection")
class StackTraceParserTest {

    private final StackTraceParser parser = new StackTraceParser();

    private ParsedTrace parse(String text) {
        return parser.parse(List.of(text.split("\n", -1)))
                .orElseThrow(() -> new AssertionError("no trace detected"));
    }

    @Test
    void javaCausedByChainPinpointsDeepestApplicationFrame() {
        ParsedTrace trace = parse("""
                2026-03-14 09:16:03.118 ERROR [nio-8080-exec-4] c.a.o.web.GlobalExceptionHandler : Request failed
                com.acme.orders.service.OrderProcessingException: Could not place order
                \tat com.acme.orders.service.OrderService.placeOrder(OrderService.java:87)
                \tat org.springframework.web.servlet.DispatcherServlet.doDispatch(DispatcherServlet.java:1089)
                Caused by: org.springframework.dao.DataAccessResourceFailureException: Unable to acquire JDBC Connection
                \tat com.acme.orders.repo.OrderRepository.save(OrderRepository.java:142)
                \t... 7 more
                Caused by: java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available
                \tat com.zaxxer.hikari.pool.HikariPool.getConnection(HikariPool.java:181)
                \tat com.acme.orders.repo.ConnectionProvider.acquire(ConnectionProvider.java:58)
                \t... 9 more""");

        assertEquals(Language.JAVA, trace.language());
        assertEquals("com.acme.orders.service.OrderProcessingException", trace.topType());
        assertEquals("java.sql.SQLTransientConnectionException", trace.rootType());
        assertEquals("HikariPool-1 - Connection is not available", trace.rootMessage());
        assertEquals(List.of("com.acme.orders.service.OrderProcessingException",
                "org.springframework.dao.DataAccessResourceFailureException",
                "java.sql.SQLTransientConnectionException"), trace.chain());

        StackFrame pin = trace.pinpoint();
        assertEquals("ConnectionProvider.java:58", pin.location());
        assertEquals("com.acme.orders.repo.ConnectionProvider.acquire", pin.qualifiedFunction());
        assertTrue(pin.application());
        assertEquals(9, pin.eventLineIndex());
    }

    @Test
    void javaRootCauseWithoutOwnApplicationFramesFallsBackToEnclosingException() {
        ParsedTrace trace = parse("""
                com.acme.PaymentException: Charge failed
                \tat com.acme.PaymentClient.charge(PaymentClient.java:96)
                \tat com.acme.PaymentService.pay(PaymentService.java:52)
                Caused by: java.net.ConnectException: Connection refused
                \t... 2 more""");
        assertEquals("java.net.ConnectException", trace.rootType());
        assertEquals("PaymentClient.java:96", trace.pinpoint().location());
    }

    @Test
    void traceWithOnlyFrameworkFramesPinpointsTopFrameAsLibrary() {
        ParsedTrace trace = parse("""
                java.lang.IllegalArgumentException: bad
                \tat java.base/java.util.Objects.requireNonNull(Objects.java:233)
                \tat org.springframework.util.Assert.notNull(Assert.java:172)""");
        assertEquals("Objects.java:233", trace.pinpoint().location());
        assertFalse(trace.pinpoint().application());
    }

    @Test
    void uncaughtJavaExceptionHeader() {
        ParsedTrace trace = parse("""
                Exception in thread "main" java.lang.OutOfMemoryError: Java heap space
                \tat java.base/java.util.Arrays.copyOf(Arrays.java:3512)
                \tat com.acme.export.CsvExporter.exportAll(CsvExporter.java:73)""");
        assertEquals("java.lang.OutOfMemoryError", trace.topType());
        assertEquals("Java heap space", trace.rootMessage());
        assertEquals("CsvExporter.java:73", trace.pinpoint().location());
    }

    @Test
    void dotNetInnerExceptionIsTheRootCause() {
        ParsedTrace trace = parse("""
                System.InvalidOperationException: Failed to save invoice 20418
                 ---> Microsoft.Data.SqlClient.SqlException (0x80131904): Violation of UNIQUE KEY constraint
                   at Microsoft.Data.SqlClient.SqlConnection.OnError(SqlException exception, Boolean breakConnection)
                   at Acme.Billing.Data.InvoiceRepository.SaveAsync(Invoice invoice) in C:\\src\\Acme.Billing\\Data\\InvoiceRepository.cs:line 74
                   --- End of inner exception stack trace ---
                   at Acme.Billing.Services.InvoiceService.CreateAsync(CreateInvoiceRequest request) in C:\\src\\Acme.Billing\\Services\\InvoiceService.cs:line 118""");

        assertEquals(Language.DOTNET, trace.language());
        assertEquals("System.InvalidOperationException", trace.topType());
        assertEquals("Microsoft.Data.SqlClient.SqlException", trace.rootType());
        assertEquals("Violation of UNIQUE KEY constraint", trace.rootMessage());
        assertEquals("InvoiceRepository.cs:74", trace.pinpoint().location());
        assertEquals("Acme.Billing.Data.InvoiceRepository.SaveAsync", trace.pinpoint().qualifiedFunction());
    }

    @Test
    void dotNetNestedInnerExceptionsOnOneChain() {
        ParsedTrace trace = parse("""
                System.AggregateException: One or more errors occurred.
                 ---> System.Threading.Tasks.TaskCanceledException: The operation was canceled.
                 ---> System.TimeoutException: The request timed out after 15000 ms
                   at Acme.Billing.Rendering.PdfClient.RenderAsync(InvoiceDocument document) in C:\\src\\PdfClient.cs:line 52
                   --- End of inner exception stack trace ---
                   at Acme.Billing.Jobs.PdfRenderJob.ExecuteAsync(Int32 invoiceId) in C:\\src\\PdfRenderJob.cs:line 33""");
        assertEquals("System.TimeoutException", trace.rootType());
        assertEquals(3, trace.chain().size());
        assertEquals("PdfClient.cs:52", trace.pinpoint().location());
    }

    @Test
    void pythonTracebackPinpointsLastApplicationCall() {
        ParsedTrace trace = parse("""
                [2026-03-14 13:42:31,675] ERROR django.request: Internal Server Error: /api/cart/88410/checkout
                Traceback (most recent call last):
                  File "/srv/venv/lib/python3.12/site-packages/django/core/handlers/base.py", line 197, in _get_response
                    response = wrapped_callback(request, *callback_args, **callback_kwargs)
                  File "/srv/app/shop/views/checkout.py", line 48, in checkout
                    order = build_order(cart, request.user)
                  File "/srv/app/shop/services/shipping.py", line 37, in quote_shipping
                    zone = ZONES[address.country_code]
                KeyError: 'XK'""");

        assertEquals(Language.PYTHON, trace.language());
        assertEquals("KeyError", trace.topType());
        assertEquals("'XK'", trace.rootMessage());
        assertEquals("shipping.py:37", trace.pinpoint().location());
        assertEquals("quote_shipping", trace.pinpoint().function());
        assertEquals(6, trace.pinpoint().eventLineIndex());
    }

    @Test
    void pythonChainedTracebackRootIsTheFirstException() {
        ParsedTrace trace = parse("""
                Traceback (most recent call last):
                  File "/srv/app/shop/mail/smtp.py", line 71, in deliver
                    self._conn.sendmail(sender, recipients, payload)
                  File "/usr/lib/python3.12/smtplib.py", line 891, in sendmail
                    raise SMTPRecipientsRefused(senderrs)
                smtplib.SMTPRecipientsRefused: bad recipient

                The above exception was the direct cause of the following exception:

                Traceback (most recent call last):
                  File "/srv/app/shop/tasks.py", line 29, in send_receipt
                    mailer.deliver(receipt)
                shop.mail.errors.MailDeliveryError: SMTP relay rejected message""");

        assertEquals("shop.mail.errors.MailDeliveryError", trace.topType());
        assertEquals("smtplib.SMTPRecipientsRefused", trace.rootType());
        assertEquals("smtp.py:71", trace.pinpoint().location());
        assertEquals(List.of("shop.mail.errors.MailDeliveryError", "smtplib.SMTPRecipientsRefused"), trace.chain());
    }

    @Test
    void nodeErrorSkipsNodeModules() {
        ParsedTrace trace = parse("""
                2026-03-14T15:21:30.004Z error: Unhandled error in GET /v1/users/9120/settings
                TypeError: Cannot read properties of undefined (reading 'preferences')
                    at Layer.handle [as handle_request] (/app/node_modules/express/lib/router/layer.js:95:5)
                    at mapSettings (/app/src/services/settingsService.js:42:27)
                    at processTicksAndRejections (node:internal/process/task_queues:95:5)""");

        assertEquals(Language.NODE, trace.language());
        assertEquals("TypeError", trace.topType());
        assertEquals("Cannot read properties of undefined (reading 'preferences')", trace.rootMessage());
        assertEquals("settingsService.js:42", trace.pinpoint().location());
        assertEquals("mapSettings", trace.pinpoint().function());
        assertEquals(27, trace.pinpoint().column());
    }

    @Test
    void nodeErrorCauseIsTheRootCause() {
        ParsedTrace trace = parse("""
                Error: Avatar upload failed
                    at AvatarService.sync (/app/src/services/avatarService.ts:88:13) {
                  [cause]: Error: connect ECONNREFUSED 10.0.4.17:9000
                      at TCPConnectWrap.afterConnect [as oncomplete] (node:net:1595:16)
                      at S3Client.putObject (/app/src/clients/s3Client.ts:34:11)
                }""");
        assertEquals("connect ECONNREFUSED 10.0.4.17:9000", trace.rootMessage());
        assertEquals("s3Client.ts:34", trace.pinpoint().location());
    }

    @Test
    void goPanicPinpointsFirstApplicationFrameOfPanickingGoroutine() {
        ParsedTrace trace = parse("""
                panic: runtime error: index out of range [12] with length 12

                goroutine 1 [running]:
                panic({0x7a1c20?, 0xb5d3f0?})
                \t/usr/local/go/src/runtime/panic.go:770 +0x132
                github.com/acme/ingest/internal/partition.Assign({0xc000180000, 0xc, 0x10}, 0xc)
                \t/build/internal/partition/assign.go:47 +0x1f8
                main.main()
                \t/build/cmd/ingest-worker/main.go:58 +0x3d1

                goroutine 18 [chan receive]:
                github.com/acme/ingest/internal/worker.(*Pool).loop(0xc0000a8000)
                \t/build/internal/worker/pool.go:71 +0x45
                exit status 2""");

        assertEquals(Language.GO, trace.language());
        assertEquals("panic", trace.topType());
        assertEquals("runtime error: index out of range [12] with length 12", trace.rootMessage());
        assertEquals("assign.go:47", trace.pinpoint().location());
        assertEquals("Assign", trace.pinpoint().function());
        assertEquals("github.com/acme/ingest/internal/partition", trace.pinpoint().module());
    }

    @Test
    void rubyErrorHeaderCarriesTypeAndFirstFrame() {
        ParsedTrace trace = parse("""
                /srv/reports/app/services/revenue_report.rb:64:in `fetch': undefined method `amount_cents' for nil:NilClass (NoMethodError)
                \tfrom /srv/reports/app/services/revenue_report.rb:31:in `block in build'
                \tfrom /srv/reports/app/jobs/nightly_report_job.rb:12:in `perform'""");
        assertEquals(Language.RUBY, trace.language());
        assertEquals("NoMethodError", trace.topType());
        assertEquals("revenue_report.rb:64", trace.pinpoint().location());
        assertEquals("fetch", trace.pinpoint().function());
    }

    @Test
    void phpFatalError() {
        ParsedTrace trace = parse("""
                PHP Fatal error:  Uncaught App\\Exceptions\\CartException: Cart 5512 has no shipping method in /var/www/shop/app/Services/CheckoutService.php:142
                Stack trace:
                #0 /var/www/shop/app/Http/Controllers/CheckoutController.php(57): App\\Services\\CheckoutService->finalize(Object(App\\Models\\Cart))
                #1 {main}
                  thrown in /var/www/shop/app/Services/CheckoutService.php on line 142""");
        assertEquals(Language.PHP, trace.language());
        assertEquals("App\\Exceptions\\CartException", trace.topType());
        assertEquals("CheckoutService.php:142", trace.pinpoint().location());
    }

    @Test
    void rustPanicTakesMessageFromNextLineAndFunctionFromBacktrace() {
        ParsedTrace trace = parse("""
                thread 'tokio-runtime-worker' panicked at src/pipeline/transform.rs:118:27:
                called `Option::unwrap()` on a `None` value
                stack backtrace:
                   0: rust_begin_unwind
                             at /rustc/9b00956e/library/std/src/panicking.rs:645:5
                   1: etl::pipeline::transform::normalize_row
                             at ./src/pipeline/transform.rs:118:27""");
        assertEquals(Language.RUST, trace.language());
        assertEquals("panic", trace.topType());
        assertEquals("called `Option::unwrap()` on a `None` value", trace.rootMessage());
        assertEquals("transform.rs:118", trace.pinpoint().location());
        assertEquals("etl::pipeline::transform::normalize_row", trace.pinpoint().function());
    }

    @Test
    void exceptionWithoutFramesStillReportsItsType() {
        ParsedTrace trace = parse("2026-03-14 10:00:00 ERROR Retry failed: java.net.SocketTimeoutException: Read timed out");
        assertEquals("java.net.SocketTimeoutException", trace.topType());
        assertEquals("Read timed out", trace.rootMessage());
        assertNull(trace.pinpoint());
    }

    @Test
    void ordinaryLinesProduceNoTrace() {
        assertTrue(parser.parse(List.of("2026-03-14 10:00:00 INFO Server started on port 8080")).isEmpty());
        assertTrue(parser.parse(List.of("WARN Internal Server Error: /api/orders took 3 retries")).isEmpty());
    }

    @Test
    void headerRecognition() {
        assertEquals("TypeError", StackTraceParser.parseHeader("TypeError: x is not a function").type());
        assertEquals("Error", StackTraceParser.parseHeader("Error: boom").type());
        assertEquals("panic", StackTraceParser.parseHeader("panic: nil map").type());
        assertEquals("com.acme.Oops", StackTraceParser.parseHeader("com.acme.Oops: bad state").type());
        assertNull(StackTraceParser.parseHeader("ERROR something went wrong"));
    }
}
