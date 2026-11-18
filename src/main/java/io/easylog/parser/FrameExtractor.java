package io.easylog.parser;

import io.easylog.model.Language;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strategy that recognises one stack-frame syntax. Implementations are stateless; the previous line is
 * supplied because some runtimes (Go, Rust) print the function and the file location on separate lines.
 */
public sealed interface FrameExtractor {

    /** A frame as written in the log, before application / framework classification. */
    record RawFrame(Language language, String file, String module, String function, int line, int column) {}

    Optional<RawFrame> extract(String line, String previousLine);

    /** Extractors in priority order: specific syntaxes first, the generic {@code file:line} fallback last. */
    static List<FrameExtractor> defaults() {
        return List.of(new JavaFrames(), new RustFrames(), new NodeFrames(), new DotNetFrames(), new PythonFrames(),
                new GoFrames(), new RubyFrames(), new PhpFrames(), new GdbFrames(), new GenericFrames());
    }

    private static int toInt(String digits) {
        if (digits == null || digits.length() > 9) {
            return -1;
        }
        return Integer.parseInt(digits);
    }

    private static boolean startsWithAt(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return line.startsWith("at ", i);
    }

    /** {@code at com.example.MyClass.myMethod(MyClass.java:42)} — also Kotlin, Scala, Groovy, Clojure. */
    record JavaFrames() implements FrameExtractor {
        private static final Pattern FRAME = Pattern.compile(
                "^\\s*at\\s+(?:[\\w.$@-]*/+)*([\\w.$]+)\\.([\\w$<>-]+)\\("
                        + "(?:([\\w$][\\w$. -]*\\.(?:java|kt|kts|scala|groovy|clj|cljs))(?::(\\d+))?"
                        + "|Native Method|Unknown Source|<generated>)\\)");

        @Override
        public Optional<RawFrame> extract(String line, String previousLine) {
            if (!startsWithAt(line)) {
                return Optional.empty();
            }
            Matcher m = FRAME.matcher(line);
            if (!m.find()) {
                return Optional.empty();
            }
            return Optional.of(new RawFrame(Language.JAVA, m.group(3), m.group(1), m.group(2), toInt(m.group(4)), -1));
        }
    }

    /** Rust backtraces: {@code 2: app::run} followed by {@code at ./src/main.rs:10:5}. */
    record RustFrames() implements FrameExtractor {
        private static final Pattern LOCATION = Pattern.compile("^\\s+at\\s+(\\S+\\.rs):(\\d+)(?::(\\d+))?\\s*$");
        private static final Pattern SYMBOL = Pattern.compile("^\\s*\\d+:\\s+(?:0x[0-9a-f]+\\s+-\\s+)?(\\S.*?)\\s*$");

        @Override
        public Optional<RawFrame> extract(String line, String previousLine) {
            Matcher m = LOCATION.matcher(line);
            if (!m.matches()) {
                return Optional.empty();
            }
            String function = null;
            if (previousLine != null) {
                Matcher symbol = SYMBOL.matcher(previousLine);
                if (symbol.matches()) {
                    function = symbol.group(1);
                }
            }
            return Optional.of(new RawFrame(Language.RUST, m.group(1), null, function, toInt(m.group(2)), toInt(m.group(3))));
        }
    }

    /** {@code at functionName (/path/to/file.js:42:15)} and {@code at /path/to/file.js:42:15}. */
    record NodeFrames() implements FrameExtractor {
        private static final Pattern FRAME = Pattern.compile(
                "^\\s*at\\s+(?:(?:async\\s+)?(\\S.*?)\\s+\\()?([^\\s()]+?):(\\d+):(\\d+)\\)?\\s*$");

        @Override
        public Optional<RawFrame> extract(String line, String previousLine) {
            if (!startsWithAt(line)) {
                return Optional.empty();
            }
            Matcher m = FRAME.matcher(line);
            if (!m.matches()) {
                return Optional.empty();
            }
            return Optional.of(new RawFrame(Language.NODE, m.group(2), null, m.group(1), toInt(m.group(3)), toInt(m.group(4))));
        }
    }

