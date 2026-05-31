package com.martinfou.trading.parser.indicators;

import java.util.Optional;

final class SqParamLiterals {

    private SqParamLiterals() {}

    static Optional<Integer> parseInt(String text, int defaultValue) {
        if (text == null || text.isBlank()) {
            return Optional.of(defaultValue);
        }
        try {
            return Optional.of(Integer.parseInt(text.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    static Optional<Double> parseDouble(String text, double defaultValue) {
        if (text == null || text.isBlank()) {
            return Optional.of(defaultValue);
        }
        try {
            return Optional.of(Double.parseDouble(text.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
