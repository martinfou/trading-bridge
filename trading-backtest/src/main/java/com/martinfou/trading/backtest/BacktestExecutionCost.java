package com.martinfou.trading.backtest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configurable execution costs for {@link BacktestEngine} runs.
 * Zero-cost must be requested explicitly (sensitivity tests); the default
 * used by {@link RunContext#forStrategy} is {@link #DEFAULT} (US-39.1).
 */
public record BacktestExecutionCost(
    double commissionPerTrade,
    double commissionPct,
    double slippagePct,
    double slippageFixed,
    double stopSlippagePct
) {

    public static final BacktestExecutionCost ZERO =
        new BacktestExecutionCost(0.0, 0.0, 0.0, 0.0, 0.0);

    /**
     * Legacy default cost model — ABANDONED for OANDA practice accounts.
     *
     * <p>$0.07 flat commission per trade plus 0.00005 fixed price-delta per leg.
     * The $0.07 commission does not exist on these spread-only accounts, and the
     * 0.00005 price-delta is a ~100× understatement of the real spread on JPY-quoted
     * pairs and ~10,000× on gold (FEE-AUDIT.md §1). Use {@link RealCostModel#costFor}
     * instead; this constant is kept only so existing call sites are visibly wrong
     * rather than silently changed.
     */
    @Deprecated
    public static final BacktestExecutionCost DEFAULT =
        new BacktestExecutionCost(0.07, 0.0, 0.0, 0.00005, 0.0);

    /**
     * OANDA Spread execution cost model.
     * Realistic representation for EUR/USD: 1 pip total round-trip spread,
     * modeled as 0.5 pip (0.00005) fixed slippage per leg, no flat commission.
     */
    public static final BacktestExecutionCost OANDA_SPREAD =
        new BacktestExecutionCost(0.0, 0.0, 0.0, 0.00005, 0.0);

    public static BacktestExecutionCost ofCommissionAndSlippage(double commissionPerTrade, double slippagePct) {
        return new BacktestExecutionCost(commissionPerTrade, 0.0, slippagePct, 0.0, 0.0);
    }

    public boolean isZero() {
        return commissionPerTrade == 0.0
            && commissionPct == 0.0
            && slippagePct == 0.0
            && slippageFixed == 0.0
            && stopSlippagePct == 0.0;
    }

    /** Applies this cost profile to a fresh engine (fluent chain). */
    public BacktestEngine configure(BacktestEngine engine) {
        if (isZero()) {
            return engine;
        }
        return engine
            .withCommissionFixed(commissionPerTrade)
            .withCommissionPct(commissionPct)
            .withSlippagePct(slippagePct)
            .withSlippageFixed(slippageFixed)
            .withStopSlippagePct(stopSlippagePct);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (commissionPerTrade != 0.0) {
            map.put("commissionPerTrade", commissionPerTrade);
        }
        if (commissionPct != 0.0) {
            map.put("commissionPct", commissionPct);
        }
        if (slippagePct != 0.0) {
            map.put("slippagePct", slippagePct);
        }
        if (slippageFixed != 0.0) {
            map.put("slippageFixed", slippageFixed);
        }
        if (stopSlippagePct != 0.0) {
            map.put("stopSlippagePct", stopSlippagePct);
        }
        return Map.copyOf(map);
    }
}
