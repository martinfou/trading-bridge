package com.martinfou.trading.runtime;

/** Result of an automated promote gate check. */
public record GateCheckResult(
    String name,
    boolean passed,
    String message,
    Double threshold,
    Double actual
) {

    public GateCheckResult(String name, boolean passed, String message) {
        this(name, passed, message, null, null);
    }

    public static GateCheckResult numeric(
        String name,
        boolean passed,
        String message,
        double threshold,
        double actual
    ) {
        return new GateCheckResult(name, passed, message, threshold, actual);
    }
}
