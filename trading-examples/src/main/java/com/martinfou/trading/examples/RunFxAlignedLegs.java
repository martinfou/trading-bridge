package com.martinfou.trading.examples;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;

import java.time.*;
import java.util.*;

/**
 * RunFxAlignedLegs — VENDREDI 9 oct 2026 (53e résultat, DEEP DIVE du vendredi).
 *
 * CONTEXTE — le 50e (mardi 6 oct) a décomposé le fade du vendredi FX en 7 JAMBES DE DEVISES par
 * moindres carrés et conclu : « le porteur optimal est la JAMBE REFUGE (CHF +3.24 bp, t 1.72 ;
 * JPY +0.68), pas la jambe GBP » ; les crosses risque/refuge (GBP_CHF +9.08 bp, t 5.13) capteraient
 * 1.4-1.7x l'edge des paires incumbentes. Le 50e a LUI-MÊME mesuré un « PLANCHER DE BRUIT » de
 * 13.9 bp sur le triangle r(GBP_JPY) − r(GBP_USD) − r(USD_JPY), soit DEUX FOIS l'edge revendiqué,
 * sans en tirer de conséquence sur sa propre table de jambes.
 *
 * LE 52e (jeudi 8 oct) a montré que ce plancher est un ARTEFACT DE CONSTRUCTION : sous une grille
 * ALIGNÉE (même barre H1 pour toutes les jambes), le résidu tombe à ~2.5 bp et le podium des
 * crosses refuges s'effondre (GBP_CHF +9.08 → +1.15, EUR_CHF +6.93 → −1.14).
 *
 * QUESTION DU JOUR — mais alors, la TABLE DE JAMBES du 50e (la pièce qui a nommé le « refuge
 * porteur ») a-t-elle été mesurée, elle aussi, sous la construction CONTAMINÉE ? Si oui, le
 * « porteur refuge » est un artefact d'horodatage et il faut re-nommer le mécanisme.
 *
 * MÉTHODE (étude statistique, aucun backtest moteur — infra minimale trading-core/data) :
 *   A) construction du pipeline (dernière barre RÉELLE du jour UTC de CHAQUE paire) — reproduit 50e.
 *   B) grille PARTAGÉE (dernière heure commune aux 8 paires ce jour-là) — les 7 jambes sont alors
 *      estimées sur des rendements qui couvrent le MÊME intervalle.
 *   P0) calibration : profil vendredi des 8 paires sous A doit redonner GBP_JPY -5.6113 bp (50e).
 *   FLOOR) résidu des moindres carrés sous A vs B + dates « sales » (une jambe saute un jour).
 *   LEGS) profil vendredi des 7 jambes sous A et sous B (bp, t, médiane, hit, Mon-Jeu, IS/OOS,
 *      décennies) → LE PORTEUR CHANGE-T-IL DE NOM ?
 *   FACTOR) panier RISQUE (AUD+GBP+NZD+EUR) − panier REFUGE (CHF+JPY) sous A et sous B.
 *   HOURS) contrôle : même heure FIXE (20:00 UTC) pour les 8 paires — le profil de jambes
 *      dépend-il de l'heure de lecture ?
 *
 * Usage : java -cp "$CP" com.martinfou.trading.examples.RunFxAlignedLegs [--all|--p0|--floor|--legs|--hours]
 */
public class RunFxAlignedLegs {

    static final String YEAR_SPEC = "2006-2026";
    static final String[] ALL8 = {"GBP_JPY", "GBP_USD", "AUD_USD", "NZD_USD", "USD_CHF", "EUR_USD", "USD_JPY", "USD_CAD"};

    // Système aux moindres carrés : 8 équations, 7 inconnues (leg(USD) = 0).
    static final String[] LSPAIRS = {"EUR_USD", "GBP_USD", "AUD_USD", "NZD_USD", "USD_JPY", "USD_CHF", "USD_CAD", "GBP_JPY"};
    static final String[] CCY = {"EUR", "GBP", "JPY", "CHF", "AUD", "NZD", "CAD"};
    static final int NC = 7;
    static final double[][] A = buildA();
    static final int FIXED_HOUR = 20;   // UTC, contrôle « même heure pour tous »

