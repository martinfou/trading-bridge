package com.martinfou.trading.intelligence.research;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;

import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * CrossAssetWeekendFactorCheck — Pré-validation (Pattern D) INTERMARKET, jeudi 17 sept 2026 (38e résultat).
 *
 * 📊 CONTEXTE
 *   Le 16 sept (FxFridayFade, 37e résultat) a établi que le VENDREDI est NÉGATIF sur 7/8 paires FX,
 *   avec des magnitudes ORDONNÉES PAR LE RISQUE (GBP_JPY -0.056% … USD_CAD +0.005%). Conclusion :
 *   un FACTEUR COMMUN (prime de risque de week-end) et non un edge d'instrument — « même prime que le
 *   bid or du vendredi » (31 août). MAIS cette affirmation de facteur commun n'a JAMAIS été testée :
 *   elle repose sur une cohérence de SIGNES entre paires FX, pas sur une mesure de co-mouvement.
 *
 * 🎯 QUESTION INTERMARKET (tâche du jeudi = cross-asset)
 *   La prime de week-end est-elle UN facteur de risque GÉNÉRIQUE (donc présent aussi dans les ACTIONS),
 *   ou un facteur FX-SPÉCIFIQUE (positionnement/funding, pas sentiment de risque) ?
 *   Les ACTIONS (MES/MNQ D1, 2006-2026) — 3e classe d'actifs, jamais utilisée pour un effet calendaire
 *   dans ce pipeline — départagent : si le vendredi est un risk-off, le S&P doit baisser le vendredi
 *   (positionnement pré-week-end) et/ou le lundi (jambe week-end) ; s'il monte, le facteur n'est pas
 *   le sentiment de risque.
 *
 * MÉTHODE ANTI-ARTEFACT
 *   A. Jour-de-semaine ACTIONS : avg/hit/médiane/t + IS/OOS + **Δ vs la MOYENNE TOUS JOURS** (le drift
 *      des actions est le contrôle bêta obligatoire : un vendredi « positif » sur un actif qui monte
 *      de +500% n'est pas un effet de jour).
 *   B. Table cross-asset symétrique VENDREDI / LUNDI sur 9 actifs (FX + or) : la jambe pré-week-end
 *      (vendredi) et la jambe week-end (lundi) mesurées sur la MÊME base de close-à-close UTC.
 *   C. TEST DE FACTEUR : matrice de corrélation des rendements du VENDREDI vs contrôle MERCREDI
 *      (mêmes actifs, même échantillon de dates) — un facteur commun doit ÉLEVER la corrélation
 *      croisée le vendredi.
 *   D. Composite « basket refuge » F (or+, carry−, risk−, refuges−) : moyenne sur vendredi vs
 *      lundi-jeudi, t-stat, IS/OOS, et corrélation de F avec le S&P le vendredi = LE test décisif
 *      « un facteur » vs « deux facteurs ».
 *
 * ⚠️ Rappel : PRÉ-VALIDATION. Aucun verdict de tradeabilité sans backtest AVEC coûts
 *    (0.07$/trade + 0.01% slippage) et lecture du swap séparément.
 */
public class CrossAssetWeekendFactorCheck {

    private static final String BARS_DIR = "data/historical/bars";
    private static final Path FUT = Path.of("data/historical/futures");

    /** Signe de chaque actif dans le composite « risk-off » F (+1 = monte en risk-off). */
    private static final String[] FX = {
        "XAU_USD", "GBP_JPY", "AUD_USD", "NZD_USD", "GBP_USD", "EUR_USD", "USD_CAD", "USD_CHF", "USD_JPY"
    };
    private static final double[] F_SIGN = { +1, -1, -1, -1, -1, -1, +1, -1, -1 };

    private static final String[][] EQ = {
        { "MES", "MES_D1.csv", "S&P 500" },
        { "MNQ", "MNQ_D1.csv", "Nasdaq 100" }
    };

    public static void main(String[] args) throws Exception {
        System.out.println("=====================================================================");
        System.out.println("CROSS-ASSET WEEKEND FACTOR CHECK — jeudi 17 sept 2026 (38e, intermarket)");
        System.out.println("La prime de week-end (vendredi négatif sur 7/8 FX) est-elle UN facteur");
        System.out.println("de risque générique (visible dans les ACTIONS) ou FX-spécifique ?");
        System.out.println("=====================================================================");

        Map<String, TreeMap<LocalDate, Double>> fx = new LinkedHashMap<>();
        for (String s : FX) fx.put(s, dailyClosesBars(s, 2006, 2026));

        Map<String, TreeMap<LocalDate, Double>> eq = new LinkedHashMap<>();
        for (String[] e : EQ) eq.put(e[0], dailyClosesCsv(e[1]));

        partA_EquityWeekday(eq);
        partB_CrossAssetFriMon(fx);
        Map<String, TreeMap<LocalDate, Double>> all = new LinkedHashMap<>(fx);
        for (String[] e : EQ) all.put(e[0], eq.get(e[0]));
        partC_FactorMatrix(all);
        partD_BasketFactor(all);
        partE_WeekConditional(fx);
        partF_MondayDecomposition(fx);
    }

    // ===================== PART F — LE LUNDI RÉVERSE-T-IL LE FADE DU VENDREDI ?

    /**
     * Part E a montré (effet non prévu) que le LUNDI est un jour de RÉVERSION conditionnel à la
     * semaine précédente sur 7/8 paires FX (USD_CAD neutre = contrôle interne). Deux mécanismes
     * possibles : (a) réversion hebdomadaire générique, ou (b) RÉVERSION DU FADE DU VENDREDI
     * (le fade = réduction temporaire de risque → ré-accumulation le lundi). Décomposition 2×2 :
     *   F1 = rendement du vendredi précédent | W4 = rendement du lundi-jeudi précédent.
     */
    private static void partF_MondayDecomposition(Map<String, TreeMap<LocalDate, Double>> fx) {
        header("PART F — DÉCOMPOSITION DU LUNDI : réversion générique ou réversion DU FADE ?");
        System.out.println("F1 = rendement du vendredi précédent | W4 = rendement lundi-jeudi précédent.");
        System.out.println("Cellules : n | avg% lundi | [IS avg → OOS avg]   (le fade est « transitoire » si la");
        System.out.println("bounce est concentrée dans F1<0, quelle que soit W4)");
        for (String s : FX) {
            TreeMap<LocalDate, Double> m = fx.get(s);
            Map<LocalDate, Double> r = retsByDate(m);
            List<LocalDate> sess = new ArrayList<>();
            for (LocalDate d : m.keySet())
                if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) sess.add(d);
            Map<String, List<Double>> cells = new LinkedHashMap<>();
            Map<String, List<Double>> cellsIS = new LinkedHashMap<>();
            Map<String, List<Double>> cellsOOS = new LinkedHashMap<>();
            for (String k : new String[]{"F1>0|W4>0", "F1>0|W4<0", "F1<0|W4>0", "F1<0|W4<0"}) {
                cells.put(k, new ArrayList<>()); cellsIS.put(k, new ArrayList<>()); cellsOOS.put(k, new ArrayList<>());
            }
            for (int i = 6; i < sess.size(); i++) {
                LocalDate d = sess.get(i);
                if (d.getDayOfWeek() != DayOfWeek.MONDAY) continue;
                Double x = r.get(d);
                if (x == null) continue;
                Double f1 = r.get(sess.get(i - 1));       // vendredi précédent
                double cA = m.get(sess.get(i - 6)), cB = m.get(sess.get(i - 2));
                if (f1 == null || cA <= 0) continue;
                double w4 = (cB - cA) / cA;               // lundi-jeudi précédents
                String k = (f1 > 0 ? "F1>0" : "F1<0") + "|" + (w4 > 0 ? "W4>0" : "W4<0");
                cells.get(k).add(x);
                (d.getYear() <= 2015 ? cellsIS : cellsOOS).get(k).add(x);
            }
            System.out.printf("%n--- %s ---%n", s);
            for (String k : cells.keySet()) {
                List<Double> v = cells.get(k);
                if (v.isEmpty()) continue;
                System.out.printf("  %-10s n=%4d avg %+7.4f%% hit %3.0f%% t %5.2f | IS %+7.4f%% → OOS %+7.4f%% %s%n",
                    k, v.size(), mean(v) * 100, hit(v) * 100, tstat(v),
                    mean(cellsIS.get(k)) * 100, mean(cellsOOS.get(k)) * 100,
                    wfTag(cellsIS.get(k), cellsOOS.get(k)));
            }
        }
    }

    // ============================== PART E — CONDITIONNEL « DÉBOUCLAGE DE CARRY »

    /**
     * Si la prime du vendredi est un DÉBOUCLAGE DE POSITIONS (carry), elle doit être conditionnelle
     * à l'amplitude des 4 séances écoulées : une semaine qui a « porté » (W > 0) laisse plus à
     * déboucler qu'une semaine déjà perdante. W = rendement des 4 séances Mon-Ven PRÉCÉDANT le jour
     * cible (mesuré AVANT la séance → aucun look-ahead), appliqué IDENTIQUEMENT à tous les jours de
     * la semaine : si seul le vendredi réagit au signe de W, l'effet est bien vendredi-spécifique.
     */
    private static void partE_WeekConditional(Map<String, TreeMap<LocalDate, Double>> fx) {
        header("PART E — LE FADE EST-IL UN DÉBOUCLAGE DE CARRY ? (conditionnel aux 4 séances écoulées)");
        System.out.println("W = rendement des 4 séances précédentes (mesuré AVANT la séance cible).");
        System.out.println("Hypothèse positionnement : W > 0 → fade plus violent. Contrôle : mêmes jours, même split.");
        for (String s : FX) {
            TreeMap<LocalDate, Double> m = fx.get(s);
            Map<LocalDate, Double> r = retsByDate(m);
            List<LocalDate> sess = new ArrayList<>();
            for (LocalDate d : m.keySet())
                if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) sess.add(d);
            System.out.printf("%n--- %s ---%n", s);
            System.out.println("DOW       | W>0  n   avg%   hit    t   | W<0  n   avg%   hit    t   |    Δ");
            for (DayOfWeek dow : new DayOfWeek[]{DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                    DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY}) {
                List<Double> pos = new ArrayList<>(), neg = new ArrayList<>();
                for (int i = 5; i < sess.size(); i++) {
                    LocalDate d = sess.get(i);
                    if (d.getDayOfWeek() != dow) continue;
                    Double x = r.get(d);
                    if (x == null) continue;
                    double c0 = m.get(sess.get(i - 5)), c1 = m.get(sess.get(i - 1));
                    if (c0 <= 0) continue;
                    double w = (c1 - c0) / c0;
                    if (w > 0) pos.add(x); else if (w < 0) neg.add(x);
                }
                System.out.printf("%-9s | %4d %+7.4f%% %5.0f%% %5.2f | %4d %+7.4f%% %5.0f%% %5.2f | %+7.4f%%%n",
                    dow, pos.size(), mean(pos) * 100, hit(pos) * 100, tstat(pos),
                    neg.size(), mean(neg) * 100, hit(neg) * 100, tstat(neg),
                    (mean(pos) - mean(neg)) * 100);
            }
        }
    }

    // ============================================================= PART A — ACTIONS

    private static void partA_EquityWeekday(Map<String, TreeMap<LocalDate, Double>> eq) {
        header("PART A — JOUR-DE-SEMAINE SUR LES ACTIONS (D1, close→close)");
        System.out.println("Contrôle bêta intégré : Δ vs MOYENNE TOUS JOURS (le drift est la référence).");
        for (String[] meta : EQ) {
            String sym = meta[0];
            TreeMap<LocalDate, Double> m = eq.get(sym);
            List<LocalDate> days = new ArrayList<>(m.keySet());
            Map<DayOfWeek, List<Double>> all = new EnumMap<>(DayOfWeek.class);
            Map<DayOfWeek, List<Double>> is = new EnumMap<>(DayOfWeek.class);
            Map<DayOfWeek, List<Double>> oos = new EnumMap<>(DayOfWeek.class);
            List<Double> every = new ArrayList<>();
            for (int i = 1; i < days.size(); i++) {
                double c0 = m.get(days.get(i - 1)), c1 = m.get(days.get(i));
                if (c0 <= 0) continue;
                double r = (c1 - c0) / c0;
                DayOfWeek d = days.get(i).getDayOfWeek();
                all.computeIfAbsent(d, k -> new ArrayList<>()).add(r);
                every.add(r);
                (days.get(i).getYear() <= 2015 ? is : oos)
                    .computeIfAbsent(d, k -> new ArrayList<>()).add(r);
            }
            double base = mean(every);
            System.out.printf("%n--- %s (%s) : n=%d jours, dérive moyenne %+.4f%%/jour ---%n",
                sym, meta[2], every.size(), base * 100);
            System.out.println("DOW       |   n    avg%     Δdrift   hit   med%     t   |  IS avg   | OOS avg   | WF");
            for (DayOfWeek d : new DayOfWeek[]{DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                    DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY}) {
                List<Double> a = all.get(d), i = is.get(d), o = oos.get(d);
                if (a == null) continue;
                System.out.printf("%-9s | %4d  %+7.4f  %+7.4f %5.0f%% %+7.4f %6.2f | %+7.4f | %+7.4f | %s%n",
                    d, a.size(), mean(a) * 100, (mean(a) - base) * 100, hit(a) * 100, median(a) * 100,
                    tstat(a), mean(i) * 100, mean(o) * 100, wfTag(i, o));
            }
            // Décomposition du « week-end » : vendredi = jambe pré-week-end, lundi = jambe week-end
            System.out.printf("  >> JAMBES : vendredi avg %+.4f%% (Δ %+.4f) | lundi avg %+.4f%% (Δ %+.4f)%n",
                mean(all.get(DayOfWeek.FRIDAY)) * 100,
                (mean(all.get(DayOfWeek.FRIDAY)) - base) * 100,
                mean(all.get(DayOfWeek.MONDAY)) * 100,
                (mean(all.get(DayOfWeek.MONDAY)) - base) * 100);
        }
    }

    // ================================================= PART B — TABLE CROSS-ASSET VEN/LUN

    private static void partB_CrossAssetFriMon(Map<String, TreeMap<LocalDate, Double>> fx) {
        header("PART B — TABLE CROSS-ASSET VENDREDI vs LUNDI (9 actifs, close UTC)");
        System.out.println("Vendredi = jambe PRÉ-week-end (positionnement) | Lundi = jambe WEEK-END.");
        System.out.printf("%-9s | %-42s | %-42s%n", "ACTIF", "VENDREDI (n / avg% / hit / méd% / t)", "LUNDI (n / avg% / hit / méd% / t)");
        for (String s : FX) {
            TreeMap<LocalDate, Double> m = fx.get(s);
            List<Double> fri = new ArrayList<>(), mon = new ArrayList<>();
            List<Double> friIS = new ArrayList<>(), friOOS = new ArrayList<>();
            for (Map.Entry<LocalDate, Double> e : retsByDate(m).entrySet()) {
                LocalDate d = e.getKey();
                double r = e.getValue();
                if (d.getDayOfWeek() == DayOfWeek.FRIDAY) {
                    fri.add(r);
                    (d.getYear() <= 2015 ? friIS : friOOS).add(r);
                } else if (d.getDayOfWeek() == DayOfWeek.MONDAY) {
                    mon.add(r);
                }
            }
            System.out.printf("%-9s | %4d  %+6.3f%% %5.0f%% %+6.3f %5.1f | %4d  %+6.3f%% %5.0f%% %+6.3f %5.1f | FRI IS %+6.3f / OOS %+6.3f %s%n",
                s, fri.size(), mean(fri) * 100, hit(fri) * 100, median(fri) * 100, tstat(fri),
                mon.size(), mean(mon) * 100, hit(mon) * 100, median(mon) * 100, tstat(mon),
                mean(friIS) * 100, mean(friOOS) * 100, wfTag(friIS, friOOS));
        }
    }

    // ==================================================== PART C — MATRICE DE FACTEUR

    private static void partC_FactorMatrix(Map<String, TreeMap<LocalDate, Double>> all) {
        header("PART C — TEST DE FACTEUR : corrélation croisée VENDREDI vs contrôle MERCREDI");
        String[] keys = { "MES", "XAU_USD", "GBP_JPY", "EUR_USD", "AUD_USD", "USD_CHF", "USD_JPY" };
        Set<LocalDate> common = new TreeSet<>(all.get(keys[0]).keySet());
        for (String k : keys) common.retainAll(all.get(k).keySet());
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate d : common) if (d.isAfter(LocalDate.of(2006, 1, 3))) dates.add(d);

        // rendements par actif sur le calendrier commun
        Map<String, Map<LocalDate, Double>> r = new LinkedHashMap<>();
        for (String k : keys) {
            Map<LocalDate, Double> rr = new HashMap<>();
            TreeMap<LocalDate, Double> c = all.get(k);
            List<LocalDate> ds = new ArrayList<>(dates);
            for (int i = 1; i < ds.size(); i++) {
                double c0 = c.get(ds.get(i - 1)), c1 = c.get(ds.get(i));
                if (c0 > 0) rr.put(ds.get(i), (c1 - c0) / c0);
            }
            r.put(k, rr);
        }
        printMatrix("VENDREDI", r, keys, dates, DayOfWeek.FRIDAY);
        printMatrix("MERCREDI (contrôle)", r, keys, dates, DayOfWeek.WEDNESDAY);
    }

    private static void printMatrix(String label, Map<String, Map<LocalDate, Double>> r,
                                    String[] keys, List<LocalDate> dates, DayOfWeek dow) {
        List<LocalDate> sel = new ArrayList<>();
        for (LocalDate d : dates) if (d.getDayOfWeek() == dow) sel.add(d);
        System.out.printf("%n=== %s (n=%d dates communes) ===%n", label, sel.size());
        System.out.print("              ");
        for (String k : keys) System.out.printf("%9s", k);
        System.out.println();
        double sumAbs = 0; int cnt = 0;
        for (String a : keys) {
            System.out.printf("%-13s ", a);
            for (String b : keys) {
                double c = corr(r.get(a), r.get(b), sel);
                System.out.printf("%9.3f", c);
                if (!a.equals(b)) { sumAbs += Math.abs(c); cnt++; }
            }
            System.out.println();
        }
        System.out.printf(">>> |corrélation| moyenne (paires hors diagonale) : %.3f%n", cnt > 0 ? sumAbs / cnt : 0);
    }

    // ============================================ PART D — COMPOSITE « BASKET REFUGE »

    private static void partD_BasketFactor(Map<String, TreeMap<LocalDate, Double>> all) {
        header("PART D — COMPOSITE F (« risk-off basket ») : vendredi vs lundi-jeudi, IS/OOS, corr. S&P");
        System.out.println("F = moyenne des rendements STANDARDISÉS de {or +, GBP_JPY −, AUD −, NZD −, GBP −,");
        System.out.println("    EUR −, USD_CAD +, USD_CHF −, USD_JPY −}. F > 0 = vendredi risk-off.");
        Set<LocalDate> common = new TreeSet<>(all.get("XAU_USD").keySet());
        for (String k : FX) common.retainAll(all.get(k).keySet());
        common.retainAll(all.get("MES").keySet());
        List<LocalDate> dates = new ArrayList<>(common);

        Map<String, Map<LocalDate, Double>> r = new LinkedHashMap<>();
        for (String k : FX) r.put(k, retsByDate(all.get(k)));
        Map<LocalDate, Double> eqr = retsByDate(all.get("MES"));

        // standardisation sur tout l'échantillon
        Map<String, Double> sd = new LinkedHashMap<>();
        for (String k : FX) {
            List<Double> v = new ArrayList<>();
            for (LocalDate d : dates) { Double x = r.get(k).get(d); if (x != null) v.add(x); }
            sd.put(k, std(v));
        }
        TreeMap<LocalDate, Double> F = new TreeMap<>();
        for (LocalDate d : dates) {
            double s = 0; int n = 0;
            for (int i = 0; i < FX.length; i++) {
                Double x = r.get(FX[i]).get(d);
                if (x == null || sd.get(FX[i]) == 0) continue;
                s += F_SIGN[i] * x / sd.get(FX[i]);
                n++;
            }
            if (n == FX.length) F.put(d, s / n);
        }

        List<Double> fri = new ArrayList<>(), oth = new ArrayList<>();
        List<Double> friIS = new ArrayList<>(), friOOS = new ArrayList<>();
        for (var e : F.entrySet()) {
            if (e.getKey().getDayOfWeek() == DayOfWeek.FRIDAY) {
                fri.add(e.getValue());
                (e.getKey().getYear() <= 2015 ? friIS : friOOS).add(e.getValue());
            } else if (e.getKey().getDayOfWeek() != DayOfWeek.SATURDAY
                    && e.getKey().getDayOfWeek() != DayOfWeek.SUNDAY) {
                oth.add(e.getValue());
            }
        }
        System.out.printf("F VENDREDI : n=%d  avg %+.4f  méd %+.4f  hit>0 %3.0f%%  t %5.2f  | IS %+.4f → OOS %+.4f  %s%n",
            fri.size(), mean(fri), median(fri), hit(fri) * 100, tstat(fri),
            mean(friIS), mean(friOOS), wfTag(friIS, friOOS));
        System.out.printf("F LUN-JEU  : n=%d  avg %+.4f  méd %+.4f  hit>0 %3.0f%%  t %5.2f%n",
            oth.size(), mean(oth), median(oth), hit(oth) * 100, tstat(oth));

        // LE test décisif : le composite refuge est-il corrélé au S&P le vendredi ?
        for (DayOfWeek dow : new DayOfWeek[]{DayOfWeek.FRIDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.TUESDAY}) {
            List<LocalDate> sel = new ArrayList<>();
            for (LocalDate d : F.keySet()) if (d.getDayOfWeek() == dow && eqr.containsKey(d)) sel.add(d);
            Map<LocalDate, Double> fMap = new HashMap<>();
            for (LocalDate d : sel) fMap.put(d, F.get(d));
            double c = corr(fMap, eqr, sel);
            // alignement de signe S&P vs F (risk-off attendu : S&P < 0 quand F > 0)
            int agree = 0;
            for (LocalDate d : sel) if (Math.signum(F.get(d)) != Math.signum(eqr.get(d))) agree++;
            System.out.printf("  %-9s n=%4d | corr(F, S&P) = %+.3f | signes OPPOSÉS (risk-off cohérent) %3.0f%%%n",
                dow, sel.size(), c, 100.0 * agree / sel.size());
        }
    }

    // ================================================================ helpers

    private static void header(String t) {
        System.out.println();
        System.out.println("##################################################################");
        System.out.println("# " + t);
        System.out.println("##################################################################");
    }

    private static Map<LocalDate, Double> retsByDate(TreeMap<LocalDate, Double> m) {
        Map<LocalDate, Double> out = new HashMap<>();
        List<LocalDate> ds = new ArrayList<>(m.keySet());
        for (int i = 1; i < ds.size(); i++) {
            double c0 = m.get(ds.get(i - 1)), c1 = m.get(ds.get(i));
            if (c0 > 0) out.put(ds.get(i), (c1 - c0) / c0);
        }
        return out;
    }

    private static double corr(Map<LocalDate, Double> a, Map<LocalDate, Double> b, List<LocalDate> dates) {
        List<Double> x = new ArrayList<>(), y = new ArrayList<>();
        for (LocalDate d : dates) {
            Double u = a.get(d), v = b.get(d);
            if (u == null || v == null) continue;
            x.add(u); y.add(v);
        }
        if (x.size() < 10) return 0;
        double mx = mean(x), my = mean(y);
        double sxy = 0, sxx = 0, syy = 0;
        for (int i = 0; i < x.size(); i++) {
            double dx = x.get(i) - mx, dy = y.get(i) - my;
            sxy += dx * dy; sxx += dx * dx; syy += dy * dy;
        }
        if (sxx == 0 || syy == 0) return 0;
        return sxy / Math.sqrt(sxx * syy);
    }

    private static TreeMap<LocalDate, Double> dailyClosesBars(String symbol, int from, int to) throws Exception {
        TreeMap<LocalDate, Double> map = new TreeMap<>();
        for (int y = from; y <= to; y++) {
            try {
                List<Bar> bars = HistoricalDataLoader.loadYear(symbol, y, Paths.get(BARS_DIR));
                if (bars == null) continue;
                for (Bar b : bars)
                    map.put(b.timestamp().atZone(ZoneId.of("UTC")).toLocalDate(), b.close());
            } catch (Exception ignored) { }
        }
        System.out.printf("[load] %-9s %s → %s (%d jours)%n", symbol,
            map.isEmpty() ? "-" : map.firstKey(), map.isEmpty() ? "-" : map.lastKey(), map.size());
        return map;
    }

    private static TreeMap<LocalDate, Double> dailyClosesCsv(String csv) throws Exception {
        TreeMap<LocalDate, Double> map = new TreeMap<>();
        for (String line : Files.readAllLines(FUT.resolve(csv))) {
            String[] f = line.split(",");
            if (f.length < 5 || f[0].startsWith("Date")) continue;
            try { map.put(LocalDate.parse(f[0].trim()), Double.parseDouble(f[4])); }
            catch (Exception ignore) { }
        }
        System.out.printf("[load] %-9s %s → %s (%d jours)%n", csv,
            map.isEmpty() ? "-" : map.firstKey(), map.isEmpty() ? "-" : map.lastKey(), map.size());
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

    private static double std(List<Double> v) {
        if (v == null || v.size() < 3) return 0;
        double m = mean(v);
        double var = v.stream().mapToDouble(d -> (d - m) * (d - m)).sum() / (v.size() - 1);
        return Math.sqrt(var);
    }

    private static double tstat(List<Double> v) {
        if (v == null || v.size() < 3) return 0;
        double sd = std(v);
        if (sd == 0) return 0;
        return mean(v) / (sd / Math.sqrt(v.size()));
    }

    private static String wfTag(List<Double> is, List<Double> oos) {
        if (is == null || oos == null || is.isEmpty() || oos.isEmpty()) return "n/a";
        double a = mean(is), b = mean(oos);
        if (a > 0 && b > 0) return a >= b ? "STABLE" : "S'AMÉLIORE";
        if (a < 0 && b < 0) return "NÉGATIF 2 CÔTÉS";
        return a > 0 ? "MEURT EN OOS" : "LATE-BLOOMER";
    }
}
