package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.GoldTurtleDualGateStrategy;
import com.martinfou.trading.strategies.creative.GoldTurtleTrendStrategy;

import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * RunGoldTurtleDualGate — Idée nouvelle, lundi 14 septembre 2026 (35e résultat).
 *
 * Question (revue hebdo 12 sept, lead #1) : le quadrant twin (« USD ferme × stress
 * actions ») — dont la DÉRIVE forward est validée (10 sept, EXPLORE) mais dont le gate
 * mono-jambe est REJETÉ — est-il énumérable comme une CONJONCTION BOOLÉENNE de deux
 * gauges (A = DXY synthétique, B = S&P 500) ? Le gate à deux jambes (AND / OR) porte-t-il
 * de l'information que ni l'une ni l'autre jambe seule ne porte ?
 *
 * ⚠️ VALIDATION D'IMPLÉMENTATION OBLIGATOIRE (avant toute conclusion) :
 *   - baseline OFF                     = 1.17 / 41.5% / 9.35% / 1594 / +$17 705
 *   - A_ONLY OPPOSITE SMA500 (DXY)     = 1.34 / 830 trades / +$18 143  (28 août)
 *   - B_ONLY OPPOSITE SMA4800 (SPX)    = 1.23 / +$12 727              (10 sept)
 *
 * Modes du gate : OFF / A_ONLY / B_ONLY / AND / OR × polarité par jambe (ALIGNED/OPPOSITE).
 * AND(A=OPPOSITE, B=ALIGNED) = « long or si USD ferme ET actions en stress » = le quadrant twin.
 *
 * Usage : java -cp "$CP" com.martinfou.trading.examples.RunGoldTurtleDualGate [--validate|--sweep|--wf|--regime]
 */
public class RunGoldTurtleDualGate {

    static final String YEAR_SPEC = "2006-2025";
    static final double CAPITAL = 50_000;
    static final String GOLD = "XAU_USD";
    static final Path SPX_CSV = Path.of("data/historical/futures/MES_D1.csv");
    static final int SMA_A = 500;     // DXY ~21 jours H1 (config 28 août)
    static final int SMA_B = 4800;    // S&P ~200 jours H1 (config 10 sept)

    static final int ALIGNED  = GoldTurtleDualGateStrategy.POL_ALIGNED;
    static final int OPPOSITE = GoldTurtleDualGateStrategy.POL_OPPOSITE;

    // DXY classic weights (SEK dropped), normalized to sum 1
    static final String[] DXY_PAIRS = {"EUR_USD", "USD_JPY", "GBP_USD", "USD_CAD", "USD_CHF"};
    static final double[] DXY_W = {0.576, 0.136, 0.119, 0.091, 0.036};
    static final double DXY_WSUM;
    static { double s = 0; for (double w : DXY_W) s += w; DXY_WSUM = s; }
    static final int[] DXY_SIGN = {-1, +1, -1, +1, +1};
    static final double DXY_K = 50.14348112;

    public static void main(String[] args) throws Exception {
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);
        var xau = HistoricalDataLoader.loadFromArgs(GOLD, GOLD, YEAR_SPEC).bars();
        Map<Long, Double> dxy = buildDxy();
        Map<Long, Double> spx = buildSpxOnXauGrid(xau);

        if (args.length > 0 && args[0].equals("--validate")) { runValidate(cost, xau, dxy, spx); return; }
        if (args.length > 0 && args[0].equals("--sweep"))    { runSweep(cost, xau, dxy, spx); return; }
        if (args.length > 0 && args[0].equals("--wf"))       { runWalkForward(cost, dxy, spx); return; }
        if (args.length > 0 && args[0].equals("--regime"))   { runRegime(cost, dxy, spx); return; }
        if (args.length > 0 && args[0].equals("--sig"))      { runSignature(cost, xau, dxy, spx); return; }

        System.out.println("=====================================================================");
        System.out.println("GOLD TURTLE + DUAL GATE (2 jambes : DXY × S&P 500) — XAU/USD H1 2006-2025");
        System.out.println("Gate A = DXY synthétique vs SMA " + SMA_A + " H1 (~21 j)");
        System.out.println("Gate B = S&P 500 (MES_D1, close J-1) vs SMA " + SMA_B + " H1 (~200 j)");
        System.out.println("Coûts : commission $0.07 + slippage 0.01% | Capital : $" + CAPITAL);
        System.out.println("=====================================================================");
        System.out.printf("XAU: %d barres | points DXY: %d | points SPX projetés: %d%n%n",
            xau.size(), dxy.size(), spx.size());

