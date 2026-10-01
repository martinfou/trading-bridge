package com.martinfou.trading.backtest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RealCostModel — the authoritative execution-cost model for OANDA practice accounts.
 *
 * <p>Measured from the broker (read-only, 2026-10-01) and documented in
 * {@code ~/.hermes/cache/scratch/fee-audit-20261001/FEE-AUDIT.md}. It REPLACES
 * {@link BacktestExecutionCost#DEFAULT} as the model to use for these accounts,
 * because those accounts are <em>spread-only</em>: there is no per-trade commission.
 *
 * <h3>The two costs that actually exist</h3>
 * <ol>
 *   <li><b>Spread</b> — median bid/ask spread over 500 H1 {@code price=BA} candles.
 *       Charged as <em>half</em> the spread per leg (entry fill pays half, exit fill
 *       pays half ⇒ one full spread per round trip). Modeled with
 *       {@link BacktestExecutionCost#slippageFixed()} as a PRICE delta (not USD).</li>
 *   <li><b>Swap</b> — OANDA {@code financing.longRate}/{@code shortRate}, an annual
 *       fraction (AUD_USD anchor −0.0040 = −0.40 %/yr). Converted to pips per standard
 *       lot per day by {@link #swapPipsPerDay(double, double, String)}.</li>
 * </ol>
 * <p>Commission is 0 everywhere. {@code BacktestExecutionCost.DEFAULT}'s $0.07 commission
 * and 0.00005 fixed price-delta are wrong on these accounts: 0.00005 is a 100× understatement
 * of the spread on JPY-quoted pairs and a ~10,000× understatement on gold.
 */
public final class RealCostModel {

    private RealCostModel() {}

    // ------------------------------------------------------------------
    // Spread — measured median half-spread per leg, in PRICE units.
    // Source: FEE-AUDIT.md §2 (500 H1 bid+ask candles, price=BA, 2026-10-01).
    // ------------------------------------------------------------------
    /** Half-spread per leg, in price units (one leg = half the quoted spread). */
    public static final Map<String, Double> HALF_SPREAD_PRICE = Map.of(
        "EUR_USD", 0.00008,   // median spread 1.60 pip → 0.80 pip per leg
        "GBP_JPY", 0.0170,    // median spread 3.40 pip → 1.70 pip per leg
        "USD_JPY", 0.0080,    // median spread 1.60 pip → 0.80 pip per leg
        "USD_CHF", 0.000075,  // median spread 1.50 pip → 0.75 pip per leg
        "XAU_USD", 0.265      // median spread 0.53 USD → 0.265 USD per leg (gold pip = 0.01)
    );

    // ------------------------------------------------------------------
    // Financing — OANDA /v3/instruments financing.longRate / shortRate,
    // annual fractions. Read 2026-10-01 from the practice host.
    // ------------------------------------------------------------------
    /** {longRate, shortRate} annual fractions (0.0040 = 0.40 %/yr), broker-sourced. */
    public static final Map<String, double[]> FINANCING_ANNUAL = Map.ofEntries(
        Map.entry("EUR_USD", new double[]{-0.0248, 0.0045}),
        Map.entry("GBP_USD", new double[]{-0.0124, -0.0080}),
        Map.entry("USD_JPY", new double[]{0.0180, -0.0382}),
        Map.entry("AUD_USD", new double[]{-0.0040, -0.0166}),   // anchor: AUD−USD differential
        Map.entry("NZD_USD", new double[]{-0.0227, 0.0014}),
        Map.entry("USD_CAD", new double[]{0.0067, -0.0284}),
        Map.entry("USD_CHF", new double[]{0.0316, -0.0528}),
        Map.entry("GBP_JPY", new double[]{0.0156, -0.0363}),
        Map.entry("EUR_GBP", new double[]{-0.0229, 0.0019}),
        Map.entry("AUD_JPY", new double[]{0.0242, -0.0449}),
        Map.entry("NZD_JPY", new double[]{0.0052, -0.0268}),
        Map.entry("EUR_JPY", new double[]{0.0033, -0.0237}),
        Map.entry("XAU_USD", new double[]{-0.0569, 0.0323})
    );

    /** Mid prices used to convert the annual rates into pips/lot/day (2026-10-01). */
    public static final Map<String, Double> REFERENCE_MIDS = Map.ofEntries(
        Map.entry("EUR_USD", 1.12456), Map.entry("GBP_USD", 1.31978), Map.entry("USD_JPY", 157.925),
        Map.entry("AUD_USD", 0.692945), Map.entry("NZD_USD", 0.56014), Map.entry("USD_CAD", 1.422295),
        Map.entry("USD_CHF", 0.83099), Map.entry("GBP_JPY", 208.433), Map.entry("EUR_GBP", 0.85204),
        Map.entry("AUD_JPY", 109.4), Map.entry("NZD_JPY", 88.4375), Map.entry("EUR_JPY", 177.544),
        Map.entry("XAU_USD", 4182.07)
    );

    /** Days per year used for the daily swap conversion. */
    public static final double DAYS_PER_YEAR = 365.0;

    /**
     * Price units per pip for an instrument.
     * <ul>
     *   <li>JPY-quoted FX (USD_JPY, GBP_JPY, …): 0.01</li>
     *   <li>Metals (XAU_USD, XAG_USD): 0.01 (one cent per troy ounce)</li>
     *   <li>Everything else (EUR_USD, USD_CHF, …): 0.0001</li>
     * </ul>
     */
    public static double pipSize(String symbol) {
        if (symbol == null) return 0.0001;
        String s = symbol.toUpperCase();
        if (s.contains("JPY")) return 0.01;
        if (s.startsWith("XAU") || s.startsWith("XAG")) return 0.01;
        return 0.0001;
    }

    /**
     * Swap in pips per standard lot per day, from an annual financing fraction.
     *
     * <pre>
     *   swap[pips/lot/day] = annualRate × midPrice / (pipSize × 365)
     * </pre>
     *
     * <p>Derivation (notional in the QUOTE currency):
     * {@code swap/day = annualRate × units × price / 365}; dividing by the pip value
     * {@code pipSize × units} gives {@code annualRate × price / (pipSize × 365)}.
     * The pip value in account currency is a separate multiplier (USD→CAD for this account)
     * and does not change the pip count.
     */
    public static double swapPipsPerDay(double annualRate, double midPrice, String symbol) {
        return annualRate * midPrice / (pipSize(symbol) * DAYS_PER_YEAR);
    }

    /** Half-spread (price units) for an instrument; conservative 1-pip fallback when unmeasured. */
    public static double halfSpread(String symbol) {
        Double h = HALF_SPREAD_PRICE.get(symbol);
        if (h != null) return h;
        return pipSize(symbol) * 0.5; // unmeasured pair: 0.5 pip per leg
    }

    /**
     * The cost profile for one instrument: zero commission, measured half-spread per leg.
     * {@code refPrice} is the current mid, used to turn the half-spread into a stop-slippage
     * percentage for SL fills ({@code half / refPrice}).
     */
    public static BacktestExecutionCost costFor(String symbol, double refPrice) {
        double half = halfSpread(symbol);
        double stopPct = refPrice > 0 ? half / refPrice : 0.0;
        return new BacktestExecutionCost(0.0, 0.0, 0.0, half, stopPct);
    }

    /** Static construction order used by {@code RunCostModelReal} for self-documenting output. */
    public static Map<String, Object> modelSummary() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("commissionPerTrade", 0.0);
        m.put("spreadSource", "median bid/ask, 500 H1 BA candles, 2026-10-01");
        m.put("swapSource", "OANDA /v3/instruments financing (annual fraction)");
        m.put("halfSpreadPerLeg", HALF_SPREAD_PRICE);
        m.put("financingAnnual", FINANCING_ANNUAL);
        return m;
    }
}
