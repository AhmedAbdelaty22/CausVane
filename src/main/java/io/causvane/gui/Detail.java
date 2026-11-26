package io.causvane.gui;

/**
 * How much technical detail the window shows, chosen with the Simple · Normal · Expert switch and
 * remembered between launches. Simple is for someone who only needs to know what went wrong and what to do;
 * Expert puts the error names, code locations and the whole error tree first.
 */
enum Detail {
    SIMPLE("Simple", "Plain words only: no error names, code locations or step-by-step trace"),
    NORMAL("Normal", "Plain words first, with the technical details underneath"),
    EXPERT("Expert", "The technical details first, with every step of the error opened");

    private final String label;
    private final String hint;
    private static Detail current;

    Detail(String label, String hint) {
        this.label = label;
        this.hint = hint;
    }

    String label() {
        return label;
    }

    String hint() {
        return hint;
    }

    static synchronized Detail level() {
        if (current == null) {
            current = Settings.get().detail();
        }
        return current;
    }

    static synchronized void set(Detail level) {
        current = level;
        Settings.get().detail(level);
    }

    static boolean simple() {
        return level() == SIMPLE;
    }

    static boolean expert() {
        return level() == EXPERT;
    }
}
