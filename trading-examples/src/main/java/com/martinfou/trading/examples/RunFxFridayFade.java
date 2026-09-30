package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.GoldWeekdayEffectStrategy;

import java.util.ArrayList;
import java.util.List;

/**
 * RunFxFridayFade — Backtest AVEC coûts ($0.07 + 0.01%) de la structure
 * calendaire découverte le 16 sept 2026 (mercredi, 37e résultat).
 *
 * 📊 PRÉ-VALIDATION (FxCalendarDimCheck, Pattern D) : le rendement close→close
 * du VENDREDI est négatif sur 7/8 paires FX majeures, avec médiane négative ET
 * négatif dans les deux sous-périodes IS/OOS :
 *   GBP_JPY -0.056% (t -2.4) · GBP_USD -0.054% (t -2.9) · AUD_USD -0.043%
 *   NZD_USD -0.035% · USD_CHF -0.034% · EUR_USD -0.032% · USD_JPY -0.017%
 *   USD_CAD +0.005% (seul positif = contrôle interne)
 * L'ordre des magnitudes suit EXACTEMENT l'ordre de risque des paires → ce n'est
 * pas un edge d'INSTRUMENT mais un FACTEUR COMMUN : prime de risque de week-end
 * (les devises refuge JPY/CHF se renforcent, les devises risk s'affaiblissent).
 * Miroir cohérent de GoldWeekdayEffect (or POSITIF le vendredi, 31 août).
 *
 * Véhicule : GoldWeekdayEffectStrategy patchée rétro-compatible (direction +
 * quantité) — aucune nouvelle classe de stratégie.
 *
 * Usage:
 *   RunFxFridayFade                 (défaut : scan SELL-FRI sur 8 paires + miroir LONG)
 *   RunFxFridayFade --validate      (contrôle de non-régression or : doit donner 1.27/+$15 131)
 *   RunFxFridayFade --beta          (contrôle BÊTA : FRI vs ALLDAYS vs Mon-Jeu)
 *   RunFxFridayFade --wf            (walk-forward IS 2006-2015 / OOS 2016-2026)
 *   RunFxFridayFade --regime        (bull 06-12 / 13-15 / bull2 16-26)
 *   RunFxFridayFade --sweep         (balayage jour par jour sur 3 paires)
 */
public class RunFxFridayFade {

    static final String YEAR_SPEC = "2006-2026";
    static final double CAPITAL = 50_000;
    static final double QTY = 10_000;      // unités FX (convention DateWindowSeasonal)
    static final double GOLD_QTY = 10;     // oz (famille or)

    /** Paires triées par magnitude de l'effet vendredi (pré-validation). */
    static final String[] FX_ALL = {
        "GBP_JPY", "GBP_USD", "AUD_USD", "NZD_USD", "USD_CHF", "EUR_USD", "USD_JPY", "USD_CAD"
    };
    /** Sous-panier « risk » (7 paires à effet négatif) — USD_CAD exclu (contrôle). */
    static final String[] BASKET = {
        "GBP_JPY", "GBP_USD", "AUD_USD", "NZD_USD", "USD_CHF", "EUR_USD", "USD_JPY"
    };
    static final boolean[] FRI = {false, false, false, false, true};
    static final boolean[] ALLDAYS = {true, true, true, true, true};
    static final boolean[] MON_THU = {true, true, true, true, false};

    public static void main(String[] args) throws Exception {
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);
        String mode = args.length > 0 ? args[0] : "--scan";

        System.out.println("==================================================");
        System.out.println("FX FRIDAY FADE — prime de risque de week-end (SELL vendredi)");
        System.out.println("Mode: " + mode + " | coûts $0.07 + 0.01% | capital $" + CAPITAL);
        System.out.println("==================================================");

