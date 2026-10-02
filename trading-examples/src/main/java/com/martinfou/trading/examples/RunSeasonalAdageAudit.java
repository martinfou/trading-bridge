package com.martinfou.trading.examples;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.data.SeasonalityAnalyzer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

/**
 * RunSeasonalAdageAudit — vendredi 2 octobre 2026 (48e résultat, deep dive).
 *
 * PISTE OUVERTE PAR LE 46e : après avoir invalidé la bibliothèque de patterns
 * {@code trading-intelligence/research/SeasonalityFilter}, auditer la SECONDE
 * bibliothèque saisonnière — {@code trading-data/SeasonalityAnalyzer} — qui
 * revendique les adages classiques (Sell in May, Santa Claus Rally, January
 * Effect, September Slump) et alimente {@code WeeklyAnalysisRunner},
 * {@code MarketSentimentAggregator} et les outils de l'agent.
 *
 * A) INTÉGRITÉ — que mesure réellement {@code monthlyReturns} ? (source lue :
 *    l'agrégation « mensuelle » ajoute le rendement d'UNE barre H1, celle de la
 *    bascule de mois en America/New_York, sur les barres CONTINUES).
 * B) MESURE PROPRE de la matrice mensuelle : clôture de fin de mois sur barres
 *    RÉELLES ({@code high > low}), UTC, tous instruments (8 FX + or + 3 indices
 *    actions D1), IS 2006-2015 / OOS 2016-2026 + contrôle de dérive.
 * C) LES 6 FENÊTRES ADAGES (dont la « saison USD_CAD Sep→Déc » demandée par le
 *    46e) avec contrôle de dérive, de spécificité (+/−1 mois) et verdict.
 *
 * Aucune stratégie n'est codée : c'est une mesure de statistique calendaire.
 */
public class RunSeasonalAdageAudit {

    static final String[] FX = {
        "EUR_USD", "GBP_USD", "USD_JPY", "AUD_USD", "USD_CAD", "NZD_USD", "USD_CHF", "GBP_JPY"
    };
    static final String GOLD = "XAU_USD";
    static final String[][] FUT = {
        {"S&P500_MES", "MES_D1.csv"}, {"Nasdaq_MNQ", "MNQ_D1.csv"}, {"Russell_M2K", "M2K_D1.csv"}
    };
    static final String[] MONTHS = {
        "JANUARY", "FEBRUARY", "MARCH", "APRIL", "MAY", "JUNE",
        "JULY", "AUGUST", "SEPTEMBER", "OCTOBER", "NOVEMBER", "DECEMBER"
    };
    static final String[] MFR = {"janv", "févr", "mars", "avr", "mai", "juin",
        "juil", "août", "sept", "oct", "nov", "déc"};

    /** Coût d'un aller-retour FX (loi du seuil, 39e) en %. */
    static final double FX_RT_COST_PCT = 0.0214;

