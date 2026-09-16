package com.martinfou.trading.intelligence.research;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;

import java.nio.file.Paths;
import java.time.*;
import java.util.*;

/**
 * FxCalendarDimCheck — Pré-validation (Pattern D) de DEUX dimensions calendaires
 * jamais testées systématiquement en FX.
 *
 * 📊 CONTEXTE (mercredi = pattern saisonnier, 37e résultat)
 *   La recherche calendaire FX a exploré la dimension MOIS (Avr/Mai/Août/Oct/Nov-Déc)
 *   et les fenêtres explicites (TurnOfMonth, MonthWeekPhase, DateWindowSeasonal).
 *   La dimension JOUR-DE-SEMAINE n'a été validée QUE sur l'or (GoldWeekdayCheck,
 *   24e résultat, 31 août) — le contrôle FX y était réduit à EUR_USD/GBP_USD pour
 *   UN seul jour (vendredi). La dimension FIN DE TRIMESTRE (flux de rebalancement
 *   institutionnel Mar/Jun/Sep/Déc) n'a JAMAIS été testée : TurnOfMonth = fin de
 *   TOUS les mois (indifférencié), aucun test ne sépare les fins de trimestre.
 *
 * 🎯 HYPOTHÈSES
 *   (A) Jour-de-semaine : microstructure de session (fixings, week-end positioning).
 *   (B) Fin de trimestre : rebalancement de portefeuille (pensions, window dressing),
 *       funding USD de fin de trimestre → biais dollar.
 *   (C) Septembre (mois courant) : contrôle de contexte.
 *
 * MÉTHODE ANTI-ARTEFACT (Pattern D, méthode du 5 août, durcie le 7 sept)
 *   1. Split chronologique IS 2006-2015 / OOS 2016-2026 (obligatoire).
 *   2. Contrôle de voisinage : pour le jour-de-semaine → les autres jours ;
 *      pour la fin de trimestre → la MÊME fenêtre sur les mois NON-trimestriels
 *      (le contrôle qui manquait à TurnOfMonth).
 *   3. TEST DE MÉDIANE (rendu systématique le 7 sept : t≈2 avec hit≈50% et médiane
 *      négative = queue droite, pas un signal).
 *   4. Décomposition par trimestre (Mar/Jun/Sep/Déc) : un seul trimestre porteur
 *      = fragilité, pas un effet de flux.
 *   5. t-stat rapportée pour situer la significativité brute.
 *
 * ⚠️ Rappel : ceci est une PRÉ-VALIDATION. Aucun verdict de tradeabilité sans
 *    backtest AVEC coûts (0.07$/trade + 0.01% slippage) et sans swap lu séparément
 *    (le swap modèle constant 2024-26 est un artefact connu sur les longues fenêtres).
 */
public class FxCalendarDimCheck {

    private static final String BARS_DIR = "data/historical/bars";

    private static final String[] SYMBOLS = {
        "EUR_USD", "GBP_USD", "USD_JPY", "USD_CAD", "USD_CHF", "AUD_USD", "NZD_USD", "GBP_JPY"
    };

    public static void main(String[] args) throws Exception {
        Map<String, TreeMap<LocalDate, Double>> closes = new LinkedHashMap<>();
        for (String s : SYMBOLS) closes.put(s, dailyCloses(s, 2006, 2026));

        partA_Weekday(closes);
        partB_QuarterEnd(closes);
        partC_September(closes);
    }

    // ---------------------------------------------------------------- PART A

    private static void partA_Weekday(Map<String, TreeMap<LocalDate, Double>> closes) {
        System.out.println("##################################################################");
        System.out.println("# PART A — JOUR-DE-SEMAINE (rendement close→close du jour D)     #");
        System.out.println("##################################################################");
        for (var e : closes.entrySet()) {
            String sym = e.getKey();
            List<LocalDate> days = new ArrayList<>(e.getValue().keySet());
            Map<DayOfWeek, List<Double>> all = new EnumMap<>(DayOfWeek.class);
            Map<DayOfWeek, List<Double>> is = new EnumMap<>(DayOfWeek.class);
            Map<DayOfWeek, List<Double>> oos = new EnumMap<>(DayOfWeek.class);
            for (int i = 1; i < days.size(); i++) {
                LocalDate cur = days.get(i), prev = days.get(i - 1);
                double c0 = e.getValue().get(prev), c1 = e.getValue().get(cur);
                if (c0 <= 0) continue;
                double r = (c1 - c0) / c0;
                all.computeIfAbsent(cur.getDayOfWeek(), k -> new ArrayList<>()).add(r);
                (cur.getYear() <= 2015 ? is : oos)
                    .computeIfAbsent(cur.getDayOfWeek(), k -> new ArrayList<>()).add(r);
            }
            System.out.printf("%n--- %s ---%n", sym);
            System.out.println("DOW       |   n   avg%    hit   med%   t   |  IS avg/hit   | OOS avg/hit   | WF");
            for (DayOfWeek d : new DayOfWeek[]{DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                    DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY}) {
                List<Double> a = all.get(d), i = is.get(d), o = oos.get(d);
                System.out.printf("%-9s | %4d %+6.3f %5.0f%% %+6.3f %5.1f | %+6.3f %4.0f%% | %+6.3f %4.0f%% | %s%n",
                    d, a.size(), mean(a) * 100, hit(a) * 100, median(a) * 100, tstat(a),
                    mean(i) * 100, hit(i) * 100, mean(o) * 100, hit(o) * 100, wfTag(i, o));
            }
        }
    }

