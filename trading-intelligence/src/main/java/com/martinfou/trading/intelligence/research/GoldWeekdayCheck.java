package com.martinfou.trading.intelligence.research;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import java.nio.file.Paths;
import java.time.*;
import java.util.*;

/**
 * GoldWeekdayCheck — Pre-validation du pattern "jour de semaine" sur XAU/USD.
 *
 * 📊 CONCEPT (idée nouvelle, 24e résultat) : la recherche calendaire du pipeline
 * n'a exploré que la dimension MOIS (Avr/Mai/Août/Oct/Nov). La dimension
 * JOUR-DE-SEMAINE n'a jamais été testée. L'or a une microstructure calendaire
 * documentée (settlement COMEX, fixing de Londres, demande physique asiatique)
 * et — avantage structurel — un swap ≈ $0 (GoldTurtlePyramid, 18 août) qui rend
 * les holds overnight/weekend GRATUITS, contrairement au FX où le carry tue
 * les edges overnight.
 *
 * MÉTHODE ANTI-ARTEFACT (Pattern D, méthode du 5 août) :
 *   1. Split IS 2006-2015 / OOS 2016-2026 (obligatoire).
 *   2. Contrôle mois voisins → ici : contrôle JOURS voisins (les autres weekdays
 *      du même actif) + contrôle paires (EUR_USD, GBP_USD) = effet or-spécifique
 *      ou effet USD générique ?
 *   3. Check NFP (1er vendredi du mois, proxy calendrier) — directement
 *      actionnable cette semaine (NFP ven 4 sept).
 *
 * RAPPEL : le rendement du jour D = close(D-1) → close(D) — un trade
 * "buy close D-1 / sell close D" capture exactement ce rendement. Pour Monday,
 * cela inclut le gap du week-end (hold vendredi soir → lundi soir, gratuit sur or).
 */
public class GoldWeekdayCheck {

    private static final String BARS_DIR = "data/historical/bars";

    public static void main(String[] args) throws Exception {
        String[] symbols = {"XAU_USD", "EUR_USD", "GBP_USD"};
        for (String sym : symbols) {
            System.out.println("==================================================");
            System.out.println("=== " + sym + " — daily close-to-close returns by weekday ===");
            System.out.println("==================================================");
            Map<Integer, List<Bar>> byYear = loadYears(sym, 2006, 2026);
            Map<DayOfWeek, List<Double>> all = new EnumMap<>(DayOfWeek.class);
            Map<DayOfWeek, List<Double>> is = new EnumMap<>(DayOfWeek.class);
            Map<DayOfWeek, List<Double>> oos = new EnumMap<>(DayOfWeek.class);

            // close par jour UTC (dernière barre du jour)
            TreeMap<LocalDate, Double> closeByDay = new TreeMap<>();
            for (var e : byYear.entrySet()) {
                for (Bar b : e.getValue()) {
                    ZonedDateTime z = b.timestamp().atZone(ZoneId.of("UTC"));
                    closeByDay.put(z.toLocalDate(), b.close());
                }
            }
            List<LocalDate> days = new ArrayList<>(closeByDay.keySet());
            for (int i = 1; i < days.size(); i++) {
                LocalDate prev = days.get(i - 1);
                LocalDate cur = days.get(i);
                // skip si jour précédent non ouvré (gap multi-jours = hold week-end, on le garde pour Monday)
                double c0 = closeByDay.get(prev), c1 = closeByDay.get(cur);
                if (c0 <= 0) continue;
                double ret = (c1 - c0) / c0;
                DayOfWeek dow = cur.getDayOfWeek();
                all.computeIfAbsent(dow, k -> new ArrayList<>()).add(ret);
                int y = cur.getYear();
                if (y <= 2015) is.computeIfAbsent(dow, k -> new ArrayList<>()).add(ret);
                else oos.computeIfAbsent(dow, k -> new ArrayList<>()).add(ret);
            }

            System.out.println("DOW      | ALL n/avg/hit  | IS n/avg/hit  | OOS n/avg/hit");
            for (DayOfWeek dow : new DayOfWeek[]{DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                                                 DayOfWeek.THURSDAY, DayOfWeek.FRIDAY}) {
                List<Double> a = all.get(dow), i = is.get(dow), o = oos.get(dow);
                System.out.printf("%-9s| %4d %+6.3f%% %4.0f%% | %4d %+6.3f%% %4.0f%% | %4d %+6.3f%% %4.0f%%%n",
                    dow, a.size(), mean(a) * 100, hit(a) * 100,
                    i.size(), mean(i) * 100, hit(i) * 100,
                    o.size(), mean(o) * 100, hit(o) * 100);
            }
            System.out.println();
        }

        // Check NFP : 1er vendredi du mois sur XAU/USD
        System.out.println("==================================================");
        System.out.println("=== XAU_USD — 1er vendredi du mois (proxy NFP) vs autres vendredis ===");
        System.out.println("==================================================");
        Map<Integer, List<Bar>> xau = loadYears("XAU_USD", 2006, 2026);
        TreeMap<LocalDate, Double> xauClose = new TreeMap<>();
        for (var e : xau.entrySet())
            for (Bar b : e.getValue())
                xauClose.put(b.timestamp().atZone(ZoneId.of("UTC")).toLocalDate(), b.close());
        List<LocalDate> xauDays = new ArrayList<>(xauClose.keySet());
        List<Double> nfpFri = new ArrayList<>(), otherFri = new ArrayList<>();
        List<Double> nfpFriIS = new ArrayList<>(), nfpFriOOS = new ArrayList<>();
        for (int i = 1; i < xauDays.size(); i++) {
            LocalDate prev = xauDays.get(i - 1), cur = xauDays.get(i);
            if (cur.getDayOfWeek() != DayOfWeek.FRIDAY) continue;
            double ret = (xauClose.get(cur) - xauClose.get(prev)) / xauClose.get(prev);
            boolean firstFriday = cur.getDayOfMonth() <= 7;
            if (firstFriday) {
                nfpFri.add(ret);
                if (cur.getYear() <= 2015) nfpFriIS.add(ret); else nfpFriOOS.add(ret);
            } else {
                otherFri.add(ret);
            }
        }
        System.out.printf("NFP Fridays    (1er ven, n=%d): avg %+6.3f%%  hit %4.0f%%%n",
            nfpFri.size(), mean(nfpFri) * 100, hit(nfpFri) * 100);
        System.out.printf("  IS 2006-15 (n=%d): avg %+6.3f%%  hit %4.0f%%%n",
            nfpFriIS.size(), mean(nfpFriIS) * 100, hit(nfpFriIS) * 100);
        System.out.printf("  OOS 2016-26 (n=%d): avg %+6.3f%%  hit %4.0f%%%n",
            nfpFriOOS.size(), mean(nfpFriOOS) * 100, hit(nfpFriOOS) * 100);
        System.out.printf("Other Fridays   (n=%d): avg %+6.3f%%  hit %4.0f%%%n",
            otherFri.size(), mean(otherFri) * 100, hit(otherFri) * 100);
    }

    private static Map<Integer, List<Bar>> loadYears(String symbol, int from, int to) throws Exception {
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

    private static double mean(List<Double> v) {
        if (v == null || v.isEmpty()) return 0;
        return v.stream().mapToDouble(d -> d).average().orElse(0);
    }

    private static double hit(List<Double> v) {
        if (v == null || v.isEmpty()) return 0;
        return v.stream().filter(d -> d > 0).count() / (double) v.size();
    }
}
