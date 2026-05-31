package com.martinfou.trading.parser.codegen;

/** Pip size helper for SL/PT offsets (story 2-9). */
public final class SqPipScale {

    private SqPipScale() {}

    public static double pipSize(String symbol) {
        if (symbol == null) {
            return 0.0001;
        }
        String normalized = symbol.replace("/", "_").toUpperCase();
        if (normalized.contains("JPY")) {
            return 0.01;
        }
        return 0.0001;
    }
}
