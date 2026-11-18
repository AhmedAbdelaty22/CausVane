package io.easylog.model;

import java.util.Locale;

/** Normalised log severity, ordered from least to most severe. */
public enum Severity {
    UNKNOWN, TRACE, DEBUG, INFO, WARN, ERROR, FATAL;

    public boolean isAtLeast(Severity other) {
        return ordinal() >= other.ordinal();
    }

    /** Maps any level token used by common logging frameworks onto the normalised scale. */
    public static Severity fromToken(String token) {
        if (token == null) {
            return UNKNOWN;
        }
        return switch (token.strip().toUpperCase(Locale.ROOT)) {
            case "FATAL", "FTL", "CRITICAL", "CRIT", "PANIC", "EMERGENCY", "EMERG", "ALERT", "FATAL ERROR" -> FATAL;
            case "ERROR", "ERR", "SEVERE", "EXCEPTION", "FAIL", "FAILURE" -> ERROR;
            case "WARN", "WARNING", "WRN" -> WARN;
            case "INFO", "INF", "INFORMATION", "NOTICE", "CONFIG" -> INFO;
            case "DEBUG", "DBG", "DBUG", "FINE" -> DEBUG;
            case "TRACE", "TRCE", "VERBOSE", "VRB", "FINER", "FINEST" -> TRACE;
            default -> UNKNOWN;
        };
    }

    /** Numeric levels as emitted by pino / bunyan style JSON loggers (10..60). */
    public static Severity fromNumeric(int level) {
        if (level >= 60) return FATAL;
        if (level >= 50) return ERROR;
        if (level >= 40) return WARN;
        if (level >= 30) return INFO;
        if (level >= 20) return DEBUG;
        if (level >= 10) return TRACE;
        return UNKNOWN;
    }
}