    // ── fenêtres adages : label, mois début, jour début, mois fin, jour fin, sens
    static final Object[][] WINDOWS = {
        {"Sell in May — long nov→avr", 11, 1, 4, 30, +1},
        {"May→oct (jambe courte)", 5, 1, 10, 31, -1},
        {"Santa Claus — déc24→jan3", 12, 24, 1, 3, +1},
        {"January Effect — janv seul", 1, 1, 1, 31, +1},
        {"September Slump — sept (short)", 9, 1, 9, 30, -1},
        {"Saison USD_CAD — sept→déc (46e)", 9, 1, 12, 31, +1},
    };

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--b")) { partB(loadAll()); return; }
        if (args.length > 0 && args[0].equals("--c")) { partC(loadAll()); return; }
        System.out.println("=====================================================================");
        System.out.println("48e — ADAGES SAISONNIERS : audit de la 2e bibliothèque + mesure propre");
        System.out.println("2 octobre 2026 | FX 8 paires + XAU + 3 indices D1 | barres RÉELLES (high>low)");
        System.out.println("=====================================================================");
        partA();
        Map<String, TreeMap<LocalDate, Double>> all = loadAll();
        partB(all);
        partC(all);
        partD(all);
        System.out.println("\nDONE");
    }

    // ============================================================ données

    static Map<String, TreeMap<LocalDate, Double>> loadAll() {
        Map<String, TreeMap<LocalDate, Double>> m = new LinkedHashMap<>();
        for (String s : FX) {
            try { m.put(s, dailyRealCloses(HistoricalDataLoader.loadFromArgs(s, s, "2006-2026").bars())); }
            catch (Exception e) { System.out.println("!! " + s + " : " + e.getMessage()); }
        }
        try { m.put(GOLD, dailyRealCloses(HistoricalDataLoader.loadFromArgs(GOLD, GOLD, "2006-2025").bars())); }
        catch (Exception e) { System.out.println("!! " + GOLD + " : " + e.getMessage()); }
        for (String[] f : FUT) {
            try { m.put(f[0], dailyClosesCsv(f[1])); }
            catch (Exception e) { System.out.println("!! " + f[0] + " : " + e.getMessage()); }
        }
        return m;
    }

    static TreeMap<LocalDate, Double> dailyRealCloses(List<Bar> bars) {
        TreeMap<LocalDate, Double> m = new TreeMap<>();
        for (Bar b : bars) {
            if (b.high() <= b.low()) continue;               // barre de carry : plate par construction
            m.put(b.timestamp().atZone(ZoneOffset.UTC).toLocalDate(), b.close());
        }
        return m;
    }

    static TreeMap<LocalDate, Double> dailyClosesCsv(String csv) throws Exception {
        TreeMap<LocalDate, Double> map = new TreeMap<>();
        for (String line : Files.readAllLines(Path.of("data/historical/futures").resolve(csv))) {
            String[] f = line.split(",");
            if (f.length < 5 || f[0].startsWith("Date")) continue;
            try { map.put(LocalDate.parse(f[0].trim()), Double.parseDouble(f[4])); } catch (Exception ignore) {}
        }
        return map;
    }

    // ============================================================ A) INTÉGRITÉ

    /** Que mesure monthlyReturns() ? Comparaison production vs vraie clôture de fin de mois. */
    static void partA() throws Exception {
        System.out.println("\n=== A) INTÉGRITÉ DE SeasonalityAnalyzer (trading-data) ===");
        System.out.println("Source : computeProfile() — l'agrégation mensuelle fait");
        System.out.println("  if (currTime.getMonth() != prevTime.getMonth()) monthlyReturns.add(ret)");
        System.out.println("  où ret = (curr.close - prev.close)/prev.close d'UNE SEULE barre H1");
        System.out.println("  (celle de la bascule de mois en America/New_York), sur les barres CONTINUES.");

        var an = new SeasonalityAnalyzer();
        for (String inst : new String[]{"EUR/USD", "USD/CAD", "XAU/USD"}) {
            SeasonalityAnalyzer.SeasonalProfile p;
            try { p = an.analyze(inst); }
            catch (Exception e) { System.out.println("\n--- " + inst + " : INDISPONIBLE (" + e.getMessage() + ")"); continue; }
            List<Bar> bars = an.loadBars(inst);
            String sym = inst.replace("/", "_");
            TreeMap<LocalDate, Double> real = dailyRealCloses(
                HistoricalDataLoader.loadFromArgs(sym, sym, inst.startsWith("XAU") ? "2006-2025" : "2006-2026").bars());

            System.out.printf("%n--- %s --- (%d barres brutes lues par l'analyseur, %d jours réels via HistoricalDataLoader)%n",
                inst, bars.size(), real.size());
            System.out.printf("annoncé : best=%s (%+.3f%%) | worst=%s (%+.3f%%) | avg/yr %+.2f%% | totalYears=%d%n",
                p.bestMonth(), p.monthlyReturns().getOrDefault(p.bestMonth(), 0.0),
                p.worstMonth(), p.monthlyReturns().getOrDefault(p.worstMonth(), 0.0),
                p.avgYearlyReturn(), p.totalYears());

            // A1 — intégrité des timestamps : décodage brut (bug) vs corrigé
            String fname = inst.equals("XAU/USD") ? "XAU_USD_H1.bars" : inst.replace("/", "_") + "_H1_H1.bars";
            Path rawPath = Path.of("data/historical/bars").resolve(fname);
            if (Files.exists(rawPath)) {
                byte[] rb = Files.readAllBytes(rawPath);
                long rawFirst = beLong(rb, 0), rawLast = beLong(rb, rb.length - 44);
                System.out.printf("A1) HORODATAGE BRUT : %,d → %,d  (unités : millisecondes epoch)%n", rawFirst, rawLast);
                System.out.printf("    décodage CORRECT (BarStore, /1 000) : %s → %s%n",
                    Instant.ofEpochSecond(rawFirst / 1000), Instant.ofEpochSecond(rawLast / 1000));
                System.out.printf("    décodage BUG (/1 000 000) : %s → %s  ⇒ %d barres dans un intervalle de %.0f minutes%n",
                    Instant.ofEpochSecond(rawFirst / 1_000_000), Instant.ofEpochSecond(rawLast / 1_000_000),
                    rb.length / 44, (rawLast - rawFirst) / 1_000_000.0 / 60);
                System.out.println("    (avant correctif : totalYears=1, best=worst=JANUARY, tous les hit rates à 0,"
                    + " dayOfWeek = un seul jour, hourOfDay = 2 heures)");
            }

            // A2 — hygiène de la source : granularité mixte + barres plates (carry)
            int m1 = 0, h1 = 0, flat = 0;
            for (int i = 1; i < bars.size(); i++) {
                long d = bars.get(i).timestamp().getEpochSecond() - bars.get(i - 1).timestamp().getEpochSecond();
                if (d == 60) m1++; else if (d == 3600) h1++;
            }
            for (Bar b : bars) if (b.high() <= b.low()) flat++;
            System.out.printf("A2) SOURCE : pas de 60 s = %d, pas de 3600 s = %d ⇒ le fichier fusionné MÉLANGE M1 et H1 ;%n",
                m1, h1);
            System.out.printf("    barres plates (high <= low, carry) = %d / %d = %.1f %%%n", flat, bars.size(),
                100.0 * flat / bars.size());

            // A3 — après correctif : les buckets mensuels sont-ils renseignés et crédibles ?
            int filled = 0, maxHit = 0;
            for (int w : p.monthlyWinRate().values()) { if (w > 0) filled++; maxHit = Math.max(maxHit, w); }
            System.out.printf("A3) APRÈS CORRECTIF : %d/12 mois renseignés (avant : 1 mois vide), hit max %d %%, "
                + "totalYears=%d (avant : 1)%n", filled, maxHit, p.totalYears());
            System.out.printf("    dayOfWeekReturns : %s%n", brief(p.dayOfWeekReturns()));
            System.out.printf("    hourOfDayReturns : %s%n", brief(p.hourOfDayReturns()));

            // A4 — ce que les chiffres devraient être (clôture de fin de mois, barres réelles)
            Map<Integer, List<Double>> trueRet = trueMonthlyReturns(real);
            System.out.printf("%-10s %-12s %-12s | %-12s %-10s%n",
                "MOIS", "annoncé %", "hit annoncé", "VRAI %", "hit vrai");
            String trueBest = null; double trueBestV = -1e9, trueWorstV = 1e9; String trueWorst = null;
            for (int m = 1; m <= 12; m++) {
                List<Double> v = trueRet.getOrDefault(m, List.of());
                double tm = avg(v);
                double tHit = v.isEmpty() ? 0 : 100.0 * v.stream().filter(x -> x > 0).count() / v.size();
                System.out.printf("%-10s %-12.3f %-12d | %-12.2f %-10.1f%n",
                    MFR[m - 1], p.monthlyReturns().getOrDefault(MONTHS[m - 1], 0.0),
                    p.monthlyWinRate().getOrDefault(MONTHS[m - 1], 0), tm, tHit);
                if (tm > trueBestV) { trueBestV = tm; trueBest = MONTHS[m - 1]; }
                if (tm < trueWorstV) { trueWorstV = tm; trueWorst = MONTHS[m - 1]; }
            }
            double ampAnn = p.monthlyReturns().values().stream().mapToDouble(v -> Math.abs(v)).average().orElse(0);
            double ampTrue = 0;
            for (int m = 1; m <= 12; m++) ampTrue += Math.abs(avg(trueRet.getOrDefault(m, List.of())));
            ampTrue /= 12.0;
            System.out.printf("→ amplitude moyenne des 12 mois : bibliothèque %.3f%% vs mesure propre %.3f%%  ⇒ écart ×%.2f%n",
                ampAnn, ampTrue, ampTrue / Math.max(1e-9, ampAnn));
            System.out.printf("→ best/worst : annoncé %s/%s vs VRAI %s (%+.2f%%) / %s (%+.2f%%)%n",
                p.bestMonth(), p.worstMonth(), trueBest, trueBestV, trueWorst, trueWorstV);
        }
    }

    /** Résumé compact d'une map de profils (3 plus fortes / 3 plus faibles valeurs). */
    static String brief(Map<String, Double> m) {
        if (m == null || m.isEmpty()) return "vide";
        List<Map.Entry<String, Double>> e = new ArrayList<>(m.entrySet());
        e.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, e.size()); i++) sb.append(e.get(i).getKey()).append(' ').append(String.format("%+.4f", e.get(i).getValue())).append("  ");
        sb.append("| ... | ");
        for (int i = Math.max(0, e.size() - 3); i < e.size(); i++) sb.append(e.get(i).getKey()).append(' ').append(String.format("%+.4f", e.get(i).getValue())).append("  ");
        return sb.toString();
    }

    // ============================================================ B) MATRICE MENSUELLE

    static void partB(Map<String, TreeMap<LocalDate, Double>> all) {
        System.out.println("\n=== B) MATRICE MENSUELLE PROPRE (fin de mois → fin de mois, barres réelles) ===");
        System.out.println("IS = 2006-2015 | OOS = 2016-2026 | ctrl = hold non conditionné de MÊME longueur (dérive)");
        System.out.println("bp = points de base (1 bp = 0.01 %) — seuil de survie FX A/R ≈ 2.1 bp (39e)");
        for (var e : all.entrySet()) {
            TreeMap<LocalDate, Double> closes = e.getValue();
            if (closes.size() < 500) continue;
            List<LocalDate> dates = new ArrayList<>(closes.keySet());
            Map<Integer, List<Double>> ret = trueMonthlyReturns(closes);
            System.out.printf("%n--- %s   %s → %s ---%n", e.getKey(),
                dates.get(0), dates.get(dates.size() - 1));
            System.out.printf("%-7s %-7s %-7s %-7s %-8s %-7s %-8s %-8s%n",
                "MOIS", "n", "mean%", "mean bp", "hit%", "t", "IS%", "OOS%");
            int bestM = -1; double bestV = -1e9;
            for (int m = 1; m <= 12; m++) {
                List<Double> v = ret.getOrDefault(m, List.of());
                if (v.isEmpty()) continue;
                double mean = avg(v);
                double[] s = stat(v);
                double[] isOos = isOosMonthly(closes, m);
                if (mean > bestV) { bestV = mean; bestM = m; }
                System.out.printf("%-7s %-7d %-7.2f %-7.1f %-7.1f %-7.2f %-8.2f %-8.2f%n",
                    MFR[m - 1], v.size(), mean, mean * 100, s[2], s[3], isOos[0], isOos[1]);
            }
            if (bestM > 0) {
                double ctrl = unconditional(closes, dates, 21, +1);
                System.out.printf("  meilleur mois : %s %+.2f%% | ctrl 21 j %+.2f%% | %s%n",
                    MFR[bestM - 1], bestV, ctrl,
                    Math.abs(bestV) > Math.abs(ctrl) ? "au-dessus de la dérive" : "SOUS la dérive (artefact)");
            }
        }
    }

    // ============================================================ C) ADAGES

    static void partC(Map<String, TreeMap<LocalDate, Double>> all) {
        System.out.println("\n=== C) LES 6 FENÊTRES ADAGES (contrôle de dérive + spécificité ±1 mois) ===");
        List<String> survivors = new ArrayList<>();
        for (Object[] w : WINDOWS) {
            String label = (String) w[0];
            int sm = (int) w[1], sd = (int) w[2], em = (int) w[3], ed = (int) w[4], sign = (int) w[5];
            System.out.printf("%n--- %s ---%n", label);
            System.out.printf("%-11s %-6s %-8s %-8s %-7s %-7s %-7s %-9s %-8s%n",
                "ACTIF", "n", "mean%", "mean bp", "hit%", "t", "All%", "IS/OOS", "ctrl%");
            for (var e : all.entrySet()) {
                TreeMap<LocalDate, Double> closes = e.getValue();
                if (closes.size() < 500) continue;
                List<LocalDate> dates = new ArrayList<>(closes.keySet());
                List<double[]> s = windowSeries(closes, dates, sm, sd, em, ed, sign, 0);
                if (s.size() < 8) continue;
                List<Double> vals = new ArrayList<>();
                for (double[] x : s) vals.add(x[1]);
                double[] allStats = stat(vals);
                double[] isSt = stat(yr(vals, s, 2006, 2015));
                double[] oosSt = stat(yr(vals, s, 2016, 2026));
                int len = (int) s.get(s.size() / 2)[2];
                double ctrl = unconditional(closes, dates, len, sign);
                boolean stable = Math.signum(isSt[1]) == Math.signum(oosSt[1]);
                boolean pos = isSt[1] > 0 && oosSt[1] > 0;
                boolean distinct = allStats[1] > ctrl;
                String flag = !pos ? "❌" : (!stable ? "❌ flip" : (!distinct ? "⚪ non dist." : "🔬 SURVIT"));
                System.out.printf("%-11s %-6d %-8.2f %-8.1f %-7.1f %-7.2f %-7.2f %-9s %-8.2f %s%n",
                    e.getKey(), vals.size(), allStats[1], allStats[1] * 100, allStats[2], allStats[3],
                    allStats[1], String.format("%+.2f/%+.2f", isSt[1], oosSt[1]), ctrl, flag);
                if (flag.startsWith("🔬")) survivors.add(label + " @ " + e.getKey());
            }
            // spécificité : la fenêtre décale-t-elle au mois voisin ?
            System.out.print("  spécificité (moyenne %, +/−1 mois) : ");
            for (int off : new int[]{-1, 0, +1}) {
                List<Double> pooled = new ArrayList<>();
                for (var e : all.entrySet()) {
                    TreeMap<LocalDate, Double> closes = e.getValue();
                    if (closes.size() < 500) continue;
                    List<LocalDate> dates = new ArrayList<>(closes.keySet());
                    for (double[] x : windowSeries(closes, dates, sm, sd, em, ed, sign, off)) pooled.add(x[1]);
                }
                double[] st = stat(pooled);
                System.out.printf("%+dm: %+.3f%% (t %.2f)   ", off, st[1], st[3]);
            }
            System.out.println();
        }
        System.out.println("\n--- SURVIVANTS ---");
        if (survivors.isEmpty()) System.out.println("AUCUN — aucun adage ne survit au triple test (IS/OOS + dérive).");
        else for (String s : survivors) System.out.println("  • " + s);
    }

    // ============================================================ helpers

    /**
     * D) L'adage « Sell in May » en SPREAD apparié : rendement long nov→avr MOINS rendement
     * long mai→oct, pour la MÊME année. C'est la formulation qui annule la dérive commune
     * (deux jambes de ~6 mois) et teste la saisonnalité RELATIVE.
     */
    static void partD(Map<String, TreeMap<LocalDate, Double>> all) {
        System.out.println("\n=== D) SELL IN MAY EN SPREAD APPARIÉ (nov→avr long − mai→oct long, même année) ===");
        System.out.println("Un spread > 0 = la fenêtre hiver-printemps bat la fenêtre été-automne, dérive annulée.");
        System.out.printf("%-12s %-5s %-9s %-7s %-7s %-8s %-10s %-8s%n",
            "ACTIF", "n", "spread%", "t", "hit%", "IS%", "OOS%", "verdict");
        for (var e : all.entrySet()) {
            TreeMap<LocalDate, Double> closes = e.getValue();
            if (closes.size() < 500) continue;
            List<LocalDate> dates = new ArrayList<>(closes.keySet());
            List<double[]> winter = windowSeries(closes, dates, 11, 1, 4, 30, +1, 0);
            List<double[]> summer = windowSeries(closes, dates, 5, 1, 10, 31, +1, 0);
            Map<Integer, Double> w = new HashMap<>(), s = new HashMap<>();
            for (double[] x : winter) w.put((int) x[0], x[1]);
            for (double[] x : summer) s.put((int) x[0], x[1]);
            List<double[]> diff = new ArrayList<>();
            List<Double> allV = new ArrayList<>(), isV = new ArrayList<>(), oosV = new ArrayList<>();
            List<Integer> yrs = new ArrayList<>(w.keySet());
            Collections.sort(yrs);
            for (int y : yrs) {
                if (!s.containsKey(y)) continue;
                double d = w.get(y) - s.get(y);
                diff.add(new double[]{y, d});
                allV.add(d);
                (y <= 2015 ? isV : oosV).add(d);
            }
            if (allV.size() < 8) continue;
            double[] st = stat(allV);
            double is = avg(isV), oos = avg(oosV);
            boolean stable = Math.signum(is) == Math.signum(oos);
            String v = (st[1] > 0 && Math.abs(st[3]) >= 2.0 && stable) ? "🔬 tilt réel"
                : (st[1] > 0 && stable ? "⚪ tilt faible" : "❌ pas de tilt");
            System.out.printf("%-12s %-5d %-9.2f %-7.2f %-7.1f %-8.2f %-10.2f %-8s%n",
                e.getKey(), allV.size(), st[1], st[3], st[2], is, oos, v);
        }
    }

    /** Rendements mensuels vrais : clôture de fin de mois sur barres réelles. */
    static Map<Integer, List<Double>> trueMonthlyReturns(TreeMap<LocalDate, Double> closes) {
        Map<YearMonth, Double> me = new TreeMap<>();
        for (var e : closes.entrySet()) me.put(YearMonth.from(e.getKey()), e.getValue());
        Map<Integer, List<Double>> out = new LinkedHashMap<>();
        YearMonth prev = null;
        for (var e : me.entrySet()) {
            if (prev != null && prev.plusMonths(1).equals(e.getKey())) {
                double a = me.get(prev), b = e.getValue();
                if (a > 0) out.computeIfAbsent(e.getKey().getMonthValue(), k -> new ArrayList<>())
                    .add((b / a - 1.0) * 100.0);
            }
            prev = e.getKey();
        }
        return out;
    }

    /** IS/OOS d'un mois : {IS mean%, OOS mean%}. */
    static double[] isOosMonthly(TreeMap<LocalDate, Double> closes, int month) {
        Map<YearMonth, Double> me = new TreeMap<>();
        for (var e : closes.entrySet()) me.put(YearMonth.from(e.getKey()), e.getValue());
        List<Double> is = new ArrayList<>(), oos = new ArrayList<>();
        YearMonth prev = null;
        for (var e : me.entrySet()) {
            if (prev != null && prev.plusMonths(1).equals(e.getKey()) && e.getKey().getMonthValue() == month) {
                double a = me.get(prev), b = e.getValue();
                if (a > 0) {
                    double r = (b / a - 1.0) * 100.0;
                    (e.getKey().getYear() <= 2015 ? is : oos).add(r);
                }
            }
            prev = e.getKey();
        }
        return new double[]{avg(is), avg(oos)};
    }

    /**
     * Série {année, rendement%, longueur en jours de bourse} d'une fenêtre calendaire.
     * endMonth <= startMonth ⇒ la fenêtre traverse l'année (nov→avr). offsetMois décale
     * les DEUX bornes (contrôle de spécificité).
     */
    static List<double[]> windowSeries(TreeMap<LocalDate, Double> closes, List<LocalDate> dates,
                                      int sm, int sd, int em, int ed, int sign, int offsetMois) {
        List<double[]> out = new ArrayList<>();
        int y0 = dates.get(0).getYear(), y1 = dates.get(dates.size() - 1).getYear();
        LocalDate last = dates.get(dates.size() - 1);
        for (int y = y0; y <= y1; y++) {
            LocalDate start = LocalDate.of(y, sm, Math.min(sd, 28)).plusMonths(offsetMois);
            LocalDate endIntended = LocalDate.of(y + (em < sm ? 1 : 0), em, Math.min(ed, 28)).plusMonths(offsetMois);
            LocalDate entry = floorOnOrBefore(dates, start);
            LocalDate exit = floorOnOrBefore(dates, endIntended);
            if (entry == null || exit == null || !exit.isAfter(entry)) continue;
            // fenêtre complète seulement : la sortie réelle doit être proche de la borne voulue
            if (Math.abs(java.time.temporal.ChronoUnit.DAYS.between(exit, endIntended)) > 12) continue;
            if (exit.isAfter(last.minusDays(10))) continue;
            double a = closes.get(entry), b = closes.get(exit);
            if (a <= 0) continue;
            int len = (int) dates.stream().filter(d -> !d.isBefore(entry) && !d.isAfter(exit)).count();
            out.add(new double[]{y, sign * (b / a - 1.0) * 100.0, len});
        }
        return out;
    }

    static List<Double> yr(List<Double> vals, List<double[]> src, int y0, int y1) {
        List<Double> out = new ArrayList<>();
        for (int i = 0; i < src.size(); i++) if (src.get(i)[0] >= y0 && src.get(i)[0] <= y1) out.add(vals.get(i));
        return out;
    }

    static double[] stat(List<Double> v) {
        if (v.isEmpty()) return new double[]{0, 0, 0, 0};
        double mean = avg(v);
        long hits = v.stream().filter(x -> x > 0).count();
        double var = v.stream().mapToDouble(x -> (x - mean) * (x - mean)).sum() / Math.max(1, v.size() - 1);
        double t = v.size() > 1 ? Math.sqrt(v.size()) * mean / Math.max(1e-9, Math.sqrt(var)) : 0;
        return new double[]{v.size(), mean, 100.0 * hits / v.size(), t};
    }

    static double avg(List<Double> v) {
        if (v.isEmpty()) return 0;
        return v.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    /** Contrôle de dérive : moyenne de TOUS les holds de len jours, non conditionnés, signés. */
    static double unconditional(TreeMap<LocalDate, Double> closes, List<LocalDate> dates, int len, int sign) {
        double sum = 0; int n = 0;
        for (int i = 0; i + len < dates.size(); i++) {
            double a = closes.get(dates.get(i)), b = closes.get(dates.get(i + len));
            if (a <= 0) continue;
            sum += sign * (b / a - 1.0) * 100.0; n++;
        }
        return n == 0 ? 0 : sum / n;
    }

    static LocalDate floorOnOrBefore(List<LocalDate> sorted, LocalDate d) {
        LocalDate best = null;
        for (LocalDate x : sorted) { if (!x.isAfter(d)) best = x; else break; }
        return best;
    }

    /** Lecture big-endian d'un long (format .bars : 44 octets, ts ms + 4 doubles + int). */
    static long beLong(byte[] d, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (d[off + i] & 0xFFL);
        return v;
    }
}
