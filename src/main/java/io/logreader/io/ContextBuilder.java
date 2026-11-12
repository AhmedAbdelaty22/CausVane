package io.logreader.io;

import io.logreader.model.LogEvent;
import io.logreader.model.StackFrame;
import io.logreader.parser.LogParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Builds the lines shown around an error occurrence, marking the exact failing line. */
public final class ContextBuilder {

    /**
     * @param lineNumber physical line number, or 0 for lines that do not exist in the file
     *                   (decoded JSON stack traces, notes)
     */
    public record ContextLine(long lineNumber, String text, Kind kind) {
        public enum Kind {
            /** Surrounding log line. */
            CONTEXT,
            /** Line belonging to the error event. */
            EVENT,
            /** The pinpointed failing line. */
            PINPOINT,
            /** Informational note. */
            NOTE
        }
    }

    private ContextBuilder() {}

    /** Returns {@code before} lines of context, the event itself, then {@code after} lines of context. */
    public static List<ContextLine> build(LogSession session, LogEvent event, int before, int after) throws IOException {
        List<ContextLine> out = new ArrayList<>();
        long start = event.lineNumber();
        long end = event.lastLineNumber();
        long shownEnd = Math.min(end, start + LogParser.MAX_EVENT_LINES - 1);

        StackFrame pin = event.trace() == null ? null : event.trace().pinpoint();
        int pinIndex = pin == null ? -1 : pin.eventLineIndex();
        boolean decodedPin = event.structured() && pinIndex > 0;
        long pinLine = event.structured() || pinIndex < 0 ? start : start + pinIndex;

        long from = Math.max(1, start - before);
        List<String> head = session.readLines(from, shownEnd);
        for (int i = 0; i < head.size(); i++) {
            long number = from + i;
            ContextLine.Kind kind = number < start ? ContextLine.Kind.CONTEXT
                    : number == pinLine && !decodedPin ? ContextLine.Kind.PINPOINT : ContextLine.Kind.EVENT;
            out.add(new ContextLine(number, head.get(i), kind));
        }
        if (event.structured() && event.lines().size() > 1) {
            out.add(new ContextLine(0, "decoded from the JSON record above:", ContextLine.Kind.NOTE));
            for (int i = 1; i < event.lines().size(); i++) {
                out.add(new ContextLine(0, event.lines().get(i),
                        i == pinIndex ? ContextLine.Kind.PINPOINT : ContextLine.Kind.EVENT));
            }
        }
        if (shownEnd < end) {
            out.add(new ContextLine(0, "… " + (end - shownEnd) + " more lines of this event not shown",
                    ContextLine.Kind.NOTE));
        }
        if (after > 0) {
            List<String> tail = session.readLines(end + 1, end + after);
            for (int i = 0; i < tail.size(); i++) {
                out.add(new ContextLine(end + 1 + i, tail.get(i), ContextLine.Kind.CONTEXT));
            }
        }
        return out;
    }
}
