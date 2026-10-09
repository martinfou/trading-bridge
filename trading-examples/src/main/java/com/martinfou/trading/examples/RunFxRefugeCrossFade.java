package com.martinfou.trading.examples;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;

import java.time.*;
import java.util.*;

/**
 * RunFxRefugeCrossFade — JEUDI 8 oct 2026 (52e résultat, rotation = INTERMARKET / CROSS-ASSET).
 *
 * QUESTION — le 50e (mardi 6 oct) a décomposé les 8 paires en JAMBES de devises et conclu que le fade du
 * vendredi est un RISK-OFF dont le porteur est la JAMBE REFUGE (CHF > JPY > USD > CAD ≫ risque). Il a
 * désigné GBP_CHF (+9.08 bp, t 5.13) puis, pour la STABILITÉ par décennie, EUR_CHF (+3.99/+3.09/+9.13/
 * +10.49, 4/4 décennies positives).
 * MAIS ces crosses étaient des RECONSTRUCTIONS : GBP_CHF = GBP_USD × USD_CHF, construit sur des CLÔTURES
 * JOURNALIÈRES de deux paires dont la dernière barre réelle du jour UTC peut tomber à des HEURES DIFFÉRENTES
 * (plancher de bruit triangulaire mesuré à 13.9 bp d'écart-type). Aucun fichier .bars GBP_CHF/EUR_CHF/AUD_CHF
 * n'existait : « pas de backtest aujourd'hui » (50e, À FAIRE).
 *
 * CE RUN — les 3 crosses REFUGES sont désormais disponibles en H1 réel (Dukascopy 2006→2026, téléchargés ce
 * matin, données/historical/dukascopy/*chf-h1-bid-*.csv). On peut donc :
 *   A. calibrer au bp le profil vendredi du 50e (P0) ;
 *   B. confronter la RECONSTRUCTION au COTÉ RÉEL — c'est la première fois que le pipeline peut le faire ;
 *   C. mesurer la JAMBE RÉELLEMENT TRADÉE (pas le proxy −r(vendredi)) avec le timestamp de sortie SONDÉ
 *      (leçon du 30e/40e : le moteur saute sam/dim, donc le trade ouvre le vendredi et sort le lundi) ;
 *   D. décomposer en SESSION (ven) + WEEK-END + reste, et contrôler la spécificité du jour ;
 *   E. re-tester le NET sous carry réel par année (leçon du 44e/47e) et sous coûts explicites 2.14 bp ;
 *   F. utiliser l'ère du PLANCHER SNB 2011-09→2015-01 comme EXPÉRIENCE NATURELLE : que devient un edge de
 *      risk-off quand le prix est ADMINISTRÉ ?
 *
 * Usage : java -cp "$CP" com.martinfou.trading.examples.RunFxRefugeCrossFade [--all|--p0|--real|--leg|--net]
 */
public class RunFxRefugeCrossFade {

    static final String YEAR_SPEC = "2006-2026";
    static final String[] PANEL8 = {"GBP_JPY", "GBP_USD", "AUD_USD", "NZD_USD", "USD_CHF", "EUR_USD", "USD_JPY", "USD_CAD"};
    /** Les 3 crosses REFUGES à coter réel, construits du panel : nom → {paire multiplicatrice gauche, droite}. */
    static final String[][] CROSSES = {
        {"GBP_CHF", "GBP_USD", "USD_CHF"},
        {"EUR_CHF", "EUR_USD", "USD_CHF"},
        {"AUD_CHF", "AUD_USD", "USD_CHF"},
    };

    /** Différentiels annuels (BASE − QUOTE) en %, 2006→2026 — table 3M du 47e (FRED), 21 valeurs. */
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

    /** 1 % de différentiel de taux = 0.274 bp de portage par jour (méthode 44e/47e). */
    static final double BP_PER_PCT_DAY = 0.274;
    /** Coûts explicites d'un aller-retour sur une majeure (leçon du 37e : 2.14 bp). */
    static final double COST_RT_BP = 2.14;
    /** Ère du plancher SNB (EUR_CHF administré) + retour à la normale. */
    static final LocalDate PEG_START = LocalDate.of(2011, 9, 6);
    static final LocalDate PEG_END = LocalDate.of(2015, 6, 30);

