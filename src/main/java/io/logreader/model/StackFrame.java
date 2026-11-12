package io.logreader.model;

/**
 * One frame of a stack trace.
 *
 * @param file           source file as written in the log (may be a full path), or null
 * @param module         class / namespace / package owning the function, or null
 * @param function       method or function name, or null
 * @param line           1-based source line, or -1 when unknown
 * @param column         1-based column, or -1 when unknown
 * @param application    true for application code, false for framework / library / runtime frames
 * @param eventLineIndex index of the line inside {@link LogEvent#lines()} this frame was parsed from
 */
public record StackFrame(
        Language language,
        String file,
        String module,
        String function,
        int line,
        int column,
        boolean application,
        int eventLineIndex) {

    /** File name without its directory. */
    public String fileName() {
        if (file == null) {
            return null;
        }
        int cut = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        return cut >= 0 && cut < file.length() - 1 ? file.substring(cut + 1) : file;
    }

    /** Compact {@code File:Line} location used in tables and summaries. */
    public String location() {
        String name = fileName();
        if (name != null) {
            return line > 0 ? name + ":" + line : name;
        }
        return module != null ? module : (function != null ? function : "?");
    }

    public String qualifiedFunction() {
        if (function == null) {
            return module;
        }
        return module == null ? function : module + "." + function;
    }

    public StackFrame withFunction(String newModule, String newFunction) {
        return new StackFrame(language, file, newModule, newFunction, line, column, application, eventLineIndex);
    }
}
