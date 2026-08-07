package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.HMMRegimeMomentumStrategy;
import com.martinfou.trading.strategies.creative.HmmRegimeMomentumStrategy;

import java.util.List;

/**
 * Deep dive — HMM Regime Momentum re-validation AVEC coûts.
 *
 * Both HMM strategies were created pre-fix (May 31 / June 3, 2026) — before the
 * look-ahead bias fix (c7a552db, July 16). They are still registered in the catalog
 * and were NEVER re-validated with the corrected engine + costs. This runner does
 * exactly that: BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001).
 *
 * Two variants tested:
 *   1. HMMRegimeMomentumStrategy — Lewis Jackson simplified 3-state transition
 *      matrix (Bull/Sideways/Bear), 80-bar window, 60/40 blend with trailing
 *      return, ATR trailing stop, cooldown + daily trade cap.
 *   2. HmmRegimeMomentumStrategy — older variant: 3×3 matrix on 50-bar history,
 *      20-bar trailing return regime, ATR exits, max hold 12 bars.
 *
 * Usage:
 *   java -cp "$CP" com.martinfou.trading.examples.RunHmmDeepDive EUR_USD 2006-2026
 */
public class RunHmmDeepDive {

    public static void main(String[] args) throws Exception {
        String symbol = args.length > 0 ? args[0] : "EUR_USD";
        String yearSpec = args.length > 1 ? args[1] : "2006-2026";
        double capital = 50_000;

        var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, yearSpec);
        List<Bar> bars = loaded.bars();
        System.out.printf("%n=== %s (%d bars, %s) — avec coûts $0.07 + 0.01%% slippage ===%n%n",
            symbol, bars.size(), yearSpec);

        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);

        System.out.println("--- HMMRegimeMomentumStrategy (v2, Lewis Jackson 60/40 blend) ---");
        var v2 = new HMMRegimeMomentumStrategy("HMMRegimeMomentum_" + symbol, symbol);
        RunContext.forStrategy(null, "HMMRegimeMomentum_" + symbol, v2, symbol,
            RunMode.BACKTEST, bars, capital, null, cost).run().printSummary();

        System.out.println();
        System.out.println("--- HmmRegimeMomentumStrategy (v1, older 50-bar matrix) ---");
        var v1 = new HmmRegimeMomentumStrategy("HmmRegimeMomentum_" + symbol, symbol);
        RunContext.forStrategy(null, "HmmRegimeMomentum_" + symbol, v1, symbol,
            RunMode.BACKTEST, bars, capital, null, cost).run().printSummary();
    }
}
