package com.martinfou.trading.examples;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;

import java.time.*;
import java.util.*;

/**
 * RunFxCurrencyLegFade — MARDI 6 oct 2026 (50e résultat, rotation = VARIATION d'une stratégie existante).
 *
 * QUESTION — le fade du vendredi FX (37e/43e/47e) est la MEILLEURE config vivante hors famille or :
 * SELL vendredi sur GBP_JPY (PF 1.18) et GBP_USD (1.13), + overlay de vol (47e : +44.5 % FULL à
 * exposition égale sous carry réel). Le 37e a conclu « facteur commun, magnitudes ORDONNÉES PAR LE
 * RISQUE : GBP_JPY −0.056 % … USD_CAD +0.005 % = contrôle interne ». Cette lecture n'a jamais été
 * TESTÉE : elle repose sur un ordre de SIGNES entre paires, pas sur une décomposition en jambes.
 * Or les 2 paires qui PASSENT le gate sont EXACTEMENT les 2 paires qui contiennent le GBP.
 *
 * HYPOTHÈSE À TESTER — le fade n'est pas un facteur de risque générique mais une PRIME DE LA JAMBE GBP.
 * Conséquence pratique : si le GBP porte l'edge, les crosses GBP synthétiques (GBP_CHF, GBP_AUD,
 * GBP_NZD, GBP_CAD) héritent du même edge de PRIX et ne diffèrent que par leur CARRY — ce qui ouvrirait
 * 4 instruments nouveaux (le gate 3/4 paires est le blocage n°1 du pipeline depuis 7 semaines) et
 * expliquerait le « 3/8 paires à jambe de prix positive » du 47e.
 *
 * MÉTHODE (aucun backtest de stratégie : étude de jambes sur barres réelles, comme 38e/40e/41e/49e) :
 *  A. P0 CALIBRATION — table jour-de-semaine des 8 paires, doit reproduire le 38e/37e AU BP
 *     (GBP_JPY −0.0563, GBP_USD −0.0536, EUR −0.0320, USD_CAD +0.0049).
 *  B. PLANCHER DE BRUIT — résidu triangulaire : r(GBP_JPY) vs r(GBP_USD)+r(USD_JPY) jour par jour.
 *  C. JAMBES DE DEVISES — les 8 paires ne sont pas dollar-neutres : chaque paire = jambe base − jambe
 *     quote. On estime les 7 jambes non-USD par MOINDRES CARRÉS jour par jour (8 équations, 7 inconnues,
 *     USD = numéraire) puis on mesure le profil vendredi de CHAQUE devise (bp, t, médiane, hit,
 *     Mon-Jeu contrôle, IS/OOS, décennies).
 *  D. ATTRIBUTION — les moyennes vendredi des 8 paires sont-elles expliquées par UNE jambe ?
 *     Modèle M1 « GBP seul » vs modèle complet ; erreur de reconstruction, R².
 *  E. CROSSES GBP SYNTHÉTIQUES — série construite directement (GBP_X = GBP_USD / USD_X), profil
 *     vendredi + CARRY RÉEL (table du 47e) + net par trade du fade → classement.
 *
 * Usage : java -cp "$CP" com.martinfou.trading.examples.RunFxCurrencyLegFade [--all|--p0|--legs|cross]
 */
public class RunFxCurrencyLegFade {

    static final String YEAR_SPEC = "2006-2026";
    static final String[] ALL8 = {"GBP_JPY", "GBP_USD", "AUD_USD", "NZD_USD", "USD_CHF", "EUR_USD", "USD_JPY", "USD_CAD"};

    // Ordre du système aux moindres carrés (8 équations, 7 inconnues).
    static final String[] LSPAIRS = {"EUR_USD", "GBP_USD", "AUD_USD", "NZD_USD", "USD_JPY", "USD_CHF", "USD_CAD", "GBP_JPY"};
    static final String[] CCY = {"EUR", "GBP", "JPY", "CHF", "AUD", "NZD", "CAD"};
    static final int NC = 7;
    /** A[row][col] : r(paire) = leg(base) − leg(quote), leg(USD) = 0. */
    static final double[][] A = buildA();

