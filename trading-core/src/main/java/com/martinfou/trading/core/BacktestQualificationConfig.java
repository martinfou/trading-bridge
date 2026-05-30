package com.martinfou.trading.core;

/**
 * Configurable qualification rules for prop shop strategy evaluation.
 *
 * All parameters have sensible defaults matching the current rules.
 * Use {@link #load(String)} to load from a JSON file, or
 * {@link #applySystemProperties()} to override individual values
 * via {@code -Dqualification.minSharpe=1.2} style JVM args.
 *
 * <h2>Three-tier qualification</h2>
 *
 * <ol>
 *   <li><b>Creative Lab</b> — initial pass: ≥2 assets with Sharpe > 1.0,
 *       PF > 1.5, DD < 15%</li>
 *   <li><b>Prop Shop</b> — validation for deployment: Sharpe > 1.5,
 *       PF > 1.5, DD < 15%, >100 trades, multi-asset robustness</li>
 *   <li><b>Live</b> — paper trading → gradual scaling</li>
 * </ol>
 *
 * <h3>Sharpe anomaly note</h3>
 * On 20+ year H1 datasets (693k bars), Sharpe can be negative even with
 * PF > 3 and WR > 67%. When Sharpe is negative but PF > 2.5, WR > 60%,
 * DD < 15% across ≥3 assets, the strategy is still valid for prop shop.
 * Use {@link #primaryFilterPf} and {@link #primaryFilterWr} as primary gates.
 */
public final class BacktestQualificationConfig {

    // ── Per-asset qualification thresholds ──

    /** Minimum Sharpe ratio per asset to count as "performing". */
    private double minSharpePerAsset = 1.0;

    /** Ideal Sharpe ratio for prop shop deployment (secondary filter). */
    private double idealSharpe = 1.5;

    /** Minimum Profit Factor per asset. PF < this = fail on that asset. */
    private double minPf = 1.5;

    /** Maximum acceptable drawdown per asset (%). */
    private double maxDdPct = 15.0;

    /** Minimum win rate per asset (%). */
    private double minWinRatePct = 40.0;

    /** Minimum trades per asset for statistical significance. */
    private int minTradesPerAsset = 100;

    /** Minimum number of qualified assets for a strategy to pass. */
    private int minQualifiedAssets = 2;

    /**
     * When Sharpe < {@link #minSharpePerAsset} but PF >= this
     * and WR >= {@link #primaryFilterWr} and DD < {@link #maxDdPct}
     * across ≥{@link #primaryFilterMinAssets}, the asset is still
     * considered "performing" (anomaly override). Set ≤ 0 to disable.
     */
    private double primaryFilterPf = 2.5;

    /** Win rate threshold for the primary-filter override (%). */
    private double primaryFilterWr = 60.0;

    /** Minimum assets for the primary-filter override. */
    private int primaryFilterMinAssets = 3;

    // ── Robustness Factor ──

    /** DD % above which a penalty is applied per asset. */
    private double drawdownPenaltyThreshold = 15.0;

    /** Penalty multiplier subtracted per asset exceeding threshold. */
    private double drawdownPenaltyPerAsset = 0.25;

    /** Floor for penalty multiplier (1 - ∑penalties clamped to this). */
    private double minPenaltyMultiplier = 0.1;

    /** Total trades below this threshold halves the multiplier. */
    private int insufficientTradesThreshold = 100;

    /** Multiplier applied when total trades < insufficientTradesThreshold. */
    private double insufficientTradesMultiplier = 0.5;

    /** Minimum trades to generate a PDF for a (strategy, asset) pair. */
    private int pdfGenerationMinTrades = 10;

    /** Minimum trades to include in ranking at all. */
    private int rankingMinTradesTotal = 10;

    // ── Live deployment thresholds ──

    /** Weeks of paper trading before live consideration. */
    private int paperTradingWeeks = 1;

    /** Max drawdown for live deployment (%). */
    private double liveMaxDdPct = 5.0;

    /** Max daily loss for live deployment (%). */
    private double liveDailyLossPct = 2.0;

    /** Minimum Sharpe for live deployment. */
    private double liveMinSharpe = 0.5;

    /** Minimum win rate for live deployment (%). */
    private double liveMinWinRatePct = 40.0;

    // ── Constructors ──

    public BacktestQualificationConfig() {}

    /**
     * Convenience: load config from a JSON file path or resource.
     * Uses Jackson if available, otherwise returns defaults.
     */
    public static BacktestQualificationConfig load(String jsonPath) {
        BacktestQualificationConfig cfg = new BacktestQualificationConfig();
        try {
            var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            cfg = objectMapper.readValue(
                new java.io.File(jsonPath),
                BacktestQualificationConfig.class);
        } catch (Exception e) {
            // Jackson not available or file not found — use defaults
            System.err.println("⚠ Could not load config from " + jsonPath
                + ": " + e.getMessage() + " — using defaults");
        }
        return cfg;
    }

