package com.martinfou.trading.intelligence.research;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;

import java.nio.file.Paths;
import java.time.*;
import java.util.*;

/**
 * FxMonthEndFlowCheck — Pré-validation (Pattern D) du FLUX DE FIN DE MOIS conditionné
 * à l'AMPLITUDE du mouvement du mois.
 *
 * 📊 CONTEXTE (mercredi 23 sept 2026 = pattern saisonnier, 41e résultat)
 *   Piste ouverte explicitement par le 40e résultat (FxHourOfDay, 21 sept) :
 *     « conditionner le fix de fin de mois sur l'AMPLITUDE DU MOUVEMENT DU MOIS —
 *       un flux de rebalancement scale avec le déséquilibre, pas avec le calendrier. »
 *   Le 40e avait mesuré la SEULE cellule trans-paire cohérente du profil horaire :
 *   7/7 paires USD FORT au fix du dernier jour ouvrable (AUD -2.28, CHF +1.74,
 *   JPY +1.57, EUR -1.60 bp) — MAIS leg max 2.28 bp ≈ seuil 2.14 bp, IS incohérent
 *   vs OOS unanime (late-bloomer) et panier = 7x les coûts. La dimension NON
 *   calendaire (l'amplitude du déséquilibre) n'avait jamais été testée.
 *
 * 🎯 HYPOTHÈSE (H_rebal)
 *   À la fin du mois, les flux de rebalancement (pensions, mandats, couvertures de
 *   ratio) ramènent les expositions vers leur poids cible. Le flux est proportionnel
 *   au DÉSÉQUILIBRE accumulé pendant le mois, donc :
 *     (1) le dernier jour du mois va CONTRE le mouvement du mois (réversion) ;
 *     (2) la magnitude de cette réversion est MONOTONE en |mouvement du mois| ;
 *     (3) l'effet est cohérent en USD (le rebalancement est un flux de dollar).
 *   H0 (nulle) : le dernier jour est un jour comme un autre (Pattern D — la dérive
 *   d'ère est l'artefact dominant, 5 cas au compteur ; le contrôle de dérive est
 *   donc INCLUS : les mêmes statistiques sans conditionnement sont imprimées en P1).
 *
 * MÉTHODE ANTI-ARTEFACT (Pattern D, méthode du 5 août, durcie le 7/21 sept)
 *   P0  CALIBRATION : reproduire deux chiffres déjà publiés par le pipeline
 *       (vendredi EUR/USD -0.032% du 37e ; lundi inconditionnel IS -2.11 bp du 40e)
 *       sous DEUX définitions de la barre quotidienne — INCL_CARRY (dernière barre
 *       du jour, définition historique) et REAL (dernière barre avec high>low,
 *       correction structurelle du 40e). Sans calibration, une mesure journalière
 *       n'est pas comparable aux résultats antérieurs.
 *   P1  BASELINE / CONTRÔLE DE DÉRIVE : rendement moyen du jour sans conditionnement.
 *   P2  CONDITIONNEMENT PAR SIGNE : le dernier jour va-t-il contre le mois ?
 *   P3  CONDITIONNEMENT PAR MAGNITUDE : quintiles de |mouvement du mois| —
 *       monotonie = signature d'un flux ; plat = bruit.
 *   P4  AGRÉGAT USD : unanimité trans-paire + CO-MOUVEMENT mesuré SÉPARÉMENT de la
 *       moyenne (leçon du 17 sept : les corrélations croisées doivent être mesurées,
 *       pas déduites d'une cohérence de signes).
 *   P5  SPÉCIFICITÉ DU JOUR : offsets -1/-2/-3/-4 ET premier jour du mois suivant,
 *       plus deux contrôles intra-mois (milieu de mois) — un vrai effet de fin de
 *       mois doit être LOCALISÉ sur la bordure.
 *   P6  PAR DÉCENNIE : un effet qui change de signe entre décennies est une dérive
 *       d'ère (6e cas au compteur : h20 du 40e), pas un flux.
 *
 * ⚠️ Ceci est une PRÉ-VALIDATION. Aucun verdict de tradeabilité sans backtest AVEC
 *    coûts (0.07$/trade + 0.01% slippage) et sans swap lu séparément.
 *    Seuil de survie FX journalier ≈ 3.4 bp (2.14 coûts + ~1.2 swap sur hold 24 h).
 */
public class FxMonthEndFlowCheck {

