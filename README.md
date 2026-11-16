# LogReader

Desktop GUI and CLI for support engineers: open any application log and get the
deduplicated errors, each pinpointed to the exact file, function and line of its
root cause. It works on the logs of any application, whatever language or
framework it was built with, in plain-text or JSON form.

For every error it also explains, in plain language, what the error means, the
likely causes and what to check next, using a built-in offline knowledge base of
over 50 rules covering common failure types (database, network, memory, null references, bad
input, permissions, deployment problems, crashes and more).

Full documentation, with diagrams of how it works, is in
[docs/LogReader-Documentation.pdf](docs/LogReader-Documentation.pdf).

## Build

Requires JDK 21+ and Maven.

```bash
mvn clean package
```

This runs the tests and produces one self-contained JAR: `target/LogReader.jar`.

## Run

```bash
java -jar target/LogReader.jar                        # GUI
java -jar target/LogReader.jar samples/api-gateway-cert-expiry.log   # GUI, file opened
java -jar target/LogReader.jar analyze samples/checkout-db-failover.log
java -jar target/LogReader.jar --help
```

Double-clicking the JAR also opens the GUI.

For a real desktop application named LogReader with its own icon and no Java
requirement, run `sh packaging/build-app.sh` (see `NOTES.md`, section 2).

### GUI

- **Open Log File**, or drag and drop a file anywhere on the window (Ctrl/Cmd+O).
- **Filter** by keyword, trace ID or regex (toggle `.*`), and by severity (Ctrl/Cmd+F).
- **Live Tail (-f)** re-analyzes the file as it grows; handles truncation and rotation.
- **Context ±** sets how many log lines are shown around the error.
- **Export Report** writes the filtered errors as Markdown or JSON.
- **Copy Error Summary** copies a ticket-ready summary of the selected error.
- **Problems over time** chart shows when errors and warnings clustered; hover a bar for counts.
- **What this means** in the spotlight explains the selected error, with likely causes and next steps.
  The same text goes into exported reports, the copied summary and the CLI output.
- **Command palette** (Ctrl/Cmd+K) searches every action and every error; type a few letters and press Enter.
- **Light and dark themes**: follows the macOS appearance at start, and the sun / moon button switches at any time.
  Start with `-Dlogreader.theme=light` or `dark` to force one.
- **For newcomers:** a plain-language overview of the whole file, a label beside every log line saying what part
  it plays (STARTS HERE, ROOT CAUSE, YOUR CODE, LIBRARY, BEFORE, AFTER), a sentence about any line you click,
  and a `?` button that explains the terms.
- The table sorts on any column. ◀ ▶ in the spotlight step through occurrences.

### CLI

```bash
java -jar target/LogReader.jar analyze app.log --min-level ERROR --top 5 --context 5
java -jar target/LogReader.jar analyze app.log --grep c90b12aa3e7745f1
java -jar target/LogReader.jar analyze app.log --format json -o report.json
java -jar target/LogReader.jar analyze app.log --follow
```

## How it works

| Package | Role |
|---|---|
| `parser` | `LogParser` groups physical lines into events (timestamps, levels, multi-line traces, JSON records). `StackTraceParser` builds the exception chain and picks the root cause; `FrameExtractor` implementations recognise each language's frame syntax; `FrameClassifier` separates application code from framework code. |
| `analysis` | `LogAnalyzer` deduplicates errors (same type at the same pinpointed location) and keeps metrics. `ErrorFilter` implements the search bar. |
| `io` | `LogSession` streams the file, supports tailing, and serves line ranges through a sparse `LineIndex`. |
| `explain` | `ErrorExplainer` matches the root cause against the knowledge base and writes the plain-language `Explanation`. Add a rule with one `add(...)` call in `registerRules()`. |
| `report` | Markdown / JSON reports and clipboard summaries. |
| `gui`, `cli` | The Swing + FlatLaf workspace and the terminal front end. |

Files are read as a stream; only error samples (25 per error, 600 lines each) and
one byte offset per 256 lines are kept in memory, so file size is not a limit.

### Behaviours worth knowing

- **Pinpoint** = first application frame of the deepest root cause (the innermost
  nested cause in a chained error, however the log marks nesting). If the
  root cause has no application frame, the enclosing exception's frames are used; if
  the whole trace is library code, the top frame is shown and labelled as such.
- **Timestamps** are shown as written in the log (zone offsets are not applied).
  Epoch values are shown in UTC; syslog stamps without a year use the current year.
- **Filtering** searches the error type, messages, location, trace IDs and the stored
  sample text. Trace IDs are tracked for up to 500 occurrences per error.
- **Live tail** publishes an error once the next event starts or the writer pauses,
  so a stack trace still being written is never cut in half.
- **Untrusted input.** Log text is treated as hostile: control and terminal escape characters are
  neutralised on reading, log content is escaped in Markdown reports, and the number of distinct
  errors listed is capped at 10,000 (everything is still counted).
- `--output` never replaces the log being analyzed, and replaces another existing file only with `--force`.
- Files are decoded as UTF-8. Lines longer than 64 KB are truncated.

To treat more packages as framework code, pass extra prefixes to
`new FrameClassifier(List.of("com.vendor."))`.

## Samples

`samples/` contains five production-incident logs, each a few thousand lines of
normal traffic with a cascading failure inside it:

| File | Incident |
|---|---|
| `checkout-db-failover.log` | A database failover: killed connections, pool exhaustion, a circuit breaker opening, cache timeouts, plus an unrelated bug hidden in the noise |
| `api-gateway-cert-expiry.log` | JSON log of a gateway whose upstream certificate expires mid-run, with rate limiting and a crash from too many open files |
| `payments-worker-deadlocks.log` | Background workers deadlocking on the database during a month-end run, with bank timeouts and a missing currency |
| `orders-api-threadpool-starvation.log` | A deployment that causes timeouts, deadlock victims and thread-pool starvation until it is rolled back |
| `ingest-crash-loop.log` | A service crash-looping on one bad message while its queue backs up |

The small logs used by the automated tests are in `src/test/resources/fixtures/`.

