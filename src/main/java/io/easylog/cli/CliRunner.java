package io.easylog.cli;

import io.easylog.analysis.ErrorFilter;
import io.easylog.analysis.ErrorFilter.TextMatcher;
import io.easylog.explain.ErrorExplainer;
import io.easylog.explain.IncidentLinker;
import io.easylog.explain.LogOverview;
import io.easylog.explain.Explanation;
import io.easylog.io.ContextBuilder;
import io.easylog.io.ContextBuilder.ContextLine;
import io.easylog.io.LogSession;
import io.easylog.io.TextSanitizer;
import io.easylog.model.AnalysisResult;
import io.easylog.model.ErrorGroup;
import io.easylog.model.Severity;
import io.easylog.model.StackFrame;
import io.easylog.report.Formats;
import io.easylog.report.ReportWriter;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.PatternSyntaxException;

/** Terminal front end: {@code EasyLog analyze <file> [options]}. */
public final class CliRunner {

    public static final String USAGE = """
            EasyLog - pinpoint root-cause errors in application logs

            Usage:
              EasyLog                          launch the desktop GUI
              EasyLog <file>                   launch the GUI and open <file>
              EasyLog analyze <file> [options] analyze <file> in the terminal
              EasyLog self-test <file>          open the window, load <file>, verify it works, exit

            Options for analyze:
              -l, --min-level <WARN|ERROR|FATAL>  lowest severity to report (default WARN)
              -g, --grep <text>                   keep errors containing every word (keyword, trace id)
              -e, --regex <pattern>               keep errors matching a regular expression
              -n, --top <N>                       number of errors to detail (default 10)
              -C, --context <N>                   log lines shown before/after each error (default 3)
                  --format <text|markdown|json>   output format (default text)
              -o, --output <file>                 write the report to a file instead of stdout
                  --force                         allow --output to replace an existing file
              -f, --follow                        keep watching the file and print new errors (like tail -f)
                  --no-color                      disable ANSI colors
              -h, --help                          show this help
              -V, --version                       show the version
            """;

    private static final int EXIT_OK = 0;
    private static final int EXIT_FAILURE = 1;
    private static final int EXIT_USAGE = 2;

    private final PrintStream out;
    private final PrintStream err;

    private Severity minLevel = Severity.WARN;
    private String query;
    private boolean regex;
    private int top = 10;
    private int context = 3;
    private String format = "text";
    private Path output;
    private boolean follow;
    private boolean force;
    private boolean color = System.console() != null && System.getenv("NO_COLOR") == null;
    private Path file;

    public CliRunner() {
        this(System.out, System.err);
    }

