package com.martinfou.trading.intelligence.research;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;

import java.nio.file.Paths;
import java.time.*;
import java.util.*;

/**
 * GoldFridayHourCheck — Pre-validation de la décomposition HORAIRE de la session
 * du vendredi sur XAU/USD (variation GoldWeekdayEffect FRI, mardi 8 sept 2026).
 *
 * CONTEXTE : GoldWeekdayEffect (31 août) a montré un bid safe-haven or le VENDREDI
 * (FRI long PF 1.27 / +$15 131 / 1041 trades) ; GoldWeekdayDxy (4 sept) a montré
 * que ce bid est un twin-refuge (OPPOSITE DXY PF 1.40, OOS-stable) ; GoldWeekendGap
 * (7 sept) a montré que le GAP de week-end est VIDE — le bid est un edge de SESSION
 * vendredi (positionnement avant fermeture ~21:00 UTC), pas de réouverture.
 *
 * QUESTION (piste ouverte 7 sept) : où DANS le vendredi le bid vit-il ?
 *   - Londres (07:00-16:00 UTC) ?  Le fixing or de Londres (10:30 / 15:00) ?
 *   - New York (12:00-21:00 UTC) ? Le dé-risk US avant la fermeture 17:00 ET ?
 *   - Réparti uniformément sur la session ? (→ le FRI full-day est déjà optimal)
 *
 * MÉTHODE (Pattern D, anti-artefacts appris) :
 *   1. Barres RÉELLES uniquement (close ≠ close précédent) — les .bars sont 24/7
 *      avec carry plat ; toute étude horaire sans ce filtre est polluée de zéros.
 *   2. Pour chaque vendredi réel : rendement de la barre courante H =
 *      close(barre H)/close(barre réelle précédente)-1 → PnL réalisé PENDANT
 *      la fenêtre H→H+1 UTC. Convention vérifiée (probe) : les timestamps
 *      marquent le DÉBUT de la fenêtre (close@H ≈ open@H+1). Donc le bucket
 *      H = rendement pendant l'heure H, sans décalage.
 *   3. Split IS 2006-2015 / OOS 2016-2025 (obligatoire — Pattern D).
 *   4. Contrôles paires : EUR_USD / GBP_USD aux MÊMES heures vendredi — si le bid
 *      est un twin-refuge, l'or monte aux heures où EUR/GBP BAISSENT.
 *   5. Cumul intra-session : profil moyen close(00:00) → close(H) par heure H —
 *      montre si le bid s'accumule régulièrement ou en un burst.
 *   6. Régimes : bull06-12 / bear13-15 / bull2 16-25.
 *
 * ⚠️ La dernière barre réelle du vendredi (~21:00 UTC) n'a PAS de barre réelle
 * suivante le même jour → son rendement appartient au weekend gap (7 sept, vide).
 * Exclue ici par construction.
 */
public class GoldFridayHourCheck {
    private static final String BARS_DIR = "data/historical/bars";

