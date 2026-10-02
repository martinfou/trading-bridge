package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RealCostModel;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.DateWindowSeasonalStrategy;
import com.martinfou.trading.strategies.creative.GoldTurtleDualGateStrategy;
import com.martinfou.trading.strategies.creative.GoldTurtleDxyFilterStrategy;
import com.martinfou.trading.strategies.creative.GoldWeekdayDxyStrategy;
import com.martinfou.trading.strategies.creative.GoldWeekdayEffectStrategy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * RunShortlistRealCosts — rejeu des 8 candidats du shortlist sous RealCostModel,
 * aux DEUX niveaux de spread (médiane mesurée et conservateur ×1.5), sur la porte
 * WF FULL/IS/OOS1/OOS2 (docs/lt-strategy-playbook.md §4.2 / §4.3).
 *
 * Fenêtres : FULL 2010-2025, IS 2010-2018, OOS1 2019-2022, OOS2 2023-2025.
 * Porte : PF ≥ 1.05, DD ≤ 35 %, OOS1/OOS2 PF < 1.0 ⇒ invalide. Sharpe non exploitable.
 *
 * Candidats exécutables (paire mesurée dans RealCostModel.HALF_SPREAD_PRICE) :
 *   1. Gold Turtle 55/20 + double porte OR            XAU_USD
 *   2. Gold Turtle + DXY opposé                       XAU_USD
 *   3. Gold vendredi long × DXY opposé                XAU_USD
 *   4. FX vendredi fade GBP_JPY + overlay volatilité   GBP_JPY
 *   6. Gold vendredi simple                           XAU_USD
 *   7. Gold janvier                                   XAU_USD
 * Candidats NON exécutables (paire non mesurée → RealCostModel refuse) :
 *   5. GBP_USD fade   (GBP_USD non mesuré)
 *   8. USD_CAD saisonnalité (USD_CAD non mesuré)
 *
 * Usage :
 *   java -cp "$CP" com.martinfou.trading.examples.RunShortlistRealCosts --validate
 *   java -cp "$CP" com.martinfou.trading.examples.RunShortlistRealCosts --gate
 */
public class RunShortlistRealCosts {

    static final String GOLD = "XAU_USD";
    static final String GBPJPY = "GBP_JPY";
    static final String GOLD_SPEC = "2006-2025";
    static final String GBPJPY_SPEC = "2006-2026";
    static final double CAPITAL = 50_000;
    static final boolean[] FRI = {false, false, false, false, true};

    static final int ALIGNED = GoldTurtleDualGateStrategy.POL_ALIGNED;
    static final int OPPOSITE = GoldTurtleDualGateStrategy.POL_OPPOSITE;

    record Window(String name, int y0, int y1) {}
    static final List<Window> WINDOWS = List.of(
        new Window("FULL", 2010, 2025),
        new Window("IS",   2010, 2018),
        new Window("OOS1", 2019, 2022),
        new Window("OOS2", 2023, 2025)
    );

