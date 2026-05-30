package com.martinfou.trading.backtest;

import com.martinfou.trading.core.TimeConventions;
import com.martinfou.trading.core.Trade;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Complete result of a backtest run, including basic metrics,
 * advanced performance ratios, and full trade/equity history.
 *
 * <p>Advanced metrics (Sharpe, Sortino, etc.) are populated by
 * {@link BacktestEngine#run()} when the engine has sufficient data.
 * For manual construction, use {@link #Builder}.</p>
 *
 * @param strategyName      strategy identifier
 * @param initialCapital    starting account balance
 * @param finalEquity       ending account balance
 * @param totalPnl          net profit/loss in currency units
 * @param totalReturnPct    total return as percentage
 * @param totalTrades       number of completed round-trip trades
 * @param winningTrades     number of profitable trades
 * @param losingTrades      number of losing trades
 * @param winRatePct        winning trades / total trades * 100
 * @param maxDrawdownPct    maximum peak-to-trough decline as percentage
 * @param avgTradePnl       arithmetic mean P&amp;L per trade
 * @param sharpeRatio       annualised Sharpe Ratio
 * @param sortinoRatio      annualised Sortino Ratio
 * @param profitFactor      gross profit / gross loss (decimal)
 * @param calmarRatio       annualised return / max drawdown
 * @param totalCommission   total commission paid
 * @param totalSlippage     total slippage incurred
 * @param equityCurve       equity values after each bar (list of doubles)
 * @param trades            list of completed trades
 * @param periodStart       start of backtest period
 * @param periodEnd         end of backtest period
 * @param periodsPerYear    detected trading periods per year (for Sharpe/Sortino annualisation)
 */
public record BacktestResult(
    String strategyName,
    double initialCapital,
    double finalEquity,
    double totalPnl,
    double totalReturnPct,
    int totalTrades,
    int winningTrades,
    int losingTrades,
    double winRatePct,
    double maxDrawdownPct,
    double avgTradePnl,
    double sharpeRatio,
    double sortinoRatio,
    double profitFactor,
    double calmarRatio,
    double totalCommission,
    double totalSlippage,
    List<Double> equityCurve,
    List<Trade> trades,
    Instant periodStart,
    Instant periodEnd,
    double periodsPerYear
) {

    /** Prints a comprehensive summary to stdout. */
    public void printSummary() {
        System.out.println("\n================================================");
        System.out.println("  BACKTEST RESULT: " + strategyName);
        System.out.println("================================================");
        System.out.println("Period:        " + TimeConventions.toDisplayString(periodStart)
            + " → " + TimeConventions.toDisplayString(periodEnd));
        System.out.println("Initial Cap:   $" + String.format("%,.2f", initialCapital));
        System.out.println("Final Equity:  $" + String.format("%,.2f", finalEquity));
        System.out.println("P&L:           $" + String.format("%,.2f", totalPnl)
            + " (" + String.format("%.2f", totalReturnPct) + "%)");
        System.out.println("─── Trades ─────────────────────────────────");
        System.out.println("Total Trades:  " + totalTrades);
        System.out.println("Winners:       " + winningTrades + " | Losers: " + losingTrades);
        System.out.println("Win Rate:      " + String.format("%.1f", winRatePct) + "%");
        System.out.println("Avg Trade:     $" + String.format("%.2f", avgTradePnl));
        System.out.println("─── Risk ───────────────────────────────────");
        System.out.println("Max DD:        " + String.format("%.2f", maxDrawdownPct) + "%");
        System.out.println("Sharpe:        " + String.format("%.2f", sharpeRatio));
        System.out.println("Sortino:       " + String.format("%.2f", sortinoRatio));
        System.out.println("Profit Fact:   " + String.format("%.2f", profitFactor));
        System.out.println("Calmar:        " + String.format("%.2f", calmarRatio));
        System.out.println("─── Costs ──────────────────────────────────");
        System.out.println("Commission:    $" + String.format("%.2f", totalCommission));
        System.out.println("Slippage:      $" + String.format("%.2f", totalSlippage));
        System.out.println("===============================================\n");
    }

    /**
     * Returns the period returns suitable for Sharpe / Sortino calculation,
     * derived from the equity curve as percentage changes between consecutive points.
     */
    public List<Double> periodReturns() {
        if (equityCurve == null || equityCurve.size() < 2) return List.of();
        List<Double> returns = new ArrayList<>(equityCurve.size() - 1);
        for (int i = 1; i < equityCurve.size(); i++) {
            double prev = equityCurve.get(i - 1);
            if (prev != 0.0) {
                returns.add((equityCurve.get(i) - prev) / prev);
            }
        }
        return returns;
    }

    /**
     * Returns the P&amp;L of each trade as a flat list for Profit Factor, etc.
     */
    public List<Double> tradePnlList() {
        if (trades == null) return List.of();
        return trades.stream().map(Trade::pnl).toList();
    }

    // ---------------------------------------------------------------
    //  Builder for convenient construction (especially from tests)
    // ---------------------------------------------------------------

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent builder for {@link BacktestResult}. All numeric fields default to 0.
     */
    public static final class Builder {
        private String strategyName = "";
        private double initialCapital;
        private double finalEquity;
        private double totalPnl;
        private double totalReturnPct;
        private int totalTrades;
        private int winningTrades;
        private int losingTrades;
        private double winRatePct;
        private double maxDrawdownPct;
        private double avgTradePnl;
        private double sharpeRatio;
        private double sortinoRatio;
        private double profitFactor;
        private double calmarRatio;
        private double totalCommission;
        private double totalSlippage;
        private List<Double> equityCurve = List.of();
        private List<Trade> trades = List.of();
        private Instant periodStart;
        private Instant periodEnd;
        private double periodsPerYear = 252.0;

        public Builder strategyName(String v) { this.strategyName = v; return this; }
        public Builder initialCapital(double v) { this.initialCapital = v; return this; }
        public Builder finalEquity(double v) { this.finalEquity = v; return this; }
        public Builder totalPnl(double v) { this.totalPnl = v; return this; }
        public Builder totalReturnPct(double v) { this.totalReturnPct = v; return this; }
        public Builder totalTrades(int v) { this.totalTrades = v; return this; }
        public Builder winningTrades(int v) { this.winningTrades = v; return this; }
        public Builder losingTrades(int v) { this.losingTrades = v; return this; }
        public Builder winRatePct(double v) { this.winRatePct = v; return this; }
        public Builder maxDrawdownPct(double v) { this.maxDrawdownPct = v; return this; }
        public Builder avgTradePnl(double v) { this.avgTradePnl = v; return this; }
        public Builder sharpeRatio(double v) { this.sharpeRatio = v; return this; }
        public Builder sortinoRatio(double v) { this.sortinoRatio = v; return this; }
        public Builder profitFactor(double v) { this.profitFactor = v; return this; }
        public Builder calmarRatio(double v) { this.calmarRatio = v; return this; }
        public Builder totalCommission(double v) { this.totalCommission = v; return this; }
        public Builder totalSlippage(double v) { this.totalSlippage = v; return this; }
        public Builder equityCurve(List<Double> v) { this.equityCurve = v; return this; }
        public Builder trades(List<Trade> v) { this.trades = v; return this; }
        public Builder periodStart(Instant v) { this.periodStart = v; return this; }
        public Builder periodEnd(Instant v) { this.periodEnd = v; return this; }
        public Builder periodsPerYear(double v) { this.periodsPerYear = v; return this; }

        public BacktestResult build() {
            return new BacktestResult(
                strategyName, initialCapital, finalEquity, totalPnl, totalReturnPct,
                totalTrades, winningTrades, losingTrades, winRatePct, maxDrawdownPct,
                avgTradePnl, sharpeRatio, sortinoRatio, profitFactor, calmarRatio,
                totalCommission, totalSlippage, equityCurve, trades, periodStart, periodEnd,
                periodsPerYear
            );
        }
    }
}
