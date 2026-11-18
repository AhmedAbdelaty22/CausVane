package io.easylog.explain;

import io.easylog.model.ErrorGroup;
import io.easylog.model.StackFrame;
import io.easylog.report.Formats;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Explains errors in plain language from a built-in knowledge base of failures that are common to
 * applications in any language. Works offline: rules match on
 * the exception type and message of the root cause first, then on the reported error.
 */
public final class ErrorExplainer {

    /**
     * One knowledge-base entry. A rule matches when every non-null pattern is found.
     *
     * @param detail optional sentence about this specific occurrence; {@code {1}}, {@code {2}} are replaced
     *               by the capture groups of {@code message} and the sentence is dropped if a group is missing
     */
    private record Rule(String category, Pattern type, Pattern message, String title, String meaning, String detail,
                        List<String> causes, List<String> steps) {}

    /** Suffix marking a message pattern as optional detail rather than a condition. */
    private static final String OPTIONAL = "|.*";

    private static final ErrorExplainer DEFAULT = new ErrorExplainer();

    private final List<Rule> rules = new ArrayList<>();

    public static ErrorExplainer standard() {
        return DEFAULT;
    }

    private ErrorExplainer() {
        registerRules();
    }

    /** Builds the explanation for a deduplicated error. Never returns null. */
    public Explanation explain(ErrorGroup group) {
        String topText = join(group.message(), sampleText(group));
        // a root cause logged without a message of its own is described by the surrounding log text
        String rootText = group.rootCauseMessage() == null || group.rootCauseMessage().isBlank()
                ? topText : group.rootCauseMessage();

        Rule rule = null;
        Matcher matched = null;
        // the root cause describes the real problem, so it gets the first chance against every rule
        String[][] candidates = {
                {group.rootCauseType(), rootText},
                {group.errorType(), topText},
                {group.rootCauseType(), topText},
        };
        search:
        for (String[] candidate : candidates) {
            for (Rule r : rules) {
                Matcher m = match(r, candidate[0], candidate[1]);
                if (m != null) {
                    rule = r;
                    matched = m;
                    break search;
                }
            }
        }

        List<String> context = context(group);
        if (rule == null) {
            return generic(group, context);
        }
        return new Explanation(rule.category(), rule.title(), rule.meaning(), detail(rule, matched), context,
                rule.causes(), rule.steps(), true);
    }

    /** Returns a matcher (possibly without groups) when the rule applies, or null. */
    private static Matcher match(Rule rule, String type, String message) {
        if (rule.type() != null && (type == null || !rule.type().matcher(type).find())) {
            return null;
        }
        if (rule.message() == null) {
            return Pattern.compile("").matcher("");
        }
        Matcher m = rule.message().matcher(message == null ? "" : message);
        return m.find() ? m : null;
    }

    private static String detail(Rule rule, Matcher m) {
        if (rule.detail() == null) {
            return null;
        }
        String text = rule.detail();
        for (int g = 1; g <= 3; g++) {
            String token = "{" + g + "}";
            if (text.contains(token)) {
                String value;
                try {
                    value = g <= m.groupCount() ? m.group(g) : null;
                } catch (IllegalStateException e) {
                    value = null;
                }
                if (value == null || value.isBlank()) {
                    return null;
                }
                text = text.replace(token, plain(Formats.truncate(value.strip(), 160)));
            }
        }
        return text;
    }

    /** Facts about this particular error: where it is, whether it is a wrapper, how often it happens. */
    private static List<String> context(ErrorGroup group) {
        List<String> lines = new ArrayList<>();
        StackFrame pin = group.pinpoint();
        if (pin != null) {
            String where = code(pin.location())
                    + (pin.qualifiedFunction() != null ? " inside " + code(pin.qualifiedFunction()) : "");
            if (pin.application()) {
                lines.add("It starts at " + where + ". That is your application's own code, so it is the first place to look.");
            } else {
                lines.add("The deepest frame is " + where + ", which is framework or library code. No application "
                        + "code appears in the trace, so the trigger is most likely the data or configuration passed in.");
            }
        } else {
            lines.add("No stack trace was logged with this error, so the exact code location is unknown. "
                    + "The log line itself and the lines just before it are the best evidence.");
        }
        if (group.rootCauseType() != null && group.errorType() != null
                && !group.rootCauseType().equals(group.errorType())) {
            lines.add("The log reports it as " + code(ErrorGroup.simpleName(group.errorType()))
                    + ", but that is only the outer wrapper. The underlying cause is "
                    + code(ErrorGroup.simpleName(group.rootCauseType()))
                    + (group.rootCauseMessage() != null
                    ? ": " + code(Formats.truncate(group.rootCauseMessage(), 200).replaceFirst("[.\\s]+$", "")) : "") + ".");
        }
        if (group.count() > 1) {
            lines.add("It happened " + Formats.count(group.count()) + " times"
                    + (group.firstSeen() != null
                    ? " between " + Formats.timestamp(group.firstSeen()) + " and " + Formats.timestamp(group.lastSeen())
                    : "")
                    + ", always at the same place, which points to a repeatable condition rather than a one-off glitch.");
        } else {
            lines.add("It happened once in this log.");
        }
        return lines;
    }

    /**
     * Text taken from the log, made safe to sit inside a {@code `code span`}: explanations are rendered as
     * Markdown and HTML, and a value must never be able to close its span and inject markup of its own.
     */
    private static String plain(String untrusted) {
        return untrusted.replace('`', '\'').replace('\n', ' ').replace('\r', ' ');
    }

    private static String code(String untrusted) {
        return "`" + plain(untrusted) + "`";
    }

