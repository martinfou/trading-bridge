package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Trade;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.GoldTurtleTrendStrategy;
import com.martinfou.trading.strategies.creative.TurtleCrossAssetStrategy;

import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * RunIndexTurtleD1 — Deep dive VENDREDI 11 septembre 2026 (34e résultat).
 *
 * QUESTION : la mécanique Turtle (Donchian 55/20) a-t-elle un edge sur un
 * UNIVERS jamais testé — les INDICES ACTIONS D1 ? `data/historical/futures/`
 * (révélé le 10 sept) contient MES_D1 (S&P 500), MNQ_D1 (Nasdaq 100) et
 * M2K_D1 (Russell 2000), 2006-2026, ~5200 barres journalières.
 *
 * Pourquoi c'est un test pertinent (pas du data-mining) :
 *   - Le système Turtle de Richard Dennis a ÉTÉ conçu pour du D1 sur futures —
 *     c'est sa fréquence native, contrairement au test XAU D1 du 25 août
 *     (agrégation H1→D1, canaux 2-3 mois) qui avait échoué.
 *   - Les indices sont le « vrai actif de risque » : après le REJECT du gauge
 *     actions comme filtre or (10 sept), reste la question symétrique —
 *     l'edge trend-following est-il spécifique à l'or ou générique aux actifs
 *     à tendance structurelle ?
 *   - Univers NEUF = aucun risque de re-tester un concept épuisé.
 *
 * CONTRÔLES ANTI-ARTEFACT INTÉGRÉS :
 *   1. Validation d'implémentation : TurtleCrossAssetStrategy (qty 10, 55/20)
 *      sur XAU_USD H1 DOIT reproduire EXACTEMENT GoldTurtleTrendStrategy
 *      (PF 1.17 / WR 41.5% / DD 9.35% / 1594 trades / +$17 705).
 *   2. Décomposition LONG/SHORT : si tout le profit vient du côté long dans un
 *      bull market de 20 ans → c'est de la BÊTA, pas un edge.
 *   3. Benchmark buy & hold (rendement + MaxDD) sur la même fenêtre.
 *   4. Crise : un trend-follower doit gagner dans le bear (GFC 2008-09, covid 2020).
 *      Si le bear le détruit, l'« edge » n'est qu'une exposition longue.
 *   5. Walk-forward IS 2006-2015 / OOS 2016-2026.
 *   6. Sweep canaux et quantité (plateau vs pic).
 *
 * Usage :
 *   java -cp "$CP" com.martinfou.trading.examples.RunIndexTurtleD1 [validate|main|sweep|qty|wf|regime|all]
 */
public class RunIndexTurtleD1 {

    static final double CAPITAL = 50_000;
    static final String XAU = "XAU_USD";
    static final String XAU_SPEC = "2006-2025";
    static final Path FUT = Path.of("data/historical/futures");
    static final String[][] INDEXES = {
        {"MES", "MES_D1.csv", "S&P 500 (MES)"},
        {"MNQ", "MNQ_D1.csv", "Nasdaq 100 (MNQ)"},
        {"M2K", "M2K_D1.csv", "Russell 2000 (M2K)"},
    };

    public static void main(String[] args) throws Exception {
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);
        String mode = args.length > 0 ? args[0] : "all";

        System.out.println("=====================================================================");
        System.out.println("INDEX TURTLE D1 — Donchian 55/20 sur MES / MNQ / M2K (2006-2026)");
        System.out.println("Coûts : commission $0.07 + slippage 0.01% | Capital : $" + CAPITAL);
        System.out.println("Vendredi 11 sept 2026 — deep dive (34e résultat)");
        System.out.println("=====================================================================");