    /** {@code at Namespace.Class.Method(String arg) in C:\path\File.cs:line 42}. */
    record DotNetFrames() implements FrameExtractor {
        private static final Pattern FRAME = Pattern.compile(
                "^\\s*at\\s+([^\\s(][^(]*?)\\((.*)\\)(?:\\s+in\\s+(.+?):line\\s+(\\d+))?\\s*$");

        @Override
        public Optional<RawFrame> extract(String line, String previousLine) {
            if (!startsWithAt(line)) {
                return Optional.empty();
            }
            Matcher m = FRAME.matcher(line);
            if (!m.matches()) {
                return Optional.empty();
            }
            String name = m.group(1);
            String file = m.group(3);
            if (file == null && (name.indexOf('.') < 0 || name.indexOf(' ') >= 0)) {
                return Optional.empty(); // prose such as "at startup (phase 2)"
            }
            String module = null;
            String function = name;
            if (name.endsWith("..ctor") || name.endsWith("..cctor")) {
                int cut = name.lastIndexOf("..");
                module = name.substring(0, cut);
                function = name.substring(cut + 1);
            } else {
                int cut = name.lastIndexOf('.');
                if (cut > 0) {
                    module = name.substring(0, cut);
                    function = name.substring(cut + 1);
                }
            }
            return Optional.of(new RawFrame(Language.DOTNET, file, module, function, toInt(m.group(4)), -1));
        }
    }

    /** {@code File "/path/to/script.py", line 42, in function_name}. */
    record PythonFrames() implements FrameExtractor {
        private static final Pattern FRAME = Pattern.compile(
                "^\\s*File \"(.+?)\", line (\\d+)(?:, in (.+?))?\\s*$");

        @Override
        public Optional<RawFrame> extract(String line, String previousLine) {
            if (!line.contains("File \"")) {
                return Optional.empty();
            }
            Matcher m = FRAME.matcher(line);
            if (!m.matches()) {
                return Optional.empty();
            }
            return Optional.of(new RawFrame(Language.PYTHON, m.group(1), null, m.group(3), toInt(m.group(2)), -1));
        }
    }

    /** Go: {@code main.handler(0xc000...)} on one line, then {@code \t/path/to/file.go:42 +0x1d}. */
    record GoFrames() implements FrameExtractor {
        private static final Pattern LOCATION = Pattern.compile(
                "^\\s+(\\S+\\.go):(\\d+)(?:\\s+\\+0x[0-9a-f]+)?\\s*$");
        private static final Pattern FUNCTION = Pattern.compile(
                "^(?:created by\\s+)?([^\\s(]\\S*?)(?:\\([^)]*\\))?(?:\\s+in goroutine \\d+)?\\s*$");

        @Override
        public Optional<RawFrame> extract(String line, String previousLine) {
            Matcher m = LOCATION.matcher(line);
            if (!m.matches()) {
                return Optional.empty();
            }
            String module = null;
            String function = null;
            if (previousLine != null) {
                Matcher fn = FUNCTION.matcher(previousLine);
                if (fn.matches()) {
                    String full = fn.group(1);
                    int dot = full.indexOf('.', full.lastIndexOf('/') + 1);
                    if (dot > 0) {
                        module = full.substring(0, dot);
                        function = full.substring(dot + 1);
                    } else {
                        function = full;
                    }
                }
            }
            return Optional.of(new RawFrame(Language.GO, m.group(1), module, function, toInt(m.group(2)), -1));
        }
    }

    /** {@code from /app/models/user.rb:42:in `find'}. */
    record RubyFrames() implements FrameExtractor {
        private static final Pattern FRAME = Pattern.compile(
                "^\\s*(?:from\\s+)?(\\S+\\.(?:rb|rake|erb)):(\\d+):in [`']([^']+)'");

        @Override
        public Optional<RawFrame> extract(String line, String previousLine) {
            if (!line.contains(":in ")) {
                return Optional.empty();
            }
            Matcher m = FRAME.matcher(line);
            if (!m.find()) {
                return Optional.empty();
            }
            return Optional.of(new RawFrame(Language.RUBY, m.group(1), null, m.group(3), toInt(m.group(2)), -1));
        }
    }

