package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.prop.EmaPullbackContinuationStrategy;
import com.martinfou.trading.strategies.creative.AugustRiskFadeStrategy;

import java.util.List;

/**
 * RunRebaselineFeesSwaps — Re-baseline des stratégies clés avec le moteur
 * fees-swaps corrigé (39.1 coût par défaut non-zéro + 39.2 swap sur sorties
 * SL/TP/force-close). Epic 41.1 (re-baseline & validation).
 *
 * Cible 1 : EmaPullbackContinuation (seul PF ~1.05 post-fix look-ahead) —
 *   avant 39.2, les sorties SL/TP ne portaient AUCUN swap. Maintenant elles
 *   en portent. Le PF "empirical reality" peut bouger.
 * Cible 2 : AugustRiskFade (rejeté le 12 août, en partie à cause du swap).
 *
 * Usage:
 *   java -cp "$CP" com.martinfou.trading.examples.RunRebaselineFeesSwaps
 */
public class RunRebaselineFeesSwaps {

    static final String[] PAIRS = {
        "EUR_USD", "GBP_USD", "USD_JPY", "USD_CHF",
        "USD_CAD", "AUD_USD", "NZD_USD", "GBP_JPY"
    };
    static final String[] AUGUST_PAIRS = {"AUD_USD", "NZD_USD", "GBP_USD"};
    static final String YEAR_SPEC = "2006-2026";
    static final double CAPITAL = 50_000;

    public static void main(String[] args) throws Exception {
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);

        System.out.println("==================================================");
        System.out.println("RE-BASELINE MOTEUR CORRIGÉ (39.1 + 39.2)");
        System.out.println("Coûts: commission $0.07 + slippage 0.01%");
        System.out.println("Swap: compté sur TOUTES les sorties (SL/TP/closeOnly/force-close)");
        System.out.println("==================================================");

        System.out.println("\n--- CIBLE 1 : EmaPullbackContinuation (8 paires) ---");
        for (String symbol : PAIRS) {
            var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, YEAR_SPEC);
            List<Bar> bars = loaded.bars();
            if (bars.isEmpty()) { System.out.println(symbol + " : PAS DE DONNÉES"); continue; }
            var strategy = new EmaPullbackContinuationStrategy(symbol);
            BacktestResult r = RunContext.forStrategy(null, "Prop_EMAPullback", strategy, symbol,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-8s bars=%-6d PF=%-6.2f WR=%-5.1f%% DD=%-6.2f%% trades=%-5d "
                    + "net=$%10.2f ret=%-7.2f%% swap=$%8.2f comm=$%8.2f%n",
                symbol, bars.size(), r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                r.totalTrades(), r.totalPnl(), r.totalReturnPct(), r.totalSwap(), r.totalCommission());
        }

        System.out.println("\n--- CIBLE 2 : AugustRiskFade (re-test post-39.2) ---");
        for (String symbol : AUGUST_PAIRS) {
            var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, YEAR_SPEC);
            List<Bar> bars = loaded.bars();
            if (bars.isEmpty()) { System.out.println(symbol + " : PAS DE DONNÉES"); continue; }
            var strategy = new AugustRiskFadeStrategy("AugustRiskFade", symbol);
            BacktestResult r = RunContext.forStrategy(null, "AugustRiskFade", strategy, symbol,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-8s bars=%-6d PF=%-6.2f WR=%-5.1f%% DD=%-6.2f%% trades=%-5d "
                    + "net=$%10.2f ret=%-7.2f%% swap=$%8.2f comm=$%8.2f%n",
                symbol, bars.size(), r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                r.totalTrades(), r.totalPnl(), r.totalReturnPct(), r.totalSwap(), r.totalCommission());
        }
        System.out.println("\nDONE");
    }
}
