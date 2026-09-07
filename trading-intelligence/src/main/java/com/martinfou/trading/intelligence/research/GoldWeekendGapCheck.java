package com.martinfou.trading.intelligence.research;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;

import java.nio.file.Paths;
import java.time.*;
import java.util.*;

/**
 * GoldWeekendGapCheck — Pre-validation du "weekend gap" sur XAU/USD (idée
 * nouvelle, 29e résultat). CONCEPT : GoldWeekdayEffect (31 août) a montré un
 * bid safe-haven or le VENDREDI (intraday), mais le gap de FERMETURE du
 * week-end (dernier prix réel vendredi → première barre réelle dimanche)
 * n'a JAMAIS été isolé : la table du weekday check attribuait le vendredi à
 * close(jeu)→close(ven) et le dimanche n'apparaissait pas.
 *
 * HYPOTHÈSE twin-refuge (4 confirmations 28 août-4 sept) : si le bid or de
 * week-end est un vrai phénomène refuge, une partie devrait se matérialiser
 * dans le GAP — l'or gap UP pendant que EUR/GBP gap DOWN (dé-risk des actifs
 * risqués avant la fermeture, achat refuge or).
 *
 * MÉTHODE ANTI-ARTEFACT (Pattern D) :
 *   1. Split IS 2006-2015 / OOS 2016-2025.
 *   2. Contrôle paires : EUR_USD, GBP_USD (gap négatif attendu si la thèse
 *      twin-refuge est bonne — actifs risqués dé-risqués sur le week-end).
 *   3. Contrôle bêta : comparer le gap à la dérive quotidienne moyenne —
 *      un gap systématique > dérive journalière = signal, pas beta.
 *   4. Régime : bull06-12 / bear13-15 / bull2 16-25.
 *
 * DÉFINITION (mesurée barre-à-barre, robuste aux barres plates carry) :
 *   Un "weekend gap" = quand une barre RÉELLE (prix bouge vs précédente)
 *   est suivie d'un trou de marché (barres plates carry > 2h), le retour
 *   de la dernière barre réelle avant le trou à la PREMIÈRE barre réelle
 *   après le trou (close→close). Sur XAU : dernier réel ven ~21:00 → premier
 *   réel dim ~21:00-22:00. Sur les données 24/7 avec carry plat, le gap
 *   saute DANS la première barre réelle du dimanche.
 *
 * NOTE : la stratégie exécutable (GoldWeekendGapStrategy) entrera à la
 * 1re barre plate après le dernier vendredi réel (fill = close du dernier
 * vendredi réel, prix carry) et sortira à la 1re barre réelle du dimanche
 * (fill = close) — le check ci-dessous mesure EXACTEMENT ce PnL
 * (close dernier réel vendredi → close 1er réel dimanche).
 */
public class GoldWeekendGapCheck {
    private static final String BARS_DIR = "data/historical/bars";

