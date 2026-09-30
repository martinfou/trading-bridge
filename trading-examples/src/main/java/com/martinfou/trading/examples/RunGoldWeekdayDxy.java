package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.GoldWeekdayDxyStrategy;
import com.martinfou.trading.strategies.creative.GoldWeekdayEffectStrategy;

import java.util.*;

/**
 * RunGoldWeekdayDxy — Deep dive vendredi 4 septembre 2026 (28e résultat).
 *
 * Question (ouverte par GoldWeekdayEffect le 31 août, re-notée 1er et 3
 * septembre) : le bid safe-haven de week-end sur l'or (VENDREDI long,
 * PF 1.27 / +$15 131 / 1041 trades) est-il une manifestation du régime
 * « twin-refuge USD/or » (GoldTurtleDxyFilter OPPOSITE, 28 août : PF
 * 1.34 ; GoldTurtleRiskIndex, 3 sept : le risk-off AUD/JPY ne
 * conditionne PAS l'or) ou une dimension indépendante ?
 *
 * Filtre DXY synthétique (mêmes 5 paires, mêmes poids, même K que
 * RunGoldTurtleDxyFilter — outil réutilisable depuis le 28 août) :
 *   EUR 0.576 / JPY 0.136 / GBP 0.119 / CAD 0.091 / CHF 0.036 (SEK
 *   absent → renormalisé), niveau = K × Π close_i^(±w_i).
 *
 * Modes (GoldWeekdayDxyStrategy, long-only, gate des ENTRÉES seulement) :
 *   ALIGNED  : long vendredi si DXY < SMA_N (bear dollar naïf)
 *   OPPOSITE : long vendredi si DXY > SMA_N (twin-refuge USD/or)
 *   OFF      : baseline GoldWeekdayEffect FRI (doit reproduire 1.27)
 *
 * Usage:
 *   java -cp "$CP" com.martinfou.trading.examples.RunGoldWeekdayDxy
 *   java -cp "$CP" com.martinfou.trading.examples.RunGoldWeekdayDxy --sweep
 *   java -cp "$CP" com.martinfou.trading.examples.RunGoldWeekdayDxy --wf
 *   java -cp "$CP" com.martinfou.trading.examples.RunGoldWeekdayDxy --regime
 *   java -cp "$CP" com.martinfou.trading.examples.RunGoldWeekdayDxy --check
 */
public class RunGoldWeekdayDxy {

    static final String YEAR_SPEC = "2006-2025";
    static final double CAPITAL = 50_000;
    static final String GOLD = "XAU_USD";

    // FRI-only day mask: [Mon..Fri]
    static final boolean[] FRI = {false, false, false, false, true};

    // DXY classic weights (SEK dropped), normalized to sum 1
    static final String[] DXY_PAIRS = {"EUR_USD", "USD_JPY", "GBP_USD", "USD_CAD", "USD_CHF"};
    static final double[] DXY_W = {0.576, 0.136, 0.119, 0.091, 0.036};
    static final double DXY_WSUM; static {
        double s = 0; for (double w : DXY_W) s += w; DXY_WSUM = s;
    }
    static final int[] DXY_SIGN = {-1, +1, -1, +1, +1};
    static final double DXY_K = 50.14348112;

    public static void main(String[] args) throws Exception {
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);

        Map<Long, Double> dxy = buildDxy();

        if (args.length > 0 && args[0].equals("--sweep")) { runSweep(cost, dxy); return; }
        if (args.length > 0 && args[0].equals("--wf")) { runWalkForward(cost, dxy); return; }
        if (args.length > 0 && args[0].equals("--regime")) { runRegime(cost, dxy); return; }
        if (args.length > 0 && args[0].equals("--check")) { runCheck(dxy); return; }

        System.out.println("==================================================");
        System.out.println("GOLD WEEKDAY FRI + DXY REGIME FILTER — XAU/USD H1");
        System.out.println("DXY synthétique (EUR/JPY/GBP/CAD/CHF, SEK absent)");
        System.out.println("Coûts: commission $0.07 + slippage 0.01% | Capital: $" + CAPITAL);
        System.out.println("Réf. 31 août FRI seul : PF 1.27 / +$15 131 / 1041 trades");
        System.out.println("Réf. 28 août DXY-OFF Turtle : PF 1.34 (twin-refuge)");
        System.out.println("==================================================");

