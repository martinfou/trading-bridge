package com.martinfou.trading.tui;

import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;

import java.util.List;
import java.util.function.Consumer;

/** Interactive prompts with validation and re-try (no uncaught exceptions on bad input). */
final class TuiPrompts {

    private TuiPrompts() {}

    static String choose(
        LineReader reader,
        String label,
        List<String> options,
        String defaultValue,
        Consumer<String> liveOutput
    ) {
        while (true) {
            try {
                String input = readLine(reader, label, defaultValue);
                if (input.isBlank() && defaultValue != null && !defaultValue.isBlank()) {
                    return defaultValue;
                }
                return TuiSelection.resolve(input, options, label);
            } catch (IllegalArgumentException e) {
                if (liveOutput != null) {
                    liveOutput.accept("  ✗ " + e.getMessage());
                }
            }
        }
    }

    static double readPositiveDouble(
        LineReader reader,
        String label,
        double defaultValue,
        Consumer<String> liveOutput
    ) {
        while (true) {
            try {
                String input = readLine(reader, label, formatDefault(defaultValue));
                if (input.isBlank()) {
                    return defaultValue;
                }
                return parsePositiveDouble(input, label);
            } catch (IllegalArgumentException e) {
                if (liveOutput != null) {
                    liveOutput.accept("  ✗ " + e.getMessage());
                }
            }
        }
    }

    static String readLine(LineReader reader, String label, String defaultValue) {
        try {
            String suffix = defaultValue != null && !defaultValue.isBlank()
                ? " [" + defaultValue + "]: "
                : ": ";
            String line = reader.readLine(label + suffix);
            if (line == null) {
                return "";
            }
            return line.trim();
        } catch (UserInterruptException e) {
            throw e;
        }
    }

    static double parsePositiveDouble(String input, String label) {
        String normalized = input.replace(",", "").replace("$", "").trim();
        try {
            double value = Double.parseDouble(normalized);
            if (value <= 0) {
                throw new IllegalArgumentException(label + " must be positive");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid " + label + ": " + input);
        }
    }

    private static String formatDefault(double value) {
        if (value == Math.rint(value)) {
            return String.format("%.0f", value);
        }
        return String.valueOf(value);
    }
}