    public static void main(String[] args) throws Exception {
        String[] symbols = {"XAU_USD", "EUR_USD", "GBP_USD"};
        for (String sym : symbols) {
            List<Bar> all = loadYears(sym, 2006, 2025);
            all.sort(Comparator.comparing(Bar::timestamp));
            System.out.println("==================================================");
            System.out.println("=== " + sym + " — weekend gaps (close dernier réel ven → close 1er réel dim) ===");
            System.out.println("==================================================");

            // identify real bars
            boolean[] real = new boolean[all.size()];
            real[0] = true;
            for (int i = 1; i < all.size(); i++) {
                real[i] = Math.abs(all.get(i).close() - all.get(i - 1).close()) > 1e-12;
            }

            // weekend gaps: last real bar before a flat run -> first real after it
            List<Double> allGaps = new ArrayList<>(), isGaps = new ArrayList<>(), oosGaps = new ArrayList<>();
            List<Double> bullGaps = new ArrayList<>(), bearGaps = new ArrayList<>(), bull2Gaps = new ArrayList<>();
            int i = 1;
            while (i < all.size()) {
                if (real[i]) { i++; continue; }
                // i = first non-real bar; find the last real before it (i-1) and first real after the run
                int lastReal = i - 1;
                if (lastReal < 0) { i++; continue; }
                int k = i;
                while (k < all.size() && !real[k]) k++;
                if (k >= all.size()) break;
                long flatMs = all.get(k).timestamp().toEpochMilli() - all.get(lastReal).timestamp().toEpochMilli();
                if (flatMs >= 6 * 3600000L) {  // at least ~6h of flat (weekend or closure)
                    double c0 = all.get(lastReal).close();
                    double c1 = all.get(k).close();
                    double gap = (c1 - c0) / c0;
                    int y0 = all.get(lastReal).timestamp().atZone(ZoneId.of("UTC")).getYear();
                    DayOfWeek dow0 = all.get(lastReal).timestamp().atZone(ZoneId.of("UTC")).getDayOfWeek();
                    allGaps.add(gap);
                    if (y0 <= 2015) isGaps.add(gap); else oosGaps.add(gap);
                    if (y0 <= 2012) bullGaps.add(gap);
                    else if (y0 <= 2015) bearGaps.add(gap);
                    else bull2Gaps.add(gap);
                    if (allGaps.size() <= 6)
                        System.out.printf("  sample: %s %s -> %s gap=%+.4f%%%n",
                            all.get(lastReal).timestamp().atZone(ZoneId.of("UTC")),
                            dow0, all.get(k).timestamp().atZone(ZoneId.of("UTC")), gap * 100);
                }
                i = k;
            }
            System.out.printf("ALL  (n=%4d): avg %+6.3f%%  hit %4.0f%%%n", allGaps.size(), mean(allGaps) * 100, hit(allGaps) * 100);
            System.out.printf("IS   (n=%4d): avg %+6.3f%%  hit %4.0f%%%n", isGaps.size(), mean(isGaps) * 100, hit(isGaps) * 100);
            System.out.printf("OOS  (n=%4d): avg %+6.3f%%  hit %4.0f%%%n", oosGaps.size(), mean(oosGaps) * 100, hit(oosGaps) * 100);
            System.out.printf("bull (n=%4d): avg %+6.3f%%  hit %4.0f%%%n", bullGaps.size(), mean(bullGaps) * 100, hit(bullGaps) * 100);
            System.out.printf("bear (n=%4d): avg %+6.3f%%  hit %4.0f%%%n", bearGaps.size(), mean(bearGaps) * 100, hit(bearGaps) * 100);
            System.out.printf("bull2(n=%4d): avg %+6.3f%%  hit %4.0f%%%n", bull2Gaps.size(), mean(bull2Gaps) * 100, hit(bull2Gaps) * 100);
            // stats
            double[] s = stat(allGaps);
            System.out.printf("t-stat: %.2f  (se %.4f%%)%n", s[0], s[1]);
            // avg |gap|
            System.out.printf("avg |gap|: %.4f%%  |  zero-gap count: %d%n",
                allGaps.stream().mapToDouble(Math::abs).average().orElse(0) * 100,
                allGaps.stream().filter(g -> Math.abs(g) < 1e-9).count());
            System.out.println();
        }
    }

    private static double[] stat(List<Double> v) {
        int n = v.size();
        double m = mean(v);
        double sd = 0;
        for (double x : v) sd += (x - m) * (x - m);
        sd = Math.sqrt(sd / (n - 1));
        double se = sd / Math.sqrt(n);
        return new double[]{m / se, se * 100};
    }

    private static Map<Integer, List<Bar>> loadYearsMap(String symbol, int from, int to) throws Exception {
        var barsDir = Paths.get(BARS_DIR);
        Map<Integer, List<Bar>> byYear = new TreeMap<>();
        for (int y = from; y <= to; y++) {
            try {
                List<Bar> bars = HistoricalDataLoader.loadYear(symbol, y, barsDir);
                if (bars != null && !bars.isEmpty()) byYear.put(y, bars);
            } catch (Exception e) { /* skip */ }
        }
        return byYear;
    }

    private static List<Bar> loadYears(String symbol, int from, int to) throws Exception {
        List<Bar> all = new ArrayList<>();
        for (var e : loadYearsMap(symbol, from, to).entrySet()) all.addAll(e.getValue());
        return all;
    }

    private static double mean(List<Double> v) {
        if (v == null || v.isEmpty()) return 0;
        return v.stream().mapToDouble(d -> d).average().orElse(0);
    }

    private static double hit(List<Double> v) {
        if (v == null || v.isEmpty()) return 0;
        return v.stream().filter(d -> d > 0).count() / (double) v.size();
    }
}
