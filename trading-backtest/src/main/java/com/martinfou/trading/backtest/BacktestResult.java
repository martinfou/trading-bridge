package com.martinfou.trading.backtest;

import com.martinfou.trading.core.TimeConventions;
import com.martinfou.trading.core.Trade;

import java.time.Instant;
import java.util.List;

public record BacktestResult(
    String strategyName,
    double initialCapital,
    double finalEquity,
    double totalPnl,
    double totalReturnPct,
    int totalTrades,
    double winRatePct,
    double maxDrawdownPct,
    List<Double> equityCurve,
    List<Trade> trades,
    Instant periodStart,
    Instant periodEnd
) {
    public void printSummary() {
        System.out.println("\n===========================================");
        System.out.println("BACKTEST RESULT: " + strategyName);
        System.out.println("===========================================");
        System.out.println("Period:     " + TimeConventions.toDisplayString(periodStart)
            + " → " + TimeConventions.toDisplayString(periodEnd));
        System.out.println("Capital:    $" + String.format("%.2f", initialCapital) + " → $" + String.format("%.2f", finalEquity));
        System.out.println("P&L:        $" + String.format("%.2f", totalPnl) + " (" + String.format("%.2f", totalReturnPct) + "%)");
        System.out.println("Trades:     " + totalTrades);
        System.out.println("Win Rate:   " + String.format("%.1f", winRatePct) + "%");
        System.out.println("Max DD:     " + String.format("%.2f", maxDrawdownPct) + "%");
        System.out.println("===========================================\n");
    }
}
