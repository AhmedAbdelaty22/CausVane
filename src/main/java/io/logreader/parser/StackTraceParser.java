package io.logreader.parser;

import io.logreader.model.Language;
import io.logreader.model.ParsedTrace;
import io.logreader.model.StackFrame;
import io.logreader.model.TraceSection;
import io.logreader.model.TraceSection.Kind;
import io.logreader.parser.FrameExtractor.RawFrame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the lines of one log event into a {@link ParsedTrace}: exception chain, frames, deepest root
 * cause and the pinpointed frame. Language-agnostic: the frame syntax is delegated to {@link FrameExtractor}s.
 */
public final class StackTraceParser {

    /** Exception type and message as written on a header line. */
    public record Header(String type, String message) {}

    private static final Pattern CAUSED_BY = Pattern.compile("^\\s*(?:Caused by:|caused by:|\\[cause\\]:)\\s*(.*)$");
    private static final Pattern SUPPRESSED = Pattern.compile("^\\s*Suppressed:\\s*(.*)$");
    private static final Pattern PY_TRACEBACK = Pattern.compile("^[\\s|+]*(?:Exception Group )?Traceback \\(most recent call last\\):\\s*$");
    private static final Pattern PY_FINAL = Pattern.compile("^([A-Za-z_][\\w.]*)(?::\\s*(.*))?$");
    private static final Pattern DOTNET_END_INNER = Pattern.compile("^\\s*--- End of inner exception stack trace ---\\s*$");
    private static final String DOTNET_ARROW = "---> ";
    private static final Pattern GOROUTINE = Pattern.compile("^goroutine \\d+ \\[.*\\]:\\s*$");
    private static final Pattern GO_PANIC = Pattern.compile("(?:^|\\s)(panic|fatal error): (.+)$");
    private static final Pattern GO_HTTP_PANIC = Pattern.compile("\\bpanic serving \\S+: (.+)$");
    private static final Pattern SIGNAL = Pattern.compile("^Program received signal (SIG[A-Z]+),?\\s*(.*)$");
    private static final Pattern RUST_PANIC = Pattern.compile("thread '.*?' panicked at (.*)$");
    private static final Pattern RUST_OLD_MESSAGE = Pattern.compile("^'(.*)',\\s*\\S+:\\d+(?::\\d+)?$");
    private static final Pattern RUBY_HEADER = Pattern.compile(":in [`'][^']*': (.*) \\(([A-Z][\\w:]*)\\)\\s*$");
    private static final Pattern QUALIFIED_TYPE_LINE = Pattern.compile(
            "^((?:[a-z_][\\w$]*\\.)+[A-Z][\\w$]*)(?::\\s*(.*))?$");
    private static final Pattern LENIENT_TYPE_LINE = Pattern.compile("^([A-Za-z_][\\w.$\\\\:]*[\\w$])(?::\\s+(.*))?$");
    private static final Pattern EXCEPTION_TYPE = Pattern.compile(
            "(?<![\\w.$])((?:[A-Za-z_$][\\w$]*(?:\\.|::|\\\\))*(?:[A-Za-z_$][\\w$]*)?(?:Exception|Error|Throwable|Interrupt))"
                    + "(?![\\w$])(?:(?:\\s*\\(0x[0-9A-Fa-f]+\\))?\\s*:\\s+(.*))?");

    private static final int MAX_HEADER_LENGTH = 2000;

    private final List<FrameExtractor> extractors;
    private final FrameClassifier classifier;

    public StackTraceParser() {
        this(FrameExtractor.defaults(), new FrameClassifier());
    }

    public StackTraceParser(List<FrameExtractor> extractors, FrameClassifier classifier) {
        this.extractors = List.copyOf(extractors);
        this.classifier = classifier;
    }

    /** Extracts a single frame from a line using the first extractor that recognises it. */
    public Optional<RawFrame> extractFrame(String line, String previousLine) {
        for (FrameExtractor extractor : extractors) {
            Optional<RawFrame> frame = extractor.extract(line, previousLine);
            if (frame.isPresent()) {
                return frame;
            }
        }
        return Optional.empty();
    }

