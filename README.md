# LogReader

Desktop GUI and CLI for support engineers: open any application log and get the
deduplicated errors, each pinpointed to the exact file, function and line of its
root cause. Works on logs from Java/Kotlin/Scala, .NET, Python, Node.js, Go, PHP,
Ruby, Rust, C/C++ and on JSON-structured logs.

For every error it also explains, in plain language, what the error means, the
likely causes and what to check next, using a built-in offline knowledge base of
about 60 common failure types (database, network, memory, null references, bad
input, permissions, deployment problems, crashes and more).

## Build

Requires JDK 21+ and Maven.

```bash
mvn clean package
```

This runs the tests and produces one self-contained JAR: `target/LogReader.jar`.

## Run

```bash
java -jar target/LogReader.jar                        # GUI
java -jar target/LogReader.jar samples/node-express.log   # GUI, file opened
java -jar target/LogReader.jar analyze samples/java-spring-app.log
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

- **Pinpoint** = first application frame of the deepest root cause (`Caused by:`,
  .NET `--->`, JS `[cause]:`, the first traceback of a chained Python error). If the
  root cause has no application frame, the enclosing exception's frames are used; if
  the whole trace is library code, the top frame is shown and labelled as such.
- **Timestamps** are shown as written in the log (zone offsets are not applied).
  Epoch values are shown in UTC; syslog stamps without a year use the current year.
- **Filtering** searches the error type, messages, location, trace IDs and the stored
  sample text. Trace IDs are tracked for up to 500 occurrences per error.
- **Live tail** publishes an error once the next event starts or the writer pauses,
  so a stack trace still being written is never cut in half.
- Files are decoded as UTF-8. Lines longer than 64 KB are truncated.

To treat more packages as framework code, pass extra prefixes to
`new FrameClassifier(List.of("com.vendor."))`.

## Samples

`samples/` contains realistic logs for Spring Boot, ASP.NET Core, Django, Express,
mixed JSON loggers (logstash, pino, structlog, Serilog), and a Go service log with
raw PHP, Ruby, Rust and gdb crash output mixed in.