    private static final String BARS_DIR = "data/historical/bars";
    private static final String[] SYMBOLS = {
        "EUR_USD", "GBP_USD", "USD_JPY", "USD_CAD", "USD_CHF", "AUD_USD", "NZD_USD", "GBP_JPY"
    };
    private static final int FROM = 2006, TO = 2026;
    private static final int IS_END = 2015;
    private static final double THRESHOLD_BP = 3.4;

    public static void main(String[] args) throws Exception {
        Map<String, TreeMap<LocalDate, Double>> real = new LinkedHashMap<>();
        Map<String, TreeMap<LocalDate, Double>> incl = new LinkedHashMap<>();
        for (String s : SYMBOLS) {
            real.put(s, dailyCloses(s, true));
            incl.put(s, dailyCloses(s, false));
        }
        System.out.println("### FxMonthEndFlowCheck — " + SYMBOLS.length + " paires, " + FROM + "-" + TO
            + " (IS <= " + IS_END + ") — définition REAL (high>low) sauf mention contraire");
        for (String s : SYMBOLS)
            System.out.printf("# %-8s jours réels=%4d | jours incl. carry=%4d%n",
                s, real.get(s).size(), incl.get(s).size());

        part0_Calibration(real, incl);
        part1_Baseline(real);
        part2_SignConditional(real);
        part3_MagnitudeQuintiles(real);
        part4_UsdAggregate(real);
        part5_Specificity(real);
        part6_Decades(real);
    }

    // ================================================================ P0

    private static void part0_Calibration(Map<String, TreeMap<LocalDate, Double>> real,
                                          Map<String, TreeMap<LocalDate, Double>> incl) {
        System.out.println();
        System.out.println("##################################################################");
        System.out.println("# P0 — CALIBRATION (reproduire le 37e et le 40e)                  #");
        System.out.println("##################################################################");
        for (String def : new String[]{"REAL", "INCL_CARRY"}) {
            Map<String, TreeMap<LocalDate, Double>> src = def.equals("REAL") ? real : incl;
            System.out.printf("%n--- def %s ---%n", def);
            for (String s : new String[]{"EUR_USD", "GBP_USD"}) {
                List<Double> fri = new ArrayList<>();
                for (DayReturn d : dayReturns(src.get(s)))
                    if (d.date.getDayOfWeek() == DayOfWeek.FRIDAY) fri.add(d.ret);
                System.out.printf("  %-8s vendredi n=%4d avg=%+7.4f%%  (37e attendu EUR -0.0320%% / GBP -0.0540%%)%n",
                    s, fri.size(), mean(fri) * 100);
            }
            List<Double> mon = new ArrayList<>(), monIS = new ArrayList<>(), monOOS = new ArrayList<>();
            for (String s : SYMBOLS)
                for (DayReturn d : dayReturns(src.get(s)))
                    if (d.date.getDayOfWeek() == DayOfWeek.MONDAY) {
                        mon.add(d.ret);
                        (d.date.getYear() <= IS_END ? monIS : monOOS).add(d.ret);
                    }
            System.out.printf("  LUNDI inconditionnel (8 paires) n=%d avg=%+6.3f%% t=%5.1f | IS %+6.3f%% | OOS %+6.3f%%%n",
                mon.size(), mean(mon) * 100, tstatArr(toArr(mon)), mean(monIS) * 100, mean(monOOS) * 100);
            System.out.printf("  -> attendu 40e : IS = -2.11 bp ; OOS = +5.8 bp%n");
        }
    }

    // ================================================================ P1

    private static void part1_Baseline(Map<String, TreeMap<LocalDate, Double>> real) {
        System.out.println();
        System.out.println("##################################################################");
        System.out.println("# P1 — BASELINE / CONTRÔLE DE DÉRIVE (sans conditionnement)        #");
        System.out.println("##################################################################");
        System.out.println("mesure            |   n   avg(bp)   hit   med(bp)    t  | IS(bp) | OOS(bp) | WF");
        for (String s : SYMBOLS) {
            List<MonthObs> ms = months(real.get(s));
            System.out.printf("%n--- %s ---%n", s);
            reportRow("J-1 (dernier j)", collect(ms, m -> m.rWin(1, 1)));
            reportRow("3 derniers jours", collect(ms, m -> m.rWin(3, 1)));
            reportRow("5 derniers jours", collect(ms, m -> m.rWin(5, 1)));
            reportRow("1er j mois suiv.", collect(ms, m -> m.rFirstNext()));
        }
    }

