package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Trade;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.MondayReversionStrategy;
import com.martinfou.trading.strategies.creative.MondayReversionStrategy.Policy;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * RunFxMondayReversion — Backtest AVEC coûts ($0.07 + 0.01%) de la RÉVERSION DU LUNDI FX
 * (39e résultat, deep dive vendredi 18 sept 2026).
 *
 * Piste EXPLORE née du 38e résultat (17 sept, CrossAssetWeekendFactorCheck partie F) :
 * le lundi FX revient sur le signe du lundi-jeudi précédent, 8/8 paires, IS/OOS stable des
 * deux côtés, or exempt, indépendant du fade du vendredi.
 *
 * Usage:
 *   RunFxMondayReversion --probe      (validation du mécanisme : timestamps entrée/sortie réels)
 *   RunFxMondayReversion --scan       (8 paires : réversion 2 jambes + décomposition long/short)
 *   RunFxMondayReversion --beta       (SPÉCIFICITÉ DU JOUR : lundi vs mardi..vendredi)
 *   RunFxMondayReversion --momentum   (contrôles directionnels : momentum / drift long / drift short)
 *   RunFxMondayReversion --wf         (walk-forward IS 2006-2015 / OOS 2016-2026)
 *   RunFxMondayReversion --regime     (bull 06-12 / taper 13-15 / bull2 16-26)
 *   RunFxMondayReversion --magnitude  (balayage du seuil |W4| — réponse de magnitude)
 *   RunFxMondayReversion --lag        (contrôle de falsification : signal périmé de 1-2 semaines)
 *   RunFxMondayReversion --f1         (filtre F1 : rebond après une semaine risk-off — ven baissier)
 *   RunFxMondayReversion --basket     (agrégat : PF, swap isolé, prix pur)
 */
public class RunFxMondayReversion {

    static final String YEAR_SPEC = "2006-2026";
    static final double CAPITAL = 50_000;
    static final double QTY = 10_000;
    static final ZoneId UTC = ZoneId.of("UTC");
    static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(UTC);

    /** Paires ordonnées par magnitude de la réversion du lundi (pré-validation 17 sept). */
    static final String[] FX_ALL = {
        "USD_JPY", "AUD_USD", "EUR_USD", "GBP_USD", "GBP_JPY", "USD_CHF", "NZD_USD", "USD_CAD"
    };

    static final boolean[] MON = {true, false, false, false, false};
    static final boolean[] TUE = {false, true, false, false, false};
    static final boolean[] WED = {false, false, true, false, false};
    static final boolean[] THU = {false, false, false, true, false};
    static final boolean[] FRI = {false, false, false, false, true};
    static final boolean[] ALL = {true, true, true, true, true};

    public static void main(String[] args) throws Exception {
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);
        String mode = args.length > 0 ? args[0] : "--scan";

        System.out.println("==============================================================");
        System.out.println("FX MONDAY REVERSION — réversion du lundi (semaine précédente)");
        System.out.println("Mode: " + mode + " | coûts $0.07 + 0.01% | capital $" + CAPITAL + " | qty " + QTY);
        System.out.println("==============================================================");

