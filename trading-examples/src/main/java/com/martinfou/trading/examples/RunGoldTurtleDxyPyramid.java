package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.GoldTurtleDxyPyramidStrategy;
import com.martinfou.trading.strategies.creative.GoldTurtleTrendStrategy;

import java.util.*;

/**
 * RunGoldTurtleDxyPyramid — Variation mardi 1er septembre 2026 (25e résultat).
 *
 * Piste ouverte du 28 août (GoldTurtleDxyFilter) : « OPPOSITE-DXY + pyramiding
 * 2u — le filtre coupe déjà les trades de moitié, le pyramiding sur trades
 * filtrés pourrait amplifier le net ».
 *
 * Combinaison de deux EXPLORE de la famille or :
 *   - GoldTurtleDxyFilter OPPOSITE SMA500 : PF 1.34, DD 8.06%, net +$18.1K, 830 trades
 *   - GoldTurtlePyramid maxUnits=2 step=0.5 : net +$28.8K (+62%), DD 15.7%
 *
 * Modes (GoldTurtleDxyPyramidStrategy) :
 *   ALIGNED  : BUY or si DXY < SMA_N, SELL or si DXY > SMA_N (contrôle inverse attendu)
 *   OPPOSITE : inverse (la config qui a fonctionné le 28 août)
 *   OFF      : baseline GoldTurtleTrend (aucun filtre DXY)
 *
 * Tests :
 *   (défaut) full-sample XAU_USD : baseline 1u vs OPPOSITE 1u/2u/3u vs ALIGNED 2u
 *   --sweep   maxUnits 1-3 × step 0.25/0.5/1.0 en OPPOSITE
 *   --dxy     période SMA DXY 400/500/600/750 × maxUnits 1/2/3 (plateau paramétrique)
 *   --wf      walk-forward IS 2006-2015 / OOS 2016-2025
 *   --regime  bull 2006-12 / bear 2013-15 / bull2 2016-25
 *   --eur     contrôle EUR_USD (même mécanique, filtre DXY) — edge attendu NÉGATIF
 *
 * Usage:
 *   java -cp "$CP" com.martinfou.trading.examples.RunGoldTurtleDxyPyramid [--sweep|--dxy|--wf|--regime|--eur]
 */
public class RunGoldTurtleDxyPyramid {

    static final String YEAR_SPEC = "2006-2025";
    static final double CAPITAL = 50_000;
    static final String GOLD = "XAU_USD";
    static final double QTY = 10; // oz par unité

    // DXY synthétique (mêmes poids que RunGoldTurtleDxyFilter)
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

        if (args.length > 0 && args[0].equals("--sweep"))  { runSweep(cost, dxy); return; }
        if (args.length > 0 && args[0].equals("--dxy"))    { runDxySweep(cost, dxy); return; }
        if (args.length > 0 && args[0].equals("--wf"))     { runWalkForward(cost, dxy); return; }
        if (args.length > 0 && args[0].equals("--regime")) { runRegime(cost, dxy); return; }
        if (args.length > 0 && args[0].equals("--eur"))    { runEurControl(cost, dxy); return; }

        System.out.println("==================================================");
        System.out.println("GOLD TURTLE DXY-PYRAMID — Donchian 55/20 + filtre DXY + pyramiding");
        System.out.println("DXY synthétique (EUR/JPY/GBP/CAD/CHF) | SMA 500 H1 (~21j)");
        System.out.println("Pyramiding: step=0.5 ATR, qty=10oz/unité");
        System.out.println("Coûts: commission $0.07 + slippage 0.01% | Capital: $50K");
        System.out.println("==================================================");

        var xau = HistoricalDataLoader.loadFromArgs(GOLD, GOLD, YEAR_SPEC).bars();
        System.out.printf("XAU_USD: %d bars | DXY points: %d%n%n", xau.size(), dxy.size());
        System.out.printf("%-26s %-6s %-6s %-6s %-7s %-12s %-9s %-10s%n",
            "CONFIG", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%", "SWAP$");

        // Baseline GoldTurtleTrend (reproduction)
        runOne(cost, "Baseline (OFF 1u)", new GoldTurtleTrendStrategy("GoldTurtleTrend", GOLD), GOLD, xau);
        // OPPOSITE 1u = reproduction GoldTurtleDxyFilter 28 août
        runOne(cost, "OPPOSITE 1u (SMA500)", new GoldTurtleDxyPyramidStrategy("GTDP", GOLD,
            55, 20, 20, 0.5, 1, QTY, GoldTurtleDxyPyramidStrategy.MODE_OPPOSITE, 500, dxy), GOLD, xau);
        // La variation : OPPOSITE + pyramiding 2u / 3u
        runOne(cost, "OPPOSITE 2u (SMA500)", new GoldTurtleDxyPyramidStrategy("GTDP", GOLD,
            55, 20, 20, 0.5, 2, QTY, GoldTurtleDxyPyramidStrategy.MODE_OPPOSITE, 500, dxy), GOLD, xau);
        runOne(cost, "OPPOSITE 3u (SMA500)", new GoldTurtleDxyPyramidStrategy("GTDP", GOLD,
            55, 20, 20, 0.5, 3, QTY, GoldTurtleDxyPyramidStrategy.MODE_OPPOSITE, 500, dxy), GOLD, xau);
        // Contrôle directionnel : ALIGNED + pyramiding (attendu : détruit l'edge, cf 28 août)
        runOne(cost, "ALIGNED 2u (SMA500)", new GoldTurtleDxyPyramidStrategy("GTDP", GOLD,
            55, 20, 20, 0.5, 2, QTY, GoldTurtleDxyPyramidStrategy.MODE_ALIGNED, 500, dxy), GOLD, xau);
        // Contrôle mécanique : OFF (sans filtre) + pyramiding 2u = GoldTurtlePyramid 2u
        runOne(cost, "OFF 2u (pyramid seul)", new GoldTurtleDxyPyramidStrategy("GTDP", GOLD,
            55, 20, 20, 0.5, 2, QTY, GoldTurtleDxyPyramidStrategy.MODE_OFF, 500, dxy), GOLD, xau);

        System.out.println("\nDONE");
    }