    // ================================================================ P2

    private static void part2_SignConditional(Map<String, TreeMap<LocalDate, Double>> real) {
        System.out.println();
        System.out.println("##################################################################");
        System.out.println("# P2 — CONDITIONNEMENT PAR SIGNE du mouvement du mois (avant J-1)  #");
        System.out.println("##################################################################");
        System.out.println("La réversion prédite : mois HAUSSIER -> dernier jour BAISSIER.");
        System.out.println("rev = -sign(mois) x r(J-1)  : positif = le dernier jour va CONTRE le mois");
        System.out.println();
        System.out.println("paire     | cell        n   r(J-1)bp   rev bp   t(rev) | rev IS  rev OOS | WF");
        for (String s : SYMBOLS) {
            List<MonthObs> ms = months(real.get(s));
            for (int cell = 0; cell < 2; cell++) {
                final int c = cell;
                List<Obs> obs = new ArrayList<>();
                for (MonthObs m : ms) {
                    double cond = m.mtdBeforeWin(1, 1), r = m.rWin(1, 1);
                    if (Double.isNaN(cond) || Double.isNaN(r)) continue;
                    boolean up = cond > 0;
                    if ((c == 0) == up) obs.add(new Obs(m.year, r, -Math.signum(cond) * r));
                }
                double[] raw = obs.stream().mapToDouble(o -> o.raw).toArray();
                double[] rev = obs.stream().mapToDouble(o -> o.rev).toArray();
                double[] revIS = obs.stream().filter(o -> o.year <= IS_END).mapToDouble(o -> o.rev).toArray();
                double[] revOOS = obs.stream().filter(o -> o.year > IS_END).mapToDouble(o -> o.rev).toArray();
                System.out.printf("%-9s | %-9s %4d %+8.3f %+8.3f %7.2f | %+7.3f %+8.3f | %s%n",
                    c == 0 ? s : "", c == 0 ? "mois UP" : "mois DOWN",
                    obs.size(), meanArr(raw) * 10000, meanArr(rev) * 10000, tstatArr(rev),
                    meanArr(revIS) * 10000, meanArr(revOOS) * 10000,
                    wfTagArr(revIS, revOOS));
            }
        }
        // Poolé 8 paires
        List<Obs> up = new ArrayList<>(), dn = new ArrayList<>();
        for (String s : SYMBOLS)
            for (MonthObs m : months(real.get(s))) {
                double cond = m.mtdBeforeWin(1, 1), r = m.rWin(1, 1);
                if (Double.isNaN(cond) || Double.isNaN(r)) continue;
                Obs o = new Obs(m.year, r, -Math.signum(cond) * r);
                (cond > 0 ? up : dn).add(o);
            }
        System.out.println();
        printPool("POOL 8p mois UP", up);
        printPool("POOL 8p mois DOWN", dn);
        List<Obs> all = new ArrayList<>(up); all.addAll(dn);
        printPool("POOL 8p TOUS", all);
    }

    // ================================================================ P3

