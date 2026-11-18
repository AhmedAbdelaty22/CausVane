package io.easylog.parser;

import io.easylog.model.Severity;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Finds and normalises the log level of an event header line. */
public final class SeverityDetector {

    /** Conventional upper-case level tokens: {@code ERROR}, {@code [WARN]}, Serilog's {@code ERR}, ... */
    private static final Pattern UPPER = Pattern.compile(
            "(?<![A-Za-z0-9_])(FATAL|FTL|CRITICAL|CRIT|PANIC|EMERGENCY|EMERG|ALERT|SEVERE|ERROR|ERR|EXCEPTION"
                    + "|WARNING|WARN|WRN|NOTICE|INFORMATION|INFO|INF|DEBUG|DBG|TRACE|VERBOSE|VRB|FINEST|FINER|FINE|CONFIG)"
                    + "(?![A-Za-z0-9_])");

    /** Lower / mixed-case levels, only when clearly delimited: {@code level=error}, {@code [error]}, {@code fail:}. */
    private static final Pattern DELIMITED = Pattern.compile(
            "(?:^|[\\[<(|=\\s\"])(fatal error|fatal|critical|crit|panic|emerg|error|err|fail|warning|warn|notice"
                    + "|information|info|debug|dbug|trace|trce|verbose)(?=[\\]>)|:,\"]|\\s+-\\s|\\s+\\w+=|\\s*$)",
            Pattern.CASE_INSENSITIVE);

    private static final int SCAN_LIMIT = 200;

    public Severity detect(String header) {
        if (header == null || header.isEmpty()) {
            return Severity.UNKNOWN;
        }
        String text = header.length() > SCAN_LIMIT ? header.substring(0, SCAN_LIMIT) : header;
        Matcher m = UPPER.matcher(text);
        if (m.find()) {
            return Severity.fromToken(m.group(1));
        }
        m = DELIMITED.matcher(text);
        if (m.find()) {
            return Severity.fromToken(m.group(1));
        }
        return Severity.UNKNOWN;
    }
}