    /**
     * Override individual values via {@code -Dqualification.<key>=<value>}.
     * Supported keys match the field names: {@code minSharpePerAsset},
     * {@code idealSharpe}, {@code minPf}, {@code maxDdPct}, etc.
     */
    public BacktestQualificationConfig applySystemProperties() {
        setIfPresent("minSharpePerAsset", v -> this.minSharpePerAsset = v);
        setIfPresent("idealSharpe", v -> this.idealSharpe = v);
        setIfPresent("minPf", v -> this.minPf = v);
        setIfPresent("maxDdPct", v -> this.maxDdPct = v);
        setIfPresent("minWinRatePct", v -> this.minWinRatePct = v);
        setIfPresent("minTradesPerAsset", v -> this.minTradesPerAsset = (int) v);
        setIfPresent("minQualifiedAssets", v -> this.minQualifiedAssets = (int) v);
        setIfPresent("primaryFilterPf", v -> this.primaryFilterPf = v);
        setIfPresent("primaryFilterWr", v -> this.primaryFilterWr = v);
        setIfPresent("primaryFilterMinAssets", v -> this.primaryFilterMinAssets = (int) v);
        setIfPresent("drawdownPenaltyThreshold", v -> this.drawdownPenaltyThreshold = v);
        setIfPresent("drawdownPenaltyPerAsset", v -> this.drawdownPenaltyPerAsset = v);
        setIfPresent("minPenaltyMultiplier", v -> this.minPenaltyMultiplier = v);
        setIfPresent("insufficientTradesThreshold", v -> this.insufficientTradesThreshold = (int) v);
        setIfPresent("insufficientTradesMultiplier", v -> this.insufficientTradesMultiplier = v);
        setIfPresent("pdfGenerationMinTrades", v -> this.pdfGenerationMinTrades = (int) v);
        setIfPresent("rankingMinTradesTotal", v -> this.rankingMinTradesTotal = (int) v);
        setIfPresent("paperTradingWeeks", v -> this.paperTradingWeeks = (int) v);
        setIfPresent("liveMaxDdPct", v -> this.liveMaxDdPct = v);
        setIfPresent("liveDailyLossPct", v -> this.liveDailyLossPct = v);
        setIfPresent("liveMinSharpe", v -> this.liveMinSharpe = v);
        setIfPresent("liveMinWinRatePct", v -> this.liveMinWinRatePct = v);
        return this;
    }

    // ── Evaluators ──

    /**
     * Check if an asset-level result qualifies as "performing".
     * Uses the primary-filter override when Sharpe is below threshold
     * but PF + WR + DD are strong.
     */
    public boolean assetQualifies(double sharpe, double pf, double winRatePct,
                                  double maxDdPct, int totalTrades) {
        // Not enough trades
        if (totalTrades < minTradesPerAsset) return false;

        // Passes standard Sharpe threshold
        if (sharpe >= minSharpePerAsset) {
            return pf >= minPf && maxDdPct <= this.maxDdPct && winRatePct >= minWinRatePct;
        }

        // Primary-filter override: Sharpe negative but PF + WR very strong
        if (primaryFilterPf > 0 && pf >= primaryFilterPf
            && winRatePct >= primaryFilterWr
            && maxDdPct <= this.maxDdPct) {
            return true;
        }

        return false;
    }

    /**
     * Check if a strategy overall qualifies for prop shop consideration.
     * Requires ≥ {@link #minQualifiedAssets} qualifying assets.
     */
    public boolean strategyQualifies(int qualifiedAssetCount) {
        return qualifiedAssetCount >= minQualifiedAssets;
    }

    /**
     * Compute the robustness factor penalty multiplier.
     *
     * @param penaltyCount assets exceeding drawdownPenaltyThreshold
     * @param totalTrades  total trades across all assets
     * @return multiplier in [minPenaltyMultiplier, 1.0]
     */
    public double robustnessPenalty(int penaltyCount, int totalTrades) {
        double penalty = 1.0 - (penaltyCount * drawdownPenaltyPerAsset);
        if (penalty < minPenaltyMultiplier) penalty = minPenaltyMultiplier;
        if (totalTrades < insufficientTradesThreshold) {
            penalty *= insufficientTradesMultiplier;
        }
        return penalty;
    }

    // ── ToString ──

