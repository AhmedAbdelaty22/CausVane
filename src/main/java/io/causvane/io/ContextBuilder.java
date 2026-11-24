package io.causvane.io;

import io.causvane.explain.LineExplainer;
import io.causvane.model.LogEvent;
import io.causvane.model.StackFrame;
import io.causvane.parser.LogParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Builds the lines shown around an error occurrence, marking the exact failing line. */
public final class ContextBuilder {

    /**
     * @param lineNumber  physical line number, or 0 for lines that do not exist in the file
     *                    (decoded JSON stack traces, notes)
     * @param role        the part this line plays in the error
     * @param explanation what the line means, in plain words
     */
    public record ContextLine(long lineNumber, String text, Kind kind, LineExplainer.Role role, String explanation) {
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
            if (number < start) {
                out.add(context(number, head.get(i), true));
                continue;
            }
            int index = (int) (number - start);
            ContextLine.Kind kind = number == pinLine && !decodedPin ? ContextLine.Kind.PINPOINT : ContextLine.Kind.EVENT;
            LineExplainer.Note note = event.structured()
                    ? LineExplainer.explainEventLine(event, 0)
                    : index < event.lines().size()
                    ? LineExplainer.explainEventLine(event, index)
                    : new LineExplainer.Note(LineExplainer.Role.DETAIL, "More detail that belongs to this log entry.");
            out.add(new ContextLine(number, head.get(i), kind, note.role(), note.text()));
        }
        if (event.structured() && event.lines().size() > 1) {
            out.add(note("decoded from the JSON record above:"));
            for (int i = 1; i < event.lines().size(); i++) {
                LineExplainer.Note note = LineExplainer.explainEventLine(event, i);
                out.add(new ContextLine(0, event.lines().get(i),
                        i == pinIndex ? ContextLine.Kind.PINPOINT : ContextLine.Kind.EVENT, note.role(), note.text()));
            }
        }
        if (shownEnd < end) {
            out.add(note("… " + (end - shownEnd) + " more lines of this event not shown"));
        }
        if (after > 0) {
            List<String> tail = session.readLines(end + 1, end + after);
            for (int i = 0; i < tail.size(); i++) {
                out.add(context(end + 1 + i, tail.get(i), false));
            }
        }
        return out;
    }

    private static ContextLine context(long number, String text, boolean before) {
        LineExplainer.Note note = LineExplainer.explainContextLine(text, before);
        return new ContextLine(number, text, ContextLine.Kind.CONTEXT, note.role(), note.text());
    }

    private static ContextLine note(String text) {
        return new ContextLine(0, text, ContextLine.Kind.NOTE, LineExplainer.Role.NOTE, text);
    }
}
