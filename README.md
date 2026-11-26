# CausVane

Desktop application for support engineers: open any application log and get the
deduplicated errors, each pinpointed to the exact file, function and line of its
root cause. It works on the logs of any application, whatever language or
framework it was built with, in plain-text or JSON form.

For every error it also explains, in plain language, what the error means, the
likely causes and what to check next, using a built-in offline knowledge base of
over 50 rules covering common failure types (database, network, memory, null references, bad
input, permissions, deployment problems, crashes and more).

It also recognises what kind of application wrote the log ("Looks like: a Java Spring Boot service using
PostgreSQL, HikariCP and Kafka") and adds advice for those technologies, can show more or less technical
detail (Simple, Normal, Expert), looks a problem up on the web without sending anything identifying, and
turns the log into six slides for a meeting.

Full documentation, with diagrams of how it works, is in
[docs/CausVane-Documentation.pdf](docs/CausVane-Documentation.pdf).

## Install

Download CausVane from the [latest release](https://github.com/AhmedAbdelaty22/CausVane/releases/latest)
and install it like any other program:

- **Windows:** `CausVane-Windows-Setup.exe`, a setup program (no administrator rights needed; Start menu,
  desktop shortcut, uninstall from Installed apps).
- **macOS:** `CausVane-macOS.dmg`; drag CausVane onto Applications.
- **Linux:** `CausVane-Linux.deb` (Ubuntu, Debian) or `CausVane-Linux.rpm` (Fedora, RHEL, openSUSE); open it
  in your software installer.

Portable versions that run without installing are there too. Each download includes its own Java, so
there is nothing else to install; `NOTES.md` has the steps for each system.

## Develop

Open the project in IntelliJ IDEA (JDK 21 or newer) and run the **CausVane** run configuration (main class
`io.causvane.Main`). The window opens straight from the compiled code; nothing is installed. Maven's
`package` phase runs the tests and builds `target/CausVane.jar`, from which GitHub builds the desktop apps
when a release is published, together with the installers.

## The window

The window is built for people who have never read a log. Above it is a menu bar (on macOS, the bar at the
top of the screen): **File** (open, paste log text, open recent, close the log, watch live, save a report), **Edit** (copy for a ticket, find a
problem), **View** (the overview, which problems to show, the log view, the detail level, the theme, Quick actions,
Meeting Mode and saving the slides as PDF) and **Help** (the words explained, search the web for a problem, keyboard shortcuts, documentation, what's new, check for updates, report a problem,
About CausVane). The window itself has four parts:

- **Toolbar:** the open log's name with **× Close** (open a log from the start screen, File → Open or by
  dropping it anywhere, Ctrl/Cmd+O), *Find a problem* (words or a request or trace ID, Ctrl/Cmd+F;
  advanced: text between slashes such as `/timeout|refused/` is a regular expression), All / Fatal / Errors /
  Warnings (one switch, with the number of problems of each), **Simple · Normal · Expert** (how much technical
  detail to show, see below), **Watch live** (follows a file that is
  still being written), **Quick actions**, **More** (copy a summary, save a report as Markdown or JSON,
  explain the words) and the light / dark switch.
- **Summary strip:** the file in numbers (errors, problems, lines, fatal, warnings, period), then key facts:
  health, first and last error, busiest minute, the probable incidents, and the most frequent problem with
  how often it happened, its share of all errors, when it ran, its kind, where in the code, and whether it is only
  a knock-on effect of another failure; and the chart **Problems over time**: errors and warnings stacked per slice of time, a scale on the
  left, times underneath and totals in the legend. Hover a bar to read its exact numbers in the chart's
  title; click a bar, or drag across several, to list only that period (the period chip in the toolbar goes back).
- **Overview of the whole log** (shown when a file opens, and with Esc or the **← Overview** button at the top of a problem): how
  serious it is, one paragraph on what went on, **Looks like** (highlighted in a box): what kind of application wrote the log, what happened in order, where to start (each with an
  **Open ›** link to its problem), a **map of all problems** (a folding tree: each probable incident as a
  branch with its first failure, warning signs and knock-on effects, and every other problem under its kind), at a glance the busiest minute, the kinds of problem and the files of the
  code involved, and **everything read from the log**: every entry counted by level, the period covered,
  stack traces, trace IDs, JSON entries, the language and the technologies mentioned, each with a sentence on what it means.
- **Problem list:** one row per problem, saying what it means in plain words with the technical name
  and the place in the code underneath, how many times it happened, and a tag when it belongs to an incident: FIRST FAILURE,
  KNOCK-ON or WARNING SIGN (linked by timing and kind of problem, so a strong hint rather than proof).
- **What went wrong:** the problem in plain words; when it belongs to an incident, **the whole incident** as one
  timeline (warning signs, the first failure and the knock-on effects in the order they began, this problem
  marked *you are here*, each step with an **Open ›** link);
  what to do, steps **for the technology this app uses** (for example the HikariCP pool settings, or where
  PostgreSQL shows blocked sessions), why it usually happens, and **everything the log says about it**: how serious, how often and
  when, where in the file, the error name and message, the underlying error, the chain of errors, where in
  the code it started, the requests affected and the occurrence shown, each with a sentence on what it means.
  When the error came with a stack trace, **the error, step by step** shows it as a folding tree: what was
  reported, each error that caused the one above it down to the root cause, and under it how the code got
  there, oldest call first, ending at *started here*; runs of library code fold into one line named after the
  library. Works the same for Java, .NET, Python, Node.js, Go and the rest; an error logged as a single line
  without a stack trace shows no tree. **Search the web** looks it up online (see below); **Copy for a ticket** copies a ready-to-paste summary; ◀ ▶ step
  through the times it happened.
- **What the log says:** *In plain words* tells the error and what was logged just before and after as short
  sentences, with the original text kept under each step. *All lines* shows every line as written, each
  labelled with the part it plays (STARTS HERE, ROOT CAUSE, YOUR CODE, LIBRARY, BEFORE, AFTER…); click a
  line for a sentence about it. *Lines around* sets how much of the log is read around each error.

**Paste instead of a file.** A log copied from a chat, a ticket or a terminal can be pasted with
**File → Paste Log Text** (Cmd+Shift+V / Ctrl+Shift+V), the **Paste Log Text** button on the start screen, or
Quick actions; opening a file works as before. A window opens with an empty box: paste the lines, check or trim
them, then click **Analyze** (Cmd+Enter / Ctrl+Enter); it shows how many lines were pasted. The text is kept in a private temporary file that only your
user account can read, deleted when another log is opened or CausVane quits.

**Detail: Simple · Normal · Expert** (in the toolbar and under **View → Detail**, remembered between launches).
*Simple* uses plain words only: no error names, places in the code or step-by-step tree, for someone who only
needs to know what went wrong and what to do. *Normal* puts plain words first with the technical details
underneath. *Expert* puts the technical details first: the full error name and the error tree with every step open.

**What kind of application is this?** CausVane recognises the language, framework, database, connection
pool, message broker, cache and platform from the log itself (package names in stack traces and logger
names, start-up lines and messages), entirely offline, and shows it in the overview as one line, for example
*Looks like: a .NET ASP.NET Core service using SQL Server and Entity Framework, running on Kubernetes*. The
advice for each problem then adds steps for those technologies.

**Search the web, safely.** **Search the web** on a problem (also **Help → Search the Web for This Problem**)
builds a search from the error's name and the general words of its message, with quoted values, addresses,
host names, paths, e-mail addresses, IDs, numbers, tokens and internal names taken out. A preview shows exactly
what will be sent and what was removed; it can be edited, and the search site chosen (Google, Bing,
DuckDuckGo or Stack Overflow, remembered). Nothing leaves the computer until **Search** is clicked.

**Meeting Mode** (**View → Meeting Mode**, Cmd+Shift+M / Ctrl+Shift+M) shows the log as six full-screen
slides with large text: what happened, when, the impact, the likely cause, its knock-on effects and what to do
next. The arrow keys move between slides and Esc leaves; the slides follow the theme and the detail level.
**Save as PDF** (on the slides, or **View → Save Meeting Slides as PDF…**) writes them as a PDF to send round.

**Closing a log.** While a log is open, the toolbar shows its name and a **× Close** button (also **File → Close Log**, Cmd+W / Ctrl+W, and Quick actions). Closing stops watching, releases the file and goes back to the start screen, where the recent files are listed again; a pasted log's private copy is deleted at once. To open another log, use the start screen, **File → Open** (Cmd+O / Ctrl+O), **Open Recent**, or drop a file on the window.

*Clear recent files* is on the start screen, in **More** and in Quick actions. Every button has the same rounded look, tinted by what it does: blue to bring a log in or run the main action (Open Log File, Paste Log Text, Analyze), red to close or clear, green to share or save (Copy for a ticket, More, Save as PDF), indigo for the web (Search the web), amber for Watch live, and grey to move around or cancel.

The window can be made larger or smaller by dragging its edges, down to 720 × 520, or maximized. The layout
follows: the toolbar shortens its labels, the chart narrows, the facts wrap onto more lines, and in a narrow
window the problem list moves above the details. Dragged dividers keep their proportion while resizing.

**Quick actions** (the ⚡ button, or Cmd+K / Ctrl+K) finds any problem, by what it means or by its technical
name, any action, and recent files, in three sections. Type a few letters and press Enter.

Also: light and dark themes with neutral surfaces and bright, clearly different colours for meaning (violet
for fatal, red for errors, amber for warnings, blue for actions, teal chips for names of files and code in the
explanations; the light / dark switch changes only the colours, instantly and in the same window:
the open file, search, selection, scroll positions, dividers, folded sections and keyboard focus
all stay as they were; follows the macOS appearance at first start, then remembers your choice), compressed `.gz` logs (recognised by content, decompressed to a
private temporary file, up to 8 GB; not watched live), and settings remembered between launches (theme, view, detail level, search site,
lines around, window position, last folder, up to 8 recent files) in the operating system's per-user preferences.

## How it works

| Package | Role |
|---|---|
| `parser` | `LogParser` groups physical lines into events (timestamps, levels, multi-line traces, JSON records). `StackTraceParser` builds the exception chain and picks the root cause; `FrameExtractor` implementations recognise each language's frame syntax; `FrameClassifier` separates application code from framework code. |
| `analysis` | `LogAnalyzer` deduplicates errors (same type at the same pinpointed location) and keeps metrics. `ErrorFilter` implements the search bar. |
| `io` | `LogSession` streams the file, supports tailing, and serves line ranges through a sparse `LineIndex`. |
| `explain` | `ErrorExplainer` matches the root cause against the knowledge base and writes the plain-language `Explanation`. Add a rule with one `add(...)` call in `registerRules()`. |
| `report` | Markdown / JSON reports and clipboard summaries. |
| `gui` | The Swing + FlatLaf window. |

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
- Saving a report never replaces the log being analyzed, and the window asks before replacing another file.
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