    static double[][] buildA() {
        double[][] a = new double[LSPAIRS.length][NC];
        a[0][0] = 1;                 // EUR_USD = EUR
        a[1][1] = 1;                 // GBP_USD = GBP
        a[2][4] = 1;                 // AUD_USD = AUD
        a[3][5] = 1;                 // NZD_USD = NZD
        a[4][2] = -1;                // USD_JPY = -JPY
        a[5][3] = -1;                // USD_CHF = -CHF
        a[6][6] = -1;                // USD_CAD = -CAD
        a[7][1] = 1; a[7][2] = -1;   // GBP_JPY = GBP - JPY
        return a;
    }

    /** Clôtures réelles par heure-époque (h depuis epoch), par paire. */
    static final Map<String, TreeMap<Long, Double>> HC = new LinkedHashMap<>();
    /** A — dernière barre réelle du jour UTC. */
    static final Map<String, TreeMap<LocalDate, Double>> CL_A = new LinkedHashMap<>();
    /** B — barre de la dernière heure commune aux 8 paires, par jour. */
    static final Map<String, TreeMap<LocalDate, Double>> CL_B = new LinkedHashMap<>();
    /** H — barre de 20:00 UTC, par jour (contrôle heure fixe). */
    static final Map<String, TreeMap<LocalDate, Double>> CL_H = new LinkedHashMap<>();
    static final TreeMap<LocalDate, Long> GRID = new TreeMap<>();