        var xau = HistoricalDataLoader.loadFromArgs(GOLD, GOLD, YEAR_SPEC).bars();
        System.out.printf("XAU_USD: %d bars | DXY points: %d%n%n", xau.size(), dxy.size());

        System.out.printf("%-24s %-6s %-6s %-6s %-7s %-12s %-9s %-10s%n",
            "CONFIG", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%", "SWAP$");
        runOne(cost, "FRI seul (OFF)", new GoldWeekdayEffectStrategy("GoldWeekdayEffect", GOLD, false, true), GOLD, xau);
        runOne(cost, "ALIGNED SMA500", new GoldWeekdayDxyStrategy("GoldWeekdayDxy", GOLD, FRI,
            GoldWeekdayDxyStrategy.MODE_ALIGNED, 500, dxy), GOLD, xau);
        runOne(cost, "OPPOSITE SMA500", new GoldWeekdayDxyStrategy("GoldWeekdayDxyOpp", GOLD, FRI,
            GoldWeekdayDxyStrategy.MODE_OPPOSITE, 500, dxy), GOLD, xau);

        // Contrôle EUR_USD : le Friday bid EUR était NÉGATIF (31 août : PF 0.83).
        var eur = HistoricalDataLoader.loadFromArgs("EUR_USD", "EUR_USD", YEAR_SPEC).bars();
        System.out.println("\n--- Contrôle EUR_USD (même mécanique FRI, même filtre DXY) ---");
        runOne(cost, "EUR FRI seul", new GoldWeekdayEffectStrategy("GoldWeekdayEffect", "EUR_USD", false, true), "EUR_USD", eur);
        runOne(cost, "EUR ALIGNED 500", new GoldWeekdayDxyStrategy("GoldWeekdayDxy", "EUR_USD", FRI,
            GoldWeekdayDxyStrategy.MODE_ALIGNED, 500, dxy), "EUR_USD", eur);
        runOne(cost, "EUR OPPOSITE 500", new GoldWeekdayDxyStrategy("GoldWeekdayDxyOpp", "EUR_USD", FRI,
            GoldWeekdayDxyStrategy.MODE_OPPOSITE, 500, dxy), "EUR_USD", eur);

        System.out.println("\nDONE");
    }

    /** Sweep période SMA × mode sur XAU_USD (FRI). */
    private static void runSweep(BacktestExecutionCost cost, Map<Long, Double> dxy) throws Exception {
        var xau = HistoricalDataLoader.loadFromArgs(GOLD, GOLD, YEAR_SPEC).bars();
        int[] periods = {250, 400, 500, 600, 750, 1000, 2000};   // ~10j / 17j / 21j / 25j / 31j / 42j / 83j
        System.out.println("=== SWEEP DXY FILTER XAU_USD FRI (SMA période × mode) ===");
        System.out.printf("%-9s %-9s %-6s %-6s %-6s %-7s %-12s%n",
            "PERIOD", "MODE", "PF", "WR%", "DD%", "TRADES", "NET$");
        for (int p : periods) {
            for (int mode : new int[]{GoldWeekdayDxyStrategy.MODE_ALIGNED,
                                      GoldWeekdayDxyStrategy.MODE_OPPOSITE}) {
                String m = mode == GoldWeekdayDxyStrategy.MODE_ALIGNED ? "ALIGNED" : "OPPOSITE";
                var strat = new GoldWeekdayDxyStrategy("GoldWeekdayDxy", GOLD, FRI, mode, p, dxy);
                BacktestResult r = RunContext.forStrategy(null, "GoldWeekdayDxy", strat, GOLD,
                    RunMode.BACKTEST, xau, CAPITAL, null, cost).run();
                System.out.printf("%-9d %-9s %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
                    p, m, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                    r.totalTrades(), r.totalPnl());
            }
        }
        System.out.println("\nDONE");
    }

