package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.GoldWeekdayEffectStrategy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

/**
 * RunFxVolRegimeSize — DEEP DIVE vendredi 25 sept 2026 (43e résultat).
 *
 * Piste ouverte par le 42e (CrossAssetVolRegime, 24 sept) : le fade du vendredi
 * FX (37e, GBP_JPY PF 1.18) doit-il être CONDITIONNÉ par la volatilité, et si oui
 * comment — comme GATE (sélection d'entrées) ou comme OVERLAY de TAILLE ?
 *
 * ⚠️ Leçon du 36e (GoldTurtleSizeOverlay) : bloquer une entrée RE-TIME la séquence
 * suivante → un gate n'est PAS un filtre appliqué à la liste de trades, et le
 * classement gate↔overlay peut S'INVERSER. Benchmark obligatoire de tout test de
 * taille : le LEVIER UNIFORME À EXPOSITION ÉGALE (FLAT 2u).
 *
 * Régime : rang percentile CAUSAL de la vol 21 j (traîne 1000 obs), mesuré jusqu'à
 * la VEILLE de l'entrée (look-ahead safe) — identique au 42e.
 *   - EQ  : vol ACTIONS (MES D1)
 *   - OWN : vol PROPRE de la paire
 *   - AND : min(EQ, OWN) = conjonction (la cellule qui portait l'effet au 42e)
 *   - MEAN: moyenne (état unique, contrôle de sensibilité)
 *
 * Usage:
 *   --base     calibration : SELL FRI 1u sur 8 paires (doit reproduire le 37e)
 *   --gate     GATE haut vs GATE bas (contrôle contrapositif) + gates mono-source
 *   --overlay  OVERLAY 2u/1u vs FLAT à exposition ÉGALE
 *   --sweep    robustesse paramétrique du seuil (50/60/70/80)
 *   --wf       walk-forward IS 2006-2015 / OOS 2016-2026
 *   --regime   sous-périodes (bull 06-12 / taper 13-15 / bull2 16-26)
 *   --control  spécificité du jour (gate appliqué Mon-Jeu) + $/trade
 *   --all      tout
 */
public class RunFxVolRegimeSize {

    static final String YEAR_SPEC = "2006-2026";
    static final double CAPITAL = 50_000;
    static final double QTY = 10_000;          // unités FX
    static final String[] ALL8 = {"GBP_JPY", "GBP_USD", "AUD_USD", "NZD_USD", "USD_CHF", "EUR_USD", "USD_JPY", "USD_CAD"};
    static final String[] BASKET = {"GBP_JPY", "GBP_USD", "AUD_USD", "NZD_USD", "USD_CHF", "EUR_USD", "USD_JPY"};
    static final boolean[] FRI = {false, false, false, false, true};
    static final boolean[] MON_THU = {true, true, true, true, false};