    private static void part3_MagnitudeQuintiles(Map<String, TreeMap<LocalDate, Double>> real) {
        System.out.println();
        System.out.println("##################################################################");
        System.out.println("# P3 — CONDITIONNEMENT PAR MAGNITUDE (quintiles de |mois|)         #");
        System.out.println("##################################################################");
        System.out.println("H_rebal : rev (bp) MONOTONE croissant en |mouvement du mois|.");
        System.out.println("Plat = pas de flux ; décroissant = bruit/artefact.");
        System.out.println();
        List<Obs> all = new ArrayList<>();
        for (String s : SYMBOLS)
            for (MonthObs m : months(real.get(s))) {
                double cond = m.mtdBeforeWin(1, 1), r = m.rWin(1, 1);
                if (Double.isNaN(cond) || Double.isNaN(r)) continue;
                all.add(new Obs(m.year, r, -Math.signum(cond) * r, Math.abs(cond)));
            }
        List<Obs> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparingDouble(o -> o.condAbs));
        int q = 5, n = sorted.size();
        System.out.println("quintile |   n    |mois| moy  rev bp    t   | rev IS  rev OOS | WF");
        for (int i = 0; i < q; i++) {
            int a = (int) Math.round(i * n / (double) q), b = (int) Math.round((i + 1) * n / (double) q);
            List<Obs> cell = sorted.subList(a, Math.min(b, n));
            double[] rev = cell.stream().mapToDouble(o -> o.rev).toArray();
            double[] revIS = cell.stream().filter(o -> o.year <= IS_END).mapToDouble(o -> o.rev).toArray();
            double[] revOOS = cell.stream().filter(o -> o.year > IS_END).mapToDouble(o -> o.rev).toArray();
            double avgAbs = cell.stream().mapToDouble(o -> o.condAbs).average().orElse(0);
            System.out.printf("Q%d       | %5d | %+8.3f%%  %+8.3f %6.2f | %+7.3f %+8.3f | %s%n",
                i + 1, cell.size(), avgAbs * 100, meanArr(rev) * 10000, tstatArr(rev),
                meanArr(revIS) * 10000, meanArr(revOOS) * 10000, wfTagArr(revIS, revOOS));
        }
        // Fenêtre de 3 jours
        System.out.println();
        System.out.println("--- variante : fenêtre des 3 DERNIERS JOURS ---");
        List<Obs> all3 = new ArrayList<>();
        for (String s : SYMBOLS)
            for (MonthObs m : months(real.get(s))) {
                double cond = m.mtdBeforeWin(3, 1), r = m.rWin(3, 1);
                if (Double.isNaN(cond) || Double.isNaN(r)) continue;
                all3.add(new Obs(m.year, r, -Math.signum(cond) * r, Math.abs(cond)));
            }
        List<Obs> s3 = new ArrayList<>(all3);
        s3.sort(Comparator.comparingDouble(o -> o.condAbs));
        System.out.println("quintile |   n    |mois| moy  rev3 bp    t   | rev IS  rev OOS | WF");
        for (int i = 0; i < q; i++) {
            int a = (int) Math.round(i * s3.size() / (double) q), b = (int) Math.round((i + 1) * s3.size() / (double) q);
            List<Obs> cell = s3.subList(a, Math.min(b, s3.size()));
            double[] rev = cell.stream().mapToDouble(o -> o.rev).toArray();
            double[] revIS = cell.stream().filter(o -> o.year <= IS_END).mapToDouble(o -> o.rev).toArray();
            double[] revOOS = cell.stream().filter(o -> o.year > IS_END).mapToDouble(o -> o.rev).toArray();
            double avgAbs = cell.stream().mapToDouble(o -> o.condAbs).average().orElse(0);
            System.out.printf("Q%d       | %5d | %+8.3f%%  %+8.3f %6.2f | %+7.3f %+8.3f | %s%n",
                i + 1, cell.size(), avgAbs * 100, meanArr(rev) * 10000, tstatArr(rev),
                meanArr(revIS) * 10000, meanArr(revOOS) * 10000, wfTagArr(revIS, revOOS));
        }
    }

    // ================================================================ P4

    private static void part4_UsdAggregate(Map<String, TreeMap<LocalDate, Double>> real) {
        System.out.println();
        System.out.println("##################################################################");
        System.out.println("# P4 — AGRÉGAT USD : unanimité + CO-MOUVEMENT mesuré              #");
        System.out.println("##################################################################");
        // Pour chaque paire, USD-return = +/- r ; condé sur le mois USD.
        Map<String, List<Obs>> byPair = new LinkedHashMap<>();
        for (String s : SYMBOLS) {
            double sign = s.startsWith("USD_") ? 1.0 : -1.0;
            List<Obs> obs = new ArrayList<>();
            for (MonthObs m : months(real.get(s))) {
                double cond = m.mtdBeforeWin(1, 1), r = m.rWin(1, 1);
                if (Double.isNaN(cond) || Double.isNaN(r)) continue;
                double uc = sign * cond, ur = sign * r;
                obs.add(new Obs(m.year, ur, -Math.signum(uc) * ur));
            }
            byPair.put(s, obs);
        }
        for (String s : SYMBOLS) {
            List<Obs> obs = byPair.get(s);
            List<Obs> dn = new ArrayList<>(), up = new ArrayList<>();
            for (Obs o : obs) (o.raw < 0 ? dn : up).add(o);
            System.out.printf("%-8s | mois USD DOWN n=%3d rev %+7.3f bp t %5.2f | mois USD UP n=%3d rev %+7.3f bp t %5.2f%n",
                s, dn.size(), meanRev(dn) * 10000, tstatRev(dn), up.size(), meanRev(up) * 10000, tstatRev(up));
        }
        // Unanimité par mois : combien de paires vont contre le mois USD ?
        Map<LocalDate, List<Double>> perMonth = new TreeMap<>();
        for (String s : SYMBOLS) {
            List<MonthObs> ms = months(real.get(s));
            for (MonthObs m : ms) {
                double cond = m.mtdBeforeWin(1, 1), r = m.rWin(1, 1);
                if (Double.isNaN(cond) || Double.isNaN(r)) continue;
                double sign = s.startsWith("USD_") ? 1.0 : -1.0;
                double uc = sign * cond, ur = sign * r;
                perMonth.computeIfAbsent(LocalDate.of(m.year, m.month, 1), k -> new ArrayList<>())
                        .add(-Math.signum(uc) * ur);
            }
        }
        int[] hist = new int[9];
        for (var e : perMonth.entrySet()) {
            long pos = e.getValue().stream().filter(d -> d > 0).count();
            hist[(int) pos]++;
        }
        System.out.println();
        System.out.printf("Unanimité « dernier jour contre le mois USD » sur %d fins de mois (8 paires) :%n", perMonth.size());
        for (int i = 0; i <= 8; i++) if (hist[i] > 0) System.out.printf("   %d/8 paires d'accord : %3d mois (%.1f%%)%n",
            i, hist[i], 100.0 * hist[i] / perMonth.size());

        // CO-MOUVEMENT mesuré : corrélation croisée moyenne sur les jours de fin de mois vs contrôle
        List<double[]> zRows = zscoredRows(real);
        Set<LocalDate> monthEndDays = new HashSet<>();
        for (List<LocalDate> group : monthGroups(real.get("EUR_USD")))
            if (group.size() >= 3) monthEndDays.add(group.get(group.size() - 1));
        List<double[]> target = new ArrayList<>(), control = new ArrayList<>();
        for (double[] row : zRows) {
            LocalDate d = LocalDate.ofEpochDay((long) row[0]);
            (monthEndDays.contains(d) ? target : control).add(row);
        }
        System.out.printf("%n|corr| croisée moyenne (8 paires, z-scores) : J-1 = %+.3f (n=%d jours) | contrôle = %+.3f (n=%d jours)%n",
            avgPairwiseCorr(target), target.size(), avgPairwiseCorr(control), control.size());
        System.out.println("(leçon du 17 sept : un facteur de POSITIONNEMENT décale les moyennes SANS élever les corrélations)");
    }

    // ================================================================ P5

    private static void part5_Specificity(Map<String, TreeMap<LocalDate, Double>> real) {
        System.out.println();
        System.out.println("##################################################################");
        System.out.println("# P5 — SPÉCIFICITÉ DU JOUR (offsets) + contrôles intra-mois        #");
        System.out.println("##################################################################");
        System.out.println("offset    |  n    rev bp     t    | rev IS  rev OOS | WF");
        int[] offsets = {1, 2, 3, 4, 8, 15};
        String[] labels = {"J-1 (dernier)", "J-2", "J-3", "J-4", "milieu ~J-8 (CTL)", "milieu ~J-15 (CTL)"};
        for (int i = 0; i < offsets.length; i++) {
            final int off = offsets[i];
            List<Obs> obs = new ArrayList<>();
            for (String s : SYMBOLS)
                for (MonthObs m : months(real.get(s))) {
                    double cond = m.mtdBeforeWin(1, off), r = m.rWin(1, off);
                    if (Double.isNaN(cond) || Double.isNaN(r)) continue;
                    obs.add(new Obs(m.year, r, -Math.signum(cond) * r));
                }
            printSpec(labels[i], obs);
        }
        // 1er jour du mois suivant
        List<Obs> first = new ArrayList<>();
        for (String s : SYMBOLS)
            for (MonthObs m : months(real.get(s))) {
                double full = m.mtdFull(), r = m.rFirstNext();
                if (Double.isNaN(full) || Double.isNaN(r)) continue;
                first.add(new Obs(m.year, r, -Math.signum(full) * r));
            }
        printSpec("1er j mois suiv.", first);
    }

    // ================================================================ P6

    private static void part6_Decades(Map<String, TreeMap<LocalDate, Double>> real) {
        System.out.println();
        System.out.println("##################################################################");
        System.out.println("# P6 — PAR DÉCENNIE (un effet qui change de signe = dérive d'ère)  #");
        System.out.println("##################################################################");
        int[][] decs = {{2006, 2010}, {2011, 2015}, {2016, 2020}, {2021, 2026}};
        System.out.println("fenêtre    | décennie     n   rev bp     t");
        for (int k : new int[]{1, 3}) {
            for (int[] d : decs) {
                List<Obs> obs = new ArrayList<>();
                for (String s : SYMBOLS)
                    for (MonthObs m : months(real.get(s))) {
                        if (m.year < d[0] || m.year > d[1]) continue;
                        double cond = m.mtdBeforeWin(k, 1), r = m.rWin(k, 1);
                        if (Double.isNaN(cond) || Double.isNaN(r)) continue;
                        obs.add(new Obs(m.year, r, -Math.signum(cond) * r));
                    }
                System.out.printf("%-10s | %d-%d   %4d  %+8.3f %6.2f%n",
                    k == 1 ? "J-1" : "3 derniers", d[0], d[1], obs.size(), meanRev(obs) * 10000, tstatRev(obs));
            }
        }
        System.out.printf("%nSeuil de survie FX journalier ≈ %.1f bp (2.14 coûts + ~1.2 swap sur hold 24 h).%n", THRESHOLD_BP);
    }

    // ================================================================ data structure

    /** Un mois : closes journalières (définition REAL), close de référence et 1er jour du mois suivant. */
    private static final class MonthObs {
        final int year, month;
        final double prevClose;      // dernier jour du mois précédent
        final double nextFirstClose; // 1er jour du mois suivant (NaN si absent)
        final double[] c;

        MonthObs(int year, int month, double prevClose, double nextFirstClose, double[] c) {
            this.year = year; this.month = month;
            this.prevClose = prevClose; this.nextFirstClose = nextFirstClose; this.c = c;
        }

        int n() { return c.length; }

        /** Rendement des {@code k} jours ouvrés se terminant au jour situé à {@code offset} de la fin (offset>=1). */
        double rWin(int k, int offset) {
            int iEnd = n() - offset;
            int iStart = iEnd - k;
            if (iEnd < 0 || iEnd >= n() || iStart < 0) return Double.NaN;
            if (c[iStart] <= 0) return Double.NaN;
            return c[iEnd] / c[iStart] - 1;
        }

        /** Mouvement du mois (vs close du mois précédent) jusqu'à la VEILLE de la fenêtre (look-ahead safe). */
        double mtdBeforeWin(int k, int offset) {
            int iStart = n() - offset - k;
            if (prevClose <= 0) return Double.NaN;
            double cc = iStart >= 0 ? c[iStart] : prevClose;
            return cc / prevClose - 1;
        }

        /** Mouvement du mois complet. */
        double mtdFull() {
            if (prevClose <= 0) return Double.NaN;
            return c[n() - 1] / prevClose - 1;
        }

        /** Rendement du 1er jour ouvré du mois suivant. */
        double rFirstNext() {
            if (Double.isNaN(nextFirstClose) || c[n() - 1] <= 0) return Double.NaN;
            return nextFirstClose / c[n() - 1] - 1;
        }
    }

    private static final class Obs {
        final int year; final double raw, rev, condAbs;
        Obs(int year, double raw, double rev) { this(year, raw, rev, 0); }
        Obs(int year, double raw, double rev, double condAbs) {
            this.year = year; this.raw = raw; this.rev = rev; this.condAbs = condAbs;
        }
    }

    private static final class DayReturn {
        final LocalDate date; final double ret;
        DayReturn(LocalDate d, double r) { date = d; ret = r; }
    }

    // ================================================================ loaders

    private static TreeMap<LocalDate, Double> dailyCloses(String symbol, boolean realOnly) throws Exception {
        TreeMap<LocalDate, Double> map = new TreeMap<>();
        Map<LocalDate, Instant> lastTs = new HashMap<>();
        for (int y = FROM; y <= TO; y++) {
            List<Bar> bars;
            try {
                bars = HistoricalDataLoader.loadYear(symbol, y, Paths.get(BARS_DIR));
            } catch (Exception ex) {
                continue;
            }
            if (bars == null) continue;
            for (Bar b : bars) {
                if (realOnly && !(b.high() > b.low())) continue;   // correction du 40e : barre réelle = high > low
                LocalDate d = b.timestamp().atZone(ZoneId.of("UTC")).toLocalDate();
                Instant prev = lastTs.get(d);
                if (prev == null || b.timestamp().isAfter(prev)) {
                    lastTs.put(d, b.timestamp());
                    map.put(d, b.close());
                }
            }
        }
        return map;
    }

    private static List<DayReturn> dayReturns(TreeMap<LocalDate, Double> closes) {
        List<DayReturn> out = new ArrayList<>();
        LocalDate prev = null; double pc = 0;
        for (var e : closes.entrySet()) {
            if (prev != null && pc > 0) out.add(new DayReturn(e.getKey(), (e.getValue() - pc) / pc));
            prev = e.getKey(); pc = e.getValue();
        }
        return out;
    }

    private static List<List<LocalDate>> monthGroups(TreeMap<LocalDate, Double> closes) {
        List<List<LocalDate>> out = new ArrayList<>();
        int curY = -1, curM = -1;
        List<LocalDate> cur = null;
        for (LocalDate d : closes.keySet()) {
            if (d.getYear() != curY || d.getMonthValue() != curM) {
                cur = new ArrayList<>();
                out.add(cur);
                curY = d.getYear(); curM = d.getMonthValue();
            }
            cur.add(d);
        }
        return out;
    }

    private static List<MonthObs> months(TreeMap<LocalDate, Double> closes) {
        List<List<LocalDate>> groups = monthGroups(closes);
        List<MonthObs> out = new ArrayList<>();
        for (int i = 1; i < groups.size(); i++) {
            List<LocalDate> g = groups.get(i);
            if (g.size() < 3) continue;
            double prevClose = closes.get(groups.get(i - 1).get(groups.get(i - 1).size() - 1));
            double nextFirst = (i + 1 < groups.size())
                ? closes.get(groups.get(i + 1).get(0)) : Double.NaN;
            double[] c = new double[g.size()];
            for (int j = 0; j < g.size(); j++) c[j] = closes.get(g.get(j));
            out.add(new MonthObs(g.get(0).getYear(), g.get(0).getMonthValue(), prevClose, nextFirst, c));
        }
        return out;
    }

    // ================================================================ reporting

    private static double[] collect(List<MonthObs> ms, java.util.function.ToDoubleFunction<MonthObs> f) {
        List<Double> v = new ArrayList<>();
        for (MonthObs m : ms) {
            double d = f.applyAsDouble(m);
            if (!Double.isNaN(d)) v.add(d);
        }
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        return a;
    }

    private static void reportRow(String label, double[] v) {
        double[] is = Arrays.stream(v).filter(x -> true).toArray();
        System.out.printf("%-17s | %4d %+8.3f %5.0f%% %+8.3f %6.2f%n",
            label, v.length, meanArr(v) * 10000, hitArr(v) * 100, medianArr(v) * 10000, tstatArr(v));
    }

    private static void printPool(String label, List<Obs> obs) {
        double[] rev = obs.stream().mapToDouble(o -> o.rev).toArray();
        double[] raw = obs.stream().mapToDouble(o -> o.raw).toArray();
        double[] is = obs.stream().filter(o -> o.year <= IS_END).mapToDouble(o -> o.rev).toArray();
        double[] oos = obs.stream().filter(o -> o.year > IS_END).mapToDouble(o -> o.rev).toArray();
        System.out.printf("%-18s n=%4d r(jour) %+7.3f bp | rev %+7.3f bp t %5.2f hit %4.0f%% med %+7.3f | IS %+7.3f OOS %+7.3f | %s%n",
            label, obs.size(), meanArr(raw) * 10000, meanArr(rev) * 10000, tstatArr(rev), hitArr(rev) * 100,
            medianArr(rev) * 10000, meanArr(is) * 10000, meanArr(oos) * 10000, wfTagArr(is, oos));
    }

    private static void printSpec(String label, List<Obs> obs) {
        double[] rev = obs.stream().mapToDouble(o -> o.rev).toArray();
        double[] is = obs.stream().filter(o -> o.year <= IS_END).mapToDouble(o -> o.rev).toArray();
        double[] oos = obs.stream().filter(o -> o.year > IS_END).mapToDouble(o -> o.rev).toArray();
        System.out.printf("%-17s | %4d %+8.3f %6.2f  | %+7.3f %+8.3f | %s%n",
            label, obs.size(), meanArr(rev) * 10000, tstatArr(rev),
            meanArr(is) * 10000, meanArr(oos) * 10000, wfTagArr(is, oos));
    }

    private static double meanRev(List<Obs> obs) {
        return obs.stream().mapToDouble(o -> o.rev).average().orElse(0);
    }

    private static double tstatRev(List<Obs> obs) {
        double[] v = obs.stream().mapToDouble(o -> o.rev).toArray();
        return tstatArr(v);
    }

    // ================================================================ stats

    private static double meanArr(double[] v) {
        if (v.length == 0) return 0;
        double s = 0; for (double d : v) s += d; return s / v.length;
    }

    private static double mean(List<Double> v) {
        if (v == null || v.isEmpty()) return 0;
        return v.stream().mapToDouble(d -> d).average().orElse(0);
    }

    private static double[] toArr(List<Double> v) {
        double[] a = new double[v.size()];
        for (int i = 0; i < a.length; i++) a[i] = v.get(i);
        return a;
    }

    private static double hitArr(double[] v) {
        if (v.length == 0) return 0;
        int k = 0; for (double d : v) if (d > 0) k++; return k / (double) v.length;
    }

    private static double medianArr(double[] v) {
        if (v.length == 0) return 0;
        double[] s = v.clone(); Arrays.sort(s);
        int n = s.length;
        return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2.0;
    }

    private static double tstatArr(double[] v) {
        if (v.length < 3) return 0;
        double m = meanArr(v), var = 0;
        for (double d : v) var += (d - m) * (d - m);
        var /= (v.length - 1);
        double sd = Math.sqrt(var);
        if (sd == 0) return 0;
        return m / (sd / Math.sqrt(v.length));
    }

    private static String wfTagArr(double[] is, double[] oos) {
        if (is.length == 0 || oos.length == 0) return "n/a";
        double a = meanArr(is), b = meanArr(oos);
        if (a > 0 && b > 0) return a >= b ? "STABLE" : "S'AMÉLIORE";
        if (a < 0 && b < 0) return "NÉGATIF 2 CÔTÉS";
        return a > 0 ? "MEURT EN OOS" : "LATE-BLOOMER";
    }

    /** Lignes de z-scores par jour : [0] = epochDay (encodé), puis 1 valeur par paire. */
    private static List<double[]> zscoredRows(Map<String, TreeMap<LocalDate, Double>> real) {
        Map<String, Map<LocalDate, Double>> rets = new LinkedHashMap<>();
        Map<String, double[]> stats = new HashMap<>();
        for (String s : SYMBOLS) {
            Map<LocalDate, Double> m = new HashMap<>();
            List<DayReturn> dr = dayReturns(real.get(s));
            double sum = 0, sum2 = 0;
            for (DayReturn d : dr) { m.put(d.date, d.ret); sum += d.ret; sum2 += d.ret * d.ret; }
            double mu = sum / dr.size();
            double sd = Math.sqrt(Math.max(1e-15, sum2 / dr.size() - mu * mu));
            rets.put(s, m);
            stats.put(s, new double[]{mu, sd});
        }
        TreeMap<LocalDate, double[]> rows = new TreeMap<>();
        for (int p = 0; p < SYMBOLS.length; p++) {
            String s = SYMBOLS[p];
            for (var e : rets.get(s).entrySet()) {
                double[] row = rows.computeIfAbsent(e.getKey(), k -> {
                    double[] r = new double[SYMBOLS.length + 1];
                    Arrays.fill(r, 1, r.length, Double.NaN);
                    r[0] = k.toEpochDay();
                    return r;
                });
                double[] st = stats.get(s);
                row[p + 1] = (e.getValue() - st[0]) / st[1];
            }
        }
        return new ArrayList<>(rows.values());
    }

    private static double avgPairwiseCorr(List<double[]> rows) {
        int p = SYMBOLS.length;
        double sum = 0; int cnt = 0;
        for (int a = 1; a <= p; a++)
            for (int b = a + 1; b <= p; b++) {
                double sx = 0, sy = 0, sxx = 0, syy = 0, sxy = 0; int n = 0;
                for (double[] r : rows) {
                    double x = r[a], y = r[b];
                    if (Double.isNaN(x) || Double.isNaN(y)) continue;
                    sx += x; sy += y; sxx += x * x; syy += y * y; sxy += x * y; n++;
                }
                if (n < 20) continue;
                double cov = (sxy - sx * sy / n) / (n - 1);
                double vx = (sxx - sx * sx / n) / (n - 1);
                double vy = (syy - sy * sy / n) / (n - 1);
                if (vx > 0 && vy > 0) { sum += cov / Math.sqrt(vx * vy); cnt++; }
            }
        return cnt == 0 ? Double.NaN : sum / cnt;
    }
}
