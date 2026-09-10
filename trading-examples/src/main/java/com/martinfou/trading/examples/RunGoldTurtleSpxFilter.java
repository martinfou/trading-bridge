package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.GoldTurtleDxyFilterStrategy;
import com.martinfou.trading.strategies.creative.GoldTurtleTrendStrategy;

import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * RunGoldTurtleSpxFilter — Jeudi intermarket 10 septembre 2026 (33e résultat).
 *
 * Question (suite GoldIndexRegimeCheck, 32e résultat) : le filtre de régime USD
 * (DXY-OPPOSITE SMA500 → PF 1.34, 28 août) est-il irréductiblement « dollar-or »,
 * ou le VRAI actif de risque (S&P 500) porte-t-il le même signal ? Le gauge risk-off
 * FX (RAI AUD/JPY) avait été REJETÉ le 3 sept ; le S&P 500 est le discriminant
 * (2013-15 : AUD/JPY en risk-off pendant que le SPX montait).
 *
 * ⚠️ NOUVELLE DONNÉE : data/historical/futures/MES_D1.csv (S&P 500, 2006-2026).
 *
 * Gauge transmis au moteur : niveau SPX journalier PROJETÉ sur les timestamps H1 de
 * l'or (chaque barre H1 du jour D reçoit la clôture SPX du dernier jour STRICTEMENT
 * antérieur → look-ahead safe). Le filtre compare alors SPX_t à la moyenne des N
 * dernières valeurs H1 — avec N ≈ 24 × jours, ce qui approxime « SPX vs SMA J-jours ».
 *
 * Réutilisation de GoldTurtleDxyFilterStrategy (série pluggable) :
 *   ALIGNED  : BUY or si SPX < SMA_N (stress actions → or fort), SELL sinon
 *   OPPOSITE : inverse (contrôle directionnel obligatoire, méthode du 26 août)
 *   OFF      : baseline GoldTurtleTrend (PF 1.17 / +$17 705)
 *
 * Usage : java -cp "$CP" com.martinfou.trading.examples.RunGoldTurtleSpxFilter [--sweep|--wf|--regime]
 */
public class RunGoldTurtleSpxFilter {

    static final String YEAR_SPEC = "2006-2025";
    static final double CAPITAL = 50_000;
    static final String GOLD = "XAU_USD";
    static final Path SPX_CSV = Path.of("data/historical/futures/MES_D1.csv");
    static final int DEFAULT_SMA = 4800;   // ~200 jours de barres H1

    public static void main(String[] args) throws Exception {
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);
        var xau = HistoricalDataLoader.loadFromArgs(GOLD, GOLD, YEAR_SPEC).bars();
        Map<Long, Double> spx = buildSpxOnXauGrid(xau);

        if (args.length > 0 && args[0].equals("--sweep")) { runSweep(cost, xau, spx); return; }
        if (args.length > 0 && args[0].equals("--wf")) { runWalkForward(cost, xau, spx); return; }
        if (args.length > 0 && args[0].equals("--regime")) { runRegime(cost, xau, spx); return; }

        System.out.println("=====================================================================");
        System.out.println("GOLD TURTLE + S&P 500 REGIME FILTER — XAU/USD H1 (2006-2025)");
        System.out.println("Gauge : MES_D1 (S&P 500) projeté sur la grille H1, close J-1 (look-ahead safe)");
        System.out.println("Coûts : commission $0.07 + slippage 0.01% | Capital : $" + CAPITAL);
        System.out.println("=====================================================================");
        System.out.printf("XAU: %d barres | points gauge: %d%n%n", xau.size(), spx.size());