        // --- Validation d'implémentation : les 3 références connues ---
        System.out.println("--- VALIDATION (doit reproduire les références publiées) ---");
        printHeader();
        run(cost, "Baseline (OFF)", GOLD, xau, str("dummy", GOLD, GoldTurtleDualGateStrategy.MODE_OFF, ALIGNED, ALIGNED, dxy, spx));
        run(cost, "A_ONLY OPP SMA500  [=28 août]", GOLD, xau, str("dummy", GOLD, GoldTurtleDualGateStrategy.MODE_A_ONLY, OPPOSITE, ALIGNED, dxy, spx));
        run(cost, "A_ONLY ALI SMA500", GOLD, xau, str("dummy", GOLD, GoldTurtleDualGateStrategy.MODE_A_ONLY, ALIGNED, ALIGNED, dxy, spx));
        run(cost, "B_ONLY OPP SMA4800 [=10 sept]", GOLD, xau, str("dummy", GOLD, GoldTurtleDualGateStrategy.MODE_B_ONLY, ALIGNED, OPPOSITE, dxy, spx));
        run(cost, "B_ONLY ALI SMA4800", GOLD, xau, str("dummy", GOLD, GoldTurtleDualGateStrategy.MODE_B_ONLY, ALIGNED, ALIGNED, dxy, spx));

        // --- Le cœur : combinaisons à deux jambes ---
        System.out.println("\n--- GATE À DEUX JAMBES (A = DXY, B = SPX) ---");
        printHeader();
        for (int pa : new int[]{ALIGNED, OPPOSITE}) {
            for (int pb : new int[]{ALIGNED, OPPOSITE}) {
                run(cost, "AND  A=" + pol(pa) + " B=" + pol(pb), GOLD, xau,
                    str("dummy", GOLD, GoldTurtleDualGateStrategy.MODE_AND, pa, pb, dxy, spx));
            }
        }
        for (int pa : new int[]{ALIGNED, OPPOSITE}) {
            for (int pb : new int[]{ALIGNED, OPPOSITE}) {
                run(cost, "OR   A=" + pol(pa) + " B=" + pol(pb), GOLD, xau,
                    str("dummy", GOLD, GoldTurtleDualGateStrategy.MODE_OR, pa, pb, dxy, spx));
            }
        }

        // --- Contrôle instrument : EUR_USD (aucun edge Turtle, contrôle négatif systématique) ---
        var eur = HistoricalDataLoader.loadFromArgs("EUR_USD", "EUR_USD", YEAR_SPEC).bars();
        Map<Long, Double> spxEur = buildSpxOnXauGrid(eur);
        System.out.println("\n--- Contrôle EUR_USD (même mécanique, mêmes gauges) ---");
        printHeader();
        run(cost, "EUR baseline", "EUR_USD", eur, str("dummy", "EUR_USD", GoldTurtleDualGateStrategy.MODE_OFF, ALIGNED, ALIGNED, dxy, spxEur));
        run(cost, "EUR A_ONLY OPP", "EUR_USD", eur, str("dummy", "EUR_USD", GoldTurtleDualGateStrategy.MODE_A_ONLY, OPPOSITE, ALIGNED, dxy, spxEur));
        run(cost, "EUR AND A=OPP B=ALI", "EUR_USD", eur, str("dummy", "EUR_USD", GoldTurtleDualGateStrategy.MODE_AND, OPPOSITE, ALIGNED, dxy, spxEur));
        run(cost, "EUR OR A=OPP B=ALI", "EUR_USD", eur, str("dummy", "EUR_USD", GoldTurtleDualGateStrategy.MODE_OR, OPPOSITE, ALIGNED, dxy, spxEur));