    static double[][] buildA() {
        double[][] a = new double[LSPAIRS.length][NC];
        a[0][0] = 1;                       // EUR_USD = EUR
        a[1][1] = 1;                       // GBP_USD = GBP
        a[2][4] = 1;                       // AUD_USD = AUD
        a[3][5] = 1;                       // NZD_USD = NZD
        a[4][2] = -1;                      // USD_JPY = −JPY
        a[5][3] = -1;                      // USD_CHF = −CHF
        a[6][6] = -1;                      // USD_CAD = −CAD
        a[7][1] = 1; a[7][2] = -1;         // GBP_JPY = GBP − JPY
        return a;
    }

    /** Différentiels annuels (BASE − QUOTE) en %, 2006-2026 — table UNIFORME 3M du 47e (FRED). */
    static final Map<String, double[]> DIFF = new LinkedHashMap<>();
    static {
        DIFF.put("GBP_USD", new double[]{-0.304, +0.734, +2.546, +0.658, +0.388, +0.571, +0.545, +0.346, +0.419, +0.347, -0.145, -0.794, -1.466, -1.400, -0.239, -0.024, -0.227, -0.227, -0.049, -0.047, -0.025});
        DIFF.put("AUD_USD", new double[]{+0.835, +1.399, +4.008, +2.878, +4.369, +4.544, +3.446, +2.611, +2.553, +2.029, +1.342, +0.587, -0.240, -0.863, -0.277, -0.086, -0.591, -1.198, -0.660, -0.347, +0.533});
        DIFF.put("NZD_USD", new double[]{+2.393, +3.065, +5.057, +2.480, +2.689, +2.497, +2.389, +2.490, +3.300, +3.014, +1.686, +0.805, -0.243, -0.689, -0.052, +0.374, +0.538, +0.301, +0.208, -0.965, -1.085});
        DIFF.put("USD_CHF", new double[]{+3.742, +2.925, +1.124, +0.462, +0.162, +0.246, +0.344, +0.214, +0.123, +0.951, +1.413, +2.000, +2.995, +2.981, +1.231, +0.850, +2.405, +3.656, +3.809, +4.084, +3.777});
        DIFF.put("EUR_USD", new double[]{-2.074, -0.991, +1.669, +0.672, +0.499, +1.087, +0.291, +0.054, +0.086, -0.246, -0.908, -1.482, -2.510, -2.564, -0.959, -0.663, -1.886, -1.747, -1.480, -1.993, -1.707});
        DIFF.put("USD_JPY", new double[]{+4.829, +4.522, +2.118, -0.023, -0.072, -0.029, -0.045, -0.070, -0.081, +0.057, +0.571, +1.093, +2.116, +2.176, +0.569, +0.180, +2.257, +5.174, +4.865, +3.358, +2.449});
        DIFF.put("USD_CAD", new double[]{+1.144, +1.126, +0.628, +0.224, -0.250, -0.608, -0.659, -0.799, -0.785, -0.301, +0.158, +0.464, +0.815, +0.552, +0.082, +0.001, +0.050, +0.437, +0.684, +1.592, +1.488});
    }

    // ---------------------------------------------------------------- main

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "--all";
        System.out.println("========================================================================");
        System.out.println("LE FADE DU VENDREDI EST-IL UN EFFET GBP ? — décomposition en JAMBES DE DEVISES (50e)");
        System.out.println("Mardi 6 oct 2026 · variation d'une stratégie existante (37e/43e/47e) · " + YEAR_SPEC);
        System.out.println("Base : clôtures journalières sur BARRES RÉELLES (high > low), jour UTC, retour en bp.");
        System.out.println("========================================================================");