    // ---------------------------------------------------------------- PART B

    private static void partB_QuarterEnd(Map<String, TreeMap<LocalDate, Double>> closes) {
        System.out.println();
        System.out.println("##################################################################");
        System.out.println("# PART B — FIN DE TRIMESTRE (N derniers jours ouvrés Mar/Jun/Sep/Déc)#");
        System.out.println("##################################################################");
        int[] sizes = {1, 3, 5};
        for (var e : closes.entrySet()) {
            String sym = e.getKey();
            System.out.printf("%n--- %s ---%n", sym);
            for (int n : sizes) {
                List<Double> qAll = new ArrayList<>(), cAll = new ArrayList<>();
                List<Double> qIS = new ArrayList<>(), qOOS = new ArrayList<>();
                List<Double> cIS = new ArrayList<>(), cOOS = new ArrayList<>();
                Map<Integer, List<Double>> byQuarter = new TreeMap<>();
                for (MonthWindow w : monthWindows(e.getValue(), n)) {
                    boolean quarterEnd = w.month == 3 || w.month == 6 || w.month == 9 || w.month == 12;
                    if (quarterEnd) {
                        qAll.add(w.ret);
                        byQuarter.computeIfAbsent(w.month, k -> new ArrayList<>()).add(w.ret);
                        (w.year <= 2015 ? qIS : qOOS).add(w.ret);
                    } else {
                        cAll.add(w.ret);
                        (w.year <= 2015 ? cIS : cOOS).add(w.ret);
                    }
                }
                System.out.printf("N=%d (derniers %d jours ouvrés du mois)%n", n, n);
                System.out.printf("   TRI  n=%3d avg%+6.3f%% hit%4.0f%% med%+6.3f%% t%5.1f | IS %+6.3f%% hit%4.0f%% | OOS %+6.3f%% hit%4.0f%% | %s%n",
                    qAll.size(), mean(qAll) * 100, hit(qAll) * 100, median(qAll) * 100, tstat(qAll),
                    mean(qIS) * 100, hit(qIS) * 100, mean(qOOS) * 100, hit(qOOS) * 100, wfTag(qIS, qOOS));
                System.out.printf("   CTL  n=%3d avg%+6.3f%% hit%4.0f%% med%+6.3f%% t%5.1f | IS %+6.3f%% hit%4.0f%% | OOS %+6.3f%% hit%4.0f%% | %s%n",
                    cAll.size(), mean(cAll) * 100, hit(cAll) * 100, median(cAll) * 100, tstat(cAll),
                    mean(cIS) * 100, hit(cIS) * 100, mean(cOOS) * 100, hit(cOOS) * 100, wfTag(cIS, cOOS));
                System.out.printf("   Δ TRI-CTL = %+6.3f%%  |  par trimestre: ", (mean(qAll) - mean(cAll)) * 100);
                for (var q : byQuarter.entrySet())
                    System.out.printf("M%02d %+6.3f%% (n=%d)  ", q.getKey(), mean(q.getValue()) * 100, q.getValue().size());
                System.out.println();
            }
            // Début de trimestre (miroir)
            for (int n : new int[]{3}) {
                List<Double> qAll = new ArrayList<>(), cAll = new ArrayList<>();
                for (MonthWindow w : monthStartWindows(e.getValue(), n)) {
                    boolean quarterStart = w.month == 3 || w.month == 6 || w.month == 9 || w.month == 12;
                    (quarterStart ? qAll : cAll).add(w.ret);
                }
                System.out.printf("DÉBUT N=%d : TRI(DÉBUT du mois de trimestre) avg%+6.3f%% hit%4.0f%% (n=%d) | CTL %+6.3f%% hit%4.0f%% (n=%d)%n",
                    n, mean(qAll) * 100, hit(qAll) * 100, qAll.size(), mean(cAll) * 100, hit(cAll) * 100, cAll.size());
            }
        }
    }

    // ---------------------------------------------------------------- PART C

    private static void partC_September(Map<String, TreeMap<LocalDate, Double>> closes) {
        System.out.println();
        System.out.println("##################################################################");
        System.out.println("# PART C — SEPTEMBRE (mois courant, contexte)                     #");
        System.out.println("##################################################################");
        for (var e : closes.entrySet()) {
            List<Double> all = new ArrayList<>(), is = new ArrayList<>(), oos = new ArrayList<>();
            for (MonthWindow w : monthWindows(e.getValue(), 0)) {
                if (w.month != 9) continue;
                all.add(w.ret);
                (w.year <= 2015 ? is : oos).add(w.ret);
            }
            System.out.printf("%-9s n=%2d avg%+6.3f%% hit%4.0f%% med%+6.3f%% t%5.1f | IS %+6.3f%% hit%4.0f%% | OOS %+6.3f%% hit%4.0f%% | %s%n",
                e.getKey(), all.size(), mean(all) * 100, hit(all) * 100, median(all) * 100, tstat(all),
                mean(is) * 100, hit(is) * 100, mean(oos) * 100, hit(oos) * 100, wfTag(is, oos));
        }
    }

