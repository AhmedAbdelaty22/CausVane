package io.easylog.parser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds correlation / trace / request identifiers in plain-text log lines. */
public final class TraceIdExtractor {

    private static final Pattern KEYED = Pattern.compile(
            "(?i)(?<![a-z])(?:trace[_-]?id|correlation[_-]?id|request[_-]?id|x-request-id|req[_-]?id|span[_-]?id|cid|rid)"
                    + "[\"']?\\s*[=:]\\s*[\"']?([A-Za-z0-9][A-Za-z0-9._-]{3,63})");

    public String extract(String line) {
        if (line == null || line.length() < 8) {
            return null;
        }
        String text = line.length() > 600 ? line.substring(0, 600) : line;
        if (text.indexOf('=') < 0 && text.indexOf(':') < 0) {
            return null;
        }
        Matcher m = KEYED.matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