    /** Parses the event lines; empty when they contain neither an exception nor a stack frame. */
    public Optional<ParsedTrace> parse(List<String> lines) {
        List<Section> sections = new ArrayList<>();
        Section current = null;
        Header candidate = null;
        Language hint = Language.UNKNOWN;
        boolean awaitingRustMessage = false;
        String previous = null;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            Matcher m = CAUSED_BY.matcher(line);
            if (m.matches()) {
                current = open(sections, Kind.CAUSE, lenientHeader(m.group(1)));
            } else if ((m = SUPPRESSED.matcher(line)).matches()) {
                current = open(sections, Kind.SUPPRESSED, lenientHeader(m.group(1)));
            } else if (PY_TRACEBACK.matcher(line).matches()) {
                current = open(sections, sections.isEmpty() ? Kind.PRIMARY : Kind.WRAPPER, null);
                current.python = true;
                hint = Language.PYTHON;
            } else if (DOTNET_END_INNER.matcher(line).matches()) {
                current = open(sections, Kind.WRAPPER, null);
            } else if (line.contains(DOTNET_ARROW)) {
                current = openDotNetChain(sections, line, candidate);
                candidate = null;
            } else if (GOROUTINE.matcher(line).matches()) {
                boolean first = sections.isEmpty();
                current = open(sections, first ? Kind.PRIMARY : Kind.OTHER, first ? candidate : null);
                candidate = null;
                hint = Language.GO;
            } else {
                Optional<RawFrame> frame = extractFrame(line, previous);
                if (frame.isPresent()) {
                    if (current == null || current.closed) {
                        // a line can be both header and first frame ("file.rb:3:in 'x': msg (Error)"), but an
                        // ordinary frame must not be read as a header just because a method name ends in "Exception"
                        Header header = candidate != null || isPlainFrame(line) ? candidate : parseHeader(line);
                        current = open(sections, sections.isEmpty() ? Kind.PRIMARY : Kind.WRAPPER, header);
                        awaitingRustMessage = header != null && "panic".equals(header.type()) && header.message() == null;
                        candidate = null;
                    }
                    RawFrame raw = frame.get();
                    current.frames.add(new StackFrame(raw.language(), raw.file(), raw.module(), raw.function(),
                            raw.line(), raw.column(), classifier.isApplication(raw), i));
                } else if (current != null && current.python && !current.closed) {
                    if (!Character.isWhitespace(line.charAt(0))) {
                        Matcher fin = PY_FINAL.matcher(line.strip());
                        if (fin.matches()) {
                            current.type = fin.group(1);
                            current.message = emptyToNull(fin.group(2));
                            current.closed = true;
                        }
                    }
                } else if (awaitingRustMessage && current != null) {
                    current.message = line.strip();
                    awaitingRustMessage = false;
                } else if (current == null || current.closed) {
                    Header header = parseHeader(line);
                    if (header != null) {
                        candidate = header;
                    }
                }
            }
            previous = line;
        }

        if (sections.isEmpty() && candidate != null) {
            open(sections, Kind.PRIMARY, candidate);
        }
        sections.removeIf(s -> s.type == null && s.frames.isEmpty());
        if (sections.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(assemble(sections, hint));
    }

    /**
     * Recognises an exception / panic header anywhere in a line, e.g.
     * {@code java.lang.IllegalStateException: boom}, {@code TypeError: x is not a function},
     * {@code panic: runtime error: index out of range}.
     */
    public static Header parseHeader(String raw) {
        String s = raw.strip();
        if (s.isEmpty()) {
            return null;
        }
        if (s.length() > MAX_HEADER_LENGTH) {
            s = s.substring(0, MAX_HEADER_LENGTH);
        }
        Matcher m = GO_PANIC.matcher(s);
        if (m.find()) {
            return new Header(m.group(1), m.group(2).strip());
        }
        m = GO_HTTP_PANIC.matcher(s);
        if (m.find()) {
            return new Header("panic", m.group(1).strip());
        }
        m = SIGNAL.matcher(s);
        if (m.find()) {
            return new Header(m.group(1), emptyToNull(m.group(2)));
        }
        m = RUST_PANIC.matcher(s);
        if (m.find()) {
            Matcher old = RUST_OLD_MESSAGE.matcher(m.group(1).strip());
            return new Header("panic", old.matches() ? old.group(1) : null);
        }
        m = RUBY_HEADER.matcher(s);
        if (m.find()) {
            return new Header(m.group(2), m.group(1));
        }
        m = QUALIFIED_TYPE_LINE.matcher(s);
        if (m.matches()) {
            return new Header(m.group(1), emptyToNull(m.group(2)));
        }
        m = EXCEPTION_TYPE.matcher(s);
        Header first = null;
        while (m.find()) {
            String type = m.group(1);
            String message = emptyToNull(m.group(2));
            boolean bare = type.equals("Exception") || type.equals("Error") || type.equals("Throwable")
                    || type.equals("Interrupt");
            if (bare) {
                // "Error: x" is a real type only as the subject of the line, not inside prose
                String before = s.substring(0, m.start());
                boolean subject = before.isEmpty() || before.endsWith(": ") || before.endsWith("Uncaught ");
                if (!subject || message == null) {
                    continue;
                }
            }
            if (message != null) {
                return new Header(type, message);
            }
            if (!bare && first == null) {
                first = new Header(type, null);
            }
        }
        return first;
    }

    private static boolean isPlainFrame(String line) {
        String trimmed = line.stripLeading();
        return trimmed.startsWith("at ") || trimmed.startsWith("File \"") ||trimmed.startsWith("from ");
    }

    /** Header following an explicit marker such as {@code Caused by:}, where any type name is accepted. */
    private static Header lenientHeader(String text) {
        String s = text.strip();
        Matcher m = LENIENT_TYPE_LINE.matcher(s);
        if (m.matches()) {
            return new Header(m.group(1), emptyToNull(m.group(2)));
        }
        Header parsed = parseHeader(s);
        return parsed != null ? parsed : new Header(s.isEmpty() ? null : s, null);
    }