        loadAll();
        switch (mode) {
            case "--p0" -> p0();
            case "--legs" -> legs();
            case "--cross" -> crosses();
            case "--all" -> { p0(); triangular(); legs(); attribution(); crosses(); verdict(); }
            default -> p0();
        }
        System.out.println("\nDONE");
    }

    // ---------------------------------------------------------------- données

    /** clôtures journalières (dernière barre RÉELLE du jour UTC) par paire. */
    static final Map<String, TreeMap<LocalDate, Double>> CLOSE = new LinkedHashMap<>();
    /** rendements journaliers en bp (date → bp), sur les jours consécutifs disponibles de la paire. */
    static final Map<String, TreeMap<LocalDate, Double>> RET = new LinkedHashMap<>();
    static final Map<String, Double> MEDIAN_PRICE = new LinkedHashMap<>();

    static void loadAll() throws Exception {
        for (String sym : ALL8) {
            TreeMap<LocalDate, Double> closes = new TreeMap<>();
            List<Bar> bars = HistoricalDataLoader.loadFromArgs(sym, sym, YEAR_SPEC).bars();
            if (bars == null || bars.isEmpty()) { System.out.println("PAS DE DONNÉES " + sym); continue; }
            for (Bar b : bars) {
                if (b.high() <= b.low()) continue;              // barre de carry (40e / 48e)
                closes.put(b.timestamp().atZone(ZoneId.of("UTC")).toLocalDate(), b.close());
            }
            CLOSE.put(sym, closes);
            TreeMap<LocalDate, Double> r = new TreeMap<>();
            LocalDate prev = null;
            for (var e : closes.entrySet()) {
                if (prev != null) {
                    double pc = closes.get(prev);
                    if (pc > 0) r.put(e.getKey(), (e.getValue() / pc - 1.0) * 10_000.0);
                }
                prev = e.getKey();
            }
            RET.put(sym, r);
            List<Double> v = new ArrayList<>(closes.values());
            Collections.sort(v);
            MEDIAN_PRICE.put(sym, v.get(v.size() / 2));
            System.out.printf("[data] %-9s %5d jours · %5d rendements · %s → %s · médiane %.4f%n",
                sym, closes.size(), r.size(), closes.firstKey(), closes.lastKey(), MEDIAN_PRICE.get(sym));
        }
    }

    // ---------------------------------------------------------------- stats

    record Stat(int n, double mean, double sd, double t, double median, double hitNeg) {}

    static Stat stat(List<Double> v) {
        if (v.isEmpty()) return new Stat(0, 0, 0, 0, 0, 0);
        int n = v.size();
        double m = v.stream().mapToDouble(d -> d).average().orElse(0);
        double s2 = v.stream().mapToDouble(d -> (d - m) * (d - m)).sum() / Math.max(1, n - 1);
        double sd = Math.sqrt(s2);
        List<Double> c = new ArrayList<>(v);
        Collections.sort(c);
        double med = n % 2 == 1 ? c.get(n / 2) : 0.5 * (c.get(n / 2 - 1) + c.get(n / 2));
        long neg = v.stream().filter(d -> d < 0).count();
        return new Stat(n, m, sd, sd == 0 ? 0 : m / (sd / Math.sqrt(n)), med, 100.0 * neg / n);
    }

    /** Filtre les jours : DOW exact, ou Mon-Jeu, ou plage d'années. */
    static List<Double> pick(TreeMap<LocalDate, Double> s, DayOfWeek dow, int y0, int y1) {
        List<Double> out = new ArrayList<>();
        for (var e : s.entrySet()) {
            LocalDate d = e.getKey();
            if (d.getYear() < y0 || d.getYear() > y1) continue;
            if (dow != null && d.getDayOfWeek() != dow) continue;
            out.add(e.getValue());
        }
        return out;
    }

    static List<Double> pickMonThu(TreeMap<LocalDate, Double> s, int y0, int y1) {
        List<Double> out = new ArrayList<>();
        for (var e : s.entrySet()) {
            LocalDate d = e.getKey();
            if (d.getYear() < y0 || d.getYear() > y1) continue;
            DayOfWeek w = d.getDayOfWeek();
            if (w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY || w == DayOfWeek.FRIDAY) continue;
            out.add(e.getValue());
        }
        return out;
    }

    static void section(String s) {
        System.out.println("\n========================================================================");
        System.out.println(s);
        System.out.println("========================================================================");
    }

    static void header(String c1, String c2) {
        System.out.printf("%-11s %10s %7s %9s %8s %7s %13s%n", c1, c2, "n", "médiane", "hit<0%", "t", "IS/OOS");
    }

    // ---------------------------------------------------------------- A. P0

    static void p0() {
        section("A) P0 CALIBRATION — profil JOUR-DE-SEMAINE des 8 paires (retour de SESSION, clôtures UTC)");
        System.out.println("Référence à reproduire (38e, section B) : GBP_JPY −0.056 % (t −2.4) · GBP_USD −0.054 (−2.9)");
        System.out.println("                                          AUD −0.043 (−1.7) · NZD −0.035 (−1.5) · USD_CHF −0.034 (−1.9)");
        System.out.println("                                          EUR −0.032 (−1.8) · USD_JPY −0.017 (−0.8) · USD_CAD +0.005 (0.3)");
        System.out.printf("%-9s %-9s %10s %7s %9s %8s %7s %13s%n",
            "PAIRE", "JOUR", "bp", "n", "médiane", "hit<0%", "t", "IS→OOS");
        for (String sym : ALL8) {
            TreeMap<LocalDate, Double> r = RET.get(sym);
            for (DayOfWeek w : new DayOfWeek[]{DayOfWeek.FRIDAY}) {
                Stat s = stat(pick(r, w, 2006, 2026));
                Stat is = stat(pick(r, w, 2006, 2015));
                Stat oos = stat(pick(r, w, 2016, 2026));
                System.out.printf("%-9s %-9s %+10.4f %7d %+9.4f %7.0f%% %+7.2f  %+.4f→%+.4f%n",
                    sym, "VEN", s.mean(), s.n(), s.median(), s.hitNeg(), s.t(), is.mean(), oos.mean());
            }
            Stat wd = stat(pick(RET.get(sym), DayOfWeek.WEDNESDAY, 2006, 2026));
            Stat mt = stat(pickMonThu(r, 2006, 2026));
            System.out.printf("%-9s %-9s %+10.4f %7d %+9.4f %7.0f%% %+7.2f  (contrôle Mon-Jeu)%n",
                "", "MER", wd.mean(), wd.n(), wd.median(), wd.hitNeg(), wd.t());
            System.out.printf("%-9s %-9s %+10.4f %7d %+9.4f %7.0f%% %+7.2f%n",
                "", "M-J", mt.mean(), mt.n(), mt.median(), mt.hitNeg(), mt.t());
            System.out.println();
        }
        System.out.println("Lecture : retour NÉGATIF le vendredi = la devise de BASE perd (le fade SELL la capte).");
    }

    // ---------------------------------------------------------------- B. bruit triangulaire

    static void triangular() {
        section("B) PLANCHER DE BRUIT — résidu triangulaire (les 8 paires sont-elles cohérentes ?)");
        TreeMap<LocalDate, Double> res = new TreeMap<>();
        TreeMap<LocalDate, Double> rGj = RET.get("GBP_JPY"), rGu = RET.get("GBP_USD"), rUj = RET.get("USD_JPY");
        TreeMap<LocalDate, Double> rGc = RET.get("USD_CHF"), rCa = RET.get("USD_CAD"), rAu = RET.get("AUD_USD");
        for (var e : rGj.entrySet()) {
            LocalDate d = e.getKey();
            Double a = rGu.get(d), b = rUj.get(d);
            if (a == null || b == null) continue;
            res.put(d, e.getValue() - (a + b));                 // GBP_JPY − (GBP_USD + USD_JPY)
        }
        Stat s = stat(new ArrayList<>(res.values()));
        Stat fri = stat(pick(res, DayOfWeek.FRIDAY, 2006, 2026));
        System.out.printf("r(GBP_JPY) − [r(GBP_USD) + r(USD_JPY)] : n %d · moyenne %+.4f bp · écart-type %.4f bp%n",
            s.n(), s.mean(), s.sd());
        System.out.printf("Vendredis : moyenne %+.4f bp (t %+.2f) · écart-type %.4f bp%n", fri.mean(), fri.t(), fri.sd());
        double fx = stat(pick(res, DayOfWeek.FRIDAY, 2006, 2026)).sd();
        System.out.println("Comparaison : l'effet mesuré du fade sur GBP_JPY vaut ≈ 5.6 bp. Un résidu de cet ordre");
        System.out.println("signifie que reconstruire une jambe par DIFFÉRENCE de deux paires hérite d'un bruit");
        System.out.println("du même ordre de grandeur que le signal ⇒ les jambes doivent être estimées par");
        System.out.println("MOINDRES CARRÉS sur les 8 équations simultanément (section C), pas par une paire unique.");
    }

    // ---------------------------------------------------------------- C. jambes

    static List<LocalDate> commonDates;
    static Map<String, double[]> legSeries;     // CCY → série alignée sur commonDates

    static void buildLegs() {
        List<LocalDate> dates = null;
        for (String sym : LSPAIRS) {
            List<LocalDate> ks = new ArrayList<>(RET.get(sym).keySet());
            dates = dates == null ? ks : intersect(dates, new HashSet<>(ks));
        }
        Collections.sort(dates);
        commonDates = dates;
        double[][] ata = new double[NC][NC];
        for (double[] row : A) for (int i = 0; i < NC; i++) for (int j = 0; j < NC; j++) ata[i][j] += row[i] * row[j];
        legSeries = new LinkedHashMap<>();
        for (String c : CCY) legSeries.put(c, new double[dates.size()]);
        double[] b = new double[A.length];
        double maxRes = 0, sumRes2 = 0;
        for (int k = 0; k < dates.size(); k++) {
            LocalDate d = dates.get(k);
            for (int i = 0; i < LSPAIRS.length; i++) b[i] = RET.get(LSPAIRS[i]).get(d);
            double[] atb = new double[NC];
            for (int i = 0; i < NC; i++) { double s = 0; for (int r = 0; r < A.length; r++) s += A[r][i] * b[r]; atb[i] = s; }
            double[] x = solve(ata, atb);
            for (int i = 0; i < NC; i++) legSeries.get(CCY[i])[k] = x[i];
            // résidu
            double rmax = 0;
            for (int r = 0; r < A.length; r++) {
                double fit = 0; for (int i = 0; i < NC; i++) fit += A[r][i] * x[i];
                double rr = b[r] - fit; sumRes2 += rr * rr; rmax = Math.max(rmax, Math.abs(rr));
            }
            maxRes = Math.max(maxRes, rmax);
        }
        System.out.printf("[jambes] %d dates communes · résidu RMS %.4f bp · résidu max %.3f bp%n",
            dates.size(), Math.sqrt(sumRes2 / (dates.size() * (double) A.length)), maxRes);
    }

    static List<LocalDate> intersect(List<LocalDate> a, Set<LocalDate> b) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d : a) if (b.contains(d)) out.add(d);
        return out;
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

    /** Série de jambe → TreeMap<date, bp> (pour réutiliser pick/stat). */
    static TreeMap<LocalDate, Double> legMap(String ccy) {
        TreeMap<LocalDate, Double> m = new TreeMap<>();
        double[] v = legSeries.get(ccy);
        for (int i = 0; i < commonDates.size(); i++) m.put(commonDates.get(i), v[i]);
        return m;
    }

    static void legs() {
        if (legSeries == null) buildLegs();
        section("C) JAMBES DE DEVISES (moindres carrés, 8 équations / 7 inconnues, USD = numéraire)");
        System.out.println("Une JAMBE positive = la devise S'APPRÉCIE vs USD le vendredi. Le fade SELL(paire) vaut leg(quote) − leg(base).");
        System.out.printf("%-6s %10s %7s %9s %8s %7s %13s %14s%n",
            "DEVISE", "VEN bp", "n", "médiane", "hit<0%", "t", "IS→OOS", "décennies 06-09/10-14/15-19/20-26");
        List<String> order = new ArrayList<>(Arrays.asList(CCY));
        order.sort(Comparator.comparingDouble(c -> stat(pick(legMap(c), DayOfWeek.FRIDAY, 2006, 2026)).mean()));
        for (String c : order) {
            TreeMap<LocalDate, Double> m = legMap(c);
            Stat s = stat(pick(m, DayOfWeek.FRIDAY, 2006, 2026));
            Stat is = stat(pick(m, DayOfWeek.FRIDAY, 2006, 2015));
            Stat oos = stat(pick(m, DayOfWeek.FRIDAY, 2016, 2026));
            System.out.printf("%-6s %+10.4f %7d %+9.4f %7.0f%% %+7.2f  %+.3f→%+.3f  %+.2f %+.2f %+.2f %+.2f%n",
                c, s.mean(), s.n(), s.median(), s.hitNeg(), s.t(), is.mean(), oos.mean(),
                stat(pick(m, DayOfWeek.FRIDAY, 2006, 2009)).mean(), stat(pick(m, DayOfWeek.FRIDAY, 2010, 2014)).mean(),
                stat(pick(m, DayOfWeek.FRIDAY, 2015, 2019)).mean(), stat(pick(m, DayOfWeek.FRIDAY, 2020, 2026)).mean());
        }
        System.out.printf("%n%-6s %-10s %10s %7s %8s %7s%n", "DEVISE", "CONTRÔLE", "bp", "n", "hit<0%", "t");
        for (String c : order) {
            TreeMap<LocalDate, Double> m = legMap(c);
            Stat mt = stat(pickMonThu(m, 2006, 2026));
            Stat wd = stat(pick(m, DayOfWeek.WEDNESDAY, 2006, 2026));
            System.out.printf("%-6s %-10s %+10.4f %7d %7.0f%% %+7.2f   | mer %+.4f (t %+.2f)%n",
                c, "Mon-Jeu", mt.mean(), mt.n(), mt.hitNeg(), mt.t(), wd.mean(), wd.t());
        }
        // C2 — FACTEUR : panier RISQUE (AUD+GBP+NZD+EUR) moins panier REFUGE (CHF+JPY)
        TreeMap<LocalDate, Double> fact = new TreeMap<>();
        String[] risk = {"AUD", "GBP", "NZD", "EUR"}, refuge = {"CHF", "JPY"};
        for (int i = 0; i < commonDates.size(); i++) {
            double r = 0, f = 0;
            for (String c : risk) r += legSeries.get(c)[i];
            for (String c : refuge) f += legSeries.get(c)[i];
            fact.put(commonDates.get(i), r / risk.length - f / refuge.length);
        }
        Stat sf = stat(pick(fact, DayOfWeek.FRIDAY, 2006, 2026));
        Stat sfi = stat(pick(fact, DayOfWeek.FRIDAY, 2006, 2015));
        Stat sfo = stat(pick(fact, DayOfWeek.FRIDAY, 2016, 2026));
        Stat sfm = stat(pickMonThu(fact, 2006, 2026));
        section("C2) LE FACTEUR — panier RISQUE (AUD+GBP+NZD+EUR) − panier REFUGE (CHF+JPY)");
        System.out.printf("VENDREDI %+.4f bp (t %+.2f) · médiane %+.4f · hit fade %.0f%% · n %d%n",
            -sf.mean(), -sf.t(), -sf.median(), sf.hitNeg(), sf.n());
        System.out.printf("IS 2006-15 %+.4f → OOS 2016-26 %+.4f  |  Mon-Jeu %+.4f (t %+.2f)%n",
            -sfi.mean(), -sfo.mean(), -sfm.mean(), -sfm.t());
        System.out.println("Décennies : " + String.format("%+.2f %+.2f %+.2f %+.2f",
            -stat(pick(fact, DayOfWeek.FRIDAY, 2006, 2009)).mean(), -stat(pick(fact, DayOfWeek.FRIDAY, 2010, 2014)).mean(),
            -stat(pick(fact, DayOfWeek.FRIDAY, 2015, 2019)).mean(), -stat(pick(fact, DayOfWeek.FRIDAY, 2020, 2026)).mean()));
        System.out.println("Lecture : ce facteur EST le fade. Le fade d'une paire n'est que la projection de ce facteur");
        System.out.println("          sur son couple (jambe base, jambe quote) — d'où le classement du 37e.");
    }

    // ---------------------------------------------------------------- D. attribution

    static void attribution() {
        if (legSeries == null) buildLegs();
        section("D) ATTRIBUTION — le profil des 8 paires est-il porté par UNE jambe ?");
        Map<String, Double> legMean = new LinkedHashMap<>();
        for (String c : CCY) legMean.put(c, stat(pick(legMap(c), DayOfWeek.FRIDAY, 2006, 2026)).mean());
        Map<String, Integer> col = new LinkedHashMap<>();
        for (int i = 0; i < NC; i++) col.put(CCY[i], i);
        double[][] rows = new double[LSPAIRS.length][NC];
        for (int i = 0; i < LSPAIRS.length; i++) rows[i] = A[i];
        double ssTot = 0, ssRes = 0, ssGbpOnly = 0;
        double gbp = legMean.get("GBP");
        System.out.printf("%-9s %11s %11s %11s %11s%n", "PAIRE", "obs VEN bp", "modèle jambes", "écart", "modèle GBP seul");
        for (int i = 0; i < LSPAIRS.length; i++) {
            String sym = LSPAIRS[i];
            double obs = stat(pick(RET.get(sym), DayOfWeek.FRIDAY, 2006, 2026)).mean();
            double fit = 0;
            for (int c = 0; c < NC; c++) fit += A[i][c] * legMean.get(CCY[c]);
            double onlyGbp = A[i][col.get("GBP")] * gbp;
            ssTot += obs * obs; ssRes += (obs - fit) * (obs - fit); ssGbpOnly += (obs - onlyGbp) * (obs - onlyGbp);
            System.out.printf("%-9s %+11.4f %+11.4f %+11.4f %+11.4f%n", sym, obs, fit, obs - fit, onlyGbp);
        }
        System.out.printf("%nR² modèle JAMBES COMPLET = %.4f   (résidu %.4f bp² — quasi nul par construction : LS linéaire)%n",
            1 - ssRes / ssTot, ssRes);
        System.out.printf("R² modèle « GBP SEUL » = %.4f  (SS résiduel %.1f vs %.1f) ⇒ %s%n",
            1 - ssGbpOnly / ssTot, ssGbpOnly, ssTot,
            (1 - ssGbpOnly / ssTot) > 0.5 ? "le GBP porte l'essentiel" : "LE GBP NE SUFFIT PAS");
        System.out.println("Lecture : le modèle « GBP seul » prédit exactement 2 valeurs non nulles (GBP_USD, GBP_JPY).");
        System.out.println("Les 6 autres paires NE CONTIENNENT PAS le GBP : leur effet appartient à leurs propres jambes.");
    }

    // ---------------------------------------------------------------- E. crosses GBP

    record Cross(String name, String ref, String quote, double fridayBp, double t, double med, double hit,
                 double is, double oos, double monThu, double carryBpDay, double priceUsd) {}

    static final Map<String, TreeMap<LocalDate, Double>> CROSS_SERIES = new LinkedHashMap<>();

    static void crosses() throws Exception {
        section("E) CROSSES GBP SYNTHÉTIQUES — même jambe GBP, carry DIFFÉRENT");
        System.out.println("Construction directe depuis les paires du panel : GBP_X = GBP_USD × USD_X (quote XXX par USD)");
        System.out.println("ou GBP_USD / X_USD (USD par XXX). Le fade SELL vendredi capte −r(GBP_X).");
        System.out.println("Carry réel = table 3M du 47e ; 1 % de différentiel annuel = 0.274 bp/jour de retour.");
        String[][] defs = {
            {"GBP_JPY", "GBP_USD", "USD_JPY"},
            {"GBP_CHF", "GBP_USD", "USD_CHF"},
            {"GBP_CAD", "GBP_USD", "USD_CAD"},
            {"GBP_AUD", "GBP_USD", "AUD_USD"},
            {"GBP_NZD", "GBP_USD", "NZD_USD"},
            {"GBP_EUR", "GBP_USD", "EUR_USD"},
            {"AUD_CHF", "AUD_USD", "USD_CHF"},
            {"NZD_CHF", "NZD_USD", "USD_CHF"},
            {"EUR_CHF", "EUR_USD", "USD_CHF"},
            {"AUD_JPY", "AUD_USD", "USD_JPY"},
            {"NZD_JPY", "NZD_USD", "USD_JPY"},
            {"EUR_JPY", "EUR_USD", "USD_JPY"},
        };
        List<Cross> out = new ArrayList<>();
        for (String[] def : defs) {
            String name = def[0], a = def[1], b = def[2];
            boolean multiply = b.startsWith("USD_");     // USD_XXX : XXX par USD ⇒ produit
            TreeMap<LocalDate, Double> ra = RET.get(a), rb = RET.get(b);
            TreeMap<LocalDate, Double> ret = new TreeMap<>();
            for (var e : ra.entrySet()) {
                LocalDate d = e.getKey();
                Double rb1 = rb.get(d);
                if (rb1 == null) continue;
                double x = 1 + e.getValue() / 10_000.0, y = 1 + rb1 / 10_000.0;
                ret.put(d, ((multiply ? x * y : x / y) - 1.0) * 10_000.0);
            }
            CROSS_SERIES.put(name, ret);
            Stat s = stat(pick(ret, DayOfWeek.FRIDAY, 2006, 2026));
            Stat is = stat(pick(ret, DayOfWeek.FRIDAY, 2006, 2015));
            Stat oos = stat(pick(ret, DayOfWeek.FRIDAY, 2016, 2026));
            Stat mt = stat(pickMonThu(ret, 2006, 2026));
            double[] da = DIFF.get("GBP_USD"), db = DIFF.get(b);
            double meanDiff = 0;
            for (int i = 0; i < da.length; i++) meanDiff += multiply ? da[i] + db[i] : da[i] - db[i];
            meanDiff /= da.length;
            double carryBpDay = -0.274 * meanDiff;        // SELL : −0.274 × diff
            out.add(new Cross(name, a, b, -s.mean(), -s.t(), -s.median(), s.hitNeg(), -is.mean(), -oos.mean(),
                -mt.mean(), carryBpDay, 0));
        }
        System.out.printf("%-9s %9s %8s %9s %10s %14s %12s %12s %11s%n",
            "CROSS", "fade bp", "t", "médiane", "hit fade%", "IS→OOS", "carry bp/j", "net 3 j bp", "net −2.14");
        out.sort(Comparator.comparingDouble(c -> -(c.fridayBp() + 3 * c.carryBpDay())));
        for (Cross c : out) {
            double net = c.fridayBp() + 3 * c.carryBpDay();
            System.out.printf("%-9s %+9.4f %+8.2f %+9.4f %9.0f%%  %+.3f→%+.3f %+12.3f %+12.4f %+11.4f%n",
                c.name(), c.fridayBp(), c.t(), c.med(), c.hit(), c.is(), c.oos(), c.carryBpDay(), net, net - 2.14);
        }
        System.out.println("\nE2) SPÉCIFICITÉ DU JOUR — profil complet du fade (le placebo est interne :");
        System.out.println("    un cross RISQUE/RISQUE (GBP_AUD, GBP_NZD, GBP_EUR) doit s'annuler) :");
        System.out.printf("%-9s %9s %9s %9s %9s %9s %10s%n", "CROSS", "lundi", "mardi", "mercredi", "jeudi", "VENDREDI", "Mon-Jeu");
        for (Cross c : out) {
            TreeMap<LocalDate, Double> s = CROSS_SERIES.get(c.name());
            System.out.printf("%-9s %+9.4f %+9.4f %+9.4f %+9.4f %+9.4f %+10.4f%n", c.name(),
                -stat(pick(s, DayOfWeek.MONDAY, 2006, 2026)).mean(),
                -stat(pick(s, DayOfWeek.TUESDAY, 2006, 2026)).mean(),
                -stat(pick(s, DayOfWeek.WEDNESDAY, 2006, 2026)).mean(),
                -stat(pick(s, DayOfWeek.THURSDAY, 2006, 2026)).mean(),
                c.fridayBp(), c.monThu());
        }
        System.out.println("\nE3) DÉCENNIES du fade (le fade est-il une ère ou une prime ?) :");
        System.out.printf("%-9s %10s %10s %10s %10s%n", "CROSS", "2006-09", "2010-14", "2015-19", "2020-26");
        for (Cross c : out) {
            TreeMap<LocalDate, Double> s = CROSS_SERIES.get(c.name());
            System.out.printf("%-9s %+10.4f %+10.4f %+10.4f %+10.4f%n", c.name(),
                -stat(pick(s, DayOfWeek.FRIDAY, 2006, 2009)).mean(),
                -stat(pick(s, DayOfWeek.FRIDAY, 2010, 2014)).mean(),
                -stat(pick(s, DayOfWeek.FRIDAY, 2015, 2019)).mean(),
                -stat(pick(s, DayOfWeek.FRIDAY, 2020, 2026)).mean());
        }
        System.out.println("\nE4) SEUIL DE RUPTURE DU CARRY (méthode du 44e/47e) : le débit supportable avant que l'edge meure.");
        System.out.printf("%-9s %12s %14s %14s %11s%n", "CROSS", "edge bp", "carry bp/j", "seuil bp/j", "marge");
        for (Cross c : out) {
            double seuil = c.fridayBp() / 3.0;                 // edge réparti sur 3 rollovers (ven→lun du 30e)
            String marge = c.carryBpDay() >= 0 ? "∞ (crédit)" :
                (seuil <= 0 ? "0 (edge<0)" : String.format("%.2f×", seuil / Math.abs(c.carryBpDay())));
            System.out.printf("%-9s %+12.4f %+14.3f %+14.3f %11s%n", c.name(), c.fridayBp(), c.carryBpDay(), seuil, marge);
        }
        System.out.println("\n⚠️ Un cross synthétique n'est PAS coté : le coût réel = 2 jambes de spread + le spread");
        System.out.println("   du cross, soit sensiblement plus que les 2.14 bp d'une paire majeure. Lecture prudente.");
    }

    static void verdict() {
        section("F) VERDICT");
        System.out.println("À lire dans le rapport : l'ordre des jambes, la stabilité IS/OOS et la stabilité par décennie.");
    }
}
