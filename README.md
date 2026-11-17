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

The window is built for people who have never read a log. It has four parts:

- **Toolbar:** Open (or drop a file anywhere, Ctrl/Cmd+O), *Find a problem* (words or a request or trace ID, Ctrl/Cmd+F;
  advanced: text between slashes such as `/timeout|refused/` is a regular expression), All / Fatal / Errors /
  Warnings (one switch, with the number of problems of each), **Watch live** (follows a file that is
  still being written), **Quick actions**, **More** (copy a summary, save a report as Markdown or JSON,
  explain the words) and the light / dark switch.
- **Summary strip:** the file in numbers (errors, problems, lines, fatal, warnings, period), then key facts:
  health, first and last error, busiest minute, the most frequent problem (click to open it) and the probable
  incidents; and the chart **Problems over time**: errors and warnings stacked per slice of time, a scale on the
  left, times underneath and totals in the legend. Hover a bar to read its exact numbers in the chart's
  title; click a bar, or drag across several, to list only that period (the period chip in the toolbar goes back).
- **Overview of the whole log** (shown when a file opens, and with Esc or the **← Overview** button at the top of a problem): how
  serious it is, one paragraph on what went on, what happened in order, where to start (each with an
  **Open ›** link to its problem), at a glance the busiest minute, the kinds of problem and the files of the
  code involved, and **everything read from the log**: every entry counted by level, the period covered,
  stack traces, trace IDs, JSON entries and the language, each with a sentence on what it means.
- **Problem list:** one row per problem, saying what it means in plain words with the technical name
  and the place in the code underneath, how many times it happened, and a tag when it belongs to an incident: FIRST FAILURE,
  KNOCK-ON or WARNING SIGN (linked by timing and kind of problem, so a strong hint rather than proof).
- **What went wrong:** the problem in plain words, how it relates to its incident (with **Open ›** links),
  what to do, why it usually happens, and **everything the log says about it**: how serious, how often and
  when, where in the file, the error name and message, the underlying error, the chain of errors, where in
  the code it started, the requests affected and the occurrence shown, each with a sentence on what it means.
  *Show the path through the code* unfolds the code locations. **Copy for a ticket** copies a ready-to-paste summary; ◀ ▶ step
  through the times it happened.
- **What the log says:** *In plain words* tells the error and what was logged just before and after as short
  sentences, with the original text kept under each step. *All lines* shows every line as written, each
  labelled with the part it plays (STARTS HERE, ROOT CAUSE, YOUR CODE, LIBRARY, BEFORE, AFTER…); click a
  line for a sentence about it. *Lines around* sets how much of the log is read around each error.

*Clear recent files* is on the start screen, in **More** and in Quick actions.

The window can be made larger or smaller by dragging its edges, down to 720 × 520, or maximized. The layout
follows: the toolbar shortens its labels, the chart narrows, the facts wrap onto more lines, and in a narrow
window the problem list moves above the details. Dragged dividers keep their proportion while resizing.

**Quick actions** (the ⚡ button, or Cmd+K / Ctrl+K) finds any problem, by what it means or by its technical
name, any action, and recent files, in three sections. Type a few letters and press Enter.

Also: light and dark themes with neutral surfaces and bright, clearly different colours for meaning (violet
for fatal, red for errors, amber for warnings, blue for actions, teal chips for names of files and code in the
explanations; follows the macOS appearance at first start, then remembers your choice;
`-Dlogreader.theme=light|dark` forces one), compressed `.gz` logs (recognised by content, decompressed to a
private temporary file, up to 8 GB; not watched live), and settings remembered between launches (theme, view,
lines around, window position, last folder, up to 8 recent files) in the operating system's per-user preferences.

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