    /** {@code Outer: msg ---> Inner: msg} — each arrow introduces a nested inner exception. */
    private Section openDotNetChain(List<Section> sections, String line, Header candidate) {
        String[] parts = line.split(Pattern.quote(DOTNET_ARROW));
        Section current = null;
        for (int p = 0; p < parts.length; p++) {
            String part = parts[p].strip();
            if (p == 0) {
                if (sections.isEmpty()) {
                    Header outer = part.isEmpty() ? null : parseHeader(part);
                    if (outer == null) {
                        outer = candidate;
                    }
                    if (outer != null) {
                        current = open(sections, Kind.PRIMARY, outer);
                    }
                }
            } else if (!part.isEmpty()) {
                Header inner = parseHeader(part);
                if (inner == null && part.indexOf('.') > 0 && part.indexOf(' ') < 0) {
                    inner = lenientHeader(part);
                }
                if (inner != null) { // otherwise the arrow is ordinary message text
                    current = open(sections, Kind.CAUSE, inner);
                }
            }
        }
        return current != null ? current : (sections.isEmpty() ? null : sections.get(sections.size() - 1));
    }

    private static Section open(List<Section> sections, Kind kind, Header header) {
        Section section = new Section(kind);
        if (header != null) {
            section.type = header.type();
            section.message = header.message();
        }
        sections.add(section);
        return section;
    }

    private ParsedTrace assemble(List<Section> built, Language hint) {
        List<TraceSection> sections = new ArrayList<>(built.size());
        boolean python = false;
        for (Section s : built) {
            List<StackFrame> frames = s.frames;
            if (s.python) {
                python = true;
                frames = new ArrayList<>(frames);
                Collections.reverse(frames); // Python prints the most recent call last
            }
            sections.add(new TraceSection(s.kind, s.type, s.message, frames));
        }

        int rootIndex = rootIndex(sections);
        TraceSection root = sections.get(rootIndex);
        StackFrame pinpoint = pinpoint(sections, rootIndex);

        List<String> chain = new ArrayList<>();
        for (TraceSection s : sections) {
            boolean inChain = s.kind() == Kind.PRIMARY || s.kind() == Kind.CAUSE || (python && s.kind() == Kind.WRAPPER);
            if (inChain && s.exceptionType() != null
                    && (chain.isEmpty() || !chain.get(chain.size() - 1).equals(s.exceptionType()))) {
                chain.add(s.exceptionType());
            }
        }
        if (python) {
            Collections.reverse(chain); // first traceback is the original cause, last one the reported error
        }

        Language language = hint;
        if (pinpoint != null && pinpoint.language() != Language.UNKNOWN) {
            language = pinpoint.language();
        } else {
            for (TraceSection s : sections) {
                if (!s.frames().isEmpty() && s.frames().get(0).language() != Language.UNKNOWN) {
                    language = s.frames().get(0).language();
                    break;
                }
            }
        }
        return new ParsedTrace(language, sections, root, pinpoint, chain);
    }

    /** The deepest cause: the last nested cause, or else the first exception printed. */
    private static int rootIndex(List<TraceSection> sections) {
        for (int i = sections.size() - 1; i >= 0; i--) {
            if (sections.get(i).kind() == Kind.CAUSE) {
                return i;
            }
        }
        return 0;
    }

    /**
     * First application frame of the root cause. When the root cause lists no application frame of its own
     * (Java elides shared frames as {@code ... 12 more}), fall back to the enclosing exceptions.
     */
    private static StackFrame pinpoint(List<TraceSection> sections, int rootIndex) {
        List<Integer> order = new ArrayList<>();
        for (int i = rootIndex; i >= 0; i--) {
            order.add(i);
        }
        for (int i = rootIndex + 1; i < sections.size(); i++) {
            order.add(i);
        }
        StackFrame firstAny = null;
        for (int index : order) {
            TraceSection section = sections.get(index);
            if (section.kind() == Kind.SUPPRESSED || section.kind() == Kind.OTHER) {
                continue;
            }
            for (StackFrame frame : section.frames()) {
                if (frame.application()) {
                    return enrich(frame, section);
                }
                if (firstAny == null) {
                    firstAny = frame;
                }
            }
        }
        return firstAny;
    }

    /** Borrows the function name from a sibling frame at the same location (header line vs. backtrace). */
    private static StackFrame enrich(StackFrame frame, TraceSection section) {
        if (frame.function() != null || frame.fileName() == null) {
            return frame;
        }
        for (StackFrame other : section.frames()) {
            if (other != frame && other.function() != null && other.line() == frame.line()
                    && frame.fileName().equals(other.fileName())) {
                return frame.withFunction(other.module(), other.function());
            }
        }
        return frame;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static final class Section {
        final Kind kind;
        final List<StackFrame> frames = new ArrayList<>();
        String type;
        String message;
        boolean python;
        boolean closed;

        Section(Kind kind) {
            this.kind = kind;
        }
    }
}
