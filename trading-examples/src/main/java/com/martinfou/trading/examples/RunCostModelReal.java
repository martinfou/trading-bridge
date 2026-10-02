package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RealCostModel;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.backtest.SwapCalculator;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.CompositeMomentumRankingStrategy;
import com.martinfou.trading.strategies.creative.ConsecutiveBarExhaustionStrategy;
import com.martinfou.trading.strategies.creative.MonthWeekPhaseStrategy;
import com.martinfou.trading.strategies.creative.VWPReversionStrategy;
import com.martinfou.trading.strategies.longterm.LtRSI3Momentum;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * RunCostModelReal — prove the corrected cost model against the broker and re-baseline
 * the five DEPLOYED strategies on the instrument their containers actually use.
 *
 * Cost model under test:
 *   BEFORE = BacktestExecutionCost.DEFAULT (commission $0.07 + slippageFixed 0.00005) + LEGACY swap table
 *   AFTER  = RealCostModel.costFor(symbol) (0 commission + measured half-spread per leg) + corrected swap
 *
 * Windows follow docs/lt-strategy-playbook.md §4.2: FULL 2010-2025, IS 2010-2018,
 * OOS1 2019-2022, OOS2 2023-2025. Gate §4.3: PF ≥ 1.05, Sharpe ≥ 0.3, DD ≤ 35%;
 * OOS1/OOS2 PF < 1.0 ⇒ invalid.
 *
 * Also prints a SWAP-SENSITIVITY sweep (×0/×1/×2) to BOUND the documented error of applying
 * the 2026 financing snapshot to 2010-2025 history (no historical rate series exist).
 *
 * Usage:
 *   java -cp "$CP" com.martinfou.trading.examples.RunCostModelReal
 */
public class RunCostModelReal {

    static final double CAPITAL = 10_000;
    static final double USD_CAD = 1.4223;

    /** Swap values as they were coded before the fix (FEE-AUDIT.md §4), for the BEFORE replay. */
    static final Map<String, double[]> LEGACY_SWAP = Map.of(
        "GBP_JPY", new double[]{-4.5, 0.8},
        "USD_CHF", new double[]{2.0, -4.5},
        "USD_JPY", new double[]{5.2, -8.5},
        "EUR_USD", new double[]{-3.5, 1.2}
    );

    record Strat(String key, String symbol, BiFunction<String, String, Strategy> factory) {}

    static final List<Strat> STRATS = List.of(
        new Strat("consecbar",     "GBP_JPY", ConsecutiveBarExhaustionStrategy::new),
        new Strat("vwpreversion",  "USD_CHF", VWPReversionStrategy::new),
        new Strat("monthweekphase","USD_JPY", MonthWeekPhaseStrategy::new),
        new Strat("compmomentum",  "USD_JPY", CompositeMomentumRankingStrategy::new),
        new Strat("ltrsi3",        "EUR_USD", LtRSI3Momentum::new)
    );

    record Window(String name, int startYear, int endYear) {}
    static final List<Window> WINDOWS = List.of(
        new Window("FULL", 2010, 2025),
        new Window("IS",   2010, 2018),
        new Window("OOS1", 2019, 2022),
        new Window("OOS2", 2023, 2025)
    );

    enum SwapHypothesis { LEGACY, CORRECTED, ZERO }

    static final double[] SWAP_FACTORS = {0.0, 1.0, 2.0};