        if (mode.equals("all") || mode.equals("validate")) validate(cost);
        if (mode.equals("all") || mode.equals("main"))     mainRun(cost);
        if (mode.equals("all") || mode.equals("sweep"))    sweep(cost);
        if (mode.equals("all") || mode.equals("qty"))      qtySweep(cost);
        if (mode.equals("all") || mode.equals("wf"))       walkForward(cost);
        if (mode.equals("all") || mode.equals("regime"))   regime(cost);

        System.out.println("\nDONE");
    }

    // ------------------------------------------------------------------
    // 1. VALIDATION D'IMPLÉMENTATION
    // ------------------------------------------------------------------
    static void validate(BacktestExecutionCost cost) throws Exception {
        System.out.println("\n### 1. VALIDATION D'IMPLÉMENTATION (XAU_USD H1, qty 10, 55/20)");
        System.out.println("    Référence GoldTurtleTrendStrategy : PF 1.17 / WR 41.5% / DD 9.35% / 1594 / +$17 705");
        System.out.printf("%-28s %-6s %-6s %-6s %-7s %-12s%n", "CONFIG", "PF", "WR%", "DD%", "TRADES", "NET$");
        var bars = HistoricalDataLoader.loadFromArgs(XAU, XAU, XAU_SPEC).bars();
        var ref = new GoldTurtleTrendStrategy("GoldTurtleTrend", XAU);
        printRow("référence GoldTurtleTrend", run(cost, ref, XAU, bars));
        var clone = new TurtleCrossAssetStrategy("TurtleXAU", XAU, 55, 20, 10.0);
        printRow("clone TurtleCrossAsset", run(cost, clone, XAU, bars));
    }

    // ------------------------------------------------------------------
    // 2. BASELINE INDICES + DÉCOMPOSITION + BENCHMARK
    // ------------------------------------------------------------------
    static void mainRun(BacktestExecutionCost cost) throws Exception {
        System.out.println("\n### 2. BASELINE INDICES D1 — Donchian 55/20, quantité 5 ($5/point)");
        System.out.printf("%-22s %-6s %-6s %-6s %-7s %-12s %-8s %-10s %-10s%n",
            "INSTRUMENT", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%", "LONG$", "SHORT$");
        for (String[] ix : INDEXES) {
            List<Bar> bars = loadD1(ix[0], ix[1]);
            var strat = new TurtleCrossAssetStrategy("IndexTurtle", ix[0], 55, 20, 5.0);
            BacktestResult r = run(cost, strat, ix[0], bars);
            System.out.printf("%-22s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-8.2f %-10.2f %-10.2f%n",
                ix[2], r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                r.totalTrades(), r.totalPnl(), r.totalReturnPct(),
                sidePnl(r, Order.Side.BUY), sidePnl(r, Order.Side.SELL));
            checkSides(r, ix[2]);
            System.out.printf("%-22s BUY & HOLD         %-6s %-6s %-6.2f %-7s %12.2f %-8.2f%n",
                "", "", "", buyHoldDd(bars), "", buyHoldPnl(bars, 5.0), buyHoldRet(bars));
        }

        System.out.println("\n### 2b. CONTRÔLE FX (même mécanique, autre univers) — EUR_USD H1");
        System.out.printf("%-22s %-6s %-6s %-6s %-7s %-12s%n", "INSTRUMENT", "PF", "WR%", "DD%", "TRADES", "NET$");
        var eur = HistoricalDataLoader.loadFromArgs("EUR_USD", "EUR_USD", XAU_SPEC).bars();
        printRow("EUR_USD H1", run(cost, new TurtleCrossAssetStrategy("TurtleEUR", "EUR_USD", 55, 20, 10_000.0), "EUR_USD", eur));

        // ---- 2c. SIGNATURE LONG/SHORT : le diagnostic décisif ----
        System.out.println("\n### 2c. SIGNATURE LONG/SHORT par univers (55/20, côté long = BUY)");
        System.out.println("    Lecture : si LONG$ et SHORT$ sont grands et OPPOSÉS, le résultat net est un");
        System.out.println("    résidu de bruit — pas une capture de tendance. Une signature saine = long et");
        System.out.println("    short positifs, ou un côté dominant SANS destruction par l'autre.");
        System.out.printf("%-22s %-8s %-12s %-12s %-9s %-9s %-8s%n",
            "UNIVERS", "TRADES", "LONG$", "SHORT$", "N_LONG", "N_SHORT", "NET$");
        var xauBars = HistoricalDataLoader.loadFromArgs(XAU, XAU, XAU_SPEC).bars();
        signatureRow(cost, "XAU_USD H1 (edge)", new TurtleCrossAssetStrategy("SigXau", XAU, 55, 20, 10.0), XAU, xauBars);
        signatureRow(cost, "EUR_USD H1", new TurtleCrossAssetStrategy("SigEur", "EUR_USD", 55, 20, 10_000.0), "EUR_USD", eur);
        for (String[] ix : INDEXES) {
            List<Bar> bars = loadD1(ix[0], ix[1]);
            signatureRow(cost, ix[2] + " D1", new TurtleCrossAssetStrategy("Sig", ix[0], 55, 20, 5.0), ix[0], bars);
        }
    }

    static void signatureRow(BacktestExecutionCost cost, String label,
                             com.martinfou.trading.core.Strategy strat, String symbol, List<Bar> bars) {
        BacktestResult r = run(cost, strat, symbol, bars);
        double longPnl = 0, shortPnl = 0; int nl = 0, ns = 0;
        for (Trade t : r.trades()) {
            if (t.side() == Order.Side.BUY) { longPnl += t.pnl(); nl++; }
            else { shortPnl += t.pnl(); ns++; }
        }
        System.out.printf("%-22s %-8d %-12.0f %-12.0f %-9d %-9d %-8.0f%n",
            label, r.totalTrades(), longPnl, shortPnl, nl, ns, r.totalPnl());
    }

    // ------------------------------------------------------------------
    // 3. SWEEP CANAUX (plateau vs pic)
    // ------------------------------------------------------------------
    static void sweep(BacktestExecutionCost cost) throws Exception {
        int[] entries = {40, 55, 70};
        int[] exits = {15, 20, 25};
        for (String[] ix : INDEXES) {
            List<Bar> bars = loadD1(ix[0], ix[1]);
            System.out.println("\n### 3. SWEEP CANAUX — " + ix[2] + " (qty 5)");
            System.out.printf("%-6s %-6s %-6s %-6s %-6s %-7s %-12s%n",
                "ENTRY", "EXIT", "PF", "WR%", "DD%", "TRADES", "NET$");
            for (int e : entries) for (int x : exits) {
                BacktestResult r = run(cost, new TurtleCrossAssetStrategy("IndexTurtle", ix[0], e, x, 5.0), ix[0], bars);
                System.out.printf("%-6d %-6d %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
                    e, x, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(), r.totalTrades(), r.totalPnl());
            }
        }
    }

    // ------------------------------------------------------------------
    // 4. SWEEP QUANTITÉ (levier : PF invariant, DD/NET scalent ?)
    // ------------------------------------------------------------------
    static void qtySweep(BacktestExecutionCost cost) throws Exception {
        double[] qtys = {2.0, 5.0, 8.0};
        for (String[] ix : INDEXES) {
            List<Bar> bars = loadD1(ix[0], ix[1]);
            System.out.println("\n### 4. SWEEP QUANTITÉ — " + ix[2] + " (55/20)");
            System.out.printf("%-6s %-6s %-6s %-6s %-7s %-12s %-8s%n",
                "QTY", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%");
            for (double q : qtys) {
                BacktestResult r = run(cost, new TurtleCrossAssetStrategy("IndexTurtle", ix[0], 55, 20, q), ix[0], bars);
                System.out.printf("%-6.1f %-6.2f %-6.1f %-6.2f %-7d %12.2f %-8.2f%n",
                    q, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(), r.totalTrades(),
                    r.totalPnl(), r.totalReturnPct());
            }
        }
    }

    // ------------------------------------------------------------------
    // 5. WALK-FORWARD + CRISES
    // ------------------------------------------------------------------
    static void walkForward(BacktestExecutionCost cost) throws Exception {
        String[][] splits = {
            {"IS 2006-2015", "2006-01-01", "2015-12-31"},
            {"OOS 2016-2026", "2016-01-01", "2026-12-31"},
        };
        System.out.println("\n### 5. WALK-FORWARD (qty 5, 55/20)");
        System.out.printf("%-16s %-22s %-6s %-6s %-6s %-7s %-12s %-8s %-12s%n",
            "SPLIT", "INSTRUMENT", "PF", "WR%", "DD%", "TRADES", "NET$", "BH%", "LONG$/SHORT$");
        for (String[] sp : splits) {
            for (String[] ix : INDEXES) {
                List<Bar> bars = loadD1Range(ix[0], ix[1], sp[1], sp[2]);
                if (bars.size() < 60) { System.out.printf("%-16s %-22s (données insuffisantes : %d barres)%n", sp[0], ix[2], bars.size()); continue; }
                BacktestResult r = run(cost, new TurtleCrossAssetStrategy("IndexTurtle", ix[0], 55, 20, 5.0), ix[0], bars);
                System.out.printf("%-16s %-22s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-8.2f %6.0f/%-8.0f%n",
                    sp[0], ix[2], r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(), r.totalTrades(),
                    r.totalPnl(), buyHoldRet(bars), sidePnl(r, Order.Side.BUY), sidePnl(r, Order.Side.SELL));
            }
        }

        System.out.println("\n### 5b. CRISES (test discriminant : un trend-follower doit gagner dans le bear)");
        String[][] crises = {
            {"GFC 2008-2009", "2008-01-01", "2009-12-31"},
            {"Covid 2020", "2020-01-01", "2020-12-31"},
            {"Bear 2022", "2022-01-01", "2022-12-31"},
        };
        System.out.printf("%-16s %-22s %-6s %-6s %-6s %-7s %-12s %-8s %-12s%n",
            "CRISE", "INSTRUMENT", "PF", "WR%", "DD%", "TRADES", "NET$", "BH%", "LONG$/SHORT$");
        for (String[] c : crises) {
            for (String[] ix : INDEXES) {
                List<Bar> bars = loadD1Range(ix[0], ix[1], c[1], c[2]);
                if (bars.size() < 60) continue;
                BacktestResult r = run(cost, new TurtleCrossAssetStrategy("IndexTurtle", ix[0], 55, 20, 5.0), ix[0], bars);
                System.out.printf("%-16s %-22s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-8.2f %6.0f/%-8.0f%n",
                    c[0], ix[2], r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(), r.totalTrades(),
                    r.totalPnl(), buyHoldRet(bars), sidePnl(r, Order.Side.BUY), sidePnl(r, Order.Side.SELL));
            }
        }
    }

    // ------------------------------------------------------------------
    // 6. RÉGIMES
    // ------------------------------------------------------------------
    static void regime(BacktestExecutionCost cost) throws Exception {
        String[][] eras = {
            {"2006-2009 GFC", "2006-01-01", "2009-12-31"},
            {"2010-2019 bull", "2010-01-01", "2019-12-31"},
            {"2020-2026 post", "2020-01-01", "2026-12-31"},
        };
        System.out.println("\n### 6. RÉGIMES (qty 5, 55/20)");
        System.out.printf("%-16s %-22s %-6s %-6s %-6s %-7s %-12s %-8s %-12s%n",
            "RÉGIME", "INSTRUMENT", "PF", "WR%", "DD%", "TRADES", "NET$", "BH%", "LONG$/SHORT$");
        for (String[] e : eras) {
            for (String[] ix : INDEXES) {
                List<Bar> bars = loadD1Range(ix[0], ix[1], e[1], e[2]);
                if (bars.size() < 60) continue;
                BacktestResult r = run(cost, new TurtleCrossAssetStrategy("IndexTurtle", ix[0], 55, 20, 5.0), ix[0], bars);
                System.out.printf("%-16s %-22s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-8.2f %6.0f/%-8.0f%n",
                    e[0], ix[2], r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(), r.totalTrades(),
                    r.totalPnl(), buyHoldRet(bars), sidePnl(r, Order.Side.BUY), sidePnl(r, Order.Side.SELL));
            }
        }
    }

    // ------------------------------------------------------------------
    // HELPERS
    // ------------------------------------------------------------------
    static BacktestResult run(BacktestExecutionCost cost, com.martinfou.trading.core.Strategy strat,
                              String symbol, List<Bar> bars) {
        return RunContext.forStrategy(null, strat.name(), strat, symbol,
            RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
    }

    static void printRow(String label, BacktestResult r) {
        System.out.printf("%-28s %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
            label, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(), r.totalTrades(), r.totalPnl());
    }

    static double sidePnl(BacktestResult r, Order.Side side) {
        double s = 0;
        for (Trade t : r.trades()) if (t.side() == side) s += t.pnl();
        return s;
    }

    static void checkSides(BacktestResult r, String label) {
        double sum = 0;
        for (Trade t : r.trades()) sum += t.pnl();
        double delta = Math.abs(sum - r.totalPnl());
        if (Math.abs(delta) > Math.max(1.0, Math.abs(r.totalPnl()) * 0.02)) {
            System.out.printf("   [warn] %s : somme trades %.2f vs totalPnl %.2f (trades=%d, totalTrades=%d)%n",
                label, sum, r.totalPnl(), r.trades().size(), r.totalTrades());
        }
    }

    static double buyHoldRet(List<Bar> bars) {
        if (bars.isEmpty()) return 0;
        double first = bars.get(0).close(), last = bars.get(bars.size() - 1).close();
        return (last - first) / first * 100.0;
    }

    static double buyHoldPnl(List<Bar> bars, double qty) {
        if (bars.isEmpty()) return 0;
        return (bars.get(bars.size() - 1).close() - bars.get(0).close()) * qty;
    }

    static double buyHoldDd(List<Bar> bars) {
        double peak = Double.NEGATIVE_INFINITY, dd = 0;
        for (Bar b : bars) {
            if (b.close() > peak) peak = b.close();
            if (peak > 0) dd = Math.max(dd, (peak - b.close()) / peak * 100.0);
        }
        return dd;
    }

    static List<Bar> loadD1(String symbol, String csv) throws Exception {
        return loadD1Range(symbol, csv, "1900-01-01", "2100-01-01");
    }

    static List<Bar> loadD1Range(String symbol, String csv, String from, String to) throws Exception {
        long lo = LocalDate.parse(from).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        long hi = LocalDate.parse(to).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
        List<Bar> out = new ArrayList<>();
        for (String line : Files.readAllLines(FUT.resolve(csv))) {
            String[] f = line.split(",");
            if (f.length < 6 || f[0].startsWith("Date")) continue;
            try {
                LocalDate d = LocalDate.parse(f[0].trim());
                Instant ts = d.atStartOfDay(ZoneOffset.UTC).toInstant();
                long ms = ts.toEpochMilli();
                if (ms < lo || ms >= hi) continue;
                double o = Double.parseDouble(f[1]), h = Double.parseDouble(f[2]);
                double l = Double.parseDouble(f[3]), c = Double.parseDouble(f[4]);
                long vol = (long) Double.parseDouble(f[6]);
                out.add(new Bar(symbol, ts, o, h, l, c, vol));
            } catch (Exception ignore) { }
        }
        return out;
    }
}