    public CliRunner(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    /** Runs the {@code analyze} command with the given arguments and returns the process exit code. */
    public int run(String[] args) {
        try {
            parseArguments(args);
        } catch (IllegalArgumentException e) {
            err.println("EasyLog: " + e.getMessage());
            err.println("Try 'EasyLog --help'.");
            return EXIT_USAGE;
        }
        if (!Files.isRegularFile(file)) {
            err.println("EasyLog: no such file: " + file);
            return EXIT_FAILURE;
        }
        ErrorFilter filter;
        try {
            filter = new ErrorFilter(TextMatcher.of(query, regex), null);
        } catch (PatternSyntaxException e) {
            err.println("EasyLog: invalid regular expression: " + e.getDescription());
            return EXIT_USAGE;
        }
        try {
            if (output != null && Files.exists(output)) {
                if (Files.isSameFile(output, file)) {
                    err.println("EasyLog: --output points at the log file being analyzed; refusing to overwrite it");
                    return EXIT_USAGE;
                }
                if (!force) {
                    err.println("EasyLog: " + output + " already exists; add --force to replace it");
                    return EXIT_USAGE;
                }
            }
            LogSession session = new LogSession(file);
            AnalysisResult result = session.load(null);
            List<ErrorGroup> groups = select(result, filter);
            String text = render(session, result, groups);
            if (output != null) {
                Files.writeString(output, text, StandardCharsets.UTF_8);
                err.println("Report written to " + output.toAbsolutePath());
            } else {
                out.print(forConsole(text, out.charset()));
                out.flush();
            }
            if (follow) {
                follow(session, result, filter);
            }
            return EXIT_OK;
        } catch (IOException e) {
            err.println("EasyLog: " + e.getMessage());
            return EXIT_FAILURE;
        }
    }

    private void parseArguments(String[] args) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "-l", "--min-level" -> {
                    minLevel = Severity.fromToken(value(args, ++i, arg));
                    if (!minLevel.isAtLeast(Severity.WARN)) {
                        throw new IllegalArgumentException("--min-level must be WARN, ERROR or FATAL");
                    }
                }
                case "-g", "--grep" -> {
                    query = value(args, ++i, arg);
                    regex = false;
                }
                case "-e", "--regex" -> {
                    query = value(args, ++i, arg);
                    regex = true;
                }
                case "-n", "--top" -> top = number(value(args, ++i, arg), arg);
                case "-C", "--context" -> context = number(value(args, ++i, arg), arg);
                case "--format" -> {
                    format = value(args, ++i, arg).toLowerCase(Locale.ROOT);
                    if (!List.of("text", "markdown", "md", "json").contains(format)) {
                        throw new IllegalArgumentException("unknown format: " + format);
                    }
                }
                case "-o", "--output" -> output = Path.of(value(args, ++i, arg));
                case "-f", "--follow" -> follow = true;
                case "--force" -> force = true;
                case "--no-color" -> color = false;
                default -> {
                    if (arg.startsWith("-") && arg.length() > 1) {
                        throw new IllegalArgumentException("unknown option: " + arg);
                    }
                    if (file != null) {
                        throw new IllegalArgumentException("only one log file can be analyzed at a time");
                    }
                    file = Path.of(arg);
                }
            }
        }
        if (file == null) {
            throw new IllegalArgumentException("missing log file");
        }
        if (output != null) {
            color = false;
        }
    }

    private static String value(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("option " + option + " needs a value");
        }
        return args[index];
    }

    private static int number(String text, String option) {
        try {
            int n = Integer.parseInt(text);
            if (n < 0) {
                throw new NumberFormatException();
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("option " + option + " needs a non-negative number, got: " + text);
        }
    }

    private List<ErrorGroup> select(AnalysisResult result, ErrorFilter filter) {
        return result.groups().stream()
                .filter(g -> g.severity().isAtLeast(minLevel))
                .filter(filter)
                .toList();
    }

    private String render(LogSession session, AnalysisResult result, List<ErrorGroup> groups) throws IOException {
        StringBuilder sb = new StringBuilder();
        switch (format) {
            case "json" -> ReportWriter.Format.JSON.writer().write(result, groups, sb);
            case "markdown", "md" -> ReportWriter.Format.MARKDOWN.writer().write(result, groups, sb);
            default -> renderText(sb, session, result, groups);
        }
        return sb.toString();
    }

    private void renderText(StringBuilder sb, LogSession session, AnalysisResult result, List<ErrorGroup> groups)
            throws IOException {
        sb.append(paint(BOLD, "EasyLog")).append("  ").append(TextSanitizer.clean(result.file().toString())).append('\n');
        sb.append("Lines scanned: ").append(paint(BOLD, Formats.count(result.totalLines())))
                .append("   Errors: ").append(paint(RED, Formats.count(result.errorCount())))
                .append("   Unique: ").append(paint(BOLD, String.valueOf(result.uniqueErrorTypes())))
                .append("   Warnings: ").append(paint(YELLOW, Formats.count(result.warningCount())))
                .append("   Time span: ").append(Formats.duration(result.timeSpan()))
                .append(" (").append(Formats.timestamp(result.firstTimestamp())).append(" → ")
                .append(Formats.timestamp(result.lastTimestamp())).append(")\n\n");
        if (!result.groups().isEmpty()) {
            LogOverview.Overview overview = LogOverview.of(result);
            sb.append(paint(overview.health() == LogOverview.Health.SERIOUS ? RED : YELLOW, overview.health().label() + "."))
                    .append('\n');
            wrap(sb, overview.verdict(), "");
            sb.append('\n').append(paint(BOLD, "What happened, in order")).append('\n');
            for (LogOverview.Moment m : overview.timeline()) {
                sb.append(String.format(Locale.ROOT, "  %-8s %-9s ", m.start() == null ? "" :
                                java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT).format(m.start()), m.kind()))
                        .append(Formats.truncate(m.text(), 110)).append('\n');
            }
            if (overview.hiddenMoments() > 0) {
                sb.append(paint(DIM, "  and " + overview.hiddenMoments() + " smaller problems below")).append('\n');
            }
            sb.append('\n').append(paint(BOLD, "Where to start")).append('\n');
            int k = 0;
            for (ErrorGroup g : overview.startWith()) {
                sb.append("  ").append(++k).append(". ").append(brief(g)).append('\n');
            }
            sb.append('\n');
        }
        sb.append(paint(BOLD, "What the log contains")).append(paint(DIM, "  (--format markdown explains each fact)")).append('\n');
        for (io.easylog.explain.LogFacts.Fact fact : io.easylog.explain.LogFacts.forLog(result,
                io.easylog.explain.IncidentLinker.find(result.groups()).size())) {
            if (fact.label().equals("File")) {
                continue; // already in the first line
            }
            sb.append(String.format(Locale.ROOT, "  %-19s ", fact.label())).append(TextSanitizer.clean(fact.value())).append('\n');
        }
        sb.append('\n');
        if (result.unlistedOccurrences() > 0) {
            sb.append(paint(YELLOW, "Note: " + Formats.count(result.unlistedOccurrences())
                    + " further occurrences are counted above but not listed: the log has more distinct errors than the limit of "
                    + Formats.count(io.easylog.analysis.LogAnalyzer.MAX_GROUPS) + ".")).append("\n\n");
        }
        if (groups.isEmpty()) {
            sb.append("No matching errors found.\n");
            return;
        }
        sb.append(paint(DIM, String.format(Locale.ROOT, "%3s  %-5s %7s  %-34s %-32s %-23s %s\n",
                "#", "SEV", "COUNT", "ERROR TYPE", "LOCATION", "FIRST SEEN", "LAST SEEN")));
        int n = 0;
        for (ErrorGroup g : groups) {
            sb.append(String.format(Locale.ROOT, "%3d  ", ++n))
                    .append(paint(severityColor(g.severity()), String.format(Locale.ROOT, "%-5s", g.severity())))
                    .append(String.format(Locale.ROOT, " %7s  %-34s ", Formats.count(g.count()), Formats.truncate(g.displayType(), 34)))
                    .append(paint(ORANGE, String.format(Locale.ROOT, "%-32s", Formats.truncate(g.location(), 32))))
                    .append(String.format(Locale.ROOT, " %-23s %s\n", Formats.timestamp(g.firstSeen()), Formats.timestamp(g.lastSeen())));
        }
        List<IncidentLinker.Incident> incidents = IncidentLinker.find(result.groups());
        if (!incidents.isEmpty()) {
            sb.append('\n').append(paint(BOLD, "Probable incidents")).append(paint(DIM,
                    "  (linked by timing and kind of problem: a strong hint, not proof)")).append('\n');
            int k = 0;
            for (IncidentLinker.Incident incident : incidents) {
                ErrorGroup lead = incident.lead();
                sb.append(String.format(Locale.ROOT, "%3d. ", ++k)).append(paint(RED, "First failure  "))
                        .append(Formats.timestamp(lead.firstSeen())).append("  ").append(brief(lead)).append('\n');
                for (ErrorGroup w : incident.earlyWarnings()) {
                    sb.append("       ").append(paint(YELLOW, "warning sign   ")).append(String.format(Locale.ROOT, "%-10s",
                            "-" + IncidentLinker.gap(w.firstSeen(), lead.firstSeen()))).append(brief(w)).append('\n');
                }
                for (ErrorGroup f : incident.followers()) {
                    sb.append("       ").append(paint(DIM, "knock-on       ")).append(String.format(Locale.ROOT, "%-10s",
                            "+" + IncidentLinker.gap(lead.firstSeen(), f.firstSeen()))).append(brief(f)).append('\n');
                }
            }
        }
        n = 0;
        for (ErrorGroup g : groups) {
            if (++n > top) {
                if (top > 0) sb.append('\n').append(paint(DIM, "… " + (groups.size() - top) + " more errors (raise --top to see them)")).append('\n');
                break;
            }
            sb.append('\n').append(paint(BOLD, "── " + n + ". [" + g.severity() + "] " + g.displayType() + " ×" + g.count() + " ──")).append('\n');
            StackFrame pin = g.pinpoint();
            if (pin != null) {
                sb.append("   Pinpoint  : ").append(paint(ORANGE, pin.location()));
                if (pin.qualifiedFunction() != null) {
                    sb.append("  in ").append(paint(ORANGE, pin.qualifiedFunction()));
                }
                sb.append(pin.application() ? "  [application code]" : "  [framework/library code]").append('\n');
            }
            if (g.message() != null && !g.message().isBlank()) {
                sb.append("   Message   : ").append(Formats.truncate(g.message(), 200)).append('\n');
            }
            if (g.rootCauseType() != null) {
                sb.append("   Root cause: ").append(paint(RED, g.rootCauseType()));
                if (g.rootCauseMessage() != null) {
                    sb.append(": ").append(Formats.truncate(g.rootCauseMessage(), 200));
                }
                sb.append('\n');
            }
            if (g.chain().size() > 1) {
                sb.append("   Chain     : ").append(String.join(" → ", g.chain())).append('\n');
            }
            if (!g.traceIds().isEmpty()) {
                sb.append("   Trace IDs : ").append(String.join(", ", g.traceIds().stream().limit(5).toList())).append('\n');
            }
            Explanation explanation = ErrorExplainer.standard().explain(g);
            sb.append('\n').append("   ").append(paint(BOLD, "What this means: " + explanation.title()))
                    .append(paint(DIM, "  [" + explanation.category() + "]")).append('\n');
            wrap(sb, explanation.meaning() + (explanation.detail() == null ? "" : " " + explanation.detail()), "   ");
            for (String line : explanation.context()) {
                wrap(sb, line.replace("`", ""), "   ");
            }
            sb.append("   ").append(paint(BOLD, "Likely causes")).append('\n');
            for (String cause : explanation.likelyCauses()) {
                wrap(sb, "- " + cause.replace("`", ""), "     ");
            }
            sb.append("   ").append(paint(BOLD, "What to check next")).append('\n');
            int step = 0;
            for (String next : explanation.nextSteps()) {
                wrap(sb, ++step + ". " + next.replace("`", ""), "     ");
            }
            sb.append('\n');
            if (!g.samples().isEmpty()) {
                sb.append("   Context (log line ").append(g.firstLine()).append("):\n");
                for (ContextLine line : ContextBuilder.build(session, g.samples().get(0), context, context)) {
                    String number = line.lineNumber() > 0 ? String.valueOf(line.lineNumber()) : "";
                    String text = Formats.truncate(line.text(), 220);
                    switch (line.kind()) {
                        case PINPOINT -> sb.append(paint(RED_BG, String.format(Locale.ROOT, " >>> %7s │ %s", number, text))).append('\n');
                        case EVENT -> sb.append(String.format(Locale.ROOT, "     %7s │ %s\n", number, text));
                        default -> sb.append(paint(DIM, String.format(Locale.ROOT, "     %7s │ %s", number, text))).append('\n');
                    }
                }
            }
        }
    }

    /**
     * Replaces box-drawing and typographic characters with ASCII when the terminal's encoding cannot show
     * them (for example the legacy Windows console code pages), instead of printing question marks.
     */
    public static String forConsole(String text, Charset consoleCharset) {
        CharsetEncoder encoder = consoleCharset.newEncoder();
        if (encoder.canEncode("│─→×…–·—")) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '│' -> sb.append('|');
                case '─', '–', '—' -> sb.append('-');
                case '→' -> sb.append("->");
                case '×' -> sb.append('x');
                case '…' -> sb.append("...");
                case '·' -> sb.append('.');
                default -> sb.append(c < 128 || encoder.canEncode(c) ? c : '?');
            }
        }
        return sb.toString();
    }

    private static String brief(ErrorGroup g) {
        String meaning = ErrorExplainer.standard().explain(g).title().replace("`", "");
        return Formats.truncate(meaning, 70) + "  (" + Formats.truncate(g.displayType(), 34)
                + (g.pinpoint() != null ? " at " + g.location() : "") + ", ×" + Formats.count(g.count()) + ")";
    }

    /** Appends text word-wrapped to about 100 columns, each line starting with the indent. */
    private static void wrap(StringBuilder sb, String text, String indent) {
        int column = 0;
        sb.append(indent);
        for (String word : text.split(" ")) {
            if (column > 0 && column + word.length() + 1 > 100 - indent.length()) {
                sb.append('\n').append(indent);
                column = 0;
            }
            if (column > 0) {
                sb.append(' ');
                column++;
            }
            sb.append(word);
            column += word.length();
        }
        sb.append('\n');
    }

    /** Polls the file and prints every error whose count grew, until interrupted. */
    private void follow(LogSession session, AnalysisResult initial, ErrorFilter filter) throws IOException {
        Map<String, Long> seen = new HashMap<>();
        initial.groups().forEach(g -> seen.put(g.id(), g.count()));
        err.println("Following " + file + " (Ctrl+C to stop)…");
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }
            Optional<AnalysisResult> update = session.poll();
            if (update.isEmpty()) {
                continue;
            }
            for (ErrorGroup g : select(update.get(), filter)) {
                long previous = seen.getOrDefault(g.id(), 0L);
                if (g.count() > previous) {
                    out.println(forConsole(paint(DIM, Formats.timestamp(g.lastSeen())) + "  "
                            + paint(severityColor(g.severity()), String.format(Locale.ROOT, "%-5s", g.severity())) + " "
                            + g.displayType() + " at " + paint(ORANGE, g.location())
                            + "  (+" + (g.count() - previous) + ", total " + g.count() + ", log line " + g.lastLine() + ")",
                            out.charset()));
                }
            }
            update.get().groups().forEach(g -> seen.put(g.id(), g.count()));
            out.flush();
        }
    }

    private static final String BOLD = "1";
    private static final String DIM = "2";
    private static final String RED = "1;31";
    private static final String YELLOW = "33";
    private static final String ORANGE = "38;5;214";
    private static final String RED_BG = "1;97;41";

    private static String severityColor(Severity severity) {
        return switch (severity) {
            case FATAL -> "1;35";
            case ERROR -> RED;
            case WARN -> YELLOW;
            default -> DIM;
        };
    }

    private String paint(String code, String text) {
        return color ? "\u001B[" + code + "m" + text + "\u001B[0m" : text;
    }
}