        System.out.println("\nDONE");
    }

    // ---------------------------------------------------------------- validation seule

    private static void runValidate(BacktestExecutionCost cost, List<Bar> xau,
                                    Map<Long, Double> dxy, Map<Long, Double> spx) {
        System.out.println("=== VALIDATION D'IMPLÉMENTATION ===");
        printHeader();
        run(cost, "Baseline (OFF)", GOLD, xau, str("d", GOLD, GoldTurtleDualGateStrategy.MODE_OFF, ALIGNED, ALIGNED, dxy, spx));
        run(cost, "A_ONLY OPP SMA500 (attendu 1.34/830/+18143)", GOLD, xau,
            str("d", GOLD, GoldTurtleDualGateStrategy.MODE_A_ONLY, OPPOSITE, ALIGNED, dxy, spx));
        run(cost, "B_ONLY OPP SMA4800 (attendu 1.23/+12727)", GOLD, xau,
            str("d", GOLD, GoldTurtleDualGateStrategy.MODE_B_ONLY, ALIGNED, OPPOSITE, dxy, spx));
        System.out.println("\nDONE");
    }

    // ---------------------------------------------------------------- sweep 2D

    private static void runSweep(BacktestExecutionCost cost, List<Bar> xau,
                                 Map<Long, Double> dxy, Map<Long, Double> spx) {
        int[] aPeriods = {250, 400, 500, 750, 1000};
        int[] bPeriods = {1200, 2400, 3600, 4800, 7200, 9600};
        sweepGrid(cost, xau, dxy, spx, aPeriods, bPeriods,
            GoldTurtleDualGateStrategy.MODE_OR, OPPOSITE, ALIGNED, "OR(A=OPP USD ferme, B=ALI stress SPX)");
        sweepGrid(cost, xau, dxy, spx, aPeriods, bPeriods,
            GoldTurtleDualGateStrategy.MODE_OR, OPPOSITE, OPPOSITE, "OR(A=OPP USD ferme, B=OPP actions calmes)");
        sweepGrid(cost, xau, dxy, spx, aPeriods, bPeriods,
            GoldTurtleDualGateStrategy.MODE_AND, OPPOSITE, ALIGNED, "AND(A=OPP USD ferme, B=ALI stress SPX) = quadrant twin");
        System.out.println("\nDONE");
    }

    private static void sweepGrid(BacktestExecutionCost cost, List<Bar> xau,
                                  Map<Long, Double> dxy, Map<Long, Double> spx,
                                  int[] aPeriods, int[] bPeriods,
                                  int mode, int pa, int pb, String title) {
        System.out.println("\n=== SWEEP 2D — " + title + " ===");
        System.out.printf("%-9s", "SMAA\\SMAB");
        for (int b : bPeriods) System.out.printf("%-10d", b);
        System.out.println();
        for (int a : aPeriods) {
            System.out.printf("%-9d", a);
            for (int b : bPeriods) {
                var s = str("d", GOLD, mode, pa, pb, a, b, dxy, spx);
                BacktestResult r = runRaw(cost, GOLD, xau, s);
                System.out.printf("%-10s", String.format("%.2f/%d", r.profitFactor(), r.totalTrades()));
            }
            System.out.println();
        }
        System.out.println("(cellule = PF/trades)");
    }

    // ---------------------------------------------------------------- walk-forward

    private static void runWalkForward(BacktestExecutionCost cost, Map<Long, Double> dxyAll,
                                       Map<Long, Double> spxAll) throws Exception {
        System.out.println("=== WALK-FORWARD XAU_USD (IS 2006-2015 / OOS 2016-2025) ===");
        System.out.printf("%-30s %-14s %-6s %-6s %-6s %-7s %-12s%n",
            "CONFIG", "PHASE", "PF", "WR%", "DD%", "TRADES", "NET$");
        String[] specs = {"2006-2015", "2016-2025"};
        String[] labels = {"IS 2006-15", "OOS 2016-25"};
        for (int i = 0; i < specs.length; i++) {
            var bars = HistoricalDataLoader.loadFromArgs(GOLD, GOLD, specs[i]).bars();
            Map<Long, Double> dxy = buildDxy(specs[i]);
            Map<Long, Double> spx = buildSpxOnXauGrid(bars);
            runWf(cost, "Baseline", labels[i], bars, str("d", GOLD, GoldTurtleDualGateStrategy.MODE_OFF, ALIGNED, ALIGNED, dxy, spx));
            runWf(cost, "A_ONLY OPP (DXY ferme)", labels[i], bars, str("d", GOLD, GoldTurtleDualGateStrategy.MODE_A_ONLY, OPPOSITE, ALIGNED, dxy, spx));
            runWf(cost, "B_ONLY ALI (SPX stress)", labels[i], bars, str("d", GOLD, GoldTurtleDualGateStrategy.MODE_B_ONLY, ALIGNED, ALIGNED, dxy, spx));
            runWf(cost, "AND A=OPP B=ALI (twin)", labels[i], bars, str("d", GOLD, GoldTurtleDualGateStrategy.MODE_AND, OPPOSITE, ALIGNED, dxy, spx));
            runWf(cost, "AND A=OPP B=OPP", labels[i], bars, str("d", GOLD, GoldTurtleDualGateStrategy.MODE_AND, OPPOSITE, OPPOSITE, dxy, spx));
            runWf(cost, "OR A=OPP B=ALI", labels[i], bars, str("d", GOLD, GoldTurtleDualGateStrategy.MODE_OR, OPPOSITE, ALIGNED, dxy, spx));
        }
        System.out.println("\nDONE");
    }

    private static void runWf(BacktestExecutionCost cost, String label, String phase,
                              List<Bar> bars, Strategy s) {
        BacktestResult r = runRaw(cost, GOLD, bars, s);
        System.out.printf("%-30s %-14s %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
            label, phase, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
            r.totalTrades(), r.totalPnl());
    }

    // ---------------------------------------------------------------- régimes

    private static void runRegime(BacktestExecutionCost cost, Map<Long, Double> dxyAll,
                                  Map<Long, Double> spxAll) throws Exception {
        String[] eras = {"bull 2006-12", "bear 2013-15", "bull2 2016-25"};
        String[] specs = {"2006-2012", "2013-2015", "2016-2025"};
        System.out.println("=== RÉGIMES ===");
        System.out.printf("%-14s %-24s %-6s %-6s %-6s %-7s %-12s%n",
            "REGIME", "CONFIG", "PF", "WR%", "DD%", "TRADES", "NET$");
        for (int i = 0; i < eras.length; i++) {
            var bars = HistoricalDataLoader.loadFromArgs(GOLD, GOLD, specs[i]).bars();
            if (bars.isEmpty()) { System.out.println(eras[i] + " : pas de données"); continue; }
            Map<Long, Double> dxy = buildDxy(specs[i]);
            Map<Long, Double> spx = buildSpxOnXauGrid(bars);
            runWf(cost, eras[i], "Baseline", bars, str("d", GOLD, GoldTurtleDualGateStrategy.MODE_OFF, ALIGNED, ALIGNED, dxy, spx));
            runWf(cost, eras[i], "A_ONLY OPP", bars, str("d", GOLD, GoldTurtleDualGateStrategy.MODE_A_ONLY, OPPOSITE, ALIGNED, dxy, spx));
            runWf(cost, eras[i], "AND twin", bars, str("d", GOLD, GoldTurtleDualGateStrategy.MODE_AND, OPPOSITE, ALIGNED, dxy, spx));
            runWf(cost, eras[i], "OR A=OPP B=ALI", bars, str("d", GOLD, GoldTurtleDualGateStrategy.MODE_OR, OPPOSITE, ALIGNED, dxy, spx));
        }
        System.out.println("\nDONE");
    }

    // ---------------------------------------------------------------- signature LONG/SHORT

    /**
     * Signature LONG/SHORT (diagnostic d'univers du 11 sept) : décompose le PnL par côté.
     * Un net positif qui est un RÉSIDU de cancellation (|LONG| ≈ |SHORT|) n'est pas une capture
     * de tendance. Vérifie AUSSI de quel côté vient un « gain » de filtre/gate.
     */
    private static void runSignature(BacktestExecutionCost cost, List<Bar> xau,
                                     Map<Long, Double> dxy, Map<Long, Double> spx) {
        System.out.println("=== SIGNATURE LONG/SHORT — XAU_USD 2006-2025 (coûts) ===");
        System.out.printf("%-34s %-6s %-12s %-12s %-12s %-7s%n",
            "CONFIG", "PF", "LONG$", "SHORT$", "NET$", "TRADES");
        sig(cost, "Baseline (OFF)", xau, str("d", GOLD, GoldTurtleDualGateStrategy.MODE_OFF, ALIGNED, ALIGNED, dxy, spx));
        sig(cost, "A_ONLY OPP (USD ferme)", xau, str("d", GOLD, GoldTurtleDualGateStrategy.MODE_A_ONLY, OPPOSITE, ALIGNED, dxy, spx));
        sig(cost, "B_ONLY ALI (stress SPX)", xau, str("d", GOLD, GoldTurtleDualGateStrategy.MODE_B_ONLY, ALIGNED, ALIGNED, dxy, spx));
        sig(cost, "OR A=OPP B=ALI", xau, str("d", GOLD, GoldTurtleDualGateStrategy.MODE_OR, OPPOSITE, ALIGNED, dxy, spx));
        sig(cost, "OR A=OPP B=OPP", xau, str("d", GOLD, GoldTurtleDualGateStrategy.MODE_OR, OPPOSITE, OPPOSITE, dxy, spx));
        sig(cost, "AND A=OPP B=ALI (twin)", xau, str("d", GOLD, GoldTurtleDualGateStrategy.MODE_AND, OPPOSITE, ALIGNED, dxy, spx));
        System.out.println("\nDONE");
    }

    private static void sig(BacktestExecutionCost cost, String label, List<Bar> bars, Strategy s) {
        BacktestResult r = runRaw(cost, GOLD, bars, s);
        double longUsd = 0, shortUsd = 0;
        for (com.martinfou.trading.core.Trade t : r.trades()) {
            if (t.side() == com.martinfou.trading.core.Order.Side.BUY) longUsd += t.pnl();
            else shortUsd += t.pnl();
        }
        System.out.printf("%-34s %-6.2f %-12.2f %-12.2f %-12.2f %-7d%n",
            label, r.profitFactor(), longUsd, shortUsd, r.totalPnl(), r.totalTrades());
    }

    // ---------------------------------------------------------------- helpers

    private static String pol(int p) { return p == OPPOSITE ? "OPP" : "ALI"; }

    private static GoldTurtleDualGateStrategy str(String name, String symbol, int mode,
                                                  int pa, int pb,
                                                  Map<Long, Double> a, Map<Long, Double> b) {
        return str(name, symbol, mode, pa, pb, SMA_A, SMA_B, a, b);
    }

    private static GoldTurtleDualGateStrategy str(String name, String symbol, int mode,
                                                  int pa, int pb, int smaA, int smaB,
                                                  Map<Long, Double> a, Map<Long, Double> b) {
        return new GoldTurtleDualGateStrategy(name, symbol, 55, 20, mode, pa, pb, smaA, smaB, a, b);
    }

    private static void printHeader() {
        System.out.printf("%-34s %-6s %-6s %-6s %-7s %-12s %-9s %-9s%n",
            "CONFIG", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%", "SWAP$");
    }

    private static BacktestResult runRaw(BacktestExecutionCost cost, String symbol,
                                         List<Bar> bars, Strategy s) {
        return RunContext.forStrategy(null, s.name(), s, symbol, RunMode.BACKTEST,
            bars, CAPITAL, null, cost).run();
    }

    private static void run(BacktestExecutionCost cost, String label, String symbol,
                            List<Bar> bars, Strategy s) {
        BacktestResult r = runRaw(cost, symbol, bars, s);
        System.out.printf("%-34s %-6.2f %-6.1f %-6.2f %-7d %12.2f %-9.2f %9.2f%n",
            label, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
            r.totalTrades(), r.totalPnl(), r.totalReturnPct(), r.totalSwap());
    }

    /** Build synthetic DXY for a given year spec. */
    static Map<Long, Double> buildDxy() throws Exception { return buildDxy(YEAR_SPEC); }

    static Map<Long, Double> buildDxy(String yearSpec) throws Exception {
        Map<String, List<Bar>> loaded = new HashMap<>();
        for (String p : DXY_PAIRS) loaded.put(p, HistoricalDataLoader.loadFromArgs(p, p, yearSpec).bars());

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
                logDxy += DXY_SIGN[i] * (DXY_W[i] / DXY_WSUM) * Math.log(closes[i]);
            }
            dxy.put(ts, DXY_K * Math.exp(logDxy));
        }
        return dxy;
    }

    /** S&P 500 D1 projeté sur la grille H1 : close du dernier jour STRICTEMENT antérieur (look-ahead safe). */
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
            Map.Entry<LocalDate, Double> e = spx.lowerEntry(d);
            if (e != null) out.put(b.timestamp().toEpochMilli(), e.getValue());
        }
        return out;
    }
}
