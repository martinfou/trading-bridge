package com.martinfou.trading.backtest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Configurable execution costs for {@link BacktestEngine} runs.
 * Zero-cost is the default — preserves legacy fill semantics when unset.
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