        System.out.printf("%-26s %-6s %-6s %-6s %-7s %-12s %-9s %-10s%n",
            "CONFIG", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%", "SWAP$");
        runOne(cost, "Baseline (OFF)", new GoldTurtleTrendStrategy("GoldTurtleTrend", GOLD), GOLD, xau);
        runOne(cost, "ALIGNED (stress SPX)", new GoldTurtleDxyFilterStrategy("GoldSpx", GOLD,
            55, 20, GoldTurtleDxyFilterStrategy.MODE_ALIGNED, DEFAULT_SMA, spx), GOLD, xau);
        runOne(cost, "OPPOSITE (calme SPX)", new GoldTurtleDxyFilterStrategy("GoldSpxOpp", GOLD,
            55, 20, GoldTurtleDxyFilterStrategy.MODE_OPPOSITE, DEFAULT_SMA, spx), GOLD, xau);

        var eur = HistoricalDataLoader.loadFromArgs("EUR_USD", "EUR_USD", YEAR_SPEC).bars();
        Map<Long, Double> spxEur = buildSpxOnXauGrid(eur);
        System.out.println("\n--- Contrôle EUR_USD (même mécanique Turtle, même gauge SPX) ---");
        runOne(cost, "EUR baseline", new GoldTurtleTrendStrategy("GoldTurtleTrend", "EUR_USD"),
            "EUR_USD", eur);
        runOne(cost, "EUR ALIGNED", new GoldTurtleDxyFilterStrategy("GoldSpx", "EUR_USD",
            55, 20, GoldTurtleDxyFilterStrategy.MODE_ALIGNED, DEFAULT_SMA, spxEur), "EUR_USD", eur);
        runOne(cost, "EUR OPPOSITE", new GoldTurtleDxyFilterStrategy("GoldSpxOpp", "EUR_USD",
            55, 20, GoldTurtleDxyFilterStrategy.MODE_OPPOSITE, DEFAULT_SMA, spxEur), "EUR_USD", eur);

        System.out.println("\nDONE");
    }

    private static void runSweep(BacktestExecutionCost cost, List<Bar> xau, Map<Long, Double> spx)
            throws Exception {
        int[] periods = {1200, 2400, 3600, 4800, 7200, 9600, 12000};   // ~50→500 jours H1
        System.out.println("=== SWEEP GAUGE SPX XAU_USD (SMA période H1 × mode) ===");
        System.out.printf("%-9s %-9s %-6s %-6s %-6s %-7s %-12s%n",
            "PERIOD", "MODE", "PF", "WR%", "DD%", "TRADES", "NET$");
        for (int p : periods) {
            for (int mode : new int[]{GoldTurtleDxyFilterStrategy.MODE_ALIGNED,
                                      GoldTurtleDxyFilterStrategy.MODE_OPPOSITE}) {
                var strat = new GoldTurtleDxyFilterStrategy("GoldSpx", GOLD, 55, 20, mode, p, spx);
                BacktestResult r = RunContext.forStrategy(null, "GoldSpx", strat, GOLD,
                    RunMode.BACKTEST, xau, CAPITAL, null, cost).run();
                System.out.printf("%-9d %-9s %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
                    p, mode == GoldTurtleDxyFilterStrategy.MODE_ALIGNED ? "ALIGNED" : "OPPOSITE",
                    r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(), r.totalTrades(), r.totalPnl());
            }
        }
        System.out.println("\nDONE");
    }

