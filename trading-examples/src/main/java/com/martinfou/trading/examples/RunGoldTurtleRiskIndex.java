package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.GoldTurtleRiskIndexStrategy;
import com.martinfou.trading.strategies.creative.GoldTurtleTrendStrategy;

import java.time.*;
import java.util.*;

/**
 * RunGoldTurtleRiskIndex — Jeudi 3 septembre 2026 (intermarket/cross-asset).
 *
 * Question : le régime derrière l'edge or Turtle est-il le RISK-OFF générique
 * (gauge AUD/JPY synthétique, décorrélé de la direction USD) plutôt que le
 * « USD ferme » capturé par DXY-OPPOSITE le 28 août (PF 1.34) ?
 *
 * RAI (Risk Appetite Index) = AUD/JPY synthétique = close(AUD_USD) ×
 * close(USD_JPY), H1 aligné par timestamp — la paire canonique
 * « devise commodity vs devise refuge ». Haut = risk-on, bas = risk-off.
 *
 * Modes (GoldTurtleRiskIndexStrategy) :
 *   ALIGNED  : BUY or si RAI > SMA_N (or = actif risk, hypothèse naïve)
 *   OPPOSITE : BUY or si RAI < SMA_N (or = refuge, hypothèse risk-off)
 *   OFF      : baseline GoldTurtleTrend
 *
 * Comparaison clé avec les chiffres documentés du 28 août (même mécanique,
 * même coûts, même période) :
 *   Baseline        PF 1.17  DD 9.35%  1594 trades  net +$17 705
 *   DXY OPPOSITE    PF 1.34  DD 8.06%   830 trades  net +$18 143
 *   DXY ALIGNED     PF 0.95  DD ...     net -$3 534
 *
 * Usage:
 *   java -cp "$CP" com.martinfou.trading.examples.RunGoldTurtleRiskIndex
 *   java -cp "$CP" com.martinfou.trading.examples.RunGoldTurtleRiskIndex --sweep
 *   java -cp "$CP" com.martinfou.trading.examples.RunGoldTurtleRiskIndex --wf
 *   java -cp "$CP" com.martinfou.trading.examples.RunGoldTurtleRiskIndex --regime
 *   java -cp "$CP" com.martinfou.trading.examples.RunGoldTurtleRiskIndex --check
 */
public class RunGoldTurtleRiskIndex {

    static final String YEAR_SPEC = "2006-2025";
    static final double CAPITAL = 50_000;
    static final String GOLD = "XAU_USD";

    // RAI components
    static final String MASTER = "EUR_USD";     // timeline maître (couverture la + complète)
    static final String[] RAI_COMPONENTS = {"AUD_USD", "USD_JPY"};

    public static void main(String[] args) throws Exception {
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);

        // Build synthetic AUD/JPY risk index (all pairs, 2006-2025)
        Map<Long, Double> rai = buildRai();

        if (args.length > 0 && args[0].equals("--sweep")) { runSweep(cost, rai); return; }
        if (args.length > 0 && args[0].equals("--wf")) { runWalkForward(cost, rai); return; }
        if (args.length > 0 && args[0].equals("--regime")) { runRegime(cost, rai); return; }
        if (args.length > 0 && args[0].equals("--check")) { runCheck(rai); return; }

        System.out.println("==================================================");
        System.out.println("GOLD TURTLE + RISK-APPETITE FILTER — XAU/USD H1");
        System.out.println("RAI = AUD/JPY synthétique (AUD_USD × USD_JPY, H1 aligné)");
        System.out.println("Coûts: commission $0.07 + slippage 0.01% | Capital: $" + CAPITAL);
        System.out.println("Réf. 28 août (même setup) : baseline PF 1.17 / DXY-OFF 1.34 / DXY-ALIGNED 0.95");
        System.out.println("==================================================");

        var xau = HistoricalDataLoader.loadFromArgs(GOLD, GOLD, YEAR_SPEC).bars();
        System.out.printf("XAU_USD: %d bars | RAI points: %d%n%n", xau.size(), rai.size());