    public static void main(String[] args) throws Exception {
        // Session blocks (UTC): 0-7 Asia/early, 7-12 London, 12-16 overlap, 16-21 NY late
        String[] syms = {"XAU_USD", "EUR_USD", "GBP_USD"};
        for (String sym : syms) {
            System.out.println("==================================================");
            System.out.println("=== " + sym + " — Friday REAL-bar hourly forward returns (UTC) ===");
            System.out.println("==================================================");
            List<Bar> all = loadYears(sym, 2006, 2025);
            all.sort(Comparator.comparing(Bar::timestamp));

            // real bars
            boolean[] real = new boolean[all.size()];
            real[0] = true;
            for (int i = 1; i < all.size(); i++)
                real[i] = Math.abs(all.get(i).close() - all.get(i - 1).close()) > 1e-12;

            // bucket: hour-of-day (UTC) -> returns of (close[i+1]/close[i]-1) where bar i is real Friday
            Map<Integer, List<Double>> byHour = new TreeMap<>();
            Map<Integer, List<Double>> byHourIS = new TreeMap<>();
            Map<Integer, List<Double>> byHourOOS = new TreeMap<>();
            Map<Integer, double[]> byHourRegime = new TreeMap<>(); // hour -> [bull sum, bull n, bear sum, bear n, b2 sum, b2 n]

            // cumulative session profile per Friday: entry at first real Friday bar (hour0), exit at each hour
            Map<Integer, List<Double>> cumByHour = new TreeMap<>();
            Map<Integer, List<Double>> cumByHourIS = new TreeMap<>();
            Map<Integer, List<Double>> cumByHourOOS = new TreeMap<>();

            for (int i = 0; i < all.size(); i++) {
                if (!real[i]) continue;
                ZonedDateTime z = all.get(i).timestamp().atZone(ZoneId.of("UTC"));
                if (z.getDayOfWeek() != DayOfWeek.FRIDAY) continue;
                int hour = z.getHour();
                // return DURING window H->H+1 = close(bar@H)/close(prev real bar) - 1
                int p = i - 1;
                while (p >= 0 && !real[p]) p--;
                if (p < 0) continue;
                double ret = (all.get(i).close() - all.get(p).close()) / all.get(p).close();
                int year = z.getYear();
                byHour.computeIfAbsent(hour, k -> new ArrayList<>()).add(ret);
                if (year <= 2015) byHourIS.computeIfAbsent(hour, k -> new ArrayList<>()).add(ret);
                else byHourOOS.computeIfAbsent(hour, k -> new ArrayList<>()).add(ret);
                double[] reg = byHourRegime.computeIfAbsent(hour, k -> new double[6]);
                int ri = year <= 2012 ? 0 : year <= 2015 ? 2 : 4;
                reg[ri] += ret; reg[ri + 1]++;
            }

            // Cumulative: for each Friday, list of real bars in order; compute close-to-close from first real bar
            // aligned by HOUR OF DAY of the bar (bars are H1, one per hour, but guard with max).
            Map<LocalDate, TreeMap<Integer, Bar>> friBars = new TreeMap<>();
            for (int i = 0; i < all.size(); i++) {
                if (!real[i]) continue;
                ZonedDateTime z = all.get(i).timestamp().atZone(ZoneId.of("UTC"));
                if (z.getDayOfWeek() != DayOfWeek.FRIDAY) continue;
                friBars.computeIfAbsent(z.toLocalDate(), k -> new TreeMap<>()).put(z.getHour(), all.get(i));
            }
            for (var e : friBars.entrySet()) {
                var bars = e.getValue();
                if (bars.isEmpty()) continue;
                LocalDate date = e.getKey();
                int year = date.getYear();
                Bar first = bars.firstEntry().getValue();
                for (var en : bars.entrySet()) {
                    int h = en.getKey();
                    if (h == bars.firstKey()) continue;
                    double cum = (en.getValue().close() - first.close()) / first.close();
                    cumByHour.computeIfAbsent(h, k -> new ArrayList<>()).add(cum);
                    if (year <= 2015) cumByHourIS.computeIfAbsent(h, k -> new ArrayList<>()).add(cum);
                    else cumByHourOOS.computeIfAbsent(h, k -> new ArrayList<>()).add(cum);
                }
            }

            System.out.println("Hour |  n  |  avg%   | hit%  | IS n/avg/hit | OOS n/avg/hit | bull/bear/bull2 avg%");
            for (int h = 0; h < 24; h++) {
                List<Double> a = byHour.get(h);
                if (a == null || a.isEmpty()) continue;
                List<Double> isL = byHourIS.get(h), oosL = byHourOOS.get(h);
                double[] reg = byHourRegime.get(h);
                System.out.printf("%4d |%4d | %+6.4f | %4.0f%% | %4d %+6.4f %4.0f%% | %4d %+6.4f %4.0f%% | %+6.3f / %+6.3f / %+6.3f%n",
                    h, a.size(), mean(a) * 100, hit(a) * 100,
                    isL == null ? 0 : isL.size(), mean(isL) * 100, hit(isL) * 100,
                    oosL == null ? 0 : oosL.size(), mean(oosL) * 100, hit(oosL) * 100,
                    avgReg(reg, 0) * 100, avgReg(reg, 2) * 100, avgReg(reg, 4) * 100);
            }
            // session blocks
            System.out.println("\nSession blocks (avg forward return by hour bucket):");
            int[][] blocks = {{0, 7}, {7, 12}, {12, 16}, {16, 21}, {0, 24}};
            String[] blockNames = {"Asia/early 0-7", "London 7-12", "Overlap 12-16", "NY late 16-21", "ALL session 0-24"};
            for (int b = 0; b < blocks.length; b++) {
                int h0 = blocks[b][0], h1 = blocks[b][1];
                List<Double> acc = new ArrayList<>(), accIS = new ArrayList<>(), accOOS = new ArrayList<>();
                for (int h = h0; h < h1; h++) {
                    if (byHour.get(h) != null) acc.addAll(byHour.get(h));
                    if (byHourIS.get(h) != null) accIS.addAll(byHourIS.get(h));
                    if (byHourOOS.get(h) != null) accOOS.addAll(byHourOOS.get(h));
                }
                System.out.printf("  %-18s n=%4d avg=%+6.4f%% hit=%4.0f%% | IS n=%4d avg=%+6.4f%% hit=%4.0f%% | OOS n=%4d avg=%+6.4f%% hit=%4.0f%%%n",
                    blockNames[b], acc.size(), mean(acc) * 100, hit(acc) * 100,
                    accIS.size(), mean(accIS) * 100, hit(accIS) * 100,
                    accOOS.size(), mean(accOOS) * 100, hit(accOOS) * 100);
            }
            // cumulative profile (avg close-to-close from first real Friday bar)
            System.out.println("\nCumulative intra-Friday (avg return close(first real bar) -> close(hour H)):");
            System.out.printf("  %-4s | %-8s | %-8s | %-8s%n", "Hour", "ALL", "IS", "OOS");
            for (int h = 0; h < 24; h++) {
                List<Double> a = cumByHour.get(h);
                if (a == null || a.isEmpty()) continue;
                List<Double> isL = cumByHourIS.get(h), oosL = cumByHourOOS.get(h);
                System.out.printf("  %-4d | %+7.4f%% | %+7.4f%% | %+7.4f%%  (n=%d)%n",
                    h, mean(a) * 100, mean(isL) * 100, mean(oosL) * 100, a.size());
            }
            System.out.println();
        }
    }

    private static double avgReg(double[] reg, int i) {
        return reg[i + 1] == 0 ? Double.NaN : reg[i] / reg[i + 1];
    }

    private static List<Bar> loadYears(String symbol, int from, int to) throws Exception {
        List<Bar> all = new ArrayList<>();
        var barsDir = Paths.get(BARS_DIR);
        for (int y = from; y <= to; y++) {
            try {
                List<Bar> bars = HistoricalDataLoader.loadYear(symbol, y, barsDir);
                if (bars != null && !bars.isEmpty()) all.addAll(bars);
            } catch (Exception e) { /* skip */ }
        }
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