    /** Sweep maxUnits × step en mode OPPOSITE (XAU_USD). */
    private static void runSweep(BacktestExecutionCost cost, Map<Long, Double> dxy) throws Exception {
        var xau = HistoricalDataLoader.loadFromArgs(GOLD, GOLD, YEAR_SPEC).bars();
        int[] units = {1, 2, 3};
        double[] steps = {0.25, 0.5, 1.0};
        System.out.println("=== SWEEP OPPOSITE XAU_USD (maxUnits × step) ===");
        System.out.printf("%-10s %-6s %-6s %-6s %-6s %-7s %-12s%n",
            "UNITS", "STEP", "PF", "WR%", "DD%", "TRADES", "NET$");
        for (int u : units) {
            for (double s : steps) {
                var strat = new GoldTurtleDxyPyramidStrategy("GTDP", GOLD, 55, 20, 20, s, u, QTY,
                    GoldTurtleDxyPyramidStrategy.MODE_OPPOSITE, 500, dxy);
                BacktestResult r = RunContext.forStrategy(null, "GTDP", strat, GOLD,
                    RunMode.BACKTEST, xau, CAPITAL, null, cost).run();
                System.out.printf("%-10d %-6.2f %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
                    u, s, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                    r.totalTrades(), r.totalPnl());
            }
        }
        System.out.println("\nDONE");
    }

    /** Sweep période DXY × maxUnits en OPPOSITE (plateau paramétrique du filtre). */
    private static void runDxySweep(BacktestExecutionCost cost, Map<Long, Double> dxy) throws Exception {
        var xau = HistoricalDataLoader.loadFromArgs(GOLD, GOLD, YEAR_SPEC).bars();
        int[] periods = {400, 500, 600, 750};
        int[] units = {1, 2, 3};
        System.out.println("=== SWEEP DXY XAU_USD (période SMA × maxUnits, OPPOSITE, step 0.5) ===");
        System.out.printf("%-10s %-9s %-6s %-6s %-6s %-7s %-12s%n",
            "PERIOD", "UNITS", "PF", "WR%", "DD%", "TRADES", "NET$");
        for (int p : periods) {
            for (int u : units) {
                var strat = new GoldTurtleDxyPyramidStrategy("GTDP", GOLD, 55, 20, 20, 0.5, u, QTY,
                    GoldTurtleDxyPyramidStrategy.MODE_OPPOSITE, p, dxy);
                BacktestResult r = RunContext.forStrategy(null, "GTDP", strat, GOLD,
                    RunMode.BACKTEST, xau, CAPITAL, null, cost).run();
                System.out.printf("%-10d %-9d %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
                    p, u, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                    r.totalTrades(), r.totalPnl());
            }
        }
        System.out.println("\nDONE");
    }

