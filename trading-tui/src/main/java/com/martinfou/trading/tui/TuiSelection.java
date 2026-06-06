package com.martinfou.trading.tui;

import java.util.List;

/** Parses numbered menu choices for interactive TUI prompts. */
final class TuiSelection {

    private TuiSelection() {}

    static String resolve(String input, List<String> options) {
        return resolve(input, options, null);
    }

    /**
     * Resolves a menu choice. Numeric input picks by 1-based index; otherwise matches option text
     * or returns the trimmed input if {@code allowFreeText} is true.
     */
    static String resolve(String input, List<String> options, String optionLabel) {
        if (options == null || options.isEmpty()) {
            throw new IllegalArgumentException("No " + label(optionLabel) + "available");
        }
        String trimmed = input == null ? "" : input.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Empty choice — enter a number or id");
        }
        if (trimmed.matches("\\d+")) {
            int index = Integer.parseInt(trimmed);
            if (index < 1 || index > options.size()) {
                throw new IllegalArgumentException(
                    "Choice " + index + " out of range (1–" + options.size() + ")");
            }
            return options.get(index - 1);
        }
        for (String option : options) {
            if (option.equalsIgnoreCase(trimmed)) {
                return option;
            }
        }
        throw new IllegalArgumentException(
            "Unknown " + label(optionLabel) + "'" + trimmed + "' — use 1–"
                + options.size() + " or the exact id from the list");
    }

    private static String label(String optionLabel) {
        return optionLabel == null || optionLabel.isBlank() ? "" : optionLabel + " ";
    }

    static int indexOfIgnoreCase(List<String> options, String value) {
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).equalsIgnoreCase(value)) {
                return i + 1;
            }
        }
        return -1;
    }
}