    public static void main(String[] args) throws Exception {
        printModelAndProof();

        System.out.println("\n\n############################################################");
        System.out.println("# RE-BASELINE — 5 stratégies déployées, avant/après coûts réels");
        System.out.println("# avant = DEFAULT (comm $0.07 + slip 0.00005) + swap legacy");
        System.out.println("# après = RealCostModel (comm 0 + demi-spread mesuré) + swap corrigé");
        System.out.println("############################################################");

        for (Strat s : STRATS) {
            List<Bar> full = load(s.symbol(), "2010-2025");
            System.out.println("\n================ " + s.key() + " (" + s.symbol() + ") — bars 2010-2025: " + full.size() + " ================");
            System.out.printf("%-6s | %-6s %-7s %-7s %-7s %-11s %-11s || %-6s %-7s %-7s %-7s %-11s %-11s%n",
                "WINDOW", "PF_B", "SH_B", "DD_B%", "TR_B", "NET_B$", "SWAP_B$",
                "PF_A", "SH_A", "DD_A%", "TR_A", "NET_A$", "SWAP_A$");

            for (Window w : WINDOWS) {
                List<Bar> bars = slice(full, w.startYear(), w.endYear());
                BacktestResult before = run(s, bars, BEFORE(), SwapHypothesis.LEGACY);
                BacktestResult after  = run(s, bars, AFTER(s.symbol()), SwapHypothesis.CORRECTED);
                System.out.printf("%-6s | %-6.2f %-7.2f %-7.2f %-7d %-11.2f %-11.2f || %-6.2f %-7.2f %-7.2f %-7d %-11.2f %-11.2f%n",
                    w.name(),
                    before.profitFactor(), before.sharpeRatio(), before.maxDrawdownPct(), before.totalTrades(),
                    before.totalPnl(), before.totalSwap(),
                    after.profitFactor(), after.sharpeRatio(), after.maxDrawdownPct(), after.totalTrades(),
                    after.totalPnl(), after.totalSwap());
            }

            // --- Attribution sur la fenêtre FULL ---
            BacktestResult zero       = run(s, full, BacktestExecutionCost.ZERO, SwapHypothesis.ZERO);          // prix pur
            BacktestResult swapOnly   = run(s, full, BacktestExecutionCost.ZERO, SwapHypothesis.CORRECTED);     // swap seul
            BacktestResult spreadOnly = run(s, full, AFTER(s.symbol()), SwapHypothesis.ZERO);                   // spread seul
            BacktestResult after      = run(s, full, AFTER(s.symbol()), SwapHypothesis.CORRECTED);              // les deux
            BacktestResult before     = run(s, full, BEFORE(), SwapHypothesis.LEGACY);

            double pricePnl = zero.totalPnl();
            double spreadCost = pricePnl - spreadOnly.totalPnl();
            double swapCost = pricePnl - swapOnly.totalPnl();
            double totalCost = spreadCost + swapCost;
            System.out.printf("  ATTRIBUTION (FULL): prix pur=%.2f | coût spread=%.2f | coût swap=%.2f | total=%.2f | net après=%.2f | net avant=%.2f%n",
                pricePnl, spreadCost, swapCost, totalCost, after.totalPnl(), before.totalPnl());
            double pctSpread = totalCost != 0 ? spreadCost / totalCost * 100 : 0;
            double pctSwap = totalCost != 0 ? swapCost / totalCost * 100 : 0;
            System.out.printf("  → part du coût: spread=%.0f%%  swap=%.0f%%  (coût total = %.0f%% du prix pur)%n",
                pctSpread, pctSwap, pricePnl != 0 ? totalCost / Math.abs(pricePnl) * 100 : 0);
        }

        printSwapSensitivity();
        System.out.println("\nDONE");
    }

    // ------------------------------------------------------------------ cost variants

    static BacktestExecutionCost BEFORE() {
        return BacktestExecutionCost.DEFAULT; // commission 0.07 + slippageFixed 0.00005
    }

    static BacktestExecutionCost AFTER(String symbol) {
        Double mid = RealCostModel.REFERENCE_MIDS.get(symbol);
        return RealCostModel.costFor(symbol, mid == null ? 1.0 : mid);
    }

    /** Swap-conversion mid: the period-average for gold, the 2026 reference mid for FX. */
    static double swapMid(String symbol) {
        if ("XAU_USD".equals(symbol)) return RealCostModel.GOLD_SWAP_MID;
        Double mid = RealCostModel.REFERENCE_MIDS.get(symbol);
        return mid == null ? 1.0 : mid;
    }