    // ---------------------------------------------------------------- données
    static final Map<String, List<Bar>> barsCache = new HashMap<>();
    static TreeMap<LocalDate, Double> eqRank;                       // rang vol MES (causal)
    static final Map<String, TreeMap<LocalDate, Double>> ownRank = new LinkedHashMap<>();
    static final Map<String, TreeMap<LocalDate, Double>> rankAnd = new LinkedHashMap<>();
    static final Map<String, TreeMap<LocalDate, Double>> rankMean = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);
        String mode = args.length > 0 ? args[0] : "--base";

        System.out.println("==============================================================");
        System.out.println("FX FRIDAY FADE × RÉGIME DE VOLATILITÉ — GATE vs OVERLAY (43e)");
        System.out.println("Mode: " + mode + " | coûts $0.07 + 0.01% | capital $" + CAPITAL + " | " + QTY + " u");
        System.out.println("==============================================================");

        buildRegimes();

        switch (mode) {
            case "--gate" -> gateMode(cost);
            case "--overlay" -> overlayMode(cost);
            case "--sweep" -> sweepMode(cost);
            case "--wf" -> wfMode(cost);
            case "--regime" -> regimeMode(cost);
            case "--control" -> controlMode(cost);
            case "--all" -> { gateMode(cost); overlayMode(cost); sweepMode(cost); wfMode(cost); }
            default -> baseMode(cost);
        }
        System.out.println("\nDONE");
    }

    // ============================================================ RÉGIMES

    private static void buildRegimes() throws Exception {
        TreeMap<LocalDate, Double> mes = dailyClosesCsv("MES_D1.csv");
        TreeMap<LocalDate, Double> mesRet = rets(mes);
        eqRank = trailingRank(vol(mesRet, 21), 1000);
        System.out.printf("[régime] MES vol21 rang causal : %s → %s (%d obs)%n",
            eqRank.isEmpty() ? "-" : eqRank.firstKey(), eqRank.isEmpty() ? "-" : eqRank.lastKey(), eqRank.size());

        for (String sym : ALL8) {
            TreeMap<LocalDate, Double> d = dailyClosesBars(sym, 2006, 2026);
            TreeMap<LocalDate, Double> r = trailingRank(vol(rets(d), 21), 1000);
            ownRank.put(sym, r);
            TreeMap<LocalDate, Double> and = new TreeMap<>(), mean = new TreeMap<>();
            for (var e : r.entrySet()) {
                Double q = regimeAt(eqRank, e.getKey());
                if (q == null) continue;
                and.put(e.getKey(), Math.min(q, e.getValue()));
                mean.put(e.getKey(), (q + e.getValue()) / 2.0);
            }
            rankAnd.put(sym, and);
            rankMean.put(sym, mean);
        }
        System.out.printf("[régime] rangs propres construits pour %d paires%n", ownRank.size());
    }

    /** Rang de la VEILLE (look-ahead safe) : dernière obs strictement antérieure à d. */
    static Double regimeAt(TreeMap<LocalDate, Double> rank, LocalDate d) {
        var e = rank.floorEntry(d.minusDays(1));
        return e == null ? null : e.getValue();
    }

    static TreeMap<LocalDate, Double> rankOf(String sym, String kind) {
        return switch (kind) {
            case "EQ" -> {   // rang actions projeté sur les dates de la paire
                TreeMap<LocalDate, Double> m = new TreeMap<>();
                for (LocalDate d : ownRank.get(sym).keySet()) {
                    Double q = regimeAt(eqRank, d);
                    if (q != null) m.put(d, q);
                }
                yield m;
            }
            case "OWN" -> ownRank.get(sym);
            case "MEAN" -> rankMean.get(sym);
            default -> rankAnd.get(sym);
        };
    }

    // ============================================================ CALIBRATION

    private static void baseMode(BacktestExecutionCost cost) throws Exception {
        section("CALIBRATION — SELL FRI 1u, 8 paires (référence 37e : GBP_JPY 1.18 / GBP_USD 1.13 / EUR 1.00 / CAD 0.80)");
        System.out.printf("%-9s %6s %5s %6s %7s %11s %10s %9s%n",
            "PAIRE", "PF", "WR%", "DD%", "TRADES", "NET$", "SWAP$", "$/TRADE");
        for (String sym : ALL8) {
            var r = run(sym, FRI, null, null, null, QTY, YEAR_SPEC, cost);
            if (r == null) { System.out.printf("%-9s PAS DE DONNÉES%n", sym); continue; }
            System.out.printf("%-9s %6.2f %4.0f%% %5.2f%% %7d %11.2f %10.2f %9.2f%n",
                sym, r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(), r.totalTrades(),
                r.totalPnl(), r.totalSwap(), r.totalTrades() == 0 ? 0 : r.totalPnl() / r.totalTrades());
        }
    }

    // ============================================================ GATE

    private static void gateMode(BacktestExecutionCost cost) throws Exception {
        section("GATE — n'entrer que si le rang de la veille ∈ [60,100] (haut) vs [0,40] (bas, contrôle contrapositif)");
        System.out.println("Un gate n'est PAS un filtre : il RE-TIME toutes les entrées suivantes (leçon du 15 sept).");
        System.out.printf("%-9s %-22s %6s %7s %11s %9s %10s%n",
            "PAIRE", "VAR.", "PF", "TRADES", "NET$", "$/TRADE", "SWAP$");
        for (String sym : BASKET) {
            row(sym, "baseline 1u (aucun gate)", run(sym, FRI, null, null, null, QTY, YEAR_SPEC, cost));
            row(sym, "GATE AND haut", run(sym, FRI, "AND", 60.0, 100.0, QTY, YEAR_SPEC, cost));
            row(sym, "GATE AND bas", run(sym, FRI, "AND", 0.0, 40.0, QTY, YEAR_SPEC, cost));
            row(sym, "GATE EQ haut", run(sym, FRI, "EQ", 60.0, 100.0, QTY, YEAR_SPEC, cost));
            row(sym, "GATE OWN haut", run(sym, FRI, "OWN", 60.0, 100.0, QTY, YEAR_SPEC, cost));
            System.out.println();
        }
        section("AGRÉGAT PANIER (7 paires, PF agrégé sur les PnL de trades)");
        System.out.printf("%-22s %6s %7s %11s %9s %10s%n", "VAR.", "PFagg", "TRADES", "NET$", "$/TRADE", "SWAP$");
        pool("baseline 1u", FRI, null, null, QTY, cost);
        pool("GATE AND haut", FRI, "AND", 60.0, QTY, cost);
        pool("GATE AND bas", FRI, "AND", 40.0, QTY, cost);
        pool("GATE EQ haut", FRI, "EQ", 60.0, QTY, cost);
        pool("GATE OWN haut", FRI, "OWN", 60.0, QTY, cost);
    }

    // ============================================================ OVERLAY

    private static void overlayMode(BacktestExecutionCost cost) throws Exception {
        section("OVERLAY — taille 2u si rang ≥ 60, sinon 1u — vs FLAT à EXPOSITION ÉGALE (benchmark obligatoire)");
        System.out.println("FLAT-u = quantité uniforme = moyenne pondérée des unités de l'overlay (comptée sur les vendredis réels).");
        System.out.printf("%-9s %-26s %6s %6s %7s %11s %11s %9s%n",
            "PAIRE", "VAR.", "PF", "u/trade", "TRADES", "NET$", "DD%", "$/TRADE");
        for (String sym : BASKET) {
            var base = run(sym, FRI, null, null, null, QTY, YEAR_SPEC, cost);
            var ov = run(sym, FRI, "AND", null, null, QTY, YEAR_SPEC, cost, true);
            double fH = highShare(sym, "AND", 60.0);
            double u = 1 + fH;
            var flat = run(sym, FRI, null, null, null, QTY * u, YEAR_SPEC, cost);
            row2(sym, "baseline 1u", base, 1.0);
            row2(sym, "OVERLAY 2u/1u (AND≥60)", ov, u);
            row2(sym, String.format("FLAT %.2fu (éq. exposition)", u), flat, u);
            System.out.printf("   → part de vendredis en régime HAUT (AND≥60) : %.1f%%%n", fH * 100);
            System.out.println();
        }
        section("AGRÉGAT PANIER — net, PF agrégé et $/trade à EXPOSITION ÉGALE");
        System.out.printf("%-26s %6s %7s %10s %11s %9s%n", "VAR.", "PFagg", "TRADES", "NET$", "$/TRADE(u)", "NET$/u");
        pool("baseline 1u", FRI, null, null, QTY, cost);
        pool2("OVERLAY 2u/1u (AND≥60)", "AND", 60.0, QTY, cost);
        pool("FLAT 2u (référence non éq.)", FRI, null, null, QTY * 2, cost);
        pool("FLAT 1.5u (éq. exposition, approx)", FRI, null, null, QTY * 1.5, cost);
    }

    // ============================================================ SWEEP

    private static void sweepMode(BacktestExecutionCost cost) throws Exception {
        section("ROBUSTESSE PARAMÉTRIQUE — balayage du seuil de gate (plateau = robuste, pic = curve fitting)");
        System.out.printf("%-12s %-22s %6s %7s %11s %9s%n", "SEUIL", "VAR.", "PFagg", "TRADES", "NET$", "$/TRADE");
        for (double[] th : new double[][]{{50, 100}, {60, 100}, {70, 100}, {80, 100}, {60, 90}}) {
            pool(String.format("GATE AND [%.0f-%.0f]", th[0], th[1]), FRI, "AND", th[0], QTY, cost, th[1]);
        }
        for (double[] th : new double[][]{{0, 50}, {0, 40}, {0, 30}, {0, 20}}) {
            pool(String.format("GATE BAS [%.0f-%.0f]", th[0], th[1]), FRI, "AND", th[1], QTY, cost, th[1], true);
        }
        System.out.println("(GATE BAS : on n'entre que sous le seuil indiqué — contrôle contrapositif du gate haut)");
    }

    // ============================================================ WALK-FORWARD

    private static void wfMode(BacktestExecutionCost cost) throws Exception {
        section("WALK-FORWARD — IS 2006-2015 / OOS 2016-2026");
        System.out.printf("%-9s %-22s %-16s %6s %7s %11s%n", "PAIRE", "VAR.", "PÉRIODE", "PF", "TRADES", "NET$");
        for (String sym : new String[]{"GBP_JPY", "GBP_USD", "AUD_USD", "EUR_USD", "USD_CAD"}) {
            for (String[] p : new String[][]{{"IS 2006-2015", "2006-2015"}, {"OOS 2016-2026", "2016-2026"}}) {
                row3(sym, "baseline 1u", p, run(sym, FRI, null, null, null, QTY, p[1], cost));
                row3(sym, "GATE AND haut", p, run(sym, FRI, "AND", 60.0, 100.0, QTY, p[1], cost));
                row3(sym, "OVERLAY 2u/1u", p, run(sym, FRI, "AND", null, null, QTY, p[1], cost, true));
                System.out.println();
            }
        }
    }

    // ============================================================ RÉGIMES

    private static void regimeMode(BacktestExecutionCost cost) throws Exception {
        section("RÉGIMES DE MARCHÉ — bull 2006-2012 / taper 2013-2015 / bull2 2016-2026");
        System.out.printf("%-9s %-16s %-22s %6s %7s %11s%n", "PAIRE", "RÉGIME", "VAR.", "PF", "TRADES", "NET$");
        for (String sym : new String[]{"GBP_JPY", "GBP_USD"}) {
            for (String[] rg : new String[][]{{"bull 2006-2012", "2006-2012"}, {"taper 2013-2015", "2013-2015"}, {"bull2 2016-2026", "2016-2026"}}) {
                row3(sym, "baseline 1u", new String[]{rg[0], rg[1]}, run(sym, FRI, null, null, null, QTY, rg[1], cost));
                row3(sym, "GATE AND haut", new String[]{rg[0], rg[1]}, run(sym, FRI, "AND", 60.0, 100.0, QTY, rg[1], cost));
                System.out.println();
            }
        }
    }

    // ============================================================ CONTRÔLES

    private static void controlMode(BacktestExecutionCost cost) throws Exception {
        section("CONTRÔLE 1 — SPÉCIFICITÉ DU JOUR : le gate vol appliqué à LUNDI-JEUDI doit échouer");
        System.out.println("(42e : le mardi répliquait 75 % du Δ du vendredi → test décisif)");
        System.out.printf("%-9s %-24s %6s %7s %11s%n", "PAIRE", "VAR.", "PFagg", "TRADES", "NET$");
        pool("Mon-Jeu gate AND haut", MON_THU, "AND", 60.0, QTY, cost);
        pool("Mon-Jeu baseline", MON_THU, null, null, QTY, cost);

        section("CONTRÔLE 2 — DÉRIVE DU JOUR (la bêta non conditionnée) : FRI vs Mon-Jeu vs ALLDAYS");
        System.out.printf("%-9s %-16s %6s %7s %11s %9s%n", "PAIRE", "MODE", "PF", "TRADES", "NET$", "$/TRADE");
        for (String sym : new String[]{"GBP_JPY", "GBP_USD", "EUR_USD", "USD_CAD"}) {
            row(sym, "SELL FRI", run(sym, FRI, null, null, null, QTY, YEAR_SPEC, cost));
            row(sym, "SELL Mon-Jeu", run(sym, MON_THU, null, null, null, QTY, YEAR_SPEC, cost));
            row(sym, "SELL ALLDAYS", run(sym, new boolean[]{true, true, true, true, true}, null, null, null, QTY, YEAR_SPEC, cost));
            System.out.println();
        }

        section("CONTRÔLE 3 — PART DE L'EDGE PAR TRADE : la jambe de PRIX seule (net − swap)");
        System.out.printf("%-9s %-22s %10s %10s %11s%n", "PAIRE", "VAR.", "PRIX$/trade", "SWAP$/trade", "NET$/trade");
        for (String sym : new String[]{"GBP_JPY", "GBP_USD", "EUR_USD"}) {
            prix(sym, "baseline 1u", run(sym, FRI, null, null, null, QTY, YEAR_SPEC, cost));
            prix(sym, "GATE AND haut", run(sym, FRI, "AND", 60.0, 100.0, QTY, YEAR_SPEC, cost));
            System.out.println();
        }
    }

    // ============================================================ HELPERS

    private static void section(String t) {
        System.out.println("\n==============================================================");
        System.out.println(t);
        System.out.println("==============================================================");
    }

    private static void row(String sym, String variant, BacktestResult r) {
        if (r == null) { System.out.printf("%-9s %-22s PAS DE DONNÉES%n", sym, variant); return; }
        System.out.printf("%-9s %-22s %6.2f %7d %11.2f %9.2f %10.2f%n",
            sym, variant, r.profitFactor(), r.totalTrades(), r.totalPnl(),
            r.totalTrades() == 0 ? 0 : r.totalPnl() / r.totalTrades(), r.totalSwap());
    }

    private static void row2(String sym, String variant, BacktestResult r, double u) {
        System.out.printf("%-9s %-26s %6.2f %6.2f %7d %11.2f %10.2f %9.2f%n",
            sym, variant, r.profitFactor(), u, r.totalTrades(), r.totalPnl(), r.maxDrawdownPct(),
            r.totalTrades() == 0 ? 0 : r.totalPnl() / r.totalTrades());
    }

    private static void row3(String sym, String variant, String[] period, BacktestResult r) {
        System.out.printf("%-9s %-22s %-16s %6.2f %7d %11.2f%n",
            sym, variant, period[0], r.profitFactor(), r.totalTrades(), r.totalPnl());
    }

    private static void prix(String sym, String variant, BacktestResult r) {
        int n = r.totalTrades();
        System.out.printf("%-9s %-22s %10.2f %10.2f %11.2f%n", sym, variant,
            n == 0 ? 0 : (r.totalPnl() - r.totalSwap()) / n, n == 0 ? 0 : r.totalSwap() / n,
            n == 0 ? 0 : r.totalPnl() / n);
    }

    /** Agrégat panier sur une variante (gate haute par défaut). */
    private static void pool(String label, boolean[] days, String kind, Double gateLo, double qty,
                             BacktestExecutionCost cost) throws Exception {
        pool(label, days, kind, gateLo, qty, cost, 100.0, false);
    }

    private static void pool(String label, boolean[] days, String kind, Double gateLo, double qty,
                             BacktestExecutionCost cost, double gateHi) throws Exception {
        pool(label, days, kind, gateLo, qty, cost, gateHi, false);
    }

    private static void pool(String label, boolean[] days, String kind, Double gateLo, double qty,
                             BacktestExecutionCost cost, double gateHi, boolean lowOnly) throws Exception {
        double lo = lowOnly ? 0.0 : (gateLo == null ? 0.0 : gateLo);
        double hi = lowOnly ? gateHi : (gateLo == null ? 100.0 : 100.0);
        List<Double> pnls = new ArrayList<>();
        double net = 0, swap = 0;
        int trades = 0;
        for (String sym : BASKET) {
            BacktestResult r = run(sym, days, kind, gateLo == null ? null : lo, hi, qty, YEAR_SPEC, cost);
            if (r == null) continue;
            pnls.addAll(r.tradePnlList());
            net += r.totalPnl(); swap += r.totalSwap(); trades += r.totalTrades();
        }
        double gp = pnls.stream().filter(d -> d > 0).mapToDouble(d -> d).sum();
        double gl = pnls.stream().filter(d -> d <= 0).mapToDouble(d -> -d).sum();
        System.out.printf("%-26s %6.2f %7d %11.2f %9.2f %10.2f%n",
            label, gl > 0 ? gp / gl : Double.NaN, trades, net, trades == 0 ? 0 : net / trades, swap);
    }

    private static void pool2(String label, String kind, double threshold, double qty,
                              BacktestExecutionCost cost) throws Exception {
        List<Double> pnls = new ArrayList<>();
        double net = 0; int trades = 0; double uSum = 0;
        for (String sym : BASKET) {
            BacktestResult r = run(sym, FRI, kind, null, null, qty, YEAR_SPEC, cost, true);
            if (r == null) continue;
            pnls.addAll(r.tradePnlList());
            net += r.totalPnl(); trades += r.totalTrades();
            uSum += 1 + highShare(sym, kind, threshold);
        }
        double u = uSum / BASKET.length;
        double gp = pnls.stream().filter(d -> d > 0).mapToDouble(d -> d).sum();
        double gl = pnls.stream().filter(d -> d <= 0).mapToDouble(d -> -d).sum();
        System.out.printf("%-26s %6.2f %7d %10.2f %11.2f %9.2f%n",
            label, gl > 0 ? gp / gl : Double.NaN, trades, net, trades == 0 ? 0 : net / trades, net / u);
    }

    /** Part des vendredis du backtest en régime haut (rang veille ≥ seuil). Causal. */
    private static double highShare(String sym, String kind, double threshold) {
        TreeMap<LocalDate, Double> ranks = rankOf(sym, kind);
        List<Bar> bars = barsCache.get(sym + "|" + YEAR_SPEC);
        if (bars == null) return 0;
        Set<LocalDate> fri = new TreeSet<>();
        ZoneId utc = ZoneId.of("UTC");
        for (Bar b : bars) {
            LocalDate d = b.timestamp().atZone(utc).toLocalDate();
            if (d.getDayOfWeek() == DayOfWeek.FRIDAY) fri.add(d);
        }
        int n = 0, hi = 0;
        for (LocalDate d : fri) {
            Double r = regimeAt(ranks, d);
            if (r == null) continue;
            n++;
            if (r >= threshold) hi++;
        }
        return n == 0 ? 0 : hi / (double) n;
    }

    // ------------------------------------------------------------- exécution

    private static BacktestResult run(String symbol, boolean[] days, String kind, Double gateLo, Double gateHi,
                                      double qty, String yearSpec, BacktestExecutionCost cost) throws Exception {
        return run(symbol, days, kind, gateLo, gateHi, qty, yearSpec, cost, false);
    }

    private static BacktestResult run(String symbol, boolean[] days, String kind, Double gateLo, Double gateHi,
                                      double qty, String yearSpec, BacktestExecutionCost cost,
                                      boolean overlay) throws Exception {
        String key = symbol + "|" + yearSpec;
        List<Bar> bars = barsCache.get(key);
        if (bars == null) {
            var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, yearSpec);
            bars = loaded.bars();
            barsCache.put(key, bars);
        }
        if (bars.isEmpty()) return null;
        var strategy = new GoldWeekdayEffectStrategy("FxWeekdaySession", symbol, days, Order.Side.SELL)
            .withQuantity(qty);
        if (kind != null) {
            strategy.withRegime(rankOf(symbol, kind));
            if (overlay) {
                strategy.withOverlay(1.0, 2.0, 60.0);
            } else if (gateLo != null && gateHi != null) {
                strategy.withGate(gateLo, gateHi);
            }
        }
        return RunContext.forStrategy(null, "FxWeekdaySession", strategy, symbol,
            RunMode.BACKTEST, bars, CAPITAL, null, cost).run();
    }

    // -------------------------------------------------------------- données

    static TreeMap<LocalDate, Double> dailyClosesCsv(String csv) throws Exception {
        TreeMap<LocalDate, Double> map = new TreeMap<>();
        for (String line : Files.readAllLines(Path.of("data/historical/futures").resolve(csv))) {
            String[] f = line.split(",");
            if (f.length < 5 || f[0].startsWith("Date")) continue;
            try { map.put(LocalDate.parse(f[0].trim()), Double.parseDouble(f[4])); } catch (Exception ignore) {}
        }
        return map;
    }

    static TreeMap<LocalDate, Double> dailyClosesBars(String symbol, int from, int to) throws Exception {
        TreeMap<LocalDate, Double> map = new TreeMap<>();
        for (int y = from; y <= to; y++) {
            try {
                List<Bar> bars = HistoricalDataLoader.loadYear(symbol, y, Paths.get("data/historical/bars"));
                if (bars == null) continue;
                for (Bar b : bars) map.put(b.timestamp().atZone(ZoneId.of("UTC")).toLocalDate(), b.close());
            } catch (Exception ignored) { }
        }
        return map;
    }

    static TreeMap<LocalDate, Double> rets(TreeMap<LocalDate, Double> m) {
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        LocalDate prev = null;
        for (var e : m.entrySet()) {
            if (prev != null && m.get(prev) > 0) out.put(e.getKey(), (e.getValue() - m.get(prev)) / m.get(prev));
            prev = e.getKey();
        }
        return out;
    }

    static TreeMap<LocalDate, Double> vol(TreeMap<LocalDate, Double> rets, int win) {
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        List<Double> buf = new ArrayList<>();
        for (var e : rets.entrySet()) {
            buf.add(e.getValue());
            if (buf.size() > win) buf.remove(0);
            if (buf.size() == win && buf.size() > 2) {
                double m = buf.stream().mapToDouble(d -> d).average().orElse(0);
                double v = buf.stream().mapToDouble(d -> (d - m) * (d - m)).sum() / (buf.size() - 1);
                out.put(e.getKey(), Math.sqrt(v));
            }
        }
        return out;
    }

    /** Rang percentile CAUSAL (0-100) : position de la valeur dans les `trail` obs PRÉCÉDENTES. */
    static TreeMap<LocalDate, Double> trailingRank(TreeMap<LocalDate, Double> s, int trail) {
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        List<Double> buf = new ArrayList<>();
        for (var e : s.entrySet()) {
            if (!buf.isEmpty()) {
                int n = Math.min(trail, buf.size());
                List<Double> win = buf.subList(buf.size() - n, buf.size());
                long le = win.stream().filter(d -> d <= e.getValue()).count();
                out.put(e.getKey(), 100.0 * le / n);
            }
            buf.add(e.getValue());
        }
        return out;
    }
}