    /** {@code #0 /var/www/app/Service.php(42): App\Service->run()}. */
    record PhpFrames() implements FrameExtractor {
        private static final Pattern FRAME = Pattern.compile(
                "^\\s*#\\d+\\s+(\\S.*?\\.(?:php|phtml|inc))\\((\\d+)\\):\\s+(\\S.*?)\\s*$");

        @Override
        public Optional<RawFrame> extract(String line, String previousLine) {
            Matcher m = FRAME.matcher(line);
            if (!m.matches()) {
                return Optional.empty();
            }
            String call = m.group(3);
            int paren = call.indexOf('(');
            String function = paren > 0 ? call.substring(0, paren) : call;
            return Optional.of(new RawFrame(Language.PHP, m.group(1), null, function, toInt(m.group(2)), -1));
        }
    }

    /** gdb / lldb style native frames: {@code #1 0x0000 in parse (buf=0x0) at src/parser.cpp:42}. */
    record GdbFrames() implements FrameExtractor {
        private static final Pattern FRAME = Pattern.compile(
                "^\\s*#\\d+\\s+(?:0x[0-9a-fA-F]+\\s+in\\s+)?(\\S+)\\s*\\(.*\\)\\s+at\\s+(\\S+?):(\\d+)\\s*$");

        @Override
        public Optional<RawFrame> extract(String line, String previousLine) {
            Matcher m = FRAME.matcher(line);
            if (!m.matches()) {
                return Optional.empty();
            }
            Language language = Language.fromFile(m.group(2));
            return Optional.of(new RawFrame(language == Language.UNKNOWN ? Language.CPP : language,
                    m.group(2), null, m.group(1), toInt(m.group(3)), -1));
        }
    }

    /**
     * Fallback for any {@code <filepath>:<line>[:<column>]} reference with a known source extension,
     * whatever the language, plus the {@code in <file> on line N} form.
     */
    record GenericFrames() implements FrameExtractor {
        private static final String EXTENSIONS =
                "php|phtml|rb|rake|rs|c|cc|cpp|cxx|h|hpp|hh|go|py|js|mjs|cjs|ts|jsx|tsx|vue|java|kt|scala|groovy"
                        + "|cs|vb|fs|swift|m|mm|ex|exs|erl|lua|pl|pm|dart|zig|nim|cr|hs|ml";
        private static final Pattern LOCATION = Pattern.compile(
                "(?<![\\w@~+./\\\\-])((?:[A-Za-z]:[\\\\/])?[\\w@~+./\\\\-]+\\.(?:" + EXTENSIONS + ")):(\\d+)(?::(\\d+))?(?![\\w.])");
        private static final Pattern ON_LINE = Pattern.compile(
                "\\bin\\s+(\\S+\\.(?:" + EXTENSIONS + "))\\s+on\\s+line\\s+(\\d+)");
        private static final Pattern FUNCTION_HINT = Pattern.compile(
                "(?:\\bin\\s+(?:function\\s+)?[`']?([\\w.:$<>#]+)[`']?\\s*$)");
        private static final int MAX_LENGTH = 2000;

        @Override
        public Optional<RawFrame> extract(String line, String previousLine) {
            if (line.length() > MAX_LENGTH || line.indexOf('.') < 0) {
                return Optional.empty();
            }
            Matcher m = LOCATION.matcher(line);
            if (m.find()) {
                String function = null;
                Matcher hint = FUNCTION_HINT.matcher(line.substring(m.end()));
                if (hint.find()) {
                    function = hint.group(1);
                }
                return Optional.of(new RawFrame(Language.fromFile(m.group(1)), m.group(1), null, function,
                        toInt(m.group(2)), toInt(m.group(3))));
            }
            m = ON_LINE.matcher(line);
            if (m.find()) {
                return Optional.of(new RawFrame(Language.fromFile(m.group(1)), m.group(1), null, null, toInt(m.group(2)), -1));
            }
            return Optional.empty();
        }
    }
}