    // ------------------------------------------------------------- helpers

    private static final class MonthWindow {
        final int year, month; final double ret;
        MonthWindow(int y, int m, double r) { year = y; month = m; ret = r; }
    }

    /** Fenêtre des n derniers jours ouvrés du mois (n=0 → mois entier). */
    private static List<MonthWindow> monthWindows(TreeMap<LocalDate, Double> closeByDay, int n) {
        List<MonthWindow> out = new ArrayList<>();
        List<LocalDate> days = new ArrayList<>(closeByDay.keySet());
        int i = 0;
        while (i < days.size()) {
            LocalDate start = days.get(i);
            int y = start.getYear(), m = start.getMonthValue();
            List<LocalDate> monthDays = new ArrayList<>();
            int j = i;
            while (j < days.size() && days.get(j).getYear() == y && days.get(j).getMonthValue() == m) {
                monthDays.add(days.get(j)); j++;
            }
            int take = (n == 0) ? monthDays.size() : Math.min(n, monthDays.size());
            List<LocalDate> win = monthDays.subList(monthDays.size() - take, monthDays.size());
            if (i == 0) { i = j; continue; }   // pas de close de référence pour le 1er mois
            // Baseline = close du jour ouvre PRECEDANT le debut de la fenetre
            // (dans le meme mois si N < jours du mois, sinon dernier jour du mois precedent).
            int firstWinIdx = monthDays.size() - take;
            double c0 = closeByDay.get(days.get(i + firstWinIdx - 1));
            double c1 = closeByDay.get(win.get(win.size() - 1));
            if (c0 > 0) out.add(new MonthWindow(y, m, (c1 - c0) / c0));
            i = j;
        }
        return out;
    }

    /** Fenêtre des n premiers jours ouvrés du mois. */
    private static List<MonthWindow> monthStartWindows(TreeMap<LocalDate, Double> closeByDay, int n) {
        List<MonthWindow> out = new ArrayList<>();
        List<LocalDate> days = new ArrayList<>(closeByDay.keySet());
        int i = 1;
        while (i < days.size()) {
            LocalDate start = days.get(i);
            int y = start.getYear(), m = start.getMonthValue();
            List<LocalDate> monthDays = new ArrayList<>();
            int j = i;
            while (j < days.size() && days.get(j).getYear() == y && days.get(j).getMonthValue() == m) {
                monthDays.add(days.get(j)); j++;
            }
            int take = Math.min(n, monthDays.size());
            double c0 = closeByDay.get(days.get(i - 1));
            double c1 = closeByDay.get(monthDays.get(take - 1));
            if (c0 > 0) out.add(new MonthWindow(y, m, (c1 - c0) / c0));
            i = j;
        }
        return out;
    }

    private static TreeMap<LocalDate, Double> dailyCloses(String symbol, int from, int to) throws Exception {
        TreeMap<LocalDate, Double> map = new TreeMap<>();
        for (int y = from; y <= to; y++) {
            try {
                List<Bar> bars = HistoricalDataLoader.loadYear(symbol, y, Paths.get(BARS_DIR));
                if (bars == null) continue;
                for (Bar b : bars)
                    map.put(b.timestamp().atZone(ZoneId.of("UTC")).toLocalDate(), b.close());
            } catch (Exception ignored) { }
        }
        return map;
    }

    private static double mean(List<Double> v) {
        if (v == null || v.isEmpty()) return 0;
        return v.stream().mapToDouble(d -> d).average().orElse(0);
    }

    private static double hit(List<Double> v) {
        if (v == null || v.isEmpty()) return 0;
        return v.stream().filter(d -> d > 0).count() / (double) v.size();
    }

    private static double median(List<Double> v) {
        if (v == null || v.isEmpty()) return 0;
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    private static double tstat(List<Double> v) {
        if (v == null || v.size() < 3) return 0;
        double m = mean(v);
        double var = v.stream().mapToDouble(d -> (d - m) * (d - m)).sum() / (v.size() - 1);
        double sd = Math.sqrt(var);
        if (sd == 0) return 0;
        return m / (sd / Math.sqrt(v.size()));
    }

    private static String wfTag(List<Double> is, List<Double> oos) {
        if (is == null || oos == null || is.isEmpty() || oos.isEmpty()) return "n/a";
        double a = mean(is), b = mean(oos);
        if (a > 0 && b > 0) return a >= b ? "STABLE" : "S'AMÉLIORE";
        if (a < 0 && b < 0) return "NÉGATIF 2 CÔTÉS";
        return a > 0 ? "MEURT EN OOS" : "LATE-BLOOMER";
    }
}
