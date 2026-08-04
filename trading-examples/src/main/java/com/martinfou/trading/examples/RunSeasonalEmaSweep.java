package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.SeasonalEmaPullbackContinuation;
import com.martinfou.trading.strategies.prop.EmaPullbackContinuationStrategy;

import java.util.List;

/**
 * Sweep paramétrique ±20% sur SeasonalEmaPullbackContinuation (EUR/USD).
 * Teste les plateaux : EMA20 (16-24), EMA50 (40-60), EMA200 (160-240).
 * Plateau = robuste. Pic = curve fitting.
 */
public class RunSeasonalEmaSweep {

    public static void main(String[] args) throws Exception {
        String symbol = args.length > 0 ? args[0] : "EUR_USD";
        String yearSpec = args.length > 1 ? args[1] : "2006-2026";
        double capital = 50_000;

        var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, yearSpec);
        List<Bar> bars = loaded.bars();
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);

        System.out.printf("%n=== SWEEP %s (%d bars) — coûts $0.07 + 0.01%% ===%n", symbol, bars.size());
        System.out.println("Baseline (non-saisonnier) pour référence:");
        var base = new EmaPullbackContinuationStrategy(symbol);
        BacktestResult br = RunContext.forStrategy(null, "Base", base, symbol,
            RunMode.BACKTEST, bars, capital, null, cost).run();
        System.out.printf("  PF=%.3f  Trades=%d  Net=$%.0f%n", br.profitFactor(), br.totalTrades(), br.totalPnl());

        System.out.println("Sweep EMA20 (EMA50=50, EMA200=200):");
        for (int e20 : new int[]{16, 18, 20, 22, 24}) {
            runOne(symbol, bars, capital, cost, e20, 50, 200);
        }
        System.out.println("Sweep EMA50 (EMA20=20, EMA200=200):");
        for (int e50 : new int[]{40, 45, 50, 55, 60}) {
            runOne(symbol, bars, capital, cost, 20, e50, 200);
        }
        System.out.println("Sweep EMA200 (EMA20=20, EMA50=50):");
        for (int e200 : new int[]{160, 180, 200, 220, 240}) {
            runOne(symbol, bars, capital, cost, 20, 50, e200);
        }
    }

    private static void runOne(String symbol, List<Bar> bars, double capital,
                               BacktestExecutionCost cost, int e20, int e50, int e200) {
        var s = new ParamSeasonalEmaPullback(symbol, e20, e50, e200);
        BacktestResult r = RunContext.forStrategy(null, "SeasonalSweep", s, symbol,
            RunMode.BACKTEST, bars, capital, null, cost).run();
        System.out.printf("  EMA20=%d EMA50=%d EMA200=%d → PF=%.3f  Trades=%d  Net=$%.0f  DD=%.1f%%%n",
            e20, e50, e200, r.profitFactor(), r.totalTrades(), r.totalPnl(), r.maxDrawdownPct());
    }

    /** Version paramétrique de SeasonalEmaPullbackContinuation (mêmes règles, EMA configurables). */
    static final class ParamSeasonalEmaPullback extends SeasonalEmaPullbackContinuation {
        private final int e20; private final int e50; private final int e200;
        ParamSeasonalEmaPullback(String symbol, int e20, int e50, int e200) {
            super(symbol);
            this.e20 = e20; this.e50 = e50; this.e200 = e200;
        }
        @Override
        protected void evaluate(Bar bar) {
            if (history.size() < 210) return;
            if (com.martinfou.trading.strategies.prop.PropSessions.inHourRange(bar, 21, 1)) return;
            double ema20 = com.martinfou.trading.core.indicators.Indicators.emaLatest(history, e20);
            double ema50 = com.martinfou.trading.core.indicators.Indicators.emaLatest(history, e50);
            double ema200 = com.martinfou.trading.core.indicators.Indicators.emaLatest(history, e200);
            double rsi = com.martinfou.trading.core.indicators.Indicators.rsi(history, 14);
            double atr = atr(14);
            com.martinfou.trading.core.Order.Side bias = getSeasonalBias(symbol, bar.timestamp());
            if (ema50 > ema200 && bar.low() <= ema20 && bar.close() > ema20
                && bar.close() > bar.open() && rsi >= 40 && rsi <= 60
                && bias != com.martinfou.trading.core.Order.Side.SELL) {
                double entry = bar.close();
                double sl = Math.min(bar.low(), ema50) - atr * 0.3;
                enterLong(bar, sl, rrTp(entry, sl, com.martinfou.trading.core.indicators.Indicators.TradeSide.LONG));
            } else if (ema50 < ema200 && bar.high() >= ema20 && bar.close() < ema20
                && bar.close() < bar.open() && rsi >= 40 && rsi <= 60
                && bias != com.martinfou.trading.core.Order.Side.BUY) {
                double entry = bar.close();
                double sl = Math.max(bar.high(), ema50) + atr * 0.3;
                enterShort(bar, sl, rrTp(entry, sl, com.martinfou.trading.core.indicators.Indicators.TradeSide.SHORT));
            }
        }
    }
}
