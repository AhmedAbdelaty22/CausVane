package io.easylog.model;

import java.util.Locale;

/** Source language / runtime a stack frame was produced by. */
public enum Language {
    JAVA("Java/JVM"),
    DOTNET(".NET"),
    PYTHON("Python"),
    NODE("Node.js/JS"),
    GO("Go"),
    RUBY("Ruby"),
    PHP("PHP"),
    RUST("Rust"),
    CPP("C/C++"),
    UNKNOWN("Unknown");

    private final String displayName;

    Language(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    /** Best-effort language guess from a source file extension. */
    public static Language fromFile(String file) {
        if (file == null) {
            return UNKNOWN;
        }
        int dot = file.lastIndexOf('.');
        if (dot < 0) {
            return UNKNOWN;
        }
        return switch (file.substring(dot + 1).toLowerCase(Locale.ROOT)) {
            case "java", "kt", "kts", "scala", "groovy", "clj" -> JAVA;
            case "cs", "vb", "fs" -> DOTNET;
            case "py" -> PYTHON;
            case "js", "mjs", "cjs", "ts", "jsx", "tsx", "vue" -> NODE;
            case "go" -> GO;
            case "rb", "erb", "rake" -> RUBY;
            case "php", "phtml" -> PHP;
            case "rs" -> RUST;
            case "c", "cc", "cpp", "cxx", "h", "hpp", "hh" -> CPP;
            default -> UNKNOWN;
        };
    }
}