        switch (mode) {
            case "--validate" -> validateGoldRegression(cost);
            case "--beta" -> betaControl(cost);
            case "--wf" -> walkForward(cost);
            case "--regime" -> regime(cost);
            case "--sweep" -> daySweep(cost);
            default -> scan(cost);
        }
        System.out.println("\nDONE");
    }

    // ---------------------------------------------------------------- scan

    private static void scan(BacktestExecutionCost cost) throws Exception {
        System.out.println("\n--- SCAN : SELL vendredi (session complète UTC) vs miroir LONG vendredi ---");
        System.out.printf("%-9s | %-38s | %-38s%n", "", "SELL FRI (la config)", "LONG FRI (miroir = contrôle)");
        System.out.printf("%-9s | %6s %5s %6s %6s %11s | %6s %6s %11s%n",
            "PAIRE", "PF", "WR%", "DD%", "TRADES", "NET$", "PF", "WR%", "NET$");
        double sumShort = 0, sumLong = 0;
        int nShort = 0;
        for (String sym : FX_ALL) {
            BacktestResult s = run(sym, FRI, Order.Side.SELL, QTY, YEAR_SPEC, cost);
            BacktestResult l = run(sym, FRI, Order.Side.BUY, QTY, YEAR_SPEC, cost);
            if (s == null) { System.out.println(sym + " : PAS DE DONNÉES"); continue; }
            System.out.printf("%-9s | %6.2f %4.0f%% %5.2f%% %6d %11.2f | %6.2f %4.0f%% %11.2f%n",
                sym, s.profitFactor(), s.winRatePct(), s.maxDrawdownPct(), s.totalTrades(), s.totalPnl(),
                l.profitFactor(), l.winRatePct(), l.totalPnl());
            sumShort += s.totalPnl(); sumLong += l.totalPnl(); nShort++;
        }
        System.out.printf("%nSOMME 8 paires : SELL FRI net=$%.2f | LONG FRI net=$%.2f | écart=$%.2f%n",
            sumShort, sumLong, sumShort - sumLong);

        System.out.println("\n--- PANIER « risk » (7 paires à effet négatif) : PF agrégé + swap isolé ---");
        System.out.printf("%-9s %6s %6s %6s %6s %11s %10s %10s %10s %11s%n",
            "PAIRE", "PF", "WR%", "DD%", "TRADES", "NET$", "SWAP$", "SLIP$", "COMM$", "PRIX$*");
        System.out.println("               (* PRIX$ = net − swap = l'edge de prix pur, swap modèle constant 2024-26 isolé)");
        List<Double> allPnls = new ArrayList<>();
        double netB = 0, swapB = 0, commB = 0, slipB = 0;
        for (String sym : BASKET) {
            BacktestResult r = run(sym, FRI, Order.Side.SELL, QTY, YEAR_SPEC, cost);
            if (r == null) continue;
            allPnls.addAll(r.tradePnlList());
            netB += r.totalPnl(); swapB += r.totalSwap(); commB += r.totalCommission(); slipB += r.totalSlippage();
            System.out.printf("%-9s %6.2f %5.0f%% %5.2f%% %6d %11.2f %10.2f %10.2f %10.2f %11.2f%n",
                sym, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(), r.totalTrades(),
                r.totalPnl(), r.totalSwap(), r.totalSlippage(), r.totalCommission(),
                r.totalPnl() - r.totalSwap());
        }
        double gp = allPnls.stream().filter(d -> d > 0).mapToDouble(d -> d).sum();
        double gl = allPnls.stream().filter(d -> d <= 0).mapToDouble(d -> -d).sum();
        System.out.printf("PANIER   PF_agrégé=%.2f trades=%d net=%.2f swap=%.2f slip=%.2f comm=%.2f prix=%.2f%n",
            gl > 0 ? gp / gl : Double.NaN, allPnls.size(), netB, swapB, slipB, commB, netB - swapB);
        System.out.printf("  Soit %.2f%% du capital engagé (7 × $50K = $350K) | par trade: $%.2f (prix pur: $%.2f)%n",
            netB / 350_000 * 100, allPnls.isEmpty() ? 0 : netB / allPnls.size(),
            allPnls.isEmpty() ? 0 : (netB - swapB) / allPnls.size());
    }

    // ------------------------------------------------------------ validate

    /** Non-régression : la famille or doit être intacte après le patch. */
    private static void validateGoldRegression(BacktestExecutionCost cost) throws Exception {
        System.out.println("\n--- NON-RÉGRESSION OR : XAU_USD long vendredi, 10 oz, 2006-2025 ---");
        BacktestResult r = run("XAU_USD", FRI, Order.Side.BUY, GOLD_QTY, "2006-2025", cost);
        System.out.printf("  XAU_USD FRI long : PF=%.2f WR=%.1f%% DD=%.2f%% trades=%d net=$%.2f (référence 1.27/56.9/5.18/1041/+$15 131)%n",
            r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(), r.totalTrades(), r.totalPnl());
        BacktestResult w = run("XAU_USD", new boolean[]{false, false, true, false, false}, Order.Side.BUY,
            GOLD_QTY, "2006-2025", cost);
        System.out.printf("  XAU_USD WED long : PF=%.2f WR=%.1f%% trades=%d net=$%.2f (référence 1.11)%n",
            w.profitFactor(), w.winRatePct(), w.totalTrades(), w.totalPnl());
        BacktestResult a = run("XAU_USD", ALLDAYS, Order.Side.BUY, GOLD_QTY, "2006-2025", cost);
        System.out.printf("  XAU_USD ALLDAYS   : PF=%.2f trades=%d net=$%.2f (référence 1.08/+$22.0K)%n",
            a.profitFactor(), a.totalTrades(), a.totalPnl());
    }

    // ------------------------------------------------------------ controls

    private static void betaControl(BacktestExecutionCost cost) throws Exception {
        System.out.println("\n=== CONTRÔLE BÊTA (anti-artefact des effets JOUR) ===");
        System.out.println("Si SELL-FRI ≈ SELL-ALLDAYS, l'effet jour n'est qu'une bêta d'exposition short.");
        System.out.printf("%-9s %-16s %6s %6s %6s %7s %11s %9s%n",
            "PAIRE", "MODE", "PF", "WR%", "DD%", "TRADES", "NET$", "$/TRADE");
        for (String sym : new String[]{"GBP_JPY", "GBP_USD", "AUD_USD", "EUR_USD", "USD_CAD"}) {
            rec(sym, "SELL FRI", FRI, cost);
            rec(sym, "SELL Mon-Jeu", MON_THU, cost);
            rec(sym, "SELL ALLDAYS", ALLDAYS, cost);
            System.out.println();
        }
    }

    private static void rec(String sym, String label, boolean[] days, BacktestExecutionCost cost) throws Exception {
        BacktestResult r = run(sym, days, Order.Side.SELL, QTY, YEAR_SPEC, cost);
        System.out.printf("%-9s %-16s %6.2f %5.0f%% %5.2f%% %7d %11.2f %9.2f%n",
            sym, label, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(), r.totalTrades(),
            r.totalPnl(), r.totalTrades() == 0 ? 0 : r.totalPnl() / r.totalTrades());
    }

    private static void walkForward(BacktestExecutionCost cost) throws Exception {
        System.out.println("\n=== WALK-FORWARD — SELL vendredi (IS 2006-2015 / OOS 2016-2026) ===");
        System.out.printf("%-9s %-16s %6s %6s %6s %7s %11s%n",
            "PAIRE", "PÉRIODE", "PF", "WR%", "DD%", "TRADES", "NET$");
        for (String sym : FX_ALL) {
            for (String[] p : new String[][]{{"IS 2006-2015", "2006-2015"}, {"OOS 2016-2026", "2016-2026"}}) {
                BacktestResult r = run(sym, FRI, Order.Side.SELL, QTY, p[1], cost);
                System.out.printf("%-9s %-16s %6.2f %5.0f%% %5.2f%% %7d %11.2f%n",
                    sym, p[0], r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(), r.totalTrades(), r.totalPnl());
            }
        }
    }

    private static void regime(BacktestExecutionCost cost) throws Exception {
        System.out.println("\n=== RÉGIMES — SELL vendredi ===");
        String[][] regs = {{"bull 2006-2012", "2006-2012"}, {"taper 2013-2015", "2013-2015"},
                           {"bull2 2016-2026", "2016-2026"}};
        System.out.printf("%-9s %-16s %6s %6s %7s %11s%n", "PAIRE", "RÉGIME", "PF", "WR%", "TRADES", "NET$");
        for (String sym : new String[]{"GBP_JPY", "GBP_USD", "AUD_USD", "EUR_USD"}) {
            for (String[] rg : regs) {
                BacktestResult r = run(sym, FRI, Order.Side.SELL, QTY, rg[1], cost);
                System.out.printf("%-9s %-16s %6.2f %5.0f%% %7d %11.2f%n",
                    sym, rg[0], r.profitFactor(), r.winRatePct(), r.totalTrades(), r.totalPnl());
            }
        }
    }

    private static void daySweep(BacktestExecutionCost cost) throws Exception {
        System.out.println("\n=== BALAYAGE JOUR PAR JOUR (SELL, exposition 1 session/semaine) ===");
        String[] labels = {"MON", "TUE", "WED", "THU", "FRI"};
        System.out.printf("%-9s %-6s %6s %6s %6s %7s %11s %9s%n",
            "PAIRE", "JOUR", "PF", "WR%", "DD%", "TRADES", "NET$", "$/TRADE");
        for (String sym : new String[]{"GBP_JPY", "GBP_USD", "EUR_USD"}) {
            for (int d = 0; d < 5; d++) {
                boolean[] mask = new boolean[5];
                mask[d] = true;
                BacktestResult r = run(sym, mask, Order.Side.SELL, QTY, YEAR_SPEC, cost);
                System.out.printf("%-9s %-6s %6.2f %5.0f%% %5.2f%% %7d %11.2f %9.2f%n",
                    sym, labels[d], r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(), r.totalTrades(),
                    r.totalPnl(), r.totalTrades() == 0 ? 0 : r.totalPnl() / r.totalTrades());
            }
            System.out.println();
        }
    }

    // --------------------------------------------------------------- helper

    private static BacktestResult run(String symbol, boolean[] days, Order.Side dir, double qty,
                                      String yearSpec, BacktestExecutionCost cost) throws Exception {
        var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, yearSpec);
        List<Bar> bars = loaded.bars();
        if (bars.isEmpty()) return null;
        var strategy = new GoldWeekdayEffectStrategy("FxWeekdaySession", symbol, days, dir).withQuantity(qty);
        return RunContext.forStrategy(null, "FxWeekdaySession", strategy, symbol,
            RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
    }
}