    // ---------------------------------------------------------------- données

    static final Set<String> ALL = new LinkedHashSet<>();
    static final Map<String, TreeMap<LocalDate, Double>> CLOSE = new LinkedHashMap<>();
    static final Map<String, TreeMap<LocalDate, Double>> RET = new LinkedHashMap<>();
    static final Map<String, List<Bar>> BARS = new LinkedHashMap<>();
    static final Map<String, List<LocalDate>> TRADINGDAYS = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "--all";
        System.out.println("========================================================================");
        System.out.println("LES CROSSES REFUGES SUR COTATIONS RÉELLES — le fade du vendredi tient-il hors reconstruction ?");
        System.out.println("Jeudi 8 oct 2026 (52e) · rotation INTERMARKET / CROSS-ASSET · " + YEAR_SPEC);
        System.out.println("Données : Dukascopy H1 réelles (GBP_CHF, EUR_CHF, AUD_CHF) + panel 8 paires pour la calibration.");
        System.out.println("========================================================================");
        loadAll();
        switch (mode) {
            case "--p0" -> { p0(); }
            case "--real" -> { realVsSynth(); profile(); }
            case "--leg" -> tradedLeg();
            case "--net" -> net();
            case "--all" -> { p0(); realVsSynth(); profile(); tradedLeg(); net(); verdict(); }
            default -> p0();
        }
        System.out.println("\nDONE");
    }

    static void loadAll() throws Exception {
        ALL.addAll(Arrays.asList(PANEL8));
        for (String[] c : CROSSES) ALL.add(c[0]);
        for (String sym : ALL) {
            List<Bar> bars;
            try {
                bars = HistoricalDataLoader.loadFromArgs(sym, sym, YEAR_SPEC).bars();
            } catch (Exception e) {
                System.out.println("[data] ECHEC " + sym + " : " + e.getMessage());
                continue;
            }
            if (bars == null || bars.isEmpty()) { System.out.println("[data] PAS DE DONNÉES " + sym); continue; }
            bars.sort(Comparator.comparing(Bar::timestamp));
            BARS.put(sym, bars);
            TreeMap<LocalDate, Double> closes = new TreeMap<>();
            List<LocalDate> days = new ArrayList<>();
            for (Bar b : bars) {
                if (b.high() <= b.low()) continue;                    // barre de carry (40e/48e)
                LocalDate d = b.timestamp().atZone(ZoneId.of("UTC")).toLocalDate();
                if (!closes.containsKey(d)) days.add(d);
                closes.put(d, b.close());
            }
            CLOSE.put(sym, closes);
            TRADINGDAYS.put(sym, days);
            TreeMap<LocalDate, Double> r = new TreeMap<>();
            LocalDate prev = null;
            for (LocalDate d : days) {
                if (prev != null) {
                    double pc = closes.get(prev);
                    if (pc > 0) r.put(d, (closes.get(d) / pc - 1.0) * 10_000.0);
                }
                prev = d;
            }
            RET.put(sym, r);
            System.out.printf("[data] %-9s %6d barres réelles · %5d jours · %5d retours · %s → %s%n",
                sym, bars.size(), closes.size(), r.size(), days.get(0), days.get(days.size() - 1));
        }
    }

    /** Cross synthétique depuis les retours journaliers du panel (méthode exacte du 50e). */
    static TreeMap<LocalDate, Double> synthetic(String left, String right) {
        TreeMap<LocalDate, Double> ra = RET.get(left), rb = RET.get(right);
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        boolean multiply = right.startsWith("USD_");
        for (var e : ra.entrySet()) {
            Double y = rb.get(e.getKey());
            if (y == null) continue;
            double x1 = 1 + e.getValue() / 10_000.0, y1 = 1 + y / 10_000.0;
            out.put(e.getKey(), ((multiply ? x1 * y1 : x1 / y1) - 1.0) * 10_000.0);
        }
        return out;
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

    // ---------------------------------------------------------------- A. P0

    static void p0() {
        section("A) P0 CALIBRATION — profil JOUR-DE-SEMAINE (retour de SESSION, clôtures UTC)");
        System.out.println("Réf 50e (à reproduire au bp) : GBP_JPY −5.61 (t −2.39) · GBP_USD −5.34 (t −2.86) · EUR −3.07 · USD_CAD +0.55");
        System.out.printf("%-9s %-5s %10s %7s %9s %8s %7s%n", "PAIRE", "JOUR", "bp", "n", "médiane", "hit<0%", "t");
        for (String sym : PANEL8) {
            if (!RET.containsKey(sym)) continue;
            Stat f = stat(pick(RET.get(sym), DayOfWeek.FRIDAY, 2006, 2026));
            System.out.printf("%-9s %-5s %+10.4f %7d %+9.4f %7.0f%% %+7.2f%n",
                sym, "VEN", f.mean(), f.n(), f.median(), f.hitNeg(), f.t());
        }
        System.out.printf("%n%-9s %-10s %10s %7s %8s %7s%n", "SÉRIE", "ORIGINE", "VEN bp", "n", "hit<0%", "t");
        for (String[] c : CROSSES) {
            TreeMap<LocalDate, Double> syn = synthetic(c[1], c[2]);
            Stat s = stat(pick(syn, DayOfWeek.FRIDAY, 2006, 2026));
            System.out.printf("%-9s %-10s %+10.4f %7d %8.0f%% %+7.2f%n", c[0], "synth 50e", -s.mean(), s.n(), s.hitNeg(), -s.t());
        }
        System.out.println("Lecture : un retour NÉGATIF le vendredi ⇒ le fade SELL le capte (signe inversé ci-dessus).");
        System.out.println("Le 50e annonçait : GBP_CHF +9.08 (t 5.13) · AUD_CHF +8.27 · EUR_CHF +6.93.");
    }

    // ---------------------------------------------------------------- B. réel vs synthétique

    record Pair(String name, TreeMap<LocalDate, Double> real, TreeMap<LocalDate, Double> syn) {}

    static List<Pair> pairs() {
        List<Pair> out = new ArrayList<>();
        for (String[] c : CROSSES) {
            if (!RET.containsKey(c[0])) continue;
            out.add(new Pair(c[0], RET.get(c[0]), synthetic(c[1], c[2])));
        }
        return out;
    }

    static void realVsSynth() {
        section("B) RÉEL vs RECONSTRUCTION — la jambe reconstruite est-elle le même objet que le cross coté ?");
        System.out.printf("%-9s %7s %10s %12s %12s %11s %13s%n",
            "CROSS", "n", "corr", "diff moy bp", "diff sd bp", "diff max", "diff VEN moy");
        for (Pair p : pairs()) {
            List<Double> d = new ArrayList<>();
            List<Double> x = new ArrayList<>(), y = new ArrayList<>();
            double mx = 0;
            for (var e : p.real().entrySet()) {
                Double s = p.syn().get(e.getKey());
                if (s == null) continue;
                double dd = e.getValue() - s;
                d.add(dd); x.add(e.getValue()); y.add(s);
                mx = Math.max(mx, Math.abs(dd));
            }
            if (d.size() < 100) { System.out.println(p.name() + " : pas assez de dates communes"); continue; }
            double corr = corr(x, y);
            Stat sd = stat(d);
            double fri = 0;
            int nf = 0;
            Stat rf = stat(pick(p.real(), DayOfWeek.FRIDAY, 2006, 2026));
            Stat sf = stat(pick(p.syn(), DayOfWeek.FRIDAY, 2006, 2026));
            System.out.printf("%-9s %7d %10.6f %+12.4f %12.4f %11.2f %+13.4f%n",
                p.name(), d.size(), corr, sd.mean(), sd.sd(), mx, rf.mean() - sf.mean());
        }
        System.out.println("Lecture : la corrélation est ≈1 par construction (identité des retours multiplicateurs) — ce qui");
        System.out.println("compte est la DISPERSION de l'écart, à comparer au signal de 5-9 bp. Si l'écart-type est du même");
        System.out.println("ordre que l'edge, l'attribution du 50e est une statistique de RECONSTRUCTION, pas de marché.");
    }

    static double corr(List<Double> a, List<Double> b) {
        int n = a.size();
        double ma = a.stream().mapToDouble(d -> d).average().orElse(0);
        double mb = b.stream().mapToDouble(d -> d).average().orElse(0);
        double sa = 0, sb = 0, sab = 0;
        for (int i = 0; i < n; i++) {
            double da = a.get(i) - ma, db = b.get(i) - mb;
            sa += da * da; sb += db * db; sab += da * db;
        }
        return sab / Math.sqrt(sa * sb);
    }

    // ---------------------------------------------------------------- C. profil vendredi réel

    static void profile() {
        section("C) PROFIL VENDREDI DES CROSSES RÉELS — fade = −r(vendredi), bp");
        System.out.printf("%-9s %10s %7s %9s %8s %7s %14s %22s%n",
            "CROSS", "fade bp", "n", "médiane", "hit fd%", "t", "IS→OOS", "décennies 06-09/10-14/15-19/20-26");
        for (Pair p : pairs()) {
            Stat s = stat(pick(p.real(), DayOfWeek.FRIDAY, 2006, 2026));
            Stat is = stat(pick(p.real(), DayOfWeek.FRIDAY, 2006, 2015));
            Stat oos = stat(pick(p.real(), DayOfWeek.FRIDAY, 2016, 2026));
            System.out.printf("%-9s %+10.4f %7d %+9.4f %8.0f%% %+7.2f  %+.2f→%+.2f  %+8.3f %+8.3f %+8.3f %+8.3f%n",
                p.name(), -s.mean(), s.n(), -s.median(), s.hitNeg(), -s.t(), -is.mean(), -oos.mean(),
                -stat(pick(p.real(), DayOfWeek.FRIDAY, 2006, 2009)).mean(),
                -stat(pick(p.real(), DayOfWeek.FRIDAY, 2010, 2014)).mean(),
                -stat(pick(p.real(), DayOfWeek.FRIDAY, 2015, 2019)).mean(),
                -stat(pick(p.real(), DayOfWeek.FRIDAY, 2020, 2026)).mean());
        }
        System.out.printf("%n%-9s %9s %9s %9s %9s %9s %9s%n", "CROSS", "lundi", "mardi", "mercredi", "jeudi", "VENDREDI", "Mon-Jeu");
        for (Pair p : pairs()) {
            System.out.printf("%-9s %+9.4f %+9.4f %+9.4f %+9.4f %+9.4f %+9.4f%n", p.name(),
                -stat(pick(p.real(), DayOfWeek.MONDAY, 2006, 2026)).mean(),
                -stat(pick(p.real(), DayOfWeek.TUESDAY, 2006, 2026)).mean(),
                -stat(pick(p.real(), DayOfWeek.WEDNESDAY, 2006, 2026)).mean(),
                -stat(pick(p.real(), DayOfWeek.THURSDAY, 2006, 2026)).mean(),
                -stat(pick(p.real(), DayOfWeek.FRIDAY, 2006, 2026)).mean(),
                -stat(pickMonThu(p.real(), 2006, 2026)).mean());
        }
        System.out.println("\nC2) EXPÉRIENCE NATURELLE — ère du PLANCHER SNB (" + PEG_START + " → " + PEG_END + ") : prix administré");
        System.out.printf("%-9s %14s %7s %14s %7s %14s%n", "CROSS", "AVANT (<plancher)", "n", "PENDANT", "n", "APRÈS (>plancher)");
        for (Pair p : pairs()) {
            List<Double> before = new ArrayList<>(), during = new ArrayList<>(), after = new ArrayList<>();
            for (var e : p.real().entrySet()) {
                if (e.getKey().getDayOfWeek() != DayOfWeek.FRIDAY) continue;
                if (e.getKey().isBefore(PEG_START)) before.add(-e.getValue());
                else if (e.getKey().isAfter(PEG_END)) after.add(-e.getValue());
                else during.add(-e.getValue());
            }
            System.out.printf("%-9s %+14.4f %7d %+14.4f %7d %+14.4f%n",
                p.name(), stat(before).mean(), before.size(), stat(during).mean(), during.size(), stat(after).mean());
        }
        System.out.println("Lecture : pendant le plancher, EUR_CHF ne peut pas porter de prime de risk-off — le prix est ADMINISTRÉ.");
        System.out.println("Un edge de risque qui survit à l'ère du plancher est un artefact ; un edge qui DISPARAÎT pendant et");
        System.out.println("REVIENT après est la signature d'un vrai mécanisme de marché.");
    }

    // ---------------------------------------------------------------- D. jambe réellement tradée

    record Trade(String cross, LocalDate friday, LocalDate exitDate, double entry, double exit,
                 double sessionBp, double weekendBp, double totalBp, double hours) {}

    static List<Trade> legs(String sym) {
        List<Bar> bars = BARS.get(sym);
        List<Trade> out = new ArrayList<>();
        if (bars == null) return out;
        List<Bar> real = new ArrayList<>();
        for (Bar b : bars) if (b.high() > b.low()) real.add(b);
        if (real.size() < 100) return out;
        int i = 0;
        while (i < real.size()) {
            LocalDate d = real.get(i).timestamp().atZone(ZoneId.of("UTC")).toLocalDate();
            int j = i;
            while (j + 1 < real.size()
                && real.get(j + 1).timestamp().atZone(ZoneId.of("UTC")).toLocalDate().equals(d)) j++;
            // d = une journée de trading réelle [i..j]
            if (d.getDayOfWeek() == DayOfWeek.FRIDAY && i + 1 <= j && j + 2 < real.size()) {
                Bar entry = real.get(i + 1);          // fill T+1 (le moteur exécute sur la barre suivante)
                Bar exit = real.get(j + 2);           // sortie : la barre qui suit la 1re barre du lundi (T+1)
                double sessionBp = Double.NaN;
                if (i - 1 >= 0) {
                    double prevClose = real.get(i - 1).close();
                    sessionBp = (real.get(j).close() / prevClose - 1.0) * 10_000.0;
                }
                double weekendBp = (exit.open() / real.get(j).close() - 1.0) * 10_000.0;
                double totalBp = -(exit.open() / entry.open() - 1.0) * 10_000.0;   // SELL
                double hours = Duration.between(entry.timestamp(), exit.timestamp()).toMinutes() / 60.0;
                out.add(new Trade(sym, d, exit.timestamp().atZone(ZoneId.of("UTC")).toLocalDate(),
                    entry.open(), exit.open(), -sessionBp, -weekendBp, totalBp, hours));
            }
            i = j + 1;
        }
        return out;
    }

    /** Différentiel annuel du cross (base − quote) en %, année → valeur. */
    static double diffYear(String base, String quote, int year) {
        String bk = base + "_USD", qk = "USD_" + quote;
        double[] a = DIFF.get(bk), b = DIFF.get(qk);
        if (a == null || b == null) return 0;
        int k = Math.max(0, Math.min(a.length - 1, year - 2006));
        return a[k] + b[k];         // (base − USD) + (USD − quote) = base − quote
    }

    static void tradedLeg() {
        section("D) LA JAMBE RÉELLEMENT TRADÉE — entrée = 2e barre du vendredi (fill T+1), sortie = barre du lundi +1");
        System.out.println("Le proxy « −r(vendredi) » mesure la SESSION ; le trade porte en plus la jambe WEEK-END (leçon du 30e/43e).");
        System.out.printf("%-9s %7s %9s %10s %9s %9s %7s %9s %9s %10s%n",
            "CROSS", "n", "heures", "session bp", "week-end bp", "reste bp", "t", "TOTAL bp", "médiane", "hit>0%");
        for (String[] c : CROSSES) {
            List<Trade> tr = legs(c[0]);
            if (tr.isEmpty()) { System.out.println(c[0] + " : aucune jambe (données ?)"); continue; }
            List<Double> se = new ArrayList<>(), we = new ArrayList<>(), tot = new ArrayList<>(), res = new ArrayList<>();
            double hrs = 0;
            for (Trade t : tr) {
                se.add(t.sessionBp()); we.add(t.weekendBp()); tot.add(t.totalBp());
                res.add(t.totalBp() - t.sessionBp() - t.weekendBp());
                hrs += t.hours();
            }
            long pos = tot.stream().filter(d -> d > 0).count();
            Stat st = stat(tot);
            System.out.printf("%-9s %7d %9.1f %+10.4f %+9.4f %+9.4f %+7.2f %+9.4f %+9.4f %9.0f%%%n",
                c[0], tr.size(), hrs / tr.size(), stat(se).mean(), stat(we).mean(), stat(res).mean(), st.t(),
                st.mean(), st.median(), 100.0 * pos / tr.size());
        }
        System.out.println("\nD2) SPÉCIFICITÉ — le trade ouvre-t-il aussi bien un autre jour ? (mêmes règles, jour = lundi..jeudi)");
        System.out.printf("%-9s %10s %10s %10s %10s %10s%n", "CROSS", "lun", "mar", "mer", "jeu", "VEN");
        for (String[] c : CROSSES) {
            List<Trade> all = legsAllDays(c[0]);
            if (all.isEmpty()) continue;
            System.out.printf("%-9s", c[0]);
            for (DayOfWeek w : new DayOfWeek[]{DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY}) {
                List<Double> v = new ArrayList<>();
                for (Trade t : all) if (t.friday().getDayOfWeek() == w) v.add(t.totalBp());
                System.out.printf(" %+10.4f", stat(v).mean());
            }
            System.out.println();
        }
    }

    static List<Trade> legsAllDays(String sym) {
        List<Bar> bars = BARS.get(sym);
        List<Trade> out = new ArrayList<>();
        if (bars == null) return out;
        List<Bar> real = new ArrayList<>();
        for (Bar b : bars) if (b.high() > b.low()) real.add(b);
        int i = 0;
        while (i < real.size()) {
            LocalDate d = real.get(i).timestamp().atZone(ZoneId.of("UTC")).toLocalDate();
            int j = i;
            while (j + 1 < real.size()
                && real.get(j + 1).timestamp().atZone(ZoneId.of("UTC")).toLocalDate().equals(d)) j++;
            DayOfWeek w = d.getDayOfWeek();
            if (w != DayOfWeek.SATURDAY && w != DayOfWeek.SUNDAY && i + 1 <= j && j + 2 < real.size()) {
                Bar entry = real.get(i + 1), exit = real.get(j + 2);
                double sessionBp = i - 1 >= 0 ? (real.get(j).close() / real.get(i - 1).close() - 1.0) * 10_000.0 : Double.NaN;
                double weekendBp = (exit.open() / real.get(j).close() - 1.0) * 10_000.0;
                double totalBp = -(exit.open() / entry.open() - 1.0) * 10_000.0;
                out.add(new Trade(sym, d, exit.timestamp().atZone(ZoneId.of("UTC")).toLocalDate(),
                    entry.open(), exit.open(), -sessionBp, -weekendBp, totalBp,
                    Duration.between(entry.timestamp(), exit.timestamp()).toMinutes() / 60.0));
            }
            i = j + 1;
        }
        return out;
    }

    // ---------------------------------------------------------------- E. net

    static void net() {
        section("E) NET — coûts explicites 2.14 bp + carry RÉEL par année (3 rollovers ven→lun)");
        System.out.println("Carry SELL = −0.274 bp/jour × différentiel annuel (base − quote) ; table du 47e, PAS de constante.");
        System.out.printf("%-9s %10s %11s %11s %12s %10s %9s %9s%n",
            "CROSS", "brut bp", "carry 3j", "coûts", "NET bp", "PF proxy", "t net", "hit net%");
        for (String[] c : CROSSES) {
            List<Trade> tr = legs(c[0]);
            if (tr.isEmpty()) continue;
            double diffMean = 0;
            List<Double> nets = new ArrayList<>();
            for (Trade t : tr) {
                double d = diffYear(c[0].substring(0, 3), c[0].substring(4), t.friday().getYear());
                diffMean += d;
                double carry = 3.0 * (-BP_PER_PCT_DAY * d);          // SELL : −0.274 × diff × 3
                nets.add(t.totalBp() + carry - COST_RT_BP);
            }
            diffMean /= tr.size();
            double gp = 0, gl = 0;
            for (double v : nets) { if (v > 0) gp += v; else gl -= v; }
            Stat sn = stat(nets);
            long pos = nets.stream().filter(d -> d > 0).count();
            System.out.printf("%-9s %+10.4f %+11.4f %+11.4f %+12.4f %10.3f %+9.2f %8.0f%%%n",
                c[0], stat(tr.stream().map(Trade::totalBp).toList()).mean(), 3 * (-BP_PER_PCT_DAY * diffMean),
                -COST_RT_BP, sn.mean(), gl > 0 ? gp / gl : Double.POSITIVE_INFINITY, sn.t(), 100.0 * pos / nets.size());
        }
        System.out.println("\nE2) STABILITÉ PAR DÉCENNIE du NET (bp/trade) et seuil de rupture du carry :");
        System.out.printf("%-9s %10s %10s %10s %10s %12s %12s %9s%n",
            "CROSS", "2006-09", "2010-14", "2015-19", "2020-26", "edge brut", "seuil bp/j", "marge");
        for (String[] c : CROSSES) {
            List<Trade> tr = legs(c[0]);
            if (tr.isEmpty()) continue;
            System.out.printf("%-9s", c[0]);
            for (int[] span : new int[][]{{2006, 2009}, {2010, 2014}, {2015, 2019}, {2020, 2026}}) {
                List<Double> v = new ArrayList<>();
                for (Trade t : tr) {
                    int y = t.friday().getYear();
                    if (y < span[0] || y > span[1]) continue;
                    double d = diffYear(c[0].substring(0, 3), c[0].substring(4), y);
                    v.add(t.totalBp() + 3.0 * (-BP_PER_PCT_DAY * d) - COST_RT_BP);
                }
                System.out.printf(" %+10.4f", stat(v).mean());
            }
            double edge = stat(tr.stream().map(Trade::totalBp).toList()).mean();
            double diffMean = 0;
            for (Trade t : tr) diffMean += diffYear(c[0].substring(0, 3), c[0].substring(4), t.friday().getYear());
            diffMean /= tr.size();
            double carryDay = -BP_PER_PCT_DAY * diffMean;
            double seuil = edge / 3.0;
            System.out.printf(" %+12.4f %+12.4f %9s%n", edge, seuil,
                carryDay >= 0 ? "∞ (crédit)" : (seuil <= 0 ? "0 (edge<0)" : String.format("%.2fx", seuil / Math.abs(carryDay))));
        }
        System.out.println("\nE3) CONTRÔLE HORS CRISE — on retire l'ère du plancher SNB (" + PEG_START + "→" + PEG_END + ") :");
        System.out.printf("%-9s %11s %7s %11s %7s%n", "CROSS", "net AVEC ère", "n", "net SANS ère", "n");
        for (String[] c : CROSSES) {
            List<Trade> tr = legs(c[0]);
            if (tr.isEmpty()) continue;
            List<Double> with = new ArrayList<>(), without = new ArrayList<>();
            for (Trade t : tr) {
                double d = diffYear(c[0].substring(0, 3), c[0].substring(4), t.friday().getYear());
                double v = t.totalBp() + 3.0 * (-BP_PER_PCT_DAY * d) - COST_RT_BP;
                with.add(v);
                if (!(t.friday().isAfter(PEG_START) && t.friday().isBefore(PEG_END))) without.add(v);
            }
            System.out.printf("%-9s %+11.4f %7d %+11.4f %7d%n", c[0], stat(with).mean(), with.size(), stat(without).mean(), without.size());
        }
        System.out.println("\n⚠️ PF proxy calculé sur des trades en bp (coûts + carry explicites), PAS un backtest moteur :");
        System.out.println("   il valide la DIRECTION et l'ordre de grandeur, pas encore la promotion en catalogue (gate 30 trades / 3-4 paires).");
    }

    static void verdict() {
        section("F) VERDICT");
        System.out.println("À lire dans la note Joplin : le réel reproduit-il la reconstruction, l'edge survit-il au plancher SNB,");
        System.out.println("et la jambe tradée (avec la dérive de week-end) conserve-t-elle le signe du proxy de session.");
    }
}
