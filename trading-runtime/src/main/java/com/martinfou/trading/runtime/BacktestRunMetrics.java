package com.martinfou.trading.runtime;

import java.util.Map;

/** Metrics extracted from a completed BACKTEST run for promote gates. */
record BacktestRunMetrics(int totalTrades, double totalReturnPct, double maxDrawdownPct, Double winRatePct) {

    static BacktestRunMetrics fromRun(RunRecord run) {
        Map<String, Object> payload = run.endedPayload().orElse(Map.of());
        int trades = ((Number) payload.getOrDefault("totalTrades", 0)).intValue();
        double returnPct = ((Number) payload.getOrDefault("totalReturnPct", 0.0)).doubleValue();
        double maxDd = ((Number) payload.getOrDefault("maxDrawdownPct", 0.0)).doubleValue();
        Double winRate = payload.get("winRatePct") instanceof Number n ? n.doubleValue() : null;
        return new BacktestRunMetrics(trades, returnPct, maxDd, winRate);
    }
}