    /** Walk-forward IS 2006-2015 / OOS 2016-2025 pour FRI seul + ALIGNED + OPPOSITE. */
    private static void runWalkForward(BacktestExecutionCost cost, Map<Long, Double> dxy) throws Exception {
        String[] specs = {"2006-2015", "2016-2025"};
        System.out.println("=== WALK-FORWARD XAU_USD FRI (SMA 500 H1) ===");
        System.out.printf("%-24s %-12s %-6s %-6s %-6s %-7s %-12s%n",
            "CONFIG", "PHASE", "PF", "WR%", "DD%", "TRADES", "NET$");
        for (String spec : specs) {
            var bars = HistoricalDataLoader.loadFromArgs(GOLD, GOLD, spec).bars();
            var base = new GoldWeekdayEffectStrategy("GoldWeekdayEffect", GOLD, false, true);
            BacktestResult rb = RunContext.forStrategy(null, "GoldWeekdayEffect", base, GOLD,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-24s %-12s %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
                "FRI seul (OFF)", spec, rb.profitFactor(), rb.winRatePct(), rb.maxDrawdownPct(),
                rb.totalTrades(), rb.totalPnl());
            var align = new GoldWeekdayDxyStrategy("GoldWeekdayDxy", GOLD, FRI,
                GoldWeekdayDxyStrategy.MODE_ALIGNED, 500, dxy);
            BacktestResult ra = RunContext.forStrategy(null, "GoldWeekdayDxy", align, GOLD,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-24s %-12s %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
                "ALIGNED 500", spec, ra.profitFactor(), ra.winRatePct(), ra.maxDrawdownPct(),
                ra.totalTrades(), ra.totalPnl());
            var opp = new GoldWeekdayDxyStrategy("GoldWeekdayDxyOpp", GOLD, FRI,
                GoldWeekdayDxyStrategy.MODE_OPPOSITE, 500, dxy);
            BacktestResult ro = RunContext.forStrategy(null, "GoldWeekdayDxyOpp", opp, GOLD,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-24s %-12s %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
                "OPPOSITE 500", spec, ro.profitFactor(), ro.winRatePct(), ro.maxDrawdownPct(),
                ro.totalTrades(), ro.totalPnl());
        }
        System.out.println("\nDONE");
    }

    /** Régime de marché : bull (2006-2012), bear (2013-2015), bull2 (2016-2025). */
    private static void runRegime(BacktestExecutionCost cost, Map<Long, Double> dxy) throws Exception {
        String[] regimes = {"bull 2006-12", "bear 2013-15", "bull2 2016-25"};
        String[] specs = {"2006-2012", "2013-2015", "2016-2025"};
        System.out.println("=== RÉGIME XAU_USD FRI (SMA 500 H1) ===");
        System.out.printf("%-14s %-24s %-6s %-6s %-6s %-7s %-12s%n",
            "REGIME", "CONFIG", "PF", "WR%", "DD%", "TRADES", "NET$");
        for (int i = 0; i < regimes.length; i++) {
            var bars = HistoricalDataLoader.loadFromArgs(GOLD, GOLD, specs[i]).bars();
            var base = new GoldWeekdayEffectStrategy("GoldWeekdayEffect", GOLD, false, true);
            BacktestResult rb = RunContext.forStrategy(null, "GoldWeekdayEffect", base, GOLD,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-14s %-24s %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
                regimes[i], "FRI seul (OFF)", rb.profitFactor(), rb.winRatePct(), rb.maxDrawdownPct(),
                rb.totalTrades(), rb.totalPnl());
            var align = new GoldWeekdayDxyStrategy("GoldWeekdayDxy", GOLD, FRI,
                GoldWeekdayDxyStrategy.MODE_ALIGNED, 500, dxy);
            BacktestResult ra = RunContext.forStrategy(null, "GoldWeekdayDxy", align, GOLD,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-14s %-24s %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
                regimes[i], "ALIGNED 500", ra.profitFactor(), ra.winRatePct(), ra.maxDrawdownPct(),
                ra.totalTrades(), ra.totalPnl());
            var opp = new GoldWeekdayDxyStrategy("GoldWeekdayDxyOpp", GOLD, FRI,
                GoldWeekdayDxyStrategy.MODE_OPPOSITE, 500, dxy);
            BacktestResult ro = RunContext.forStrategy(null, "GoldWeekdayDxyOpp", opp, GOLD,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-14s %-24s %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
                regimes[i], "OPPOSITE 500", ro.profitFactor(), ro.winRatePct(), ro.maxDrawdownPct(),
                ro.totalTrades(), ro.totalPnl());
        }
        System.out.println("\nDONE");
    }