    /** Walk-forward IS 2006-2015 / OOS 2016-2025 : baseline vs OPPOSITE 1u/2u. */
    private static void runWalkForward(BacktestExecutionCost cost, Map<Long, Double> dxy) throws Exception {
        System.out.println("=== WALK-FORWARD XAU_USD (IS 2006-2015 / OOS 2016-2025) ===");
        System.out.printf("%-24s %-6s %-6s %-6s %-6s %-7s %-12s%n",
            "CONFIG", "PHASE", "PF", "WR%", "DD%", "TRADES", "NET$");
        String[] specs = {"2006-2015", "2016-2025"};
        String[] phases = {"IS", "OOS"};
        for (int i = 0; i < 2; i++) {
            var bars = HistoricalDataLoader.loadFromArgs(GOLD, GOLD, specs[i]).bars();
            var base = new GoldTurtleTrendStrategy("GoldTurtleTrend", GOLD);
            BacktestResult rb = RunContext.forStrategy(null, "GoldTurtleTrend", base, GOLD,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-24s %-6s %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
                "Baseline", phases[i], rb.profitFactor(), rb.winRatePct(), rb.maxDrawdownPct(),
                rb.totalTrades(), rb.totalPnl());
            for (int u : new int[]{1, 2}) {
                var strat = new GoldTurtleDxyPyramidStrategy("GTDP", GOLD, 55, 20, 20, 0.5, u, QTY,
                    GoldTurtleDxyPyramidStrategy.MODE_OPPOSITE, 500, dxy);
                BacktestResult r = RunContext.forStrategy(null, "GTDP", strat, GOLD,
                    RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
                System.out.printf("%-24s %-6s %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
                    "OPPOSITE " + u + "u", phases[i], r.profitFactor(), r.winRatePct(),
                    r.maxDrawdownPct(), r.totalTrades(), r.totalPnl());
            }
        }
        System.out.println("\nDONE");
    }

    /** Régime de marché : bull / bear / bull2. */
    private static void runRegime(BacktestExecutionCost cost, Map<Long, Double> dxy) throws Exception {
        String[] regimes = {"bull 2006-12", "bear 2013-15", "bull2 2016-25"};
        String[] specs = {"2006-2012", "2013-2015", "2016-2025"};
        System.out.println("=== RÉGIME XAU_USD (OPPOSITE 1u vs 2u vs baseline) ===");
        System.out.printf("%-14s %-18s %-6s %-6s %-6s %-7s %-12s%n",
            "REGIME", "CONFIG", "PF", "WR%", "DD%", "TRADES", "NET$");
        for (int i = 0; i < regimes.length; i++) {
            var bars = HistoricalDataLoader.loadFromArgs(GOLD, GOLD, specs[i]).bars();
            var base = new GoldTurtleTrendStrategy("GoldTurtleTrend", GOLD);
            BacktestResult rb = RunContext.forStrategy(null, "GoldTurtleTrend", base, GOLD,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-14s %-18s %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
                regimes[i], "Baseline", rb.profitFactor(), rb.winRatePct(), rb.maxDrawdownPct(),
                rb.totalTrades(), rb.totalPnl());
            for (int u : new int[]{1, 2}) {
                var strat = new GoldTurtleDxyPyramidStrategy("GTDP", GOLD, 55, 20, 20, 0.5, u, QTY,
                    GoldTurtleDxyPyramidStrategy.MODE_OPPOSITE, 500, dxy);
                BacktestResult r = RunContext.forStrategy(null, "GTDP", strat, GOLD,
                    RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
                System.out.printf("%-14s %-18s %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
                    regimes[i], "OPPOSITE " + u + "u", r.profitFactor(), r.winRatePct(),
                    r.maxDrawdownPct(), r.totalTrades(), r.totalPnl());
            }
        }
        System.out.println("\nDONE");
    }

    /** Contrôle EUR_USD : la même mécanique + filtre DXY ne doit PAS créer d'edge. */
    private static void runEurControl(BacktestExecutionCost cost, Map<Long, Double> dxy) throws Exception {
        var eur = HistoricalDataLoader.loadFromArgs("EUR_USD", "EUR_USD", YEAR_SPEC).bars();
        System.out.println("=== CONTRÔLE EUR_USD (même mécanique, même filtre DXY) ===");
        System.out.printf("%-26s %-6s %-6s %-6s %-7s %-12s %-10s%n",
            "CONFIG", "PF", "WR%", "DD%", "TRADES", "NET$", "SWAP$");
        runOne(cost, "EUR baseline", new GoldTurtleTrendStrategy("GoldTurtleTrend", "EUR_USD"), "EUR_USD", eur);
        runOne(cost, "EUR OPPOSITE 1u", new GoldTurtleDxyPyramidStrategy("GTDP", "EUR_USD",
            55, 20, 20, 0.5, 1, QTY, GoldTurtleDxyPyramidStrategy.MODE_OPPOSITE, 500, dxy), "EUR_USD", eur);
        runOne(cost, "EUR OPPOSITE 2u", new GoldTurtleDxyPyramidStrategy("GTDP", "EUR_USD",
            55, 20, 20, 0.5, 2, QTY, GoldTurtleDxyPyramidStrategy.MODE_OPPOSITE, 500, dxy), "EUR_USD", eur);
        System.out.println("\nDONE");
    }

    private static void runOne(BacktestExecutionCost cost, String label, Strategy strategy,
                               String symbol, List<Bar> bars) {
        BacktestResult r = RunContext.forStrategy(null, strategy.name(), strategy, symbol,
            RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
        System.out.printf("%-26s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-9.2f %10.2f%n",
            label, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
            r.totalTrades(), r.totalPnl(), r.totalReturnPct(), r.totalSwap());
    }

    /** Build synthetic DXY (copie de RunGoldTurtleDxyFilter.buildDxy). */
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