        // Baseline (OFF = GoldTurtleTrend exact) + ALIGNED + OPPOSITE, SMA 500 H1 (~21 jours)
        System.out.printf("%-26s %-6s %-6s %-6s %-7s %-12s %-9s %-10s%n",
            "CONFIG", "PF", "WR%", "DD%", "TRADES", "NET$", "RET%", "SWAP$");
        runOne(cost, "Baseline (OFF)", new GoldTurtleTrendStrategy("GoldTurtleTrend", GOLD), GOLD, xau);
        runOne(cost, "ALIGNED RAI SMA500", new GoldTurtleRiskIndexStrategy("GoldTurtleRai", GOLD,
            55, 20, GoldTurtleRiskIndexStrategy.MODE_ALIGNED, 500, rai), GOLD, xau);
        runOne(cost, "OPPOSITE RAI SMA500", new GoldTurtleRiskIndexStrategy("GoldTurtleRaiOpp", GOLD,
            55, 20, GoldTurtleRiskIndexStrategy.MODE_OPPOSITE, 500, rai), GOLD, xau);

        // Contrôle secondaire : le filtre RAI appliqué à EUR_USD (même mécanique Turtle)
        var eur = HistoricalDataLoader.loadFromArgs("EUR_USD", "EUR_USD", YEAR_SPEC).bars();
        System.out.println("\n--- Contrôle EUR_USD (même mécanique Turtle, même filtre RAI) ---");
        runOne(cost, "EUR baseline", new GoldTurtleTrendStrategy("GoldTurtleTrend", "EUR_USD"), "EUR_USD", eur);
        runOne(cost, "EUR ALIGNED RAI 500", new GoldTurtleRiskIndexStrategy("GoldTurtleRai", "EUR_USD",
            55, 20, GoldTurtleRiskIndexStrategy.MODE_ALIGNED, 500, rai), "EUR_USD", eur);
        runOne(cost, "EUR OPPOSITE RAI 500", new GoldTurtleRiskIndexStrategy("GoldTurtleRaiOpp", "EUR_USD",
            55, 20, GoldTurtleRiskIndexStrategy.MODE_OPPOSITE, 500, rai), "EUR_USD", eur);