    /** Sanity check : niveau du DXY synthétique à des dates connues vs DXY réel. */
    private static void runCheck(Map<Long, Double> dxy) {
        long[] known = {1205755200000L, 1311729600000L, 1483425600000L, 1664337600000L, 1609924800000L};
        String[] labels = {"2008-03-17 (~71.3 réel)", "2011-07-27 (~74 réel)", "2017-01-03 (~103 réel)",
                           "2022-09-28 (~114 réel)", "2021-01-06 (~89 réel)"};
        System.out.println("=== SANITY CHECK DXY SYNTHÉTIQUE ===");
        for (int i = 0; i < known.length; i++) {
            Map.Entry<Long, Double> e = ((TreeMap<Long, Double>) dxy).floorEntry(known[i]);
            System.out.printf("%-22s ts=%d -> DXY synth %.2f%n", labels[i], e.getKey(), e.getValue());
        }
        double min = Double.MAX_VALUE, max = 0, sum = 0;
        for (double v : dxy.values()) { min = Math.min(min, v); max = Math.max(max, v); sum += v; }
        System.out.printf("DXY synthétique: %d points, min %.2f, max %.2f, avg %.2f%n",
            dxy.size(), min, max, sum / dxy.size());
        System.out.println("\nDONE");
    }

    private static void runOne(BacktestExecutionCost cost, String label, Strategy strategy,
                               String symbol, List<Bar> bars) {
        BacktestResult r = RunContext.forStrategy(null, strategy.name(), strategy, symbol,
            RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
        System.out.printf("%-24s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-9.2f %10.2f%n",
            label, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
            r.totalTrades(), r.totalPnl(), r.totalReturnPct(), r.totalSwap());
    }

    /** Build synthetic DXY: level per aligned H1 timestamp (look-ahead safe par construction). */
    static Map<Long, Double> buildDxy() throws Exception {
        Map<String, List<Bar>> loaded = new HashMap<>();
        for (String p : DXY_PAIRS) loaded.put(p, HistoricalDataLoader.loadFromArgs(p, p, YEAR_SPEC).bars());

        List<Bar> eur = loaded.get("EUR_USD");
        List<Map<Long, Bar>> others = new ArrayList<>();
        for (int i = 1; i < DXY_PAIRS.length; i++) {
            Map<Long, Bar> idx = new HashMap<>();
            for (Bar b : loaded.get(DXY_PAIRS[i])) idx.put(b.timestamp().toEpochMilli(), b);
            others.add(idx);
        }

        Map<Long, Double> dxy = new TreeMap<>();
        for (Bar b : eur) {
            long ts = b.timestamp().toEpochMilli();
            double[] closes = new double[DXY_PAIRS.length];
            closes[0] = b.close();
            boolean complete = true;
            for (int i = 1; i < DXY_PAIRS.length; i++) {
                Bar c = others.get(i - 1).get(ts);
                if (c == null) { complete = false; break; }
                closes[i] = c.close();
            }
            if (!complete) continue;
            double logDxy = 0;
            for (int i = 0; i < DXY_PAIRS.length; i++) {
                double w = DXY_W[i] / DXY_WSUM;
                logDxy += DXY_SIGN[i] * w * Math.log(closes[i]);
            }
            dxy.put(ts, DXY_K * Math.exp(logDxy));
        }
        return dxy;
    }
}