    private static Explanation generic(ErrorGroup group, List<String> context) {
        boolean hasType = group.errorType() != null;
        String title = hasType
                ? "The application raised " + code(ErrorGroup.simpleName(group.errorType()))
                : "The application logged " + switch (group.severity()) {
                    case FATAL -> "a fatal error";
                    case ERROR -> "an error";
                    case WARN -> "a warning";
                    default -> "a problem";
                };
        String meaning = hasType
                ? "This error type is not in EasyLog's knowledge base, so there is no specific guidance for it. "
                + "The name and message usually describe the problem in the developers' own words."
                : "This is a message the developers chose to log at this level; it has no exception attached. "
                + "Its wording is the description of the problem.";
        String detail = group.message() == null || group.message().isBlank() ? null
                : "Message: " + code(Formats.truncate(group.message(), 300));
        return new Explanation("Unclassified", title, meaning, detail, context,
                List.of("A condition the code explicitly checks for and rejects",
                        "Unexpected input or state reaching this part of the application"),
                List.of("Read the message and the log lines just before it for what the request or job was doing",
                        "Search the code base or its issue tracker for the error name or message text",
                        "Pass the pinpointed location and this summary to the development team"),
                false);
    }

    private static String sampleText(ErrorGroup group) {
        if (group.samples().isEmpty()) {
            return null;
        }
        List<String> lines = group.samples().get(0).lines();
        return String.join("\n", lines.subList(0, Math.min(lines.size(), 12)));
    }

    private static String join(String a, String b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : a + "\n" + b;
    }

    // ------------------------------------------------------------------ knowledge base