    static final Map<String, TreeMap<LocalDate, Double>> RET_A = new LinkedHashMap<>();
    static final Map<String, TreeMap<LocalDate, Double>> RET_B = new LinkedHashMap<>();
    static final Map<String, TreeMap<LocalDate, Double>> RET_H = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "--all";
        System.out.println("========================================================================");
        System.out.println("LE « PORTEUR REFUGE » DU 50e SURVIT-IL A LA GRILLE PARTAGEE ?");
        System.out.println("Vendredi 9 oct 2026 (53e) · DEEP DIVE · jambes de devises sous A (pipeline) vs B (aligne)");
        System.out.println("========================================================================");
        load();
        switch (mode) {
            case "--p0" -> p0();
            case "--floor" -> floor();
            case "--legs" -> legs();
            case "--hours" -> hours();
            case "--cross" -> { if (legsByTag.isEmpty()) legs(); cross(); }
            case "--all" -> { p0(); floor(); legs(); factor(); hours(); cross(); verdict(); }
            default -> p0();
        }
        System.out.println("\nDONE");
    }

    // ------------------------------------------------------------------ données

    static void load() throws Exception {
        for (String sym : ALL8) {
            List<Bar> bars;
            try {
                bars = HistoricalDataLoader.loadFromArgs(sym, sym, YEAR_SPEC).bars();
            } catch (Exception e) {
                System.out.println("[data] ECHEC " + sym + " : " + e.getMessage());
                continue;
            }
            if (bars == null || bars.isEmpty()) { System.out.println("[data] VIDE " + sym); continue; }
            bars.sort(Comparator.comparing(Bar::timestamp));
            TreeMap<Long, Double> hc = new TreeMap<>();
            TreeMap<LocalDate, Double> a = new TreeMap<>();
            for (Bar b : bars) {
                if (b.high() <= b.low()) continue;                       // barre de carry (40e)
                long h = b.timestamp().getEpochSecond() / 3600;
                LocalDate d = LocalDate.ofEpochDay(h / 24);              // heure-époque -> jour UTC
                hc.put(h, b.close());
                a.put(d, b.close());                                     // A : la DERNIÈRE gagne
            }
            HC.put(sym, hc);
            CL_A.put(sym, a);
            System.out.printf("[data] %-9s %6d barres réelles · %5d jours · %s → %s%n",
                sym, hc.size(), a.size(), a.firstKey(), a.lastKey());
        }

        // --- grille partagée : par jour, la dernière heure présente dans LES 8 paires
        Map<LocalDate, Map<String, Set<Long>>> byDate = new TreeMap<>();
        for (String sym : ALL8) {
            for (Long h : HC.getOrDefault(sym, new TreeMap<>()).keySet()) {
                LocalDate d = LocalDate.ofEpochDay(h / 24);
                byDate.computeIfAbsent(d, k -> new LinkedHashMap<>())
                      .computeIfAbsent(sym, k -> new TreeSet<>()).add(h);
            }
        }
        for (var e : byDate.entrySet()) {
            Map<String, Set<Long>> m = e.getValue();
            if (m.size() < ALL8.length) continue;
            Set<Long> inter = null;
            for (String s : ALL8) {
                Set<Long> hs = m.get(s);
                if (hs == null) { inter = null; break; }
                if (inter == null) inter = new TreeSet<>(hs); else inter.retainAll(hs);
            }
            if (inter == null || inter.isEmpty()) continue;
            GRID.put(e.getKey(), ((TreeSet<Long>) inter).last());
        }
        long gridMin = GRID.isEmpty() ? 0 : GRID.values().stream().min(Long::compare).orElse(0L);
        long gridMax = GRID.isEmpty() ? 0 : GRID.values().stream().max(Long::compare).orElse(0L);
        System.out.printf("[aligné] grille partagée 8 paires : %d jours · heures %02d:00 → %02d:00 UTC%n",
            GRID.size(), gridMin % 24, gridMax % 24);
        // histogramme heure × jour-de-semaine de la grille (le contrôle qui dit ce qu'on mesure vraiment)
        TreeMap<String, Integer> hist = new TreeMap<>();
        TreeMap<Integer, Integer> byHour = new TreeMap<>();
        for (var e : GRID.entrySet()) {
            int hh = (int) (e.getValue() % 24);
            byHour.merge(hh, 1, Integer::sum);
            hist.merge(e.getKey().getDayOfWeek() + "@" + hh, 1, Integer::sum);
        }
        StringBuilder hb = new StringBuilder();
        for (var e : byHour.entrySet()) hb.append(String.format("%02d:00=%d  ", e.getKey(), e.getValue()));
        System.out.println("[grille] heure de grille, tous jours : " + hb);
        for (DayOfWeek d : DayOfWeek.values()) {
            StringBuilder sb = new StringBuilder();
            int tot = 0;
            for (int hh = 0; hh < 24; hh++) {
                Integer c = hist.get(d + "@" + hh);
                if (c != null) { sb.append(String.format("%02d:00=%d ", hh, c)); tot += c; }
            }
            System.out.printf("[grille] %-9s n=%4d  %s%n", d, tot, sb);
        }

        for (String sym : ALL8) {
            TreeMap<LocalDate, Double> b = new TreeMap<>();
            TreeMap<LocalDate, Double> h = new TreeMap<>();
            for (var e : GRID.entrySet()) {
                Double c = HC.get(sym).get(e.getValue());
                if (c != null) b.put(e.getKey(), c);
                Double cf = HC.get(sym).get(e.getKey().toEpochDay() * 24L + FIXED_HOUR);
                if (cf != null) h.put(e.getKey(), cf);
            }
            CL_B.put(sym, b);
            CL_H.put(sym, h);
        }
        for (String sym : ALL8) {
            RET_A.put(sym, ret(CL_A.get(sym)));
            RET_B.put(sym, ret(CL_B.get(sym)));
            RET_H.put(sym, ret(CL_H.get(sym)));
        }
    }

    static TreeMap<LocalDate, Double> ret(TreeMap<LocalDate, Double> s) {
        TreeMap<LocalDate, Double> r = new TreeMap<>();
        Double pc = null;
        for (var e : s.entrySet()) {
            if (pc != null && pc > 0 && e.getValue() > 0) {
                r.put(e.getKey(), 10000.0 * Math.log(e.getValue() / pc));   // bp
            }
            pc = e.getValue();
        }
        return r;
    }

    // ------------------------------------------------------------------ stats

    static final class St { double mean; int n; double med; double hitNeg; double t; }

    static St stat(List<Double> v) {
        St s = new St();
        s.n = v.size();
        if (s.n == 0) return s;
        double sum = 0; int neg = 0;
        for (double x : v) { sum += x; if (x < 0) neg++; }
        s.mean = sum / s.n;
        s.hitNeg = 100.0 * neg / s.n;
        List<Double> c = new ArrayList<>(v);
        Collections.sort(c);
        s.med = c.get(c.size() / 2);
        if (s.n > 1) {
            double ss = 0;
            for (double x : v) ss += (x - s.mean) * (x - s.mean);
            double sd = Math.sqrt(ss / (s.n - 1));
            s.t = sd > 0 ? s.mean / (sd / Math.sqrt(s.n)) : 0;
        }
        return s;
    }

    static List<Double> pick(TreeMap<LocalDate, Double> s, DayOfWeek dow) { return pick(s, dow, 2006, 2026); }

    static List<Double> pick(TreeMap<LocalDate, Double> s, DayOfWeek dow, int y0, int y1) {
        List<Double> out = new ArrayList<>();
        for (var e : s.entrySet()) {
            int y = e.getKey().getYear();
            if (y < y0 || y > y1) continue;
            if (e.getKey().getDayOfWeek() == dow) out.add(e.getValue());
        }
        return out;
    }

    static List<Double> pickMonThu(TreeMap<LocalDate, Double> s) {
        List<Double> out = new ArrayList<>();
        for (var e : s.entrySet()) {
            DayOfWeek d = e.getKey().getDayOfWeek();
            if (d != DayOfWeek.SATURDAY && d != DayOfWeek.SUNDAY && d != DayOfWeek.FRIDAY) out.add(e.getValue());
        }
        return out;
    }

    static void section(String s) {
        System.out.println("\n" + "=".repeat(72));
        System.out.println(s);
        System.out.println("=".repeat(72));
    }

    static void line(String fmt, Object... a) { System.out.printf(fmt + "%n", a); }

    // ------------------------------------------------------------------ A. P0

    static void p0() {
        section("P0) CALIBRATION — profil VENDREDI des 8 paires (retour de SESSION, clôtures UTC)");
        line("Réf 50e (construction A) : GBP_JPY -5.6113 · GBP_USD -5.3390 · AUD -5.0796 · NZD -3.4304 · USD_CHF -3.6173");
        line("%-9s %11s %7s %10s %9s %7s   %11s %7s %9s %7s", "PAIRE", "A: VEN bp", "n", "médiane", "hit<0%", "t", "B: VEN bp", "n", "médiane", "t");
        for (String sym : ALL8) {
            St a = stat(pick(RET_A.get(sym), DayOfWeek.FRIDAY));
            St b = stat(pick(RET_B.get(sym), DayOfWeek.FRIDAY));
            line("%-9s %+11.4f %7d %+10.4f %8.0f%% %+7.2f   %+11.4f %7d %+9.4f %+7.2f",
                sym, a.mean, a.n, a.med, a.hitNeg, a.t, b.mean, b.n, b.med, b.t);
        }
        line("\nLecture : sous A la clôture de chaque paire est sa DERNIÈRE barre réelle ; sous B c'est la");
        line("dernière heure COMMUNE aux 8 paires. Si le profil se déplace, la table du 50e mesurait l'heure.");
    }

    // ------------------------------------------------------------------ FLOOR

    static void floor() {
        section("FLOOR) RÉSIDU DES MOINDRES CARRÉS — A vs B, et les dates « sales »");
        for (String tag : new String[]{"A", "B"}) {
            Map<String, TreeMap<LocalDate, Double>> ret = tag.equals("A") ? RET_A : RET_B;
            List<LocalDate> dates = commonDates(ret);
            double sum2 = 0, max = 0; int big = 0, nres = 0;
            double[][] ata = ata();
            for (LocalDate d : dates) {
                double[] b = new double[LSPAIRS.length];
                boolean ok = true;
                for (int i = 0; i < LSPAIRS.length; i++) {
                    Double v = ret.get(LSPAIRS[i]).get(d);
                    if (v == null) { ok = false; break; }
                    b[i] = v;
                }
                if (!ok) continue;
                double[] atb = new double[NC];
                for (int i = 0; i < NC; i++) { double s = 0; for (int r = 0; r < A.length; r++) s += A[r][i] * b[r]; atb[i] = s; }
                double[] x = solve(ata, atb);
                for (int r = 0; r < A.length; r++) {
                    double fit = 0; for (int i = 0; i < NC; i++) fit += A[r][i] * x[i];
                    double rr = b[r] - fit; sum2 += rr * rr; max = Math.max(max, Math.abs(rr)); nres++;
                    if (Math.abs(rr) > 100) big++;
                }
            }
            double rms = Math.sqrt(sum2 / Math.max(1, nres));
            line("[%s] %d dates · %d résidus · résidu RMS %.4f bp · résidu max %.2f bp · résidus > 100 bp : %d",
                tag, dates.size(), nres, rms, max, big);
        }
        // dates « sales » : une paire du système n'a pas de retour (jour sauté)
        List<LocalDate> dirty = new ArrayList<>();
        List<LocalDate> datesB = commonDates(RET_B);
        for (LocalDate d : datesB) {
            boolean clean = true;
            for (String sym : ALL8) if (RET_A.get(sym).get(d) == null) { clean = false; break; }
            if (!clean) dirty.add(d);
        }
        line("%nDates présentes en B mais ABSENTES en A (un jour sauté dans au moins une paire) : %d", dirty.size());
        int shown = 0;
        for (LocalDate d : dirty) {
            if (shown++ >= 8) break;
            StringBuilder sb = new StringBuilder();
            for (String sym : ALL8) if (RET_A.get(sym).get(d) == null) sb.append(sym).append(" ");
            line("   %s : manque %s", d, sb.toString());
        }
        if (dirty.size() > 8) line("   … et %d autres", dirty.size() - 8);
    }

    static List<LocalDate> commonDates(Map<String, TreeMap<LocalDate, Double>> ret) {
        List<LocalDate> dates = null;
        for (String sym : LSPAIRS) {
            List<LocalDate> ks = new ArrayList<>(ret.get(sym).keySet());
            if (dates == null) dates = ks;
            else { Set<LocalDate> s = new HashSet<>(ks); dates.removeIf(d -> !s.contains(d)); }
        }
        Collections.sort(dates);
        return dates;
    }

    static double[][] ata() {
        double[][] ata = new double[NC][NC];
        for (double[] row : A) for (int i = 0; i < NC; i++) for (int j = 0; j < NC; j++) ata[i][j] += row[i] * row[j];
        return ata;
    }

    static double[] solve(double[][] ata, double[] atb) {
        int n = atb.length;
        double[][] m = new double[n][n + 1];
        for (int i = 0; i < n; i++) { System.arraycopy(ata[i], 0, m[i], 0, n); m[i][n] = atb[i]; }
        for (int c = 0; c < n; c++) {
            int p = c;
            for (int r = c + 1; r < n; r++) if (Math.abs(m[r][c]) > Math.abs(m[p][c])) p = r;
            double[] t = m[c]; m[c] = m[p]; m[p] = t;
            if (Math.abs(m[c][c]) < 1e-12) continue;
            for (int r = 0; r < n; r++) {
                if (r == c) continue;
                double f = m[r][c] / m[c][c];
                for (int k = c; k <= n; k++) m[r][k] -= f * m[c][k];
            }
        }
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = m[i][i] != 0 ? m[i][n] / m[i][i] : 0;
        return x;
    }

    // ------------------------------------------------------------------ LEGS

    static Map<String, TreeMap<LocalDate, Double>> legsCache = new LinkedHashMap<>();

    static void buildLegs(Map<String, TreeMap<LocalDate, Double>> ret, String tag, List<LocalDate> dates) {
        double[][] ata = ata();
        Map<String, TreeMap<LocalDate, Double>> out = new LinkedHashMap<>();
        for (String c : CCY) out.put(c, new TreeMap<>());
        for (LocalDate d : dates) {
            double[] b = new double[LSPAIRS.length];
            boolean ok = true;
            for (int i = 0; i < LSPAIRS.length; i++) {
                Double v = ret.get(LSPAIRS[i]).get(d);
                if (v == null) { ok = false; break; }
                b[i] = v;
            }
            if (!ok) continue;
            double[] atb = new double[NC];
            for (int i = 0; i < NC; i++) { double s = 0; for (int r = 0; r < A.length; r++) s += A[r][i] * b[r]; atb[i] = s; }
            double[] x = solve(ata, atb);
            for (int i = 0; i < NC; i++) out.get(CCY[i]).put(d, x[i]);
        }
        legsCache.put(tag, null);
        legsByTag.put(tag, out);
    }

    static final Map<String, Map<String, TreeMap<LocalDate, Double>>> legsByTag = new LinkedHashMap<>();

    static void legs() {
        // même jeu de dates pour A et B → comparaison appareillée
        List<LocalDate> datesA = commonDates(RET_A);
        List<LocalDate> datesB = commonDates(RET_B);
        Set<LocalDate> sA = new HashSet<>(datesA);
        List<LocalDate> both = new ArrayList<>();
        for (LocalDate d : datesB) if (sA.contains(d)) both.add(d);
        buildLegs(RET_A, "A", both);
        buildLegs(RET_B, "B", both);

        section("LEGS) PROFIL VENDREDI DES 7 JAMBES — A (pipeline) vs B (grille partagée)");
        line("%d dates communes · fade SELL(X/Y) = leg(Y) - leg(X) · jambe > 0 = devise qui s'apprécie vs USD le vendredi", both.size());
        line("%-5s | %10s %6s %8s %7s | %10s %6s %8s %7s",
            "CCY", "A: VEN bp", "n", "hit<0%", "t", "B: VEN bp", "n", "hit<0%", "t");
        Map<String, Map<String, TreeMap<LocalDate, Double>>> L = legsByTag;
        List<String> order = new ArrayList<>(Arrays.asList(CCY));
        order.sort(Comparator.comparingDouble(c -> stat(pick(L.get("B").get(c), DayOfWeek.FRIDAY)).mean));
        for (String c : order) {
            St a = stat(pick(L.get("A").get(c), DayOfWeek.FRIDAY));
            St b = stat(pick(L.get("B").get(c), DayOfWeek.FRIDAY));
            line("%-5s | %+10.4f %6d %7.0f%% %+7.2f | %+10.4f %6d %7.0f%% %+7.2f",
                c, a.mean, a.n, a.hitNeg, a.t, b.mean, b.n, b.hitNeg, b.t);
        }

        section("LEGS2) MON-JEU (contrôle) ET STABILITÉ — jambes sous B");
        line("%-5s %11s %7s %11s %10s %10s %10s %10s", "CCY", "Mon-Jeu", "t", "IS 06-15", "OOS 16-26", "06-09", "10-14", "15-19/20-26");
        for (String c : order) {
            TreeMap<LocalDate, Double> m = L.get("B").get(c);
            St mj = stat(pickMonThu(m));
            St is = stat(pick(m, DayOfWeek.FRIDAY, 2006, 2015));
            St oos = stat(pick(m, DayOfWeek.FRIDAY, 2016, 2026));
            double d1 = stat(pick(m, DayOfWeek.FRIDAY, 2006, 2009)).mean;
            double d2 = stat(pick(m, DayOfWeek.FRIDAY, 2010, 2014)).mean;
            double d3 = stat(pick(m, DayOfWeek.FRIDAY, 2015, 2019)).mean;
            double d4 = stat(pick(m, DayOfWeek.FRIDAY, 2020, 2026)).mean;
            line("%-5s %+11.4f %+7.2f %+11.4f %+10.4f %+10.2f %+10.2f %+10.2f/%+.2f",
                c, mj.mean, mj.t, is.mean, oos.mean, d1, d2, d3, d4);
        }
    }

    // ------------------------------------------------------------------ FACTOR

    static void factor() {
        section("FACTOR) PANIER RISQUE (AUD+GBP+NZD+EUR) - PANIER REFUGE (CHF+JPY) — A vs B");
        for (String tag : new String[]{"A", "B"}) {
            Map<String, TreeMap<LocalDate, Double>> L = legsByTag.get(tag);
            TreeMap<LocalDate, Double> f = new TreeMap<>();
            for (LocalDate d : L.get("CHF").keySet()) {
                Double a = L.get("AUD").get(d), g = L.get("GBP").get(d), n = L.get("NZD").get(d),
                       e = L.get("EUR").get(d), c = L.get("CHF").get(d), j = L.get("JPY").get(d);
                if (a == null || g == null || n == null || e == null || c == null || j == null) continue;
                f.put(d, (a + g + n + e) / 4.0 - (c + j) / 2.0);
            }
            St fri = stat(pick(f, DayOfWeek.FRIDAY));
            St mj = stat(pickMonThu(f));
            St is = stat(pick(f, DayOfWeek.FRIDAY, 2006, 2015));
            St oos = stat(pick(f, DayOfWeek.FRIDAY, 2016, 2026));
            line("[%s] VENDREDI %+8.4f bp (t %+5.2f, n %d, médiane %+7.4f, hit fade %.0f%%) · Mon-Jeu %+7.4f (t %+5.2f)",
                tag, fri.mean, fri.t, fri.n, fri.med, 100 - fri.hitNeg, mj.mean, mj.t);
            line("     IS 06-15 %+8.4f → OOS 16-26 %+8.4f · décennies %+7.2f %+7.2f %+7.2f %+7.2f",
                is.mean, oos.mean,
                stat(pick(f, DayOfWeek.FRIDAY, 2006, 2009)).mean,
                stat(pick(f, DayOfWeek.FRIDAY, 2010, 2014)).mean,
                stat(pick(f, DayOfWeek.FRIDAY, 2015, 2019)).mean,
                stat(pick(f, DayOfWeek.FRIDAY, 2020, 2026)).mean);
        }
    }

    // ------------------------------------------------------------------ HOURS

    static void hours() {
        section("HOURS) CONTRÔLE HEURE FIXE — jambes mesurées à " + FIXED_HOUR + ":00 UTC, tous les jours");
        List<LocalDate> dH = commonDates(RET_H);
        buildLegs(RET_H, "H", dH);
        Map<String, TreeMap<LocalDate, Double>> L = legsByTag.get("H");
        List<String> order = new ArrayList<>(Arrays.asList(CCY));
        order.sort(Comparator.comparingDouble(c -> stat(pick(L.get(c), DayOfWeek.FRIDAY)).mean));
        line("%-5s %11s %7s %9s   %11s %7s", "CCY", "VEN bp", "n", "t", "(rappel B)", "t(B)");
        for (String c : order) {
            St h = stat(pick(L.get(c), DayOfWeek.FRIDAY));
            St b = stat(pick(legsByTag.get("B").get(c), DayOfWeek.FRIDAY));
            line("%-5s %+11.4f %7d %+9.2f   %+11.4f %+7.2f", c, h.mean, h.n, h.t, b.mean, b.t);
        }
        line("\nLecture : si le profil de jambes est stable entre B (grille) et H (heure fixe), le mécanisme");
        line("n'est pas une banque d'heures ; s'il bascule, la table du 50e mesurait l'HEURE de lecture.");
    }

    // ------------------------------------------------------------------ CROSS

    /** Les 12 crosses du 50e : {nom, jambe GAUCHE (base/USD), jambe DROITE}. */
    static final String[][] CROSSES = {
        {"GBP_CHF", "GBP_USD", "USD_CHF"}, {"EUR_CHF", "EUR_USD", "USD_CHF"}, {"AUD_CHF", "AUD_USD", "USD_CHF"},
        {"GBP_JPY", "GBP_USD", "USD_JPY"}, {"AUD_JPY", "AUD_USD", "USD_JPY"}, {"EUR_JPY", "EUR_USD", "USD_JPY"},
        {"GBP_CAD", "GBP_USD", "USD_CAD"}, {"AUD_CAD", "AUD_USD", "USD_CAD"}, {"EUR_CAD", "EUR_USD", "USD_CAD"},
        {"GBP_EUR", "GBP_USD", "EUR_USD"}, {"AUD_NZD", "AUD_USD", "NZD_USD"}, {"NZD_CAD", "NZD_USD", "USD_CAD"},
    };

    /** Références publiées : 50e (construction A, t) — pour juger la reproduction. */
    static final Map<String, Double> REF50 = new LinkedHashMap<>();
    static {
        REF50.put("GBP_CHF", 9.0754); REF50.put("EUR_CHF", 6.9309); REF50.put("AUD_CHF", 8.2675);
        REF50.put("GBP_JPY", 7.0300); REF50.put("AUD_JPY", 6.9982); REF50.put("EUR_JPY", 4.6064);
        REF50.put("GBP_CAD", 4.9379); REF50.put("AUD_CAD", 4.6278); REF50.put("EUR_CAD", 2.6655);
        REF50.put("GBP_EUR", 2.1787); REF50.put("AUD_NZD", 0.8624); REF50.put("NZD_CAD", 3.4181);
    }
    /** Références 52e (construction B, « fade » publié) — la colonne à falsifier. */
    static final Map<String, Double> REF52B = new LinkedHashMap<>();
    static {
        REF52B.put("GBP_CHF", 1.1514); REF52B.put("EUR_CHF", -1.1378); REF52B.put("AUD_CHF", 1.6148);
        REF52B.put("GBP_JPY", 3.2860); REF52B.put("AUD_JPY", 3.0504); REF52B.put("EUR_JPY", 0.7150);
        REF52B.put("GBP_CAD", 5.4396); REF52B.put("AUD_CAD", 5.3424); REF52B.put("EUR_CAD", 3.1739);
        REF52B.put("GBP_EUR", 2.1815); REF52B.put("AUD_NZD", 0.7924); REF52B.put("NZD_CAD", 3.0969);
    }

    /**
     * Série de TAUX du cross. RÈGLE DE CHANGE : X/Y = (X/USD) x (USD/Y) — le produit — quand la jambe
     * droite est cotée USD/YYY ; X/Y = (X/USD) / (Y/USD) — le quotient — quand la jambe droite est
     * cotée YYY/USD. Le quotient appliqué à une jambe USD/YYY construit un taux qui n'existe pas :
     * son retour vaut alors r(gauche) - r(droite) = -leg(gauche) - leg(droite), soit la VRAIE valeur
     * décalée de -2 x leg(droite).
     */
    static TreeMap<LocalDate, Double> crossSeries(String left, String right, Map<String, TreeMap<LocalDate, Double>> src, boolean correct) {
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        TreeMap<LocalDate, Double> L = src.get(left), R = src.get(right);
        if (L == null || R == null) return out;
        boolean multiply = correct && right.startsWith("USD_");
        for (var e : L.entrySet()) {
            Double r = R.get(e.getKey());
            if (r == null || r <= 0 || e.getValue() <= 0) continue;
            out.put(e.getKey(), multiply ? e.getValue() * r : e.getValue() / r);
        }
        return out;
    }

    static void cross() {
        section("CROSS) LES 12 CROSSES DU 50e — LAQUELLE DES DEUX CONSTRUCTIONS EST LE TAUX ?");
        line("règle CORRECTE : jambe droite USD_YYY ⇒ PRODUIT (X/USD x USD/YYY) ; jambe droite YYY_USD ⇒ QUOTIENT.");
        line("règle 52e      : QUOTIENT dans tous les cas.");
        line("%-9s %9s | %9s %7s | %9s %7s | %9s | %9s", "CROSS", "réf 50e", "A: correct", "t", "B: correct", "t", "B: 52e", "B: jambes");
        for (String[] c : CROSSES) {
            TreeMap<LocalDate, Double> cA = crossSeries(c[1], c[2], CL_A, true);
            TreeMap<LocalDate, Double> cB = crossSeries(c[1], c[2], CL_B, true);
            TreeMap<LocalDate, Double> bB = crossSeries(c[1], c[2], CL_B, false);
            St sA = stat(pick(ret(cA), DayOfWeek.FRIDAY));
            St sB = stat(pick(ret(cB), DayOfWeek.FRIDAY));
            St sBug = stat(pick(ret(bB), DayOfWeek.FRIDAY));
            String quote = c[2].startsWith("USD_") ? c[2].substring(4) : c[2].substring(0, 3);
            double legBased = stat(pick(legsByTag.get("B").get(quote), DayOfWeek.FRIDAY)).mean
                            - stat(pick(legsByTag.get("B").get(c[1].substring(0, 3)), DayOfWeek.FRIDAY)).mean;
            line("%-9s %+9.4f | %+9.4f %+7.2f | %+9.4f %+7.2f | %+9.4f | %+9.4f",
                c[0], REF50.getOrDefault(c[0], 0.0), -sA.mean, -sA.t, -sB.mean, -sB.t, -sBug.mean, legBased);
        }
        line("\nLecture : si la colonne « B: correct » colle à « B: jambes » et que « B: 52e » en diffère de");
        line("~2 x leg(jambe droite), c'est la CONSTRUCTION du 52e qui est fausse, pas le refuge qui meurt.");
    }

    // ------------------------------------------------------------------ VERDICT

    static void verdict() {
        section("VERDICT — à lire dans la note Joplin du 9 oct");
        line("1. la table de jambes du 50e se déplace-t-elle entre A et B ?");
        line("2. la jambe refuge (CHF) reste-t-elle la première jambe positive ?");
        line("3. le facteur risque/refuge survit-il au changement de grille ?");
        line("4. le profil est-il stable à heure FIXE (contrôle HOURS) ?");
    }
}