    /** Runs a strategy under an explicit cost profile + swap hypothesis. */
    static BacktestResult run(Strat s, List<Bar> bars, BacktestExecutionCost cost, SwapHypothesis swap) throws Exception {
        try {
            switch (swap) {
                case LEGACY -> {
                    double[] l = LEGACY_SWAP.get(s.symbol());
                    if (l != null) SwapCalculator.setRateOverride(s.symbol(), l[0], l[1]);
                }
                case ZERO -> SwapCalculator.setRateOverride(s.symbol(), 0.0, 0.0);
                case CORRECTED -> SwapCalculator.clearRateOverride();
            }
            Strategy strategy = s.factory().apply(s.key(), s.symbol());
            return RunContext.forStrategy(null, s.key(), strategy, s.symbol(),
                RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
        } finally {
            SwapCalculator.clearRateOverride();
        }
    }

    /** Runs a strategy with the corrected swap scaled by {@code factor} (0 = no swap, 2 = double). */
    static BacktestResult runSwapScaled(Strat s, List<Bar> bars, double factor) throws Exception {
        try {
            if (factor == 1.0) {
                SwapCalculator.clearRateOverride();
            } else {
                double l = SwapCalculator.getLongSwap(s.symbol());
                double sh = SwapCalculator.getShortSwap(s.symbol());
                SwapCalculator.setRateOverride(s.symbol(), l * factor, sh * factor);
            }
            Strategy strategy = s.factory().apply(s.key(), s.symbol());
            return RunContext.forStrategy(null, s.key(), strategy, s.symbol(),
                RunMode.BACKTEST, bars, CAPITAL, null, AFTER(s.symbol())).run();
        } finally {
            SwapCalculator.clearRateOverride();
        }
    }

    // ------------------------------------------------------------------ swap sensitivity

    /** Bounds the "2026 snapshot applied to history" error: does the gate verdict move under ×0/×1/×2? */
    static void printSwapSensitivity() throws Exception {
        System.out.println("\n\n############################################################");
        System.out.println("# SENSIBILITÉ AU SWAP — bornage de l'instantané 2026 appliqué à l'historique");
        System.out.println("# taux corrigés × {0, 1, 2} sur FULL/IS/OOS1/OOS2 (PF | net $)");
        System.out.println("# Porte: FULL PF ≥ 1.05, OOS PF ≥ 1.0 (sinon invalide).");
        System.out.println("############################################################");
        for (Strat s : STRATS) {
            List<Bar> full = load(s.symbol(), "2010-2025");
            System.out.println("\n---- " + s.key() + " (" + s.symbol() + ") ----");
            System.out.printf("%-7s | %-20s | %-20s | %-20s | %-20s%n",
                "FACTEUR", "FULL", "IS", "OOS1", "OOS2");
            for (double f : SWAP_FACTORS) {
                StringBuilder row = new StringBuilder(String.format("×%-6.1f ", f));
                for (Window w : WINDOWS) {
                    List<Bar> bars = slice(full, w.startYear(), w.endYear());
                    BacktestResult r = runSwapScaled(s, bars, f);
                    row.append(String.format("| PF %-6.2f net %-9.2f ", r.profitFactor(), r.totalPnl()));
                }
                System.out.println(row);
            }
        }
    }

    // ------------------------------------------------------------------ data

    static List<Bar> load(String symbol, String years) throws Exception {
        var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, years);
        List<Bar> bars = loaded.bars();
        if (bars.isEmpty()) throw new IllegalStateException("PAS DE DONNÉES pour " + symbol + " " + years);
        return bars;
    }

    static List<Bar> slice(List<Bar> bars, int startYear, int endYear) {
        List<Bar> out = new ArrayList<>();
        for (Bar b : bars) {
            int y = b.timestamp().atZone(java.time.ZoneOffset.UTC).getYear();
            if (y >= startYear && y <= endYear) out.add(b);
        }
        return out;
    }

    // ------------------------------------------------------------------ proof