    // ---------------------------------------------------------------- entry

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "--gate";
        if (mode.equals("--validate")) { validate(); return; }
        gate();
    }

    // ---------------------------------------------------------------- validation (ancien modèle de coûts)

    /** Reproduit les références publiées sous l'ANCIEN coût ($0.07 + 0.01%) pour valider la construction. */
    static void validate() throws Exception {
        java.util.function.Function<String, BacktestExecutionCost> oldCost =
            s -> BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);

        List<Bar> gold = load(GOLD, GOLD_SPEC);
        Map<Long, Double> dxy = RunGoldTurtleDualGate.buildDxy(GOLD_SPEC);
        Map<Long, Double> spx = RunGoldTurtleDualGate.buildSpxOnXauGrid(gold);

        System.out.println("=== VALIDATION (ancien coût $0.07 + 0.01% ; références publiées) ===");
        System.out.printf("%-34s %-6s %-6s %-6s %-7s %-12s%n", "CONFIG", "PF", "WR%", "DD%", "TRADES", "NET$");

        val(oldCost, GOLD, gold, "OR gate (att. 1.37/839/+20157)", c1(GOLD, dxy, spx));
        val(oldCost, GOLD, gold, "DxyFilter OPP (att. 1.34/830/+18143)", c2(GOLD, dxy));
        val(oldCost, GOLD, gold, "WeekdayDxy OPP FRI (att. 1.40)", c3(GOLD, dxy));
        val(oldCost, GOLD, gold, "FRI seul (att. 1.27/1041/+15131)", c6(GOLD));
        val(oldCost, GOLD, gold, "Gold Janvier (att. 5.09/20)", c7(GOLD));

        List<Bar> gj = load(GBPJPY, GBPJPY_SPEC);
        Map<LocalDate, Double> and = buildAndRank(GBPJPY);
        val(oldCost, GBPJPY, gj, "GBP_JPY FRI overlay (att. 1.23/1061/+10648)", c4(GBPJPY, and));

        System.out.println("\nDONE-VALIDATE");
    }

    static void val(java.util.function.Function<String, BacktestExecutionCost> cost, String sym,
                    List<Bar> bars, String label, Strategy s) {
        BacktestResult r = run(cost, sym, bars, s);
        System.out.printf("%-34s %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
            label, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(), r.totalTrades(), r.totalPnl());
    }

    // ---------------------------------------------------------------- porte

    static void gate() throws Exception {
        // chargement + régimes construits une fois sur la plage complète
        List<Bar> gold = load(GOLD, GOLD_SPEC);
        Map<Long, Double> dxy = RunGoldTurtleDualGate.buildDxy(GOLD_SPEC);
        Map<Long, Double> spx = RunGoldTurtleDualGate.buildSpxOnXauGrid(gold);
        List<Bar> gj = load(GBPJPY, GBPJPY_SPEC);
        Map<LocalDate, Double> and = buildAndRank(GBPJPY);

        record Cand(String id, String label, String sym, List<Bar> full, StrategyFactory f) {}
        Cand[] cands = {
            new Cand("1", "Gold Turtle 55/20 + double porte OR", GOLD, gold, () -> c1(GOLD, dxy, spx)),
            new Cand("2", "Gold Turtle + DXY opposé", GOLD, gold, () -> c2(GOLD, dxy)),
            new Cand("3", "Gold vendredi long × DXY opposé", GOLD, gold, () -> c3(GOLD, dxy)),
            new Cand("4", "FX vendredi fade GBP_JPY + overlay vol", GBPJPY, gj, () -> c4(GBPJPY, and)),
            new Cand("6", "Gold vendredi simple", GOLD, gold, () -> c6(GOLD)),
            new Cand("7", "Gold janvier", GOLD, gold, () -> c7(GOLD)),
        };

        double[] levels = {1.0, 1.5};
        String[] levelNames = {"MEDIANE", "CONSERVATIF x1.5"};

        for (Cand c : cands) {
            for (int li = 0; li < levels.length; li++) {
                final double mult = levels[li];
                java.util.function.Function<String, BacktestExecutionCost> cost =
                    s -> costScaled(s, mult);
                System.out.println("\n==================================================================");
                System.out.println("CANDIDAT " + c.id + " — " + c.label + "  [" + c.sym + "]  spread=" + levelNames[li]);
                System.out.println("==================================================================");
                System.out.printf("%-6s %-6s %-7s %-7s %-11s %-11s%n",
                    "WINDOW", "PF", "DD%", "TRADES", "NET$", "SWAP$");
                for (Window w : WINDOWS) {
                    List<Bar> bars = slice(c.full, w.y0, w.y1);
                    Strategy s = c.f.build();
                    BacktestResult r = run(cost, c.sym, bars, s);
                    System.out.printf("%-6s %-6.2f %-7.2f %-7d %11.2f %11.2f%n",
                        w.name, r.profitFactor(), r.maxDrawdownPct(), r.totalTrades(),
                        r.totalPnl(), r.totalSwap());
                }
            }
        }
        System.out.println("\nDONE-GATE");
    }

    interface StrategyFactory { Strategy build(); }

    // ---------------------------------------------------------------- stratégies

    static GoldTurtleDualGateStrategy c1(String sym, Map<Long, Double> dxy, Map<Long, Double> spx) {
        return new GoldTurtleDualGateStrategy("c1", sym, 55, 20,
            GoldTurtleDualGateStrategy.MODE_OR, OPPOSITE, ALIGNED, 500, 4800, 1, 0,
            GoldTurtleDualGateStrategy.SIDE_BOTH, dxy, spx);
    }

    static GoldTurtleDxyFilterStrategy c2(String sym, Map<Long, Double> dxy) {
        return new GoldTurtleDxyFilterStrategy("c2", sym, 55, 20,
            GoldTurtleDxyFilterStrategy.MODE_OPPOSITE, 500, dxy);
    }

    static GoldWeekdayDxyStrategy c3(String sym, Map<Long, Double> dxy) {
        return new GoldWeekdayDxyStrategy("c3", sym, FRI,
            GoldWeekdayDxyStrategy.MODE_OPPOSITE, 500, dxy);
    }

    static GoldWeekdayEffectStrategy c4(String sym, Map<LocalDate, Double> andRank) {
        return new GoldWeekdayEffectStrategy("c4", sym, FRI, Order.Side.SELL)
            .withQuantity(10_000)
            .withRegime(andRank)
            .withOverlay(1.0, 2.0, 60.0);
    }

    static GoldWeekdayEffectStrategy c6(String sym) {
        return new GoldWeekdayEffectStrategy("c6", sym, false, true); // long FRI, 10 oz
    }

    static DateWindowSeasonalStrategy c7(String sym) {
        return new DateWindowSeasonalStrategy("c7", sym, 1, 1, 1, 31, Order.Side.BUY, 10);
    }

    // ---------------------------------------------------------------- coûts

    static BacktestExecutionCost costScaled(String sym, double mult) {
        double half = RealCostModel.halfSpread(sym);            // refuse les paires non mesurées
        Double ref = RealCostModel.REFERENCE_MIDS.get(sym);
        double refP = ref == null ? 1.0 : ref;
        double h = half * mult;
        return new BacktestExecutionCost(0.0, 0.0, 0.0, h, refP > 0 ? h / refP : 0.0);
    }

    // ---------------------------------------------------------------- régime de volatilité (candidat 4)

    static Map<LocalDate, Double> buildAndRank(String sym) throws Exception {
        TreeMap<LocalDate, Double> mes = RunFxVolRegimeSize.dailyClosesCsv("MES_D1.csv");
        TreeMap<LocalDate, Double> eqRank = RunFxVolRegimeSize.trailingRank(
            RunFxVolRegimeSize.vol(RunFxVolRegimeSize.rets(mes), 21), 1000);
        TreeMap<LocalDate, Double> d = RunFxVolRegimeSize.dailyClosesBars(sym, 2006, 2026);
        TreeMap<LocalDate, Double> r = RunFxVolRegimeSize.trailingRank(
            RunFxVolRegimeSize.vol(RunFxVolRegimeSize.rets(d), 21), 1000);
        TreeMap<LocalDate, Double> and = new TreeMap<>();
        for (var e : r.entrySet()) {
            Double q = RunFxVolRegimeSize.regimeAt(eqRank, e.getKey());
            if (q == null) continue;
            and.put(e.getKey(), Math.min(q, e.getValue()));
        }
        return and;
    }

    // ---------------------------------------------------------------- exécution / données

    static BacktestResult run(java.util.function.Function<String, BacktestExecutionCost> cost,
                              String sym, List<Bar> bars, Strategy s) {
        return RunContext.forStrategy(null, s.name(), s, sym, RunMode.BACKTEST,
            bars, CAPITAL, null, cost).run();
    }

    static List<Bar> load(String sym, String spec) throws Exception {
        var loaded = HistoricalDataLoader.loadFromArgs(sym, sym, spec);
        List<Bar> bars = loaded.bars();
        if (bars.isEmpty()) throw new IllegalStateException("PAS DE DONNÉES pour " + sym + " " + spec);
        return bars;
    }

    static List<Bar> slice(List<Bar> bars, int y0, int y1) {
        List<Bar> out = new ArrayList<>();
        for (Bar b : bars) {
            int y = b.timestamp().atZone(java.time.ZoneOffset.UTC).getYear();
            if (y >= y0 && y <= y1) out.add(b);
        }
        return out;
    }
}
