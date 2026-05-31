package com.martinfou.trading.runtime;

/** Result of a pre-trade risk check (Story 16.8). */
public record RiskCheckResult(
    boolean passed,
    String limitName,
    String message,
    Double threshold,
    Double actual
) {

    public static RiskCheckResult pass() {
        return new RiskCheckResult(true, null, "Within risk limits", null, null);
    }

    public static RiskCheckResult fail(String limitName, String message, double threshold, double actual) {
        return new RiskCheckResult(false, limitName, message, threshold, actual);
    }
}
