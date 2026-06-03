package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.*;
import com.martinfou.trading.core.*;
import com.martinfou.trading.data.HistoricalDataLoader;
import java.util.List;

/**
 * Quick engine test — runs SMA Crossover to verify PF is non-zero.
 */
public class DebugBacktest {
    public static void main(String[] args) throws Exception {
        String symbol = "EUR_USD";
        var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2023-2024");
        List<Bar> bars = loaded.bars();
        System.out.println("DEBUG: Loaded " + bars.size() + " bars for " + symbol);

        Strategy strat = new SmaCrossoverStrategy("SMATest", symbol, 10, 30);
        BacktestResult result = RunContext.forStrategy(
            null, null, strat, symbol, RunMode.BACKTEST, bars, 100000.0, null,
            BacktestExecutionCost.ZERO
        ).run();

        System.out.printf("DEBUG: Trades=%d PF=%.4f Sharpe=%.4f PnL=$%.2f DD=%.2f%%%n",
            result.totalTrades(), result.profitFactor(), result.sharpeRatio(),
            result.totalPnl(), result.maxDrawdownPct());
        System.out.printf("DEBUG: Winners=%d Losers=%d%n",
            result.winningTrades(), result.losingTrades());

        var trades = result.trades();
        for (int i = 0; i < Math.min(10, trades.size()); i++) {
            var t = trades.get(i);
            System.out.printf("DEBUG: Trade %d: %s %s entry=%.5f exit=%.5f PnL=%.2f%n",
                i+1, t.symbol(), t.side(), t.entryPrice(), t.exitPrice(), t.pnl());
        }

        // Test HMMRegimeMomentum (V4)
        System.out.println("\n--- Testing HMMRegimeMomentumStrategy (V4) ---");
        Strategy hmmStrat = new com.martinfou.trading.strategies.creative.HMMRegimeMomentumStrategy(
            "HMMTest", symbol);
        result = RunContext.forStrategy(
            null, null, hmmStrat, symbol, RunMode.BACKTEST, bars, 100000.0, null,
            BacktestExecutionCost.ZERO
        ).run();

        System.out.printf("DEBUG: HMM - Trades=%d PF=%.4f Sharpe=%.4f PnL=$%.2f DD=%.2f%%%n",
            result.totalTrades(), result.profitFactor(), result.sharpeRatio(),
            result.totalPnl(), result.maxDrawdownPct());
        System.out.printf("DEBUG: HMM - Winners=%d Losers=%d%n",
            result.winningTrades(), result.losingTrades());

        trades = result.trades();
        for (int i = 0; i < Math.min(10, trades.size()); i++) {
            var t = trades.get(i);
            System.out.printf("DEBUG: Trade %d: %s %s entry=%.5f exit=%.5f PnL=%.2f%n",
                i+1, t.symbol(), t.side(), t.entryPrice(), t.exitPrice(), t.pnl());
        }

        // Test VWAPMomentum with CORRECT pip-based costs
        System.out.println("\n--- Testing VWAPMomentumStrategy with PIP costs ---");
        double commissionInCurrency = 0.5 * 0.0001 * 10000; // $0.50 per trade
        double pipValue = 0.0001; // EUR/USD pip in price terms
        double units = 10000;
        // CORRECT: slippageFixed is the pip value in price term (0.0001 for EUR/USD)
        // commissionPerTrade is the fixed dollar commission
        BacktestExecutionCost pipCosts = new BacktestExecutionCost(commissionInCurrency, 0.0, 0.0, pipValue, 0.0);
        Strategy vwapPip = new com.martinfou.trading.strategies.creative.VWAPMomentumStrategy(
            "VWAPPip", symbol);
        result = RunContext.forStrategy(
            null, null, vwapPip, symbol, RunMode.BACKTEST, bars, 100000.0, null, pipCosts
        ).run();
        System.out.printf("DEBUG: VWAP+pip - Trades=%d PF=%.4f PNl=$%.2f Comm=$%.2f Slip=$%.2f Winners=%d Losers=%d%n",
            result.totalTrades(), result.profitFactor(), result.totalPnl(),
            result.totalCommission(), result.totalSlippage(),
            result.winningTrades(), result.losingTrades());

        // Test HMM with CORRECT pip-based costs
        System.out.println("\\n--- Testing HMMRegimeMomentum with PIP costs ---");
        Strategy hmmPip = new com.martinfou.trading.strategies.creative.HMMRegimeMomentumStrategy(
            "HMMPip", symbol);
        result = RunContext.forStrategy(
            null, null, hmmPip, symbol, RunMode.BACKTEST, bars, 100000.0, null, pipCosts
        ).run();
        System.out.printf("DEBUG: HMM+pip - Trades=%d PF=%.4f PNl=$%.2f Winners=%d Losers=%d%n",
            result.totalTrades(), result.profitFactor(), result.totalPnl(),
            result.winningTrades(), result.losingTrades());
    }
}