        System.out.println("\nDONE");
    }

    /** Sweep période SMA × mode sur XAU_USD. */
    private static void runSweep(BacktestExecutionCost cost, Map<Long, Double> rai) throws Exception {
        var xau = HistoricalDataLoader.loadFromArgs(GOLD, GOLD, YEAR_SPEC).bars();
        int[] periods = {250, 400, 500, 600, 750, 1000, 2000};   // ~10j / 17j / 21j / 25j / 31j / 42j / 83j
        System.out.println("=== SWEEP RAI FILTER XAU_USD (SMA période × mode) ===");
        System.out.printf("%-9s %-9s %-6s %-6s %-6s %-7s %-12s%n",
            "PERIOD", "MODE", "PF", "WR%", "DD%", "TRADES", "NET$");
        for (int p : periods) {
            for (int mode : new int[]{GoldTurtleRiskIndexStrategy.MODE_ALIGNED,
                                      GoldTurtleRiskIndexStrategy.MODE_OPPOSITE}) {
                String m = mode == GoldTurtleRiskIndexStrategy.MODE_ALIGNED ? "ALIGNED" : "OPPOSITE";
                var strat = new GoldTurtleRiskIndexStrategy("GoldTurtleRai", GOLD, 55, 20, mode, p, rai);
                BacktestResult r = RunContext.forStrategy(null, "GoldTurtleRai", strat, GOLD,
                    RunMode.BACKTEST, xau, CAPITAL, null, cost).run();
                System.out.printf("%-9d %-9s %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
                    p, m, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                    r.totalTrades(), r.totalPnl());
            }
        }
        System.out.println("\nDONE");
    }

    /** Walk-forward IS 2006-2015 / OOS 2016-2025 pour baseline + ALIGNED + OPPOSITE. */
    private static void runWalkForward(BacktestExecutionCost cost, Map<Long, Double> rai) throws Exception {
        System.out.println("=== WALK-FORWARD XAU_USD (SMA 500 H1) ===");
        System.out.printf("%-26s %-12s %-6s %-6s %-6s %-7s %-12s%n",
            "CONFIG", "PHASE", "PF", "WR%", "DD%", "TRADES", "NET$");
        String[] specs = {"2006-2015", "2016-2025"};
        for (String spec : specs) {
            var bars = HistoricalDataLoader.loadFromArgs(GOLD, GOLD, spec).bars();
            var base = new GoldTurtleTrendStrategy("GoldTurtleTrend", GOLD);
            BacktestResult rb = RunContext.forStrategy(null, "GoldTurtleTrend", base, GOLD,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-26s %-12s %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
                "Baseline", spec, rb.profitFactor(), rb.winRatePct(), rb.maxDrawdownPct(),
                rb.totalTrades(), rb.totalPnl());
            var align = new GoldTurtleRiskIndexStrategy("GoldTurtleRai", GOLD,
                55, 20, GoldTurtleRiskIndexStrategy.MODE_ALIGNED, 500, rai);
            BacktestResult ra = RunContext.forStrategy(null, "GoldTurtleRai", align, GOLD,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-26s %-12s %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
                "ALIGNED RAI 500", spec, ra.profitFactor(), ra.winRatePct(), ra.maxDrawdownPct(),
                ra.totalTrades(), ra.totalPnl());
            var opp = new GoldTurtleRiskIndexStrategy("GoldTurtleRaiOpp", GOLD,
                55, 20, GoldTurtleRiskIndexStrategy.MODE_OPPOSITE, 500, rai);
            BacktestResult ro = RunContext.forStrategy(null, "GoldTurtleRaiOpp", opp, GOLD,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-26s %-12s %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
                "OPPOSITE RAI 500", spec, ro.profitFactor(), ro.winRatePct(), ro.maxDrawdownPct(),
                ro.totalTrades(), ro.totalPnl());
        }
        System.out.println("\nDONE");
    }

    /** Régime de marché : bull (2006-2012), bear (2013-2015), bull2 (2016-2025). */
    private static void runRegime(BacktestExecutionCost cost, Map<Long, Double> rai) throws Exception {
        String[] regimes = {"bull 2006-12", "bear 2013-15", "bull2 2016-25"};
        String[] specs = {"2006-2012", "2013-2015", "2016-2025"};
        System.out.println("=== RÉGIME XAU_USD (SMA 500 H1) ===");
        System.out.printf("%-14s %-26s %-6s %-6s %-6s %-7s %-12s%n",
            "REGIME", "CONFIG", "PF", "WR%", "DD%", "TRADES", "NET$");
        for (int i = 0; i < regimes.length; i++) {
            var bars = HistoricalDataLoader.loadFromArgs(GOLD, GOLD, specs[i]).bars();
            var base = new GoldTurtleTrendStrategy("GoldTurtleTrend", GOLD);
            BacktestResult rb = RunContext.forStrategy(null, "GoldTurtleTrend", base, GOLD,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-14s %-26s %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
                regimes[i], "Baseline", rb.profitFactor(), rb.winRatePct(), rb.maxDrawdownPct(),
                rb.totalTrades(), rb.totalPnl());
            var align = new GoldTurtleRiskIndexStrategy("GoldTurtleRai", GOLD,
                55, 20, GoldTurtleRiskIndexStrategy.MODE_ALIGNED, 500, rai);
            BacktestResult ra = RunContext.forStrategy(null, "GoldTurtleRai", align, GOLD,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-14s %-26s %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
                regimes[i], "ALIGNED RAI 500", ra.profitFactor(), ra.winRatePct(), ra.maxDrawdownPct(),
                ra.totalTrades(), ra.totalPnl());
            var opp = new GoldTurtleRiskIndexStrategy("GoldTurtleRaiOpp", GOLD,
                55, 20, GoldTurtleRiskIndexStrategy.MODE_OPPOSITE, 500, rai);
            BacktestResult ro = RunContext.forStrategy(null, "GoldTurtleRaiOpp", opp, GOLD,
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
            System.out.printf("%-14s %-26s %-6.2f %-6.1f %-6.2f %-7d %12.2f%n",
                regimes[i], "OPPOSITE RAI 500", ro.profitFactor(), ro.winRatePct(), ro.maxDrawdownPct(),
                ro.totalTrades(), ro.totalPnl());
        }
        System.out.println("\nDONE");
    }

    /** Sanity check : niveau du RAI (AUD/JPY synth) à des dates connues vs AUD/JPY réel. */
    private static void runCheck(Map<Long, Double> rai) throws Exception {
        // dates connues (AUD/JPY réel) : 2007-07 (~106 haut boom), 2008-10 (~57 bas GFC),
        // 2011-08 (~70s creux), 2013-01 (~96 haut), 2020-03 (~59 bas covid)
        String[] labels = {"2007-07-24 (AUDJPY ~106 haut boom)",
                           "2008-10-24 (AUDJPY ~57 bas GFC)",
                           "2011-08-09 (AUDJPY ~70s creux)",
                           "2013-01-18 (AUDJPY ~96 haut)",
                           "2020-03-19 (AUDJPY ~59 bas covid)"};
        LocalDate[] dates = {LocalDate.of(2007, 7, 24), LocalDate.of(2008, 10, 24),
                             LocalDate.of(2011, 8, 9), LocalDate.of(2013, 1, 18),
                             LocalDate.of(2020, 3, 19)};
        System.out.println("=== SANITY CHECK RAI (AUD/JPY SYNTHÉTIQUE) ===");
        for (int i = 0; i < labels.length; i++) {
            long ts = dates[i].atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli();
            Map.Entry<Long, Double> e = ((TreeMap<Long, Double>) rai).floorEntry(ts);
            System.out.printf("%-32s -> RAI %.2f%n", labels[i], e.getValue());
        }
        double min = Double.MAX_VALUE, max = 0, sum = 0;
        for (double v : rai.values()) { min = Math.min(min, v); max = Math.max(max, v); sum += v; }
        System.out.printf("RAI: %d points, min %.2f, max %.2f, avg %.2f%n",
            rai.size(), min, max, sum / rai.size());
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

    /**
     * Build synthetic AUD/JPY risk index = close(AUD_USD) × close(USD_JPY)
     * per aligned H1 timestamp (timeline maître : EUR_USD, la + complète).
     * Les points où une composante manque sont sautés.
     */
    static Map<Long, Double> buildRai() throws Exception {
        List<Bar> master = HistoricalDataLoader.loadFromArgs(MASTER, MASTER, YEAR_SPEC).bars();
        Map<String, List<Bar>> loaded = new HashMap<>();
        for (String p : RAI_COMPONENTS) loaded.put(p, HistoricalDataLoader.loadFromArgs(p, p, YEAR_SPEC).bars());
        Map<Long, Bar> aud = new HashMap<>();
        Map<Long, Bar> jpy = new HashMap<>();
        for (Bar b : loaded.get("AUD_USD")) aud.put(b.timestamp().toEpochMilli(), b);
        for (Bar b : loaded.get("USD_JPY")) jpy.put(b.timestamp().toEpochMilli(), b);

        Map<Long, Double> rai = new TreeMap<>();
        for (Bar b : master) {
            long ts = b.timestamp().toEpochMilli();
            Bar a = aud.get(ts);
            Bar j = jpy.get(ts);
            if (a == null || j == null) continue;
            rai.put(ts, a.close() * j.close());
        }
        return rai;
    }
}