    static void printModelAndProof() {
        System.out.println("================================================================");
        System.out.println("MODÈLE DE COÛTS AUTORITAIRE — RealCostModel (courtier OANDA practice, 2026-10-01)");
        System.out.println("================================================================");
        System.out.println("Commission: 0 (compte spread-only, aucune commission par trade)");
        System.out.println("Repli spread: REFUS pour une paire non mesurée (aucun repli sûr).");
        System.out.println("SWAP: instantané 2026 appliqué à l'historique (borné par le balayage ×0/×1/×2).\n");

        System.out.println("1) SPREAD — médiane bid/ask sur 500 bougies H1 price=BA (FEE-AUDIT.md §2).");
        System.out.println("   demi-spread par jambe (aller-retour = 1 spread complet):");
        for (Map.Entry<String, Double> e : RealCostModel.HALF_SPREAD_PRICE.entrySet()) {
            System.out.printf("     %-8s  demi-spread %s (unités de prix) par jambe%n",
                e.getKey(), String.format("%.6g", e.getValue()));
        }

        System.out.println("\n2) SWAP — OANDA /v3/instruments financing (fraction annuelle) → pips/jour.");
        System.out.println("   Formule: swap[pips/jour] = taux_annuel × mid / (pipSize × 365)");
        System.out.println("   pipSize: JPY et métaux (XAU/XAG) = 0.01 ; autres FX = 0.0001");
        System.out.println("   XAU_USD utilise le mid MOYEN 2010-2025 (" + RealCostModel.GOLD_SWAP_MID + "), pas le spot 4182.");
        for (Map.Entry<String, double[]> e : RealCostModel.FINANCING_ANNUAL.entrySet()) {
            String sym = e.getKey();
            double p = swapMid(sym);
            double l = RealCostModel.swapPipsPerDay(e.getValue()[0], p, sym);
            double s = RealCostModel.swapPipsPerDay(e.getValue()[1], p, sym);
            System.out.printf("     %-8s  long %+.4f/an → %+.2f pip/j   short %+.4f/an → %+.2f pip/j%n",
                sym, e.getValue()[0], l, e.getValue()[1], s);
        }

        System.out.println("\n3) CONVERSION PAS À PAS (devise du compte = CAD, USD_CAD = " + USD_CAD + "):");
        // a) non-JPY: EUR_USD
        double eurR = -0.0248, eurP = 1.12456;
        double eurNotional = 100000 * eurP;
        double eurUsdDay = eurR * eurNotional / 365;
        System.out.println("   a) EUR_USD (non-JPY, quote USD)");
        System.out.printf("      notional = 100 000 × 1.12456 = %.0f USD%n", eurNotional);
        System.out.printf("      swap/jour = -0.0248 × %.0f / 365 = %.2f USD/jour → ×%.4f = %.2f CAD/jour%n",
            eurNotional, eurUsdDay, USD_CAD, eurUsdDay * USD_CAD);
        System.out.println("      (en pips: pipValue = 0.0001×100000 = 10 USD → -7.64/10 = -0.76 pip/jour)");

        // b) JPY: GBP_JPY
        double gjR = 0.0156, gjP = 208.433, usdJpy = 157.925;
        double gjNotional = 100000 * gjP;
        double gjJpyDay = gjR * gjNotional / 365;
        System.out.println("   b) GBP_JPY (quote JPY)");
        System.out.printf("      notional = 100 000 × 208.433 = %.0f JPY%n", gjNotional);
        System.out.printf("      swap/jour = 0.0156 × %.0f / 365 = %.2f JPY/jour → ÷%.3f = %.2f USD → ×%.4f = %.2f CAD/jour%n",
            gjNotional, gjJpyDay, usdJpy, gjJpyDay / usdJpy, USD_CAD, gjJpyDay / usdJpy * USD_CAD);
        System.out.println("      (en pips: pipValue = 0.01×100000/157.925 = 6.33 USD → +5.64/6.33 = +0.89 pip/jour)");

        // c) or: XAU_USD — mid MOYEN de période pour le portage (pas le spot)
        double auR = -0.0569, auP = RealCostModel.GOLD_SWAP_MID;
        double auNotional = 100 * auP;
        double auUsdDay = auR * auNotional / 365;
        System.out.println("   c) XAU_USD (métal, quote USD, lot = 100 oz, mid MOYEN 2010-2025)");
        System.out.printf("      notional = 100 × %.0f = %.0f USD (moyenne de période; au spot 4182 ce serait 2.5× plus)%n",
            auP, auNotional);
        System.out.printf("      swap/jour = -0.0569 × %.0f / 365 = %.2f USD/jour → ×%.4f = %.2f CAD/jour%n",
            auNotional, auUsdDay, USD_CAD, auUsdDay * USD_CAD);
        System.out.println("      (en pips: pipValue = 0.01×100 = 1 USD → -25.9/1 = -25.9 pip/jour ; l'ancienne table disait -2.0)");
    }
}