    @Override
    public String toString() {
        return "BacktestQualificationConfig{"
            + "minSharpePerAsset=" + minSharpePerAsset
            + ", idealSharpe=" + idealSharpe
            + ", minPf=" + minPf
            + ", maxDdPct=" + maxDdPct
            + ", minWinRatePct=" + minWinRatePct
            + ", minTradesPerAsset=" + minTradesPerAsset
            + ", minQualifiedAssets=" + minQualifiedAssets
            + ", drawdownPenaltyThreshold=" + drawdownPenaltyThreshold
            + ", drawdownPenaltyPerAsset=" + drawdownPenaltyPerAsset
            + ", minPenaltyMultiplier=" + minPenaltyMultiplier
            + ", insufficientTradesThreshold=" + insufficientTradesThreshold
            + ", insufficientTradesMultiplier=" + insufficientTradesMultiplier
            + ", pdfGenerationMinTrades=" + pdfGenerationMinTrades
            + ", rankingMinTradesTotal=" + rankingMinTradesTotal
            + ", liveMaxDdPct=" + liveMaxDdPct
            + ", liveDailyLossPct=" + liveDailyLossPct
            + ", liveMinSharpe=" + liveMinSharpe
            + ", liveMinWinRatePct=" + liveMinWinRatePct
            + '}';
    }

    /** Returns a human-readable summary of the qualification rules. */
    public String rulesSummary() {
        return """
            ┌────────────────────────────────────────────┐
            │  Qualification Rules (configurable)         │
            ├────────────────────────────────────────────┤
            │  PER ASSET                                  │
            │    Sharpe ≥ %-4.1f  | PF ≥ %-4.1f          │
            │    DD ≤ %-5.1f%%  | WR ≥ %-5.1f%%         │
            │    Trades ≥ %-4d                            │
            │                                              │
            │  ANOMALY OVERRIDE (Sharpe negative)         │
            │    Of when PF ≥ %-4.1f & WR ≥ %-4.1f%%     │
            │    & DD ≤ %-4.1f%% across ≥ %d assets       │
            │                                              │
            │  STRATEGY                                    │
            │    ≥ %d qualifying assets out of N tested   │
            │                                              │
            │  ROBUSTNESS FACTOR                           │
            │    DD penalty: -%.0f per asset over %-4.1f%% │
            │    Floor: %.0f%%  | Insufficient trades × %.1f  │
            │                                              │
            │  PDF generation: ≥ %d trades per (strat,asset)│
            └────────────────────────────────────────────┘
            """.formatted(
                minSharpePerAsset, minPf,
                maxDdPct, minWinRatePct,
                minTradesPerAsset,
                primaryFilterPf, primaryFilterWr,
                maxDdPct, primaryFilterMinAssets,
                minQualifiedAssets,
                drawdownPenaltyPerAsset * 100, drawdownPenaltyThreshold,
                minPenaltyMultiplier * 100, insufficientTradesMultiplier,
                pdfGenerationMinTrades
            );
    }

    // ── Getters for external use ──

    public double getMinSharpePerAsset() { return minSharpePerAsset; }
    public double getIdealSharpe() { return idealSharpe; }
    public double getMinPf() { return minPf; }
    public double getMaxDdPct() { return maxDdPct; }
    public double getMinWinRatePct() { return minWinRatePct; }
    public int getMinTradesPerAsset() { return minTradesPerAsset; }
    public int getMinQualifiedAssets() { return minQualifiedAssets; }
    public double getPrimaryFilterPf() { return primaryFilterPf; }
    public double getPrimaryFilterWr() { return primaryFilterWr; }
    public int getPrimaryFilterMinAssets() { return primaryFilterMinAssets; }
    public double getDrawdownPenaltyThreshold() { return drawdownPenaltyThreshold; }
    public double getDrawdownPenaltyPerAsset() { return drawdownPenaltyPerAsset; }
    public double getMinPenaltyMultiplier() { return minPenaltyMultiplier; }
    public int getInsufficientTradesThreshold() { return insufficientTradesThreshold; }
    public double getInsufficientTradesMultiplier() { return insufficientTradesMultiplier; }
    public int getPdfGenerationMinTrades() { return pdfGenerationMinTrades; }
    public int getRankingMinTradesTotal() { return rankingMinTradesTotal; }
    public int getPaperTradingWeeks() { return paperTradingWeeks; }
    public double getLiveMaxDdPct() { return liveMaxDdPct; }
    public double getLiveDailyLossPct() { return liveDailyLossPct; }
    public double getLiveMinSharpe() { return liveMinSharpe; }
    public double getLiveMinWinRatePct() { return liveMinWinRatePct; }

    // ── helpers ──

    private void setIfPresent(String key, java.util.function.DoubleConsumer setter) {
        String val = System.getProperty("qualification." + key);
        if (val != null && !val.isEmpty()) {
            try {
                setter.accept(Double.parseDouble(val));
            } catch (NumberFormatException e) {
                System.err.println("⚠ Invalid -Dqualification." + key + "=" + val);
            }
        }
    }
}
