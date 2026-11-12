package io.logreader.parser;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.logreader.model.Severity;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Decodes JSON-formatted log lines (logstash, pino, bunyan, Serilog compact, structlog, zap, docker json-file, ...)
 * by auto-detecting the usual field names for timestamp, level, message, error and stack trace.
 */
public final class JsonLineParser {

    /** A decoded structured log line. {@code stackLines} holds the stack trace split into lines (may be empty). */
    public record JsonRecord(LocalDateTime timestamp, Severity severity, String message, List<String> stackLines,
                             String traceId) {}

    private static final List<String> TIME_KEYS = List.of(
            "timestamp", "@timestamp", "time", "ts", "@t", "datetime", "date", "eventtime", "asctime", "t");
    private static final List<String> LEVEL_KEYS = List.of(
            "level", "severity", "lvl", "loglevel", "log.level", "levelname", "@l", "log_level", "priority");
    private static final List<String> MESSAGE_KEYS = List.of(
            "message", "msg", "@m", "@mt", "log", "text", "event", "short_message", "body");
    private static final List<String> STACK_KEYS = List.of(
            "stack_trace", "stacktrace", "stack", "exception", "@x", "exc_info", "traceback", "err", "error",
            "error.stack_trace", "error.stacktrace", "error.stack", "err.stack", "err.stack_trace", "err.stacktrace",
            "exception.stacktrace", "exception.stack_trace", "exception.stack", "error.trace", "exception.trace",
            "full_message");
    private static final List<String> ERROR_TYPE_KEYS = List.of(
            "error.type", "err.type", "exception.type", "error.kind", "error.name", "err.name", "exception.class",
            "error.class", "exception.name", "error_type", "exception_type", "exc_type");
    private static final List<String> ERROR_MESSAGE_KEYS = List.of(
            "error.message", "err.message", "err.msg", "error.msg", "exception.message", "exc_value");
    private static final List<String> TRACE_ID_KEYS = List.of(
            "trace_id", "traceid", "trace.id", "correlation_id", "correlationid", "request_id", "requestid",
            "x-request-id", "req_id", "reqid", "dd.trace_id", "span_id", "spanid");

    private final ObjectMapper mapper = new ObjectMapper();
    private final TimestampDetector timestamps;

    public JsonLineParser(TimestampDetector timestamps) {
        this.timestamps = timestamps;
    }

    /** Returns the decoded record, or null when the line is not a JSON log entry. */
    public JsonRecord tryParse(String line) {
        int end = line.length() - 1;
        while (end > 0 && (line.charAt(end) == ' ' || line.charAt(end) == ',')) {
            end--;
        }
        if (end < 1 || line.charAt(0) != '{' || line.charAt(end) != '}') {
            return null;
        }
        JsonNode root;
        try {
            root = mapper.readTree(end == line.length() - 1 ? line : line.substring(0, end + 1));
        } catch (JsonProcessingException e) {
            return null;
        }
        if (root == null || !root.isObject()) {
            return null;
        }
        Map<String, JsonNode> fields = flatten(root);

        JsonNode timeNode = first(fields, TIME_KEYS);
        JsonNode levelNode = first(fields, LEVEL_KEYS);
        JsonNode messageNode = first(fields, MESSAGE_KEYS);
        if (timeNode == null && levelNode == null && messageNode == null) {
            return null; // a JSON payload dumped into a text log, not a log record
        }

        String message = messageNode == null ? "" : text(messageNode);
        List<String> stack = stackLines(fields, message);
        return new JsonRecord(timestamp(timeNode), severity(levelNode), message, stack, traceId(fields));
    }

    /** Lower-cased keys; nested objects are flattened one level as {@code parent.child}. */
    private static Map<String, JsonNode> flatten(JsonNode root) {
        Map<String, JsonNode> fields = new HashMap<>();
        root.properties().forEach(entry -> {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            JsonNode value = entry.getValue();
            fields.put(key, value);
            if (value.isObject()) {
                value.properties().forEach(child ->
                        fields.putIfAbsent(key + "." + child.getKey().toLowerCase(Locale.ROOT), child.getValue()));
            }
        });
        return fields;
    }

    private static JsonNode first(Map<String, JsonNode> fields, List<String> keys) {
        for (String key : keys) {
            JsonNode node = fields.get(key);
            if (node != null && !node.isNull() && !node.isObject()) {
                return node;
            }
        }
        return null;
    }

    private static String text(JsonNode node) {
        if (node.isArray()) {
            List<String> parts = new ArrayList<>();
            node.forEach(item -> parts.add(item.isValueNode() ? item.asText() : item.toString()));
            return String.join("\n", parts);
        }
        return node.asText();
    }

    private LocalDateTime timestamp(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isNumber()) {
            return timestamps.fromEpoch(node.asDouble());
        }
        return timestamps.parse(node.asText());
    }

    private static Severity severity(JsonNode node) {
        if (node == null) {
            return Severity.UNKNOWN;
        }
        if (node.isNumber()) {
            return Severity.fromNumeric(node.asInt());
        }
        return Severity.fromToken(node.asText());
    }

    private static List<String> stackLines(Map<String, JsonNode> fields, String message) {
        List<String> lines = new ArrayList<>();
        JsonNode typeNode = first(fields, ERROR_TYPE_KEYS);
        JsonNode errorMessageNode = first(fields, ERROR_MESSAGE_KEYS);
        String stack = null;
        for (String key : STACK_KEYS) {
            JsonNode node = fields.get(key);
            if (node != null && (node.isTextual() || node.isArray())) {
                String candidate = text(node);
                if (!candidate.isBlank() && !candidate.equals(message)) {
                    stack = candidate;
                    break;
                }
            }
        }
        if (typeNode != null) {
            String type = typeNode.asText();
            if (stack == null || !stack.contains(type)) {
                lines.add(errorMessageNode == null ? type : type + ": " + errorMessageNode.asText());
            }
        } else if (errorMessageNode != null && stack == null) {
            lines.add(errorMessageNode.asText());
        }
        if (stack != null) {
            for (String line : stack.split("\\r?\\n")) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static String traceId(Map<String, JsonNode> fields) {
        JsonNode node = first(fields, TRACE_ID_KEYS);
        return node == null || node.asText().isBlank() ? null : node.asText();
    }
}