        switch (mode) {
            case "--probe" -> probe(cost);
            case "--beta" -> beta(cost);
            case "--momentum" -> momentum(cost);
            case "--wf" -> walkForward(cost);
            case "--regime" -> regime(cost);
            case "--magnitude" -> magnitude(cost);
            case "--lag" -> lag(cost);
            case "--basket" -> basket(cost);
            case "--f1" -> f1Filter(cost);
            case "--f1wf" -> f1LongOnlyRobustness(cost);
            case "--csweep" -> candidateSweep(cost);
            default -> scan(cost);
        }
        System.out.println("\nDONE");
    }

    // ---------------------------------------------------------------- probe
    private static void probe(BacktestExecutionCost cost) throws Exception {
        String sym = "GBP_USD";
        System.out.println("\n--- PROBE : mécanisme sur " + sym + " (2006) ---");
        var loaded = HistoricalDataLoader.loadFromArgs(sym, sym, "2006");
        List<Bar> bars = loaded.bars();
        System.out.printf("  bars=%d | première=%s | dernière=%s%n", bars.size(),
            FMT.format(bars.get(0).timestamp()), FMT.format(bars.get(bars.size() - 1).timestamp()));

        var st = new MondayReversionStrategy("Probe", sym);
        BacktestResult r = RunContext.forStrategy(null, "Probe", st, sym, RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
        System.out.printf("  entrées=%d sorties=%d | skipMagnitude=%d skipHistory=%d | dernier signal=%.4f%%%n",
            st.entryCount(), st.exitCount(), st.skippedByMagnitude(), st.skippedNoHistory(),
            st.lastSignal() * 100);
        System.out.println("  8 premiers trades (heure UTC réelle d'entrée/sortie) :");
        List<Trade> trades = r.trades();
        for (int i = 0; i < Math.min(8, trades.size()); i++) {
            Trade t = trades.get(i);
            System.out.printf("   %-4s %s -> %s | %s @ %.5f -> %.5f | pnl $%.2f (%.2f j)%n",
                t.side(), FMT.format(t.entryTime()), FMT.format(t.exitTime()), t.symbol(),
                t.entryPrice(), t.exitPrice(), t.pnl(),
                java.time.Duration.between(t.entryTime(), t.exitTime()).toMinutes() / 1440.0);
        }
        System.out.printf("  total trades=%d net=$%.2f swap=$%.2f comm=$%.2f slip=$%.2f%n",
            r.totalTrades(), r.totalPnl(), r.totalSwap(), r.totalCommission(), r.totalSlippage());
    }

    // ----------------------------------------------------------------- scan
    private static void scan(BacktestExecutionCost cost) throws Exception {
        System.out.println("\n--- SCAN 8 PAIRES : REVERSION 2 jambes (la config) | LONG seule | SHORT seule ---");
        System.out.printf("%-9s | %-31s | %-22s | %-22s%n", "", "REVERSION (2 jambes)", "LONG si W4<0", "SHORT si W4>0");
        System.out.printf("%-9s | %6s %5s %6s %6s %11s | %6s %6s %11s | %6s %6s %11s%n",
            "PAIRE", "PF", "WR%", "DD%", "TRADES", "NET$", "PF", "TRADES", "NET$", "PF", "TRADES", "NET$");
        double sumAll = 0, sumL = 0, sumS = 0, sumSwap = 0, sumPrix = 0;
        List<Double> allPnl = new ArrayList<>();
        for (String sym : FX_ALL) {
            BacktestResult a = run(sym, Policy.REVERSION, MON, 0, 0.0, YEAR_SPEC, cost);
            if (a == null) { System.out.println(sym + " : PAS DE DONNÉES"); continue; }
            BacktestResult l = run(sym, Policy.REVERSION_LONG_ONLY, MON, 0, 0.0, YEAR_SPEC, cost);
            BacktestResult s = run(sym, Policy.REVERSION_SHORT_ONLY, MON, 0, 0.0, YEAR_SPEC, cost);
            System.out.printf("%-9s | %6.2f %4.0f%% %5.2f%% %6d %11.2f | %6.2f %6d %11.2f | %6.2f %6d %11.2f%n",
                sym, a.profitFactor(), a.winRatePct(), a.maxDrawdownPct(), a.totalTrades(), a.totalPnl(),
                l.profitFactor(), l.totalTrades(), l.totalPnl(),
                s.profitFactor(), s.totalTrades(), s.totalPnl());
            sumAll += a.totalPnl(); sumL += l.totalPnl(); sumS += s.totalPnl();
            sumSwap += a.totalSwap(); sumPrix += a.totalPnl() - a.totalSwap();
            allPnl.addAll(a.tradePnlList());
        }
        System.out.printf("%nSOMME 8 paires : net=$%.2f | LONG=$%.2f | SHORT=$%.2f | swap=$%.2f | prix pur=$%.2f%n",
            sumAll, sumL, sumS, sumSwap, sumPrix);
        double gp = allPnl.stream().filter(d -> d > 0).mapToDouble(d -> d).sum();
        double gl = allPnl.stream().filter(d -> d <= 0).mapToDouble(d -> -d).sum();
        System.out.printf("PF agrégé 8 paires = %.2f | trades=%d | $/trade=%.2f (prix pur $/trade=%.2f)%n",
            gl > 0 ? gp / gl : Double.NaN, allPnl.size(),
            allPnl.isEmpty() ? 0 : sumAll / allPnl.size(),
            allPnl.isEmpty() ? 0 : sumPrix / allPnl.size());
        System.out.printf("Net sur 8 x $50K = %.2f%%%n", sumAll / (8 * CAPITAL) * 100);
    }

    // ----------------------------------------------------------------- beta
    private static void beta(BacktestExecutionCost cost) throws Exception {
        System.out.println("\n=== CONTRÔLE BÊTA / SPÉCIFICITÉ DU JOUR ===");
        System.out.println("Même règle de réversion, appliquée sur chaque jour de la semaine.");
        System.out.println("Si lundi ≈ autres jours → ce n'est pas un effet du lundi mais une réversion générique.");
        System.out.printf("%-9s %-6s %6s %5s %6s %7s %11s %9s%n",
            "PAIRE", "JOUR", "PF", "WR%", "DD%", "TRADES", "NET$", "$/TRADE");
        String[] labels = {"MON", "TUE", "WED", "THU", "FRI", "ALL"};
        boolean[][] masks = {MON, TUE, WED, THU, FRI, ALL};
        for (String sym : new String[]{"USD_JPY", "EUR_USD", "GBP_USD", "AUD_USD", "USD_CAD"}) {
            for (int i = 0; i < masks.length; i++) {
                BacktestResult r = run(sym, Policy.REVERSION, masks[i], 0, 0.0, YEAR_SPEC, cost);
                System.out.printf("%-9s %-6s %6.2f %4.0f%% %5.2f%% %7d %11.2f %9.2f%n",
                    sym, labels[i], r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(), r.totalTrades(),
                    r.totalPnl(), r.totalTrades() == 0 ? 0 : r.totalPnl() / r.totalTrades());
            }
            System.out.println();
        }
    }

    // ------------------------------------------------------------- momentum
    private static void momentum(BacktestExecutionCost cost) throws Exception {
        System.out.println("\n=== CONTRÔLES DIRECTIONNELS (lundi) ===");
        System.out.println("REVERSION (la config) vs MOMENTUM (inverse) vs LONG permanent vs SHORT permanent.");
        System.out.printf("%-9s %-14s %6s %5s %6s %7s %11s %9s%n",
            "PAIRE", "POLITIQUE", "PF", "WR%", "DD%", "TRADES", "NET$", "$/TRADE");
        for (String sym : FX_ALL) {
            for (Policy p : new Policy[]{Policy.REVERSION, Policy.MOMENTUM, Policy.ALWAYS_LONG, Policy.ALWAYS_SHORT}) {
                BacktestResult r = run(sym, p, MON, 0, 0.0, YEAR_SPEC, cost);
                System.out.printf("%-9s %-14s %6.2f %4.0f%% %5.2f%% %7d %11.2f %9.2f%n",
                    sym, p, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(), r.totalTrades(),
                    r.totalPnl(), r.totalTrades() == 0 ? 0 : r.totalPnl() / r.totalTrades());
            }
            System.out.println();
        }
    }

    // ------------------------------------------------------------------- wf
    private static void walkForward(BacktestExecutionCost cost) throws Exception {
        System.out.println("\n=== WALK-FORWARD — REVERSION du lundi (IS 2006-2015 / OOS 2016-2026) ===");
        System.out.printf("%-9s %-16s %6s %5s %6s %7s %11s %9s%n",
            "PAIRE", "PÉRIODE", "PF", "WR%", "DD%", "TRADES", "NET$", "$/TRADE");
        for (String sym : FX_ALL) {
            for (String[] p : new String[][]{{"IS 2006-2015", "2006-2015"}, {"OOS 2016-2026", "2016-2026"}}) {
                BacktestResult r = run(sym, Policy.REVERSION, MON, 0, 0.0, p[1], cost);
                System.out.printf("%-9s %-16s %6.2f %4.0f%% %5.2f%% %7d %11.2f %9.2f%n",
                    sym, p[0], r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(), r.totalTrades(),
                    r.totalPnl(), r.totalTrades() == 0 ? 0 : r.totalPnl() / r.totalTrades());
            }
        }
    }

    // --------------------------------------------------------------- regime
    private static void regime(BacktestExecutionCost cost) throws Exception {
        System.out.println("\n=== RÉGIMES — REVERSION du lundi ===");
        String[][] regs = {{"bull 2006-2012", "2006-2012"}, {"taper 2013-2015", "2013-2015"},
                           {"bull2 2016-2026", "2016-2026"}};
        System.out.printf("%-9s %-16s %6s %5s %6s %7s %11s%n", "PAIRE", "RÉGIME", "PF", "WR%", "TRADES", "NET$", "");
        for (String sym : new String[]{"USD_JPY", "EUR_USD", "GBP_USD", "AUD_USD", "USD_CHF", "USD_CAD"}) {
            for (String[] rg : regs) {
                BacktestResult r = run(sym, Policy.REVERSION, MON, 0, 0.0, rg[1], cost);
                System.out.printf("%-9s %-16s %6.2f %4.0f%% %7d %11.2f%n",
                    sym, rg[0], r.profitFactor(), r.winRatePct(), r.totalTrades(), r.totalPnl());
            }
        }
    }

    // ------------------------------------------------------------ magnitude
    private static void magnitude(BacktestExecutionCost cost) throws Exception {
        double[] thresholds = {0.0, 0.0025, 0.005, 0.0075, 0.010, 0.015};
        for (Policy pol : new Policy[]{Policy.REVERSION, Policy.REVERSION_LONG_ONLY}) {
            System.out.println("\n=== BALAYAGE DE MAGNITUDE (seuil |W4|) — " + pol + " ===");
            System.out.println("Réponse de magnitude attendue MONOTONE (l'edge croît avec l'amplitude de la semaine)");
            StringBuilder head = new StringBuilder(String.format("%-9s", "PAIRE"));
            for (double t : thresholds) head.append(String.format(" %8s", t == 0 ? "0(all)" : String.format("%.2f%%", t * 100)));
            System.out.println(head + "   [PF / trades]");
            for (String sym : new String[]{"USD_JPY", "EUR_USD", "GBP_USD", "AUD_USD", "USD_CAD", "USD_CHF"}) {
                StringBuilder pf = new StringBuilder(String.format("%-9s", sym));
                StringBuilder tr = new StringBuilder(String.format("%-9s", ""));
                for (double t : thresholds) {
                    BacktestResult r = run(sym, pol, MON, 0, t, YEAR_SPEC, cost);
                    pf.append(String.format(" %8.2f", r.profitFactor()));
                    tr.append(String.format(" %8d", r.totalTrades()));
                }
                System.out.println(pf + " PF");
                System.out.println(tr + " trades");
            }
        }
    }

    // ------------------------------------------------------------------ lag
    private static void lag(BacktestExecutionCost cost) throws Exception {
        System.out.println("\n=== CONTRÔLE DE FALSIFICATION : signal périmé (semaines de décalage) ===");
        System.out.println("Si W4 de la semaine écoulée n'est pas spécial, les semaines périmées donnent autant.");
        System.out.printf("%-9s %-10s %6s %5s %6s %7s %11s %9s%n",
            "PAIRE", "DÉCALAGE", "PF", "WR%", "DD%", "TRADES", "NET$", "$/TRADE");
        for (String sym : new String[]{"USD_JPY", "EUR_USD", "GBP_USD", "AUD_USD"}) {
            for (int l = 0; l <= 2; l++) {
                BacktestResult r = run(sym, Policy.REVERSION, MON, l, 0.0, YEAR_SPEC, cost);
                System.out.printf("%-9s %-10s %6.2f %4.0f%% %5.2f%% %7d %11.2f %9.2f%n",
                    sym, l + " sem.", r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(), r.totalTrades(),
                    r.totalPnl(), r.totalTrades() == 0 ? 0 : r.totalPnl() / r.totalTrades());
            }
            System.out.println();
        }
    }

    // ------------------------------------------------------------------ f1
    /**
     * Filtre F1 (issu de la décomposition Python, 18 sept) : la cellule 2×2 la plus forte de la
     * pré-validation était « vendredi baissier ET semaine baissière » (+6.4 bp, n=2013) contre
     * +1.7 bp pour « vendredi haussier ET semaine baissière ». Test moteur du raffinage
     * conceptuel : le lundi ne « révise » pas la semaine, il REBONDIT après une semaine risk-off.
     */
    private static void f1Filter(BacktestExecutionCost cost) throws Exception {
        System.out.println("\n=== FILTRE F1 : rebond du lundi après une semaine RISK-OFF (ven baissier) ===");
        System.out.printf("%-9s | %-19s | %-19s | %-19s%n", "",
            "REVERSION nu (réf.)", "REV + F1<0 (2 jambes)", "REV + F1<0 (LONG seul)");
        System.out.printf("%-9s | %6s %5s %6s %11s | %6s %5s %6s %11s | %6s %5s %6s %11s%n",
            "PAIRE", "PF", "WR%", "TRADES", "NET$", "PF", "WR%", "TRADES", "NET$", "PF", "WR%", "TRADES", "NET$");
        double s0 = 0, s1 = 0, s2 = 0; int n1 = 0, n2 = 0;
        for (String sym : FX_ALL) {
            BacktestResult b = run(sym, Policy.REVERSION, MON, 0, 0.0, false, YEAR_SPEC, cost);
            BacktestResult f = run(sym, Policy.REVERSION, MON, 0, 0.0, true, YEAR_SPEC, cost);
            BacktestResult l = run(sym, Policy.REVERSION_LONG_ONLY, MON, 0, 0.0, true, YEAR_SPEC, cost);
            System.out.printf("%-9s | %6.2f %4.0f%% %6d %11.2f | %6.2f %4.0f%% %6d %11.2f | %6.2f %4.0f%% %6d %11.2f%n",
                sym, b.profitFactor(), b.winRatePct(), b.totalTrades(), b.totalPnl(),
                f.profitFactor(), f.winRatePct(), f.totalTrades(), f.totalPnl(),
                l.profitFactor(), l.winRatePct(), l.totalTrades(), l.totalPnl());
            s0 += b.totalPnl(); s1 += f.totalPnl(); s2 += l.totalPnl(); n1 += f.totalTrades(); n2 += l.totalTrades();
        }
        System.out.printf("%nSOMME : réf=$%.0f | F1<0 2 jambes=$%.0f (%d trades) | F1<0 long seul=$%.0f (%d trades)%n",
            s0, s1, n1, s2, n2);
        System.out.println("\nWF du filtre F1 (2 jambes) :");
        System.out.printf("%-9s %-16s %6s %6s %7s %11s%n", "PAIRE", "PÉRIODE", "PF", "WR%", "TRADES", "NET$");
        for (String sym : new String[]{"GBP_USD", "USD_CAD", "USD_CHF", "EUR_USD", "AUD_USD"}) {
            for (String[] p : new String[][]{{"IS 2006-2015", "2006-2015"}, {"OOS 2016-2026", "2016-2026"}}) {
                BacktestResult r = run(sym, Policy.REVERSION, MON, 0, 0.0, true, p[1], cost);
                System.out.printf("%-9s %-16s %6.2f %4.0f%% %7d %11.2f%n",
                    sym, p[0], r.profitFactor(), r.winRatePct(), r.totalTrades(), r.totalPnl());
            }
        }
    }

    /**
     * Robustesse de la MEILLEURE variante identifiée (F1&lt;0 + LONG seul) : walk-forward, régimes,
     * agrégat swap/prix. C'est LA configuration candidate — elle doit passer le split IS/OOS.
     */
    private static void f1LongOnlyRobustness(BacktestExecutionCost cost) throws Exception {
        System.out.println("\n=== ROBUSTESSE — F1<0 + LONG SEUL (config candidate) ===");
        System.out.println("\n-- A) Walk-forward IS 2006-2015 / OOS 2016-2026 --");
        System.out.printf("%-9s %-16s %6s %5s %6s %7s %11s %9s%n",
            "PAIRE", "PÉRIODE", "PF", "WR%", "DD%", "TRADES", "NET$", "$/TRADE");
        for (String sym : FX_ALL) {
            for (String[] p : new String[][]{{"IS 2006-2015", "2006-2015"}, {"OOS 2016-2026", "2016-2026"}}) {
                BacktestResult r = run(sym, Policy.REVERSION_LONG_ONLY, MON, 0, 0.0, true, p[1], cost);
                System.out.printf("%-9s %-16s %6.2f %4.0f%% %5.2f%% %7d %11.2f %9.2f%n",
                    sym, p[0], r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(), r.totalTrades(),
                    r.totalPnl(), r.totalTrades() == 0 ? 0 : r.totalPnl() / r.totalTrades());
            }
        }
        System.out.println("\n-- B) Régimes --");
        String[][] regs = {{"bull 2006-2012", "2006-2012"}, {"taper 2013-2015", "2013-2015"},
                           {"bull2 2016-2026", "2016-2026"}};
        System.out.printf("%-9s %-16s %6s %5s %7s %11s%n", "PAIRE", "RÉGIME", "PF", "WR%", "TRADES", "NET$");
        for (String sym : new String[]{"USD_JPY", "EUR_USD", "GBP_USD", "AUD_USD", "USD_CHF", "USD_CAD"}) {
            for (String[] rg : regs) {
                BacktestResult r = run(sym, Policy.REVERSION_LONG_ONLY, MON, 0, 0.0, true, rg[1], cost);
                System.out.printf("%-9s %-16s %6.2f %4.0f%% %7d %11.2f%n",
                    sym, rg[0], r.profitFactor(), r.winRatePct(), r.totalTrades(), r.totalPnl());
            }
        }
        System.out.println("\n-- C) Agrégat 8 paires : swap isolé --");
        System.out.printf("%-9s %6s %5s %6s %7s %11s %10s %11s%n",
            "PAIRE", "PF", "WR%", "DD%", "TRADES", "NET$", "SWAP$", "PRIX$*");
        List<Double> pnls = new ArrayList<>();
        double net = 0, swap = 0;
        for (String sym : FX_ALL) {
            BacktestResult r = run(sym, Policy.REVERSION_LONG_ONLY, MON, 0, 0.0, true, YEAR_SPEC, cost);
            pnls.addAll(r.tradePnlList());
            net += r.totalPnl(); swap += r.totalSwap();
            System.out.printf("%-9s %6.2f %4.0f%% %5.2f%% %7d %11.2f %10.2f %11.2f%n",
                sym, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(), r.totalTrades(),
                r.totalPnl(), r.totalSwap(), r.totalPnl() - r.totalSwap());
        }
        double gp = pnls.stream().filter(d -> d > 0).mapToDouble(d -> d).sum();
        double gl = pnls.stream().filter(d -> d <= 0).mapToDouble(d -> -d).sum();
        System.out.printf("AGRÉGAT  PF=%.2f trades=%d net=%.2f swap=%.2f prix=%.2f | $/trade net=%.2f prix=%.2f%n",
            gl > 0 ? gp / gl : Double.NaN, pnls.size(), net, swap, net - swap,
            pnls.isEmpty() ? 0 : net / pnls.size(), pnls.isEmpty() ? 0 : (net - swap) / pnls.size());
        System.out.println("Seuil de rentabilité : coût A/R ≈ 2.14 bp (commission 0.14$ + 2 x slippage 0.0001)");
    }

    /**
     * Robustesse PARAMÉTRIQUE de la config candidate (F1&lt;0 + LONG seul) : les seuls « paramètres »
     * sont les seuils de magnitude (|W4| et |F1|). Aucune valeur ne doit dominer seule : on cherche
     * un PLATEAU, pas un pic (règle d'or du pipeline).
     */
    private static void candidateSweep(BacktestExecutionCost cost) throws Exception {
        double[] w4th = {0.0, 0.0025, 0.005, 0.0075, 0.010};
        System.out.println("\n=== PLATEAU — F1<0 + LONG seul, balayage du seuil |W4| ===");
        StringBuilder head = new StringBuilder(String.format("%-9s", "PAIRE"));
        for (double t : w4th) head.append(String.format(" %8s", t == 0 ? "0(all)" : String.format("%.2f%%", t * 100)));
        System.out.println(head + "   [PF / trades]");
        for (String sym : new String[]{"USD_JPY", "EUR_USD", "GBP_USD", "AUD_USD", "USD_CHF", "USD_CAD", "GBP_JPY"}) {
            StringBuilder pf = new StringBuilder(String.format("%-9s", sym));
            StringBuilder tr = new StringBuilder(String.format("%-9s", ""));
            for (double t : w4th) {
                BacktestResult r = run(sym, Policy.REVERSION_LONG_ONLY, MON, 0, t, true, YEAR_SPEC, cost);
                pf.append(String.format(" %8.2f", r.profitFactor()));
                tr.append(String.format(" %8d", r.totalTrades()));
            }
            System.out.println(pf + " PF");
            System.out.println(tr + " trades");
        }
        System.out.println("\nRappel règle d'or : PF stable sur toute la bande = plateau approuvé ; un pic isolé = curve fitting.");
    }

    // --------------------------------------------------------------- basket
    private static void basket(BacktestExecutionCost cost) throws Exception {
        System.out.println("\n=== AGRÉGAT 8 PAIRES — swap isolé (piège de la taille de panier) ===");
        System.out.printf("%-9s %6s %5s %6s %7s %11s %10s %10s %10s %11s%n",
            "PAIRE", "PF", "WR%", "DD%", "TRADES", "NET$", "SWAP$", "SLIP$", "COMM$", "PRIX$*");
        List<Double> pnls = new ArrayList<>();
        double net = 0, swap = 0, slip = 0, comm = 0;
        for (String sym : FX_ALL) {
            BacktestResult r = run(sym, Policy.REVERSION, MON, 0, 0.0, YEAR_SPEC, cost);
            if (r == null) continue;
            pnls.addAll(r.tradePnlList());
            net += r.totalPnl(); swap += r.totalSwap(); slip += r.totalSlippage(); comm += r.totalCommission();
            System.out.printf("%-9s %6.2f %4.0f%% %5.2f%% %7d %11.2f %10.2f %10.2f %10.2f %11.2f%n",
                sym, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(), r.totalTrades(),
                r.totalPnl(), r.totalSwap(), r.totalSlippage(), r.totalCommission(), r.totalPnl() - r.totalSwap());
        }
        double gp = pnls.stream().filter(d -> d > 0).mapToDouble(d -> d).sum();
        double gl = pnls.stream().filter(d -> d <= 0).mapToDouble(d -> -d).sum();
        System.out.printf("AGRÉGAT  PF=%.2f trades=%d net=%.2f swap=%.2f slip=%.2f comm=%.2f prix=%.2f%n",
            gl > 0 ? gp / gl : Double.NaN, pnls.size(), net, swap, slip, comm, net - swap);
        System.out.printf("  $/trade net = %.2f | $/trade prix pur = %.2f | net sur 8x$50K = %.2f%%%n",
            pnls.isEmpty() ? 0 : net / pnls.size(), pnls.isEmpty() ? 0 : (net - swap) / pnls.size(),
            net / (8 * CAPITAL) * 100);
    }

    // --------------------------------------------------------------- helper
    private static BacktestResult run(String symbol, Policy policy, boolean[] days, int lagWeeks,
                                      double minAbsSignal, String yearSpec, BacktestExecutionCost cost) throws Exception {
        return run(symbol, policy, days, lagWeeks, minAbsSignal, false, yearSpec, cost);
    }

    private static BacktestResult run(String symbol, Policy policy, boolean[] days, int lagWeeks,
                                      double minAbsSignal, boolean requireFridayDown, String yearSpec,
                                      BacktestExecutionCost cost) throws Exception {
        var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, yearSpec);
        List<Bar> bars = loaded.bars();
        if (bars.isEmpty()) return null;
        var st = new MondayReversionStrategy("FxMondayReversion", symbol)
            .withPolicy(policy).withDays(days).withSignalLagWeeks(lagWeeks)
            .withMinAbsSignal(minAbsSignal).withRequireFridayDown(requireFridayDown).withQuantity(QTY);
        return RunContext.forStrategy(null, "FxMondayReversion", st, symbol,
            RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
    }
}
