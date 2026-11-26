package io.causvane.explain;

import io.causvane.model.ErrorGroup;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds a web search for a problem that is safe to send: the error's name and the general words of its
 * message, with everything that could identify the company, its systems or its customers taken out
 * (quoted values, addresses, host names, paths, e-mail addresses, IDs, numbers and tokens). The reader
 * sees and can edit the result before anything leaves the computer.
 */
public final class WebSearch {

    /** Search sites the reader can choose from. */
    public enum Engine {
        GOOGLE("Google", "https://www.google.com/search?q="),
        BING("Bing", "https://www.bing.com/search?q="),
        DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q="),
        STACK_OVERFLOW("Stack Overflow", "https://stackoverflow.com/search?q=");

        private final String label;
        private final String prefix;

        Engine(String label, String prefix) {
            this.label = label;
            this.prefix = prefix;
        }

        public String label() {
            return label;
        }

        /** The address that searches this site for the text. */
        public String url(String query) {
            return prefix + URLEncoder.encode(query.strip(), StandardCharsets.UTF_8);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** What was taken out, for telling the reader. */
    public record Query(String text, List<String> removed) {}

    private static final int MAX_WORDS = 14;
    private static final Pattern LOGFMT = Pattern.compile("\\b(msg|message|err|error)=(?:\"([^\"]*)\"|(\\S+))");

    // order matters: the larger patterns go before the ones that would cut them in pieces
    private static final List<Cleaner> CLEANERS = List.of(
            new Cleaner("quoted values", "'[^']*'|\"[^\"]*\"|`[^`]*`|\u2018[^\u2019]*\u2019|\u201c[^\u201d]*\u201d"),
            new Cleaner("web addresses", "(?i)\\b(?:[a-z][a-z0-9+.-]*)://\\S+"),
            new Cleaner("e-mail addresses", "[\\w.+-]+@[\\w-]+(?:\\.[\\w-]+)+"),
            new Cleaner("IP addresses", "\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d+)?\\b|\\b(?:[0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}\\b"),
            new Cleaner("file paths", "(?:[A-Za-z]:\\\\|\\\\\\\\)[^\\s,;)]+|(?<![\\w.])/(?:[\\w.@-]+/)+[\\w.@-]*"),
            new Cleaner("IDs", "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b|\\b0x[0-9a-fA-F]+\\b|\\b[0-9a-fA-F]{12,}\\b"),
            new Cleaner("tokens and keys", "\\b(?:[A-Za-z0-9+/_-]{24,}={0,2})\\b|\\b(?:Bearer|Basic|token|key|secret|password|pwd)[=: ]+\\S+"),
            new Cleaner("host names", "(?i)\\b(?:[a-z0-9-]+\\.)+(?:com|net|org|io|dev|app|cloud|internal|local|lan|corp|intra|svc|cluster|[a-z]{2})(?::\\d+)?\\b"),
            new Cleaner("names and values", "\\b[\\w.-]+=\\S+"),
            new Cleaner("internal names", "\\b[a-z][a-z0-9_-]*(?:[.:/][a-z0-9_-]+)+\\b"),
            new Cleaner("IDs", "\\b(?=[A-Za-z_-]*\\d)(?=[\\d_-]*[A-Za-z])[\\w-]{6,}\\b"),
            new Cleaner("numbers", "\\b\\d[\\d.,:_-]*\\b"));

    private record Cleaner(String what, Pattern pattern) {
        Cleaner(String what, String regex) {
            this(what, Pattern.compile(regex));
        }
    }

    private WebSearch() {}

    /** The search for a problem: the error's short name, the underlying error's, and the message's general words. */
    public static Query forProblem(ErrorGroup group) {
        Set<String> names = new LinkedHashSet<>();
        if (group.errorType() != null && !group.errorType().isBlank()) {
            names.add(ErrorGroup.simpleName(group.errorType()));
        }
        String message = group.rootCauseMessage() != null && !group.rootCauseMessage().isBlank()
                ? group.rootCauseMessage() : traceMessage(group);
        if (group.rootCauseType() != null && !group.rootCauseType().isBlank()) {
            names.add(ErrorGroup.simpleName(group.rootCauseType()));
        }
        Query cleaned = clean(message == null ? "" : stripLogHeader(message));
        String text = (String.join(" ", names) + " " + cleaned.text()).strip();
        return new Query(text, cleaned.removed());
    }

    /** Takes out everything that could identify a system or person; keeps the general words. */
    public static Query clean(String text) {
        String out = text == null ? "" : text;
        Set<String> removed = new LinkedHashSet<>();
        for (Cleaner c : CLEANERS) {
            java.util.regex.Matcher m = c.pattern().matcher(out);
            if (m.find()) {
                removed.add(c.what());
                out = m.replaceAll(" ");
            }
        }
        // what is left of brackets and punctuation, then a sensible length
        out = out.replaceAll("[\\[\\](){}<>|]+", " ").replaceAll("\\s*[:;,]+\\s*(?=\\s|$)", " ").replaceAll("\\s+", " ").strip();
        List<String> words = new ArrayList<>();
        for (String word : out.split(" ")) {
            String bare = word.replaceAll("^[^\\w]+|[^\\w]+$", "");
            if (!bare.isEmpty() && !(bare.length() == 1 && !Character.isLetter(bare.charAt(0))) && words.size() < MAX_WORDS) {
                words.add(bare);
            }
        }
        return new Query(String.join(" ", words), List.copyOf(removed));
    }

    /** The message the error itself carried, or the log line when it carried none. */
    private static String traceMessage(ErrorGroup group) {
        if (!group.samples().isEmpty() && group.samples().get(0).trace() != null) {
            for (io.causvane.model.TraceSection section : group.samples().get(0).trace().sections()) {
                if (section.message() != null && !section.message().isBlank()) {
                    return section.message();
                }
            }
        }
        return group.message();
    }

    /** Drops a log line's date, level and logger, which say nothing about the error itself. */
    private static String stripLogHeader(String message) {
        // logfmt ("level=error msg=\"...\" err=\"...\""): the message and error are the useful part
        java.util.regex.Matcher fields = LOGFMT.matcher(message);
        StringBuilder logfmt = new StringBuilder();
        while (fields.find()) {
            logfmt.append(fields.group(2) != null ? fields.group(2) : fields.group(3)).append(' ');
        }
        if (!logfmt.isEmpty()) {
            return logfmt.toString();
        }
        String m = message.replaceFirst("^\\s*\\d{4}-\\d{2}-\\d{2}[T ][\\d:.,]+\\S*\\s*", "");
        m = m.replaceFirst("^(?i)(?:\\[?(?:FATAL|FTL|CRITICAL|CRIT|ERROR|ERR|WARN(?:ING)?|WRN|INFO|INF|DEBUG|DBG|TRACE|TRC)\\b\\S*\\]?\\s+)", "");
        int separator = m.indexOf(" : ");
        if (separator >= 0 && separator < 120) {
            m = m.substring(separator + 3);
        }
        return m;
    }

    /** "IDs, numbers and file paths": what was removed, in plain words. */
    public static String describeRemoved(List<String> removed) {
        if (removed.isEmpty()) {
            return "Nothing needed removing.";
        }
        List<String> parts = new ArrayList<>(new LinkedHashSet<>(removed));
        String joined = parts.size() == 1 ? parts.get(0)
                : String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1);
        return "Removed: " + joined + ".";
    }

    /** The engine with this name, or Google. */
    public static Engine engine(String name) {
        for (Engine e : Engine.values()) {
            if (e.name().equalsIgnoreCase(String.valueOf(name))) {
                return e;
            }
        }
        return Engine.GOOGLE;
    }
}
