package com.martinfou.trading.runtime;

import com.martinfou.trading.data.oanda.OandaAccountSnapshot;

/**
 * Margin protection guard to verify account margin health (Phase 3).
 */
public final class MarginGuard {

    private final double maxMarginUsedPercent;
    private final double maxCloseoutPercent;

    public MarginGuard(double maxMarginUsedPercent, double maxCloseoutPercent) {
        this.maxMarginUsedPercent = maxMarginUsedPercent;
        this.maxCloseoutPercent = maxCloseoutPercent;
    }

    public static MarginGuard loadDefault() {
        return new MarginGuard(80.0, 50.0); // 80% used max, 50% closeout max
    }

    public RiskCheckResult checkMargin(OandaAccountSnapshot snapshot) {
        if (snapshot == null) {
            return RiskCheckResult.fail("margin", "Account snapshot is missing", 0, 0);
        }

        double marginUsedPct = snapshot.marginAvailable() > 0 
            ? (snapshot.marginUsed() / (snapshot.marginUsed() + snapshot.marginAvailable())) * 100.0 
            : 0.0;

        if (marginUsedPct > maxMarginUsedPercent) {
            return RiskCheckResult.fail(
                "margin_used_percent",
                "Margin used " + String.format("%.2f", marginUsedPct) + "% exceeds max " + maxMarginUsedPercent + "%",
                maxMarginUsedPercent,
                marginUsedPct
            );
        }

        if (snapshot.marginCloseoutPercent() > maxCloseoutPercent) {
            return RiskCheckResult.fail(
                "margin_closeout_percent",
                "Margin closeout " + String.format("%.2f", snapshot.marginCloseoutPercent() * 100) + "% exceeds max " + maxCloseoutPercent + "%",
                maxCloseoutPercent,
                snapshot.marginCloseoutPercent() * 100
            );
        }

        return RiskCheckResult.pass();
    }
}