    private void add(String category, String type, String message, String title, String meaning, String detail,
                     List<String> causes, List<String> steps) {
        Pattern messagePattern = null;
        if (message != null && message.endsWith(OPTIONAL)) {
            // "specific|.*": always matches, capturing the specifics when they are present
            String specific = message.substring(0, message.length() - OPTIONAL.length());
            messagePattern = Pattern.compile("^(?:.*?(?:" + specific + "))?", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        } else if (message != null) {
            messagePattern = Pattern.compile(message, Pattern.CASE_INSENSITIVE);
        }
        rules.add(new Rule(category,
                type == null ? null : Pattern.compile(type),
                messagePattern,
                title, meaning, detail, causes, steps));
    }

    /** Ordered from most specific to most general; the first match wins. */
    private void registerRules() {
        // ---- protective mechanisms and capacity: usually symptoms of an earlier failure -------------
        add("Protection", "CallNotPermitted|CircuitBreakerOpen|BrokenCircuit|BulkheadFull", "circuit ?breaker '?([\\w.-]+)'? (?:is|changed state from \\w+ to) OPEN|.*",
                "A safety switch (circuit breaker) is blocking calls",
                "After many failures talking to another system, the application stops trying for a while so that it does "
                        + "not make things worse. Calls are rejected immediately until the other system recovers. "
                        + "This error is a symptom: the real problem is whatever failed just before it.",
                "The circuit breaker involved is `{1}`.",
                List.of("The system behind the breaker is down or very slow (database, cache, another service)",
                        "A burst of timeouts pushed the failure rate over the breaker's limit"),
                List.of("Look at the errors logged just before the first of these; they show what tripped the breaker",
                        "Check the health of the system named in the breaker",
                        "These stop on their own once the breaker closes again"));
        add("Protection", null, "circuit ?breaker '?([\\w.-]+)'? changed state from \\w+ to OPEN|circuit ?breaker .* (?:is|now) open",
                "A safety switch (circuit breaker) opened",
                "The application saw too many failures talking to another system and has stopped calling it for a while. "
                        + "From this moment, requests that need that system fail immediately.",
                "The circuit breaker involved is `{1}`.",
                List.of("The system behind the breaker is down or very slow"),
                List.of("Treat this line as the start of the outage for that dependency",
                        "Look at the errors in the minute before it for the cause"));
        add("Resources", null, "thread ?pool starvation|(\\d+) work items queued, 0 worker threads|no worker threads available|ThreadPool.*exhaust",
                "The application ran out of worker threads",
                "Every thread that handles requests was busy waiting, so new work piled up and the application "
                        + "stopped responding even though it was still running. This usually follows slow calls to another system.",
                "{1} pieces of work were waiting.",
                List.of("Slow or hanging calls to a database or another service are holding all the threads",
                        "Code that blocks while waiting for a result instead of waiting asynchronously",
                        "A recent change that added many more outgoing calls per request"),
                List.of("Find which calls were slow at that time (timeouts logged just before are the clue)",
                        "Check whether a deployment happened shortly before",
                        "Restarting restores service briefly; the slow dependency or the new code is the real fix"));
        add("Database", null, "Pool stats \\(total=(\\d+), active=\\1, idle=0, waiting=(\\d+)\\)|connection pool (?:is )?(?:full|at capacity)",
                "The database connection pool is completely in use",
                "All database connections the application is allowed to hold are busy, and requests are queuing for one. "
                        + "If the queue does not clear, those requests fail with a timeout.",
                "All {1} connections were in use and {2} requests were waiting.",
                List.of("The database is slow or unreachable, so connections are not being returned",
                        "More simultaneous requests than the pool was sized for"),
                List.of("Check the database at this time", "Watch whether the waiting number grows or shrinks in later lines"));
        add("Security", null, "certificate .{0,120}expires (?:at|in|on) ([^;,]+)",
                "A security certificate is about to expire",
                "Certificates that secure connections between systems have an end date. Once it passes, every connection "
                        + "that relies on the certificate is refused. This warning is the chance to renew it in time.",
                "It expires {1}.",
                List.of("Automatic renewal stopped working", "Nobody was assigned to renew it manually"),
                List.of("Renew the certificate now and check why automatic renewal did not run",
                        "Look for connection failures that begin at the expiry time"));
        add("Capacity", "RateLimit|TooManyRequests|Throttl", "rate limit|too many requests|\\b429\\b|throttl|.*",
                "Requests were refused because of a rate limit",
                "A limit on how many requests one client may send in a period was reached, so further requests were "
                        + "turned away. The limit is working as designed; the question is why the client sent so many.",
                null,
                List.of("A client retrying failed requests in a tight loop", "A genuine traffic spike", "A limit set too low for normal use"),
                List.of("Identify the client in the message and check whether it was retrying after earlier errors",
                        "Compare the timing with other failures; rate limiting often follows an outage"));
        add("Capacity", null, "rate limit exceeded|too many requests|\\b429 Too Many\\b|request (?:was )?throttled",
                "Requests were refused because of a rate limit",
                "A limit on how many requests one client may send in a period was reached, so further requests were turned away.",
                null,
                List.of("A client retrying failed requests in a tight loop", "A genuine traffic spike"),
                List.of("Identify the client and check whether it was retrying after earlier errors"));
        add("Capacity", null, "shedding load|retry budget|load shedding|backpressure|server (?:is )?overloaded|queue (?:is )?full",
                "The application is deliberately rejecting work because it is overloaded",
                "To protect itself, the application has stopped retrying or accepting some work. Requests fail fast instead "
                        + "of piling up. Like a circuit breaker, this is a symptom of an earlier failure or of too much traffic.",
                null,
                List.of("A dependency is failing, and retries multiplied the load", "Traffic beyond what the service can handle"),
                List.of("Find the failures that started just before this message", "Check the dependency named in the message"));
        add("Capacity", null, "lag (?:is )?(?:growing|increasing)|consumer lag|queue depth (\\d+)|backlog (?:is )?growing|falling behind",
                "Work is arriving faster than it is being processed",
                "The application reads work from a queue, and the queue is getting longer. Nothing has failed yet, but "
                        + "results are increasingly delayed, and it often means the workers are stuck or crashing.",
                null,
                List.of("The workers are slowed down by errors or retries", "A worker is crashing and restarting",
                        "More incoming work than usual"),
                List.of("Look for errors or restarts in the same period", "Check whether the number keeps growing or recovers"));
        add("Messaging", "CommitFailed|RebalanceInProgress|IllegalGeneration|UnknownMemberId", null,
                "A message-queue consumer lost its place in its group",
                "Several copies of the application share the work of reading a message queue. This copy took too long "
                        + "between check-ins, was removed from the group, and could not record which messages it had finished. "
                        + "Those messages will be processed again by another copy.",
                null,
                List.of("Processing a batch took longer than the allowed time, often because a database or service was slow",
                        "The application was paused (long garbage collection, starved of CPU)"),
                List.of("Check what slowed processing down at that moment", "Expect some messages to be processed twice"));
        add("Messaging", null, "offset commit failed|not aware of this member|kicked out of the group|rebalanc",
                "A message-queue consumer lost its place in its group",
                "This copy of the application took too long between check-ins with the message queue and was removed "
                        + "from its group. Some messages will be processed again.",
                null,
                List.of("Slow processing, often caused by a slow database or service", "The application was paused"),
                List.of("Check what slowed processing down at that moment"));
        add("Lifecycle", null, "SIGTERM|SIGKILL|warm shutdown|graceful shutdown|application is shutting down|OOMKilled|exit status \\d+",
                "The application was stopped",
                "Something outside the application told it to stop: a deployment, an operator, or the platform after a "
                        + "failed health check. On its own this is not a fault, but an unexpected stop is worth explaining.",
                null,
                List.of("A deployment or restart", "The platform restarting an unhealthy instance", "Running out of memory"),
                List.of("Check whether a deployment or scaling event happened at this time",
                        "If it was not planned, look at the errors and warnings in the minutes before"));
        add("Performance", null, "thread starvation or clock leap|clock leap detected|long gc pause|stop-the-world",
                "The application froze for a moment",
                "A background timer inside the application noticed it had not run for much longer than expected. The whole "
                        + "application was paused or had no CPU time. Requests during the pause were delayed.",
                null,
                List.of("A long garbage-collection pause", "The machine or container was starved of CPU", "The virtual machine was paused"),
                List.of("Check CPU and memory graphs for this moment", "Look for timeouts shortly afterwards; they are a consequence"));

        // ---- database -------------------------------------------------------------------------------
        add("Database", null, "Connection is not available, request timed out after (\\d+) ?ms|connection pool (?:is )?exhausted"
                        + "|Timeout waiting for (?:idle object|connection from pool)|pool timed out|max pool size was reached",
                "The database connection pool ran out of connections",
                "The application keeps a fixed number of database connections and lends them to requests. Every "
                        + "connection was in use, and this request gave up waiting for a free one.",
                "The request waited {1} ms before giving up.",
                List.of("The database is slow or down, so connections are held much longer than normal",
                        "A traffic spike needs more simultaneous connections than the pool allows",
                        "A connection leak: some code takes a connection and never returns it",
                        "Long-running queries or transactions holding connections"),
                List.of("Check database health and slow queries at the time of the first occurrence",
                        "Compare the timing with traffic peaks or batch jobs",
                        "Look at connection pool metrics (active, idle, waiting) if the application exposes them",
                        "If it began after a deployment, suspect a connection leak in new code"));
        add("Database", "SqlException|SQLIntegrityConstraintViolation|IntegrityError|UniqueViolation|DuplicateKey|ConstraintViolation|PostgresException",
                "UNIQUE (?:KEY )?constraint '?([\\w.]+)?|duplicate key|Duplicate entry|unique constraint",
                "The database rejected a duplicate value",
                "A row was being saved with a value that must be unique (such as an ID, number or e-mail address), "
                        + "and a row with that value already exists.",
                "The constraint that was violated is `{1}`.",
                List.of("The same request was submitted twice (double click, retry, replayed message)",
                        "Two processes generated the same identifier at the same time",
                        "The code does not check whether the record already exists before inserting"),
                List.of("Find the duplicate value in the message and look up the existing record",
                        "Check whether the operation was retried or sent twice (same trace ID or timestamps close together)",
                        "Ask the developers how the unique value is generated"));
        add("Database", "Deadlock|LockAcquisition|CannotAcquireLock|LockTimeout", null,
                "Two database operations blocked each other",
                "The database stopped one transaction because it and another transaction were each waiting for a lock "
                        + "the other one held (a deadlock), or a lock could not be obtained in time.",
                null,
                List.of("Concurrent requests updating the same rows in a different order",
                        "A long transaction holding locks while other work queues up"),
                List.of("Retrying the operation usually succeeds; check whether the application retried",
                        "If it is frequent, give the developers the two statements involved (the database log has them)"));
        add("Database", null, "deadlock detected|Deadlock found when trying to get lock|was deadlocked on lock",
                "Two database operations blocked each other",
                "The database stopped one transaction because it and another transaction were each waiting for a lock "
                        + "the other one held.",
                null,
                List.of("Concurrent requests updating the same rows in a different order"),
                List.of("Retrying the operation usually succeeds", "If frequent, report the statements involved to the developers"));
        add("Database", "OperationalError|SQLTransientConnection|CommunicationsException|JDBCConnectionException|DataAccessResourceFailure|SQLRecoverable",
                null,
                "The application lost or could not get its database connection",
                "The database did not accept or keep the connection, so the query never ran. This is an "
                        + "infrastructure problem rather than a bug in the query itself.",
                null,
                List.of("The database server restarted, failed over or is overloaded",
                        "A network interruption or firewall dropped the connection",
                        "Wrong host, port or credentials in the configuration"),
                List.of("Check whether the database was reachable at that time",
                        "Look for a burst of the same error across many requests, which confirms an outage",
                        "Verify the connection settings if this happens from the first request after start-up"));
        add("Database", null, "server closed the connection unexpectedly|Lost connection to MySQL|connection (?:is|has been) closed|terminating connection|database connection lost|broken pipe.*(?:sql|database)",
                "The database connection was cut while in use",
                "The connection to the database dropped in the middle of an operation.",
                null,
                List.of("The database restarted or failed over", "An idle-timeout or network device closed the connection"),
                List.of("Check database uptime and failover events at that time",
                        "If it recurs after idle periods, the pool's keep-alive or validation settings need adjusting"));
        add("Data", "DoesNotExist|NoResultException|EntityNotFound|RecordNotFound|ObjectNotFound|EmptyResultDataAccess|NoSuchElement",
                null,
                "A record the code expected to find does not exist",
                "The application looked something up and assumed exactly one result, but nothing matched.",
                null,
                List.of("The record was deleted, or was never created (for example an earlier step failed)",
                        "The identifier in the request is wrong or belongs to another environment",
                        "Events arriving out of order: the reference came before the record itself"),
                List.of("Find the identifier being looked up (in the request or the log lines before the error) and check whether it exists",
                        "If it should exist, trace where it was supposed to be created"));

        // ---- memory and resources -------------------------------------------------------------------
        add("Memory", "OutOfMemory|MemoryError|OutOfMemoryException|bad_alloc", null,
                "The application ran out of memory",
                "The process needed more memory than it is allowed to use. After this error the application is often "
                        + "unstable or exits, so a restart is usually needed.",
                null,
                List.of("Loading a very large amount of data at once (a big export, file or query result)",
                        "A memory leak that grows until the limit is reached",
                        "The memory limit is too low for the current workload"),
                List.of("Check what the pinpointed code was processing and how large it was",
                        "Look at memory usage over time: a steady climb suggests a leak, a sudden jump suggests one large operation",
                        "As a stopgap, raise the memory limit (for Java, `-Xmx`); the real fix is processing data in smaller pieces"));
        add("Memory", null, "JavaScript heap out of memory|Java heap space|GC overhead limit exceeded|Cannot allocate memory|out of memory",
                "The application ran out of memory",
                "The process needed more memory than it is allowed to use.",
                null,
                List.of("Loading a very large amount of data at once", "A memory leak", "A memory limit that is too low"),
                List.of("Check what was being processed and how large it was",
                        "Review memory usage over time for a leak pattern",
                        "Raise the memory limit as a stopgap"));
        add("Code defect", "StackOverflow|RecursionError|RangeError|InternalError", "Maximum call stack size exceeded|maximum recursion depth|too much recursion|^\\s*(?:null)?\\s*$|stack overflow",
                "A function called itself without stopping",
                "The code kept calling itself (directly or in a loop of functions) until the call stack was full. "
                        + "The repeated lines in the stack trace show which functions are in the loop.",
                null,
                List.of("Recursion with no end condition for this particular input",
                        "Data that refers back to itself, such as a menu or category that is its own parent",
                        "Unusually deep, but valid, nested data"),
                List.of("Look at the repeating frames in the trace to see the loop",
                        "Check the data being processed for a circular reference",
                        "Report the input that triggers it to the developers"));
        add("Resources", null, "Too many open files|EMFILE|ENFILE",
                "The process ran out of file handles",
                "The operating system limits how many files and network connections one process may have open, and the limit was reached.",
                null,
                List.of("Files or connections are opened and never closed (a leak)", "The limit (`ulimit -n`) is too low for the load"),
                List.of("Check the number of open files of the process", "Restarting clears it temporarily; a leak will bring it back"));
        add("Resources", null, "No space left on device|ENOSPC|disk (?:is )?full|There is not enough space on the disk",
                "The disk is full",
                "A file could not be written because the disk or volume has no free space left.",
                null,
                List.of("Log files or temporary files have filled the disk", "Uploaded or generated data grew beyond capacity"),
                List.of("Check free disk space on the server", "Clear old logs or temporary files, then confirm the application recovers"));
        add("Configuration", null, "address already in use|EADDRINUSE|Failed to bind to address|bind: address",
                "The application could not start because its network port is taken",
                "Another process is already listening on the port this application needs.",
                null,
                List.of("A previous instance of the same application is still running",
                        "Another service is configured with the same port"),
                List.of("Find what is using the port (`lsof -i :PORT` or `netstat`) and stop it, or change the configured port",
                        "Check for an old instance that did not shut down"));

        // ---- network --------------------------------------------------------------------------------
        add("Network", null, "ECONNREFUSED ?([\\w.:\\-]+)?|Connection refused|actively refused",
                "The service it tried to reach refused the connection",
                "The application reached the target machine, but nothing accepted the connection on that port. "
                        + "The target service is down, still starting, or the address is wrong.",
                "The target was `{1}`.",
                List.of("The dependent service is stopped or crashed",
                        "Wrong host or port in the configuration",
                        "A firewall or security group rejects the connection"),
                List.of("Check whether the target service is running and listening on that port",
                        "Verify the configured address for this environment",
                        "Test connectivity from the application's host"));
        add("Network", "UnknownHost|NameResolution|gaierror", null,
                "A host name could not be resolved",
                "The application asked DNS for the address of a server name and got no answer, so it could not connect at all.",
                null,
                List.of("A typo or wrong environment in the configured host name", "A DNS outage or missing DNS record",
                        "The application's network cannot reach the DNS server"),
                List.of("Check the host name in the message and try resolving it from the same server",
                        "Verify the configuration for this environment"));
        add("Network", null, "ENOTFOUND ?([\\w.\\-]+)?|Name or service not known|No such host is known|Temporary failure in name resolution|no such host",
                "A host name could not be resolved",
                "The application asked DNS for the address of a server name and got no answer.",
                "The name was `{1}`.",
                List.of("A typo or wrong environment in the configured host name", "A DNS outage or missing record"),
                List.of("Try resolving the host name from the same server", "Verify the configuration"));
        add("Security", "SSL|Certificate|TLS", null,
                "A secure (TLS/SSL) connection could not be established",
                "The two sides could not agree on a secure connection, usually because a certificate was not trusted or has expired.",
                null,
                List.of("An expired or self-signed certificate", "A missing intermediate or company root certificate",
                        "The host name does not match the certificate", "Old TLS versions disabled on one side"),
                List.of("Check the certificate's expiry date and issuer for the target address",
                        "Confirm whether a certificate was recently renewed or changed"));
        add("Security", null, "certificate (?:has expired|verify failed)|unable to verify the first certificate|self[- ]signed certificate|PKIX path building failed|handshake_failure",
                "A secure (TLS/SSL) connection could not be established",
                "A certificate was not trusted or has expired, so the connection was refused.",
                null,
                List.of("An expired or self-signed certificate", "A missing root or intermediate certificate"),
                List.of("Check the certificate's expiry date and issuer", "Ask whether a certificate was recently changed"));
        add("Network", "Timeout|TimedOut|TaskCanceled|DeadlineExceeded", "(?:after|within|timed out after|Timeout of)\\s*(\\d+ ?(?:ms|milliseconds|seconds?|second\\(s\\)|s\\b))|.*",
                "An operation took too long and was abandoned",
                "The application waited for a response from another system (a service, database or network call) and "
                        + "stopped waiting when its time limit was reached. The other side may still have completed the work.",
                "The limit was {1}.",
                List.of("The system being called is slow, overloaded or down",
                        "Network latency or packet loss between the two systems",
                        "The request was unusually large or expensive",
                        "The time limit is too short for normal peaks"),
                List.of("Identify which system was being called (the stack frames just above the pinpoint usually name it)",
                        "Check that system's health and response times at the same moment",
                        "See whether the errors cluster at particular times, which points to load"));
        add("Network", null, "timed out|timeout|ETIMEDOUT|deadline exceeded|i/o timeout",
                "An operation took too long and was abandoned",
                "The application waited for another system and gave up when its time limit was reached.",
                null,
                List.of("The system being called is slow or down", "Network problems between the two systems"),
                List.of("Identify which system was being called and check its health at that time",
                        "Check whether the errors cluster at busy times"));
        add("Network", null, "ECONNRESET|Connection reset|Broken pipe|EPIPE|socket hang up|connection was closed|unexpected EOF|Premature end",
                "A network connection was cut off mid-conversation",
                "The other side closed the connection while data was still being sent or awaited.",
                null,
                List.of("The remote service restarted or crashed", "A load balancer or proxy closed an idle or long connection",
                        "The client gave up and disconnected (common and often harmless for web requests)"),
                List.of("Check whether the remote service restarted at that time",
                        "If these are user-facing requests that were cancelled, they may be safe to ignore"));

        // ---- null / undefined -----------------------------------------------------------------------
        add("Null reference", "NullPointerException", "because (?:the return value of )?\"([^\"]+)\" is null|.*",
                "The code used a value that was empty (null)",
                "The program expected an object to be there, got nothing (null) instead, and failed when it tried to "
                        + "use it. This is almost always a missing piece of data that the code did not anticipate.",
                "The empty value was `{1}`.",
                nullCauses(), nullSteps());
        add("Null reference", "NullReferenceException|ArgumentNullException", null,
                "The code used a value that was empty (null)",
                "The program expected an object to be there, got nothing (null) instead, and failed when it tried to use it.",
                null, nullCauses(), nullSteps());
        add("Null reference", "TypeError", "Cannot read propert(?:y|ies) of (undefined|null)(?: \\(reading '([^']+)'\\))?|Cannot set propert|(undefined|null) is not (?:an object|a function)|is not a function",
                "The code used a value that was missing (undefined or null)",
                "JavaScript tried to read a field or call a function on something that does not exist. The object it "
                        + "expected was never set, or the data has a different shape than the code assumes.",
                "It tried to read `{2}` from a value that was {1}.",
                nullCauses(), nullSteps());
        add("Null reference", "AttributeError|TypeError", "'NoneType' object|NoneType",
                "The code used a value that was empty (None)",
                "Python got `None` where it expected a real object, and failed when it tried to use it.",
                null, nullCauses(), nullSteps());
        add("Null reference", "NoMethodError", "undefined method [`']([^']+)' for nil",
                "The code used a value that was empty (nil)",
                "Ruby tried to call a method on `nil`: the object it expected was not there.",
                "The method it tried to call was `{1}`.",
                nullCauses(), nullSteps());
        add("Null reference", null, "nil pointer dereference|invalid memory address|null pointer|Object reference not set",
                "The code used a value that was empty (nil / null)",
                "The program followed a reference that pointed to nothing and crashed or failed at that line.",
                null, nullCauses(), nullSteps());

        // ---- data and input -------------------------------------------------------------------------
        add("Data", "KeyError|KeyNotFound", "^\\s*'?([^'\\n]{1,80})'?",
                "A lookup used a key that is not in the table",
                "The code looked up a value by name in a dictionary or map, and that name is not present.",
                "The missing key was `{1}`.",
                List.of("A new or unusual value (country code, status, type) that the code has no entry for",
                        "Input data missing an expected field", "A typo or renamed key after a change"),
                List.of("Note the missing key and find where that value came from (user input, another system, the database)",
                        "Decide whether the value is legitimate and needs to be supported, or is bad data to be rejected"));
        add("Data", "IndexOutOfBounds|IndexError|ArgumentOutOfRange|IndexOutOfRange", null,
                "The code asked for an item beyond the end of a list",
                "A list or array was accessed at a position that does not exist, typically because it has fewer items "
                        + "than the code assumed (often it is empty).",
                null, indexCauses(), indexSteps());
        add("Data", null, "index out of range \\[(\\d+)\\] with length (\\d+)|index out of (?:range|bounds)|out of bounds",
                "The code asked for an item beyond the end of a list",
                "A list or array was accessed at a position that does not exist.",
                "It asked for position {1} in a list of only {2} items.",
                indexCauses(), indexSteps());
        add("Data", "JsonParse|JSONDecode|JsonException|JsonProcessing|MismatchedInput|JsonReaderException|InvalidJson|SyntaxError",
                "JSON|Unexpected token|Expecting value|Unexpected end|Unrecognized token",
                "Data that should be JSON could not be read",
                "The application received text it expected to be valid JSON, but the text was malformed, empty or "
                        + "something else entirely (often an HTML error page from another server).",
                null,
                List.of("A client sent a malformed request body",
                        "Another service returned an error page or an empty response instead of JSON",
                        "The data was truncated in transit or by a size limit"),
                List.of("Find out where the data came from: an incoming request or a call to another service",
                        "If it is another service, check that service's status at that time",
                        "If it is a client request, it is usually the client's bug and a 400 response is correct"));
        add("Data", null, "unexpected end of JSON input|invalid character .* looking for beginning of value|Unexpected token .* in JSON",
                "Data that should be JSON could not be read",
                "The text received was malformed, empty or not JSON at all.",
                null,
                List.of("A malformed request body", "Another service returned an error page or empty response"),
                List.of("Find where the data came from and check that source"));
        add("Data", "NumberFormat|FormatException|InvalidCast|ClassCast|ValueError|InputMismatch|DateTimeParse|ParseException|MismatchedInput|InvalidFormat", null,
                "A value had the wrong type or format",
                "The code tried to convert or interpret a value (for example text into a number or date) and the "
                        + "value was not in a form it can accept.",
                null,
                List.of("User or partner input in an unexpected format", "Empty text where a number or date was required",
                        "Locale differences such as decimal commas or date order", "A data model change that old data does not match"),
                List.of("Find the offending value in the message or nearby log lines",
                        "Check which input field or upstream system produced it"));
        add("Validation", "Validation|ConstraintViolation|InvalidArgument|IllegalArgument|ArgumentException|BadRequest",
                null,
                "The input was rejected as invalid",
                "The application checked a value against its rules and refused it. This usually means the code is "
                        + "working as designed and the data it was given is wrong.",
                null,
                List.of("A user or calling system sent a value outside the allowed range or format",
                        "A required field was missing", "The rules changed and callers have not been updated"),
                List.of("Read the message for which field and rule failed",
                        "Identify who sent the request (trace ID, user, calling service) and tell them what was wrong",
                        "If valid values are being rejected, report the rule to the developers"));
        add("Code defect", "ZeroDivision|DivideByZero|ArithmeticException", null,
                "The code divided by zero",
                "A calculation used zero as the divisor. Usually a total or count that the code assumed could never be zero.",
                null,
                List.of("An empty set of items (zero quantity, zero records) in a ratio or average",
                        "Missing data defaulting to zero"),
                List.of("Find which quantity was zero for this request", "Report the case to developers so it can be handled"));

        // ---- state, concurrency, crashes ------------------------------------------------------------
        add("State", "IllegalState|InvalidOperation", null,
                "An operation was attempted at the wrong moment",
                "The application found itself in a state where the requested action is not allowed, for example "
                        + "something is already closed, not yet initialised, or its numbers do not add up. Developers "
                        + "raise this deliberately, so the message normally says exactly what was wrong.",
                null,
                List.of("Steps happening out of order or twice (double submit, retry, race between requests)",
                        "Data that breaks a business rule the code relies on",
                        "An earlier failure left something half-finished"),
                List.of("Read the message: it usually states the broken expectation",
                        "Look at the log lines before the error for an earlier failure on the same record or trace ID"));
        add("Concurrency", "ConcurrentModification|OptimisticLock|StaleObjectState|DbUpdateConcurrency|StaleElement", null,
                "Two operations changed the same data at the same time",
                "The data was modified by someone else between the moment this operation read it and the moment it "
                        + "tried to save, so the save was rejected to avoid overwriting the other change.",
                null,
                List.of("Two users or jobs editing the same record", "A retry running in parallel with the original request"),
                List.of("Retrying normally succeeds; check whether it was retried",
                        "If constant, find which two operations collide (same record ID in nearby log lines)"));
        add("Concurrency", null, "all goroutines are asleep|deadlock",
                "The program froze because its tasks were all waiting on each other",
                "Every running task was blocked waiting for another one, so nothing could make progress and the runtime stopped the program.",
                null,
                List.of("A locking or channel-ordering bug in the code"),
                List.of("This is a code defect: send the full stack dump to the developers", "Restart the service to restore it"));
        add("Crash", "SIGSEGV|SIGABRT|SIGBUS|AccessViolation", null,
                "The program crashed while accessing memory",
                "Native code touched memory it does not own (a segmentation fault). The operating system stopped the "
                        + "process immediately, so the whole program went down, not just one request.",
                null,
                List.of("Using a pointer that is null or already freed", "Writing past the end of a buffer",
                        "A faulty native library or driver"),
                List.of("The service needs a restart if it has not restarted itself",
                        "Send the stack frames and any core dump to the developers",
                        "Note what input was being processed when it crashed"));
        add("Crash", null, "segmentation fault|access violation|core dumped",
                "The program crashed while accessing memory",
                "Native code touched memory it does not own and the operating system stopped the process.",
                null,
                List.of("A null or dangling pointer", "A buffer overrun", "A faulty native library"),
                List.of("Restart the service if needed", "Send the stack frames and any core dump to the developers"));
        add("Crash", "^panic$", "called `(?:Option|Result)::unwrap\\(\\)` on (?:a|an) `(\\w+)` value|.*",
                "The program hit a condition it could not recover from (panic)",
                "A panic is a deliberate emergency stop: the code reached a situation its authors treated as "
                        + "impossible. Unless it is caught, the whole process or the current request ends.",
                "The code assumed a value was present, but it was `{1}`.",
                List.of("Unexpected or missing data reaching code that does not handle it",
                        "A bug such as using an uninitialised map or an invalid index"),
                List.of("Read the panic message, which states the immediate reason",
                        "Check whether the service restarted afterwards",
                        "Send the message and the pinpointed location to the developers"));

        // ---- files, permissions, configuration ------------------------------------------------------
        add("File system", "FileNotFound|NoSuchFile|DirectoryNotFound", "(?:Could not find file|No such file or directory:?|open) '?([^'\\n:]+)'?|.*",
                "A file the application needs is missing",
                "The application tried to open a file or folder at a path that does not exist.",
                "The path was `{1}`.",
                fileCauses(), fileSteps());
        add("File system", null, "No such file or directory|ENOENT|cannot find the (?:file|path) specified",
                "A file the application needs is missing",
                "The application tried to open a file or folder at a path that does not exist.",
                null, fileCauses(), fileSteps());
        add("Security", "AccessDenied|UnauthorizedAccess|PermissionError|Forbidden|SecurityException|AuthorizationException", null,
                "The application was not allowed to do something",
                "An operation was blocked by permissions: either the operating system refused access to a file or "
                        + "resource, or the user or service account lacks the required rights.",
                null, permissionCauses(), permissionSteps());
        add("Security", null, "permission denied|\\bEACCES\\b|\\bEPERM\\b|Access is denied|access denied|not authorized|403 Forbidden",
                "The application was not allowed to do something",
                "An operation was blocked by permissions.",
                null, permissionCauses(), permissionSteps());
        add("Security", "Authentication|Unauthorized|InvalidToken|TokenExpired|JwtException|BadCredentials|LoginException",
                null,
                "A login or credential check failed",
                "The application could not prove its identity (or a user's identity) to the system it was calling.",
                null,
                List.of("Wrong or expired password, API key or token", "A rotated secret that was not updated everywhere",
                        "Clock differences making a token look expired"),
                List.of("Check which credential was used and whether it recently changed or expired",
                        "If it affects one user only, it is probably that user's session or password"));
        add("Security", null, "401 Unauthorized|invalid (?:credentials|api key|token)|token (?:has )?expired|authentication failed",
                "A login or credential check failed",
                "The application could not prove its identity to the system it was calling.",
                null,
                List.of("Wrong or expired password, API key or token", "A secret that was rotated but not updated"),
                List.of("Check which credential was used and whether it recently changed or expired"));
        add("Deployment", "ClassNotFound|NoClassDefFound|NoSuchMethodError|ModuleNotFound|ImportError|TypeLoad|MissingMethod|FileLoadException|LoadError|UnsatisfiedLink",
                null,
                "A piece of code or a library is missing or the wrong version",
                "The application tried to load a class, module or function that is not present in what was deployed, "
                        + "or exists in an incompatible version. This is a packaging or deployment problem, not a data problem.",
                null,
                List.of("A dependency was not installed or not included in the build",
                        "Two versions of the same library conflict", "An incomplete or mixed-version deployment"),
                List.of("Check whether this started right after a deployment or upgrade",
                        "Note the missing name from the message and give it to the developers",
                        "Rolling back to the previous version normally restores service"));
        add("Deployment", null, "Cannot find module '([^']+)'|No module named '?([\\w.]+)'?|Could not load file or assembly",
                "A piece of code or a library is missing or the wrong version",
                "The application tried to load a module that is not present in what was deployed.",
                "The missing module was `{1}{2}`.",
                List.of("A dependency was not installed", "An incomplete deployment"),
                List.of("Check whether this started after a deployment", "Reinstall dependencies or roll back"));
        add("File system", "IOException|IOError|OSError", null,
                "Reading or writing data failed",
                "An input/output operation (a file, network stream or device) failed. The message says which resource and why.",
                null,
                List.of("The file, disk or network resource is unavailable", "Insufficient permissions or disk space",
                        "The other end of a connection closed it"),
                List.of("Read the message for the path or address involved", "Check that resource's availability and free space"));
        add("Network", null, "unreachable|service unavailable|503 Service|bad gateway|502 Bad|upstream .*(?:failed|error)",
                "A system this application depends on could not be reached",
                "The application tried to use another service and could not get through, even after any retries it made.",
                null,
                List.of("The dependent service is down or overloaded", "A network or firewall problem between the two systems",
                        "A wrong address in the configuration"),
                List.of("Identify the service named in the message and check its status at that time",
                        "Look for the same error across many requests, which confirms an outage rather than one bad request"));
        add("E-mail", "SMTP|MailSend|MessagingException|MailException", null,
                "An e-mail could not be sent",
                "The mail server refused or failed to accept a message the application tried to send.",
                null,
                List.of("An invalid or mistyped recipient address", "The mail server rejected the sender or the content",
                        "The mail server is unreachable or requires different credentials"),
                List.of("Read the server's reply code and text in the message (for example 5xx codes are permanent rejections)",
                        "Check the recipient address for obvious mistakes",
                        "If every e-mail fails, check the mail server settings and status"));
        add("Performance", null, "\\bslow\\b|latency|responded in \\d+ ?ms|took \\d+ ?ms|above threshold|exceeded (?:the )?(?:limit|threshold)|high (?:load|cpu|memory)",
                "Something was slower or heavier than it should be",
                "This is an early-warning message: the operation worked, but it took longer or used more than the "
                        + "limit the developers consider healthy. On its own it is not a failure.",
                null,
                List.of("The system being called is under load", "Unusually large or expensive requests",
                        "A resource (database, cache, network) is degrading"),
                List.of("Check whether these warnings come before the real errors in time: they often announce them",
                        "Note which dependency is named and watch whether the numbers are growing"));
        add("Unhandled error", "AggregateException|ExecutionException|InvocationTarget|UndeclaredThrowable|CompletionException|RuntimeException|ServletException",
                null,
                "A background or wrapped operation failed",
                "This error type is only an envelope: it means \"something inside failed\". The useful information is "
                        + "in the inner (root) cause.",
                null,
                List.of("Whatever the inner cause describes"),
                List.of("Focus on the deepest root cause shown above rather than on this wrapper",
                        "If no inner cause was logged, ask developers to log it"));
    }

    private static List<String> nullCauses() {
        return List.of("A record is missing an optional field that the code assumed is always filled in",
                "A lookup returned nothing (deleted, not yet created, or wrong ID) and the result was used anyway",
                "Another service returned a response with a different shape than expected",
                "A recent change introduced a new path where the value is not set");
    }

    private static List<String> nullSteps() {
        return List.of("Identify which record or request triggered it (trace ID, user or order number in nearby log lines)",
                "Check that record for missing data compared with one that works",
                "If it began after a release, tell the developers the pinpointed line: they will know which value was empty");
    }

    private static List<String> indexCauses() {
        return List.of("An empty or shorter-than-expected list (no results, no items, empty file)",
                "Input with fewer fields or columns than the code assumes",
                "An off-by-one mistake that only shows at a boundary");
    }

    private static List<String> indexSteps() {
        return List.of("Find what list was being read and why it was shorter than expected for this request",
                "Compare the failing input with one that works",
                "Report the input that triggers it to the developers");
    }

    private static List<String> fileCauses() {
        return List.of("The file was never deployed or was deleted or moved",
                "A wrong path in the configuration, or a path valid only in another environment",
                "The application runs from a different working directory than expected");
    }

    private static List<String> fileSteps() {
        return List.of("Check whether the path in the message exists on the server",
                "Compare with a working environment or the previous release",
                "Confirm the deployment completed and the configuration points to the right location");
    }

    private static List<String> permissionCauses() {
        return List.of("The service account lacks rights on a file, folder or resource",
                "The user's role does not include this action",
                "File ownership or permissions changed after a deployment");
    }

    private static List<String> permissionSteps() {
        return List.of("Identify which resource and which account are involved from the message",
                "Compare the account's permissions with what the operation needs",
                "Check whether permissions or roles changed recently");
    }
}
