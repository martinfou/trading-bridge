package com.martinfou.trading.core;

/**
 * Risk-budget position sizing: the lot size is an OUTPUT of the risk budget, never an input.
 *
 * <p><b>Rule (Martin, 2026-09-29).</b> Every trade budgets a fixed percentage of account equity as
 * risk, and the units are derived from the distance to the stop:
 *
 * <pre>
 *   riskAmount = equity × riskPct / 100            (account currency)
 *   units      = riskAmount / (stopDistance × quoteToAccountFactor)
 * </pre>
 *
 * <p><b>Why the conversion factor is mandatory.</b> A P&amp;L is earned in the instrument's QUOTE
 * currency: 1 unit of GBP_JPY moving 1.0 price unit earns 1 JPY, not 1 CAD. The previous cap formula
 * ({@code NAV × risk% / stopDistance}, in {@code LiveStrategyRunner}) omitted the conversion and so
 * treated 1 JPY as 1 CAD. Measured on the CAD practice account (NAV 96,302 CAD, JPY→CAD 0.0091533)
 * that made the cap <b>~1.4× too loose</b> on USD-quoted pairs and <b>~100× too tight</b> on
 * JPY-quoted pairs (GBP_JPY: 2,343 units where the true 1% budget is ~256,000).
 * {@code quoteToAccountFactor} is the value of one quote-currency unit in the account's home currency
 * per one price unit of movement, taken from the broker's own {@code homeConversions} (loss side),
 * which is the same factor OANDA applies to realized P&amp;L — so sizing and P&amp;L accounting can
 * never disagree.
 *
 * <p>Rounding to the broker's lot step (100 units for FX) keeps unit counts orderable; the floor
 * matches {@code Indicators.calcRiskPosition}.
 */
public final class RiskSizing {

    /** Broker lot step for FX: order sizes are multiples of 100 units. */
    public static final long ROUND_TO_UNITS = 100;

    /** Smallest order the broker accepts for FX. */
    public static final long MIN_UNITS = 100;

    private RiskSizing() {}

    /**
     * Loss, in account currency, incurred by {@code units} when price travels {@code stopDistance}.
     *
     * @param units                 position size (sign ignored)
     * @param stopDistance          |entry − stop|, in price units
     * @param quoteToAccountFactor  account-currency value of one quote unit per price unit
     * @return the risk in account currency
     */
    public static double riskAmount(long units, double stopDistance, double quoteToAccountFactor) {
        return Math.abs(units) * Math.abs(stopDistance) * quoteToAccountFactor;
    }

    /**
     * Units whose loss at the stop equals exactly {@code equity × riskPct%}.
     *
     * <p>Returns {@code 0} when the inputs cannot produce a size — no stop distance, no equity, no
     * conversion factor. A caller that receives 0 MUST NOT invent a size: without a denominator the
     * risk budget is undefined.
     *
     * @param equity                account equity (NAV), in account currency
     * @param riskPct               percent of equity to risk on this trade (1.0 = 1%)
     * @param stopDistance          |entry − stop|, in price units
     * @param quoteToAccountFactor  account-currency value of one quote unit per price unit
     * @param roundTo               lot step (use {@link #ROUND_TO_UNITS})
     * @return units, rounded to {@code roundTo}, floored at {@link #MIN_UNITS}; 0 if undefined
     */
    public static long unitsForRisk(double equity, double riskPct, double stopDistance,
                                    double quoteToAccountFactor, long roundTo) {
        if (!(equity > 0) || !(riskPct > 0) || !(stopDistance > 0) || !(quoteToAccountFactor > 0)) {
            return 0;
        }
        double riskAmount = equity * (riskPct / 100.0);
        double units = riskAmount / (stopDistance * quoteToAccountFactor);
        if (!Double.isFinite(units) || units <= 0) return 0;
        long step = Math.max(1, roundTo);
        long rounded = Math.round(units / step) * step;
        return Math.max(MIN_UNITS, rounded);
    }
}
