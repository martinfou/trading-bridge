package com.martinfou.trading.backtest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Canonical RUN_ENDED / run-record payload for backtest metrics (control plane + events). */
public final class BacktestResultPayload {

    private BacktestResultPayload() {}

    public static Map<String, Object> toEndedPayload(BacktestResult result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("totalTrades", result.totalTrades());
        payload.put("totalReturnPct", result.totalReturnPct());
        payload.put("finalEquity", result.finalEquity());
        payload.put("initialCapital", result.initialCapital());
        payload.put("totalPnl", result.totalPnl());
        payload.put("winningTrades", result.winningTrades());
        payload.put("losingTrades", result.losingTrades());
        payload.put("winRatePct", result.winRatePct());
        payload.put("avgTradePnl", result.avgTradePnl());
        payload.put("maxDrawdownPct", result.maxDrawdownPct());
        payload.put("sharpeRatio", result.sharpeRatio());
        payload.put("sortinoRatio", result.sortinoRatio());
        payload.put("profitFactor", result.profitFactor());
        payload.put("calmarRatio", result.calmarRatio());
        if (result.totalCommission() > 0.0 || result.totalSlippage() > 0.0) {
            payload.put("totalCommission", result.totalCommission());
            payload.put("totalSlippage", result.totalSlippage());
        }
        if (result.periodStart() != null) {
            payload.put("periodStart", result.periodStart().toString());
        }
        if (result.periodEnd() != null) {
            payload.put("periodEnd", result.periodEnd().toString());
        }
        List<Map<String, Object>> serializedTrades = new ArrayList<>();
        if (result.trades() != null) {
            for (var t : result.trades()) {
                Map<String, Object> tm = new LinkedHashMap<>();
                tm.put("symbol", t.symbol());
                tm.put("side", t.side().name());
                tm.put("entryPrice", t.entryPrice());
                tm.put("exitPrice", t.exitPrice());
                tm.put("quantity", t.quantity());
                tm.put("entryTime", t.entryTime().toString());
                tm.put("exitTime", t.exitTime().toString());
                tm.put("pnl", t.pnl());
                serializedTrades.add(tm);
            }
        }
        payload.put("trades", serializedTrades);
        payload.put("equityCurveSample", sampleEquityCurve(result.equityCurve(), 500));
        return Map.copyOf(payload);
    }

    private static List<Double> sampleEquityCurve(List<Double> curve, int maxPoints) {
        if (curve == null || curve.isEmpty()) {
            return List.of();
        }
        if (curve.size() <= maxPoints) {
            return List.copyOf(curve);
        }
        List<Double> sampled = new ArrayList<>(maxPoints);
        sampled.add(curve.get(0));
        double step = (double) (curve.size() - 1) / (maxPoints - 1);
        for (int i = 1; i < maxPoints - 1; i++) {
            int index = (int) Math.round(i * step);
            sampled.add(curve.get(index));
        }
        sampled.add(curve.get(curve.size() - 1));
        return sampled;
    }
}