    private static void runWalkForward(BacktestExecutionCost cost, List<Bar> xau, Map<Long, Double> spx)
            throws Exception {
        record Split(String label, String spec) { }
        Split[] splits = {new Split("IS 2006-2015", "2006-2015"), new Split("OOS 2016-2025", "2016-2025")};
        System.out.println("=== WALK-FORWARD (baseline / ALIGNED / OPPOSITE, SMA " + DEFAULT_SMA + ") ===");
        System.out.printf("%-15s %-10s %-6s %-6s %-6s %-7s %-12s%n",
            "SPLIT", "CONFIG", "PF", "WR%", "DD%", "TRADES", "NET$");
        for (Split s : splits) {
            var bars = HistoricalDataLoader.loadFromArgs(GOLD, GOLD, s.spec()).bars();
            Map<Long, Double> g = buildSpxOnXauGrid(bars);
            for (int mode : new int[]{-1, GoldTurtleDxyFilterStrategy.MODE_ALIGNED,
                                      GoldTurtleDxyFilterStrategy.MODE_OPPOSITE}) {
                var strat = mode < 0
                    ? new GoldTurtleTrendStrategy("GoldTurtleTrend", GOLD)
                    : new GoldTurtleDxyFilterStrategy("GoldSpx", GOLD, 55, 20, mode, DEFAULT_SMA, g);
                BacktestResult r = RunContext.forStrategy(null, "GoldSpx", strat, GOLD,
                    RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
                System.out.printf("%-15s %-10s %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
                    s.label(), mode < 0 ? "baseline" : (mode == GoldTurtleDxyFilterStrategy.MODE_ALIGNED ? "ALIGNED" : "OPPOSITE"),
                    r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(), r.totalTrades(), r.totalPnl());
            }
        }
        System.out.println("\nDONE");
    }

    private static void runRegime(BacktestExecutionCost cost, List<Bar> xau, Map<Long, Double> spx)
            throws Exception {
        String[][] eras = {{"bull 2006-2012", "2006-2012"}, {"bear or 2013-2015", "2013-2015"},
                           {"bull2 2016-2025", "2016-2025"}};
        System.out.println("=== RÉGIMES (baseline / ALIGNED / OPPOSITE, SMA " + DEFAULT_SMA + ") ===");
        System.out.printf("%-18s %-10s %-6s %-6s %-6s %-7s %-12s%n",
            "RÉGIME", "CONFIG", "PF", "WR%", "DD%", "TRADES", "NET$");
        for (String[] e : eras) {
            var bars = HistoricalDataLoader.loadFromArgs(GOLD, GOLD, e[1]).bars();
            if (bars.isEmpty()) { System.out.println(e[0] + " : pas de données"); continue; }
            Map<Long, Double> g = buildSpxOnXauGrid(bars);
            for (int mode : new int[]{-1, GoldTurtleDxyFilterStrategy.MODE_ALIGNED,
                                      GoldTurtleDxyFilterStrategy.MODE_OPPOSITE}) {
                var strat = mode < 0
                    ? new GoldTurtleTrendStrategy("GoldTurtleTrend", GOLD)
                    : new GoldTurtleDxyFilterStrategy("GoldSpx", GOLD, 55, 20, mode, DEFAULT_SMA, g);
                BacktestResult r = RunContext.forStrategy(null, "GoldSpx", strat, GOLD,
                    RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
                System.out.printf("%-18s %-10s %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
                    e[0], mode < 0 ? "baseline" : (mode == GoldTurtleDxyFilterStrategy.MODE_ALIGNED ? "ALIGNED" : "OPPOSITE"),
                    r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(), r.totalTrades(), r.totalPnl());
            }
        }
        System.out.println("\nDONE");
    }

    /**
     * Gauge SPX projeté sur la grille H1 de l'actif : pour chaque barre H1 du jour D,
     * la clôture SPX du dernier jour de bourse STRICTEMENT antérieur à D (look-ahead safe).
     */
    static Map<Long, Double> buildSpxOnXauGrid(List<Bar> bars) throws Exception {
        TreeMap<LocalDate, Double> spx = new TreeMap<>();
        for (String line : Files.readAllLines(SPX_CSV)) {
            String[] f = line.split(",");
            if (f.length < 5 || f[0].startsWith("Date")) continue;
            try { spx.put(LocalDate.parse(f[0].trim()), Double.parseDouble(f[4])); }
            catch (Exception ignore) { }
        }
        Map<Long, Double> out = new LinkedHashMap<>();
        for (Bar b : bars) {
            LocalDate d = b.timestamp().atZone(ZoneId.of("UTC")).toLocalDate();
            Map.Entry<LocalDate, Double> e = spx.lowerEntry(d);   // strictement antérieur
            if (e != null) out.put(b.timestamp().toEpochMilli(), e.getValue());
        }
        return out;
    }

    private static void runOne(BacktestExecutionCost cost, String label,
                               com.martinfou.trading.core.Strategy strat, String symbol, List<Bar> bars) {
        BacktestResult r = RunContext.forStrategy(null, label, strat, symbol,
            RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
        System.out.printf("%-26s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-9.2f %-10.2f%n",
            label, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
            r.totalTrades(), r.totalPnl(), r.totalReturnPct(), r.totalSwap());
    }
}
