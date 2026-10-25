package com.martinfou.trading.examples;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;

import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * RunCrossSectionalFxMomentum — LUNDI 5 octobre 2026 (49e résultat, idée NOUVELLE).
 *
 * 🎯 LA DIMENSION MANQUANTE : LE CROSS-SECTION.
 *   Depuis 39 résultats, le pipeline a miné la SÉRIE TEMPORELLE de chaque paire (mois, jour de
 *   semaine, fenêtres datées, régimes de vol, carry) et l'AGRÉGAT du panier (composite F du 38e,
 *   = une MOYENNE des 8 paires). Il n'a JAMAIS testé la seule dimension qui reste : le RANG
 *   RELATIF des 8 paires les unes par rapport aux autres à chaque date — c'est-à-dire la SÉLECTION
 *   dans la coupe transversale, et non le niveau ni le timing.
 *
 *   Pourquoi ça compte ici plus qu'ailleurs : le goulot d'étranglement identifié par le pipeline
 *   est une BORNE D'AMPLITUDE (loi du 19 sept : un edge FX journalier meurt sous 3.4 bp ; les 3
 *   mesures convergentes 0.1-5.6 bp des 40e/41e sont SOUS ou À la limite du seuil). La prime de
 *   momentum de change cross-sectionnel est, dans la littérature (Menkhoff-Sarno-Schmeling-Schrimpf
 *   2012), la SEULE anomalie FX documentée dont l'amplitude est d'un ORDRE DE GRANDEUR au-dessus
 *   de ce seuil. Si elle existe dans nos données, elle change la nature du problème : les coûts
 *   cessent d'être la contrainte (2.14 bp par rebalancement contre ~10 bp/semaine).
 *
 * MÉTHODE ANTI-ARTEFACT (discipline du pipeline)
 *   P0  Calibration : reproduire les vendredis du 37e/42e AU BP (EUR −0.032 %, GBP −0.054 %,
 *       GBP_JPY −0.056 %, USD_CAD +0.005 %) → valide loader + définition de la barre quotidienne.
 *   P1  Structure cross-sectionnelle : la dispersion des rangs existe-t-elle ? (sinon rien à ranger)
 *   P2  Table principale : L ∈ {5,21,63,126,252} × H ∈ {5,21} × k ∈ {1,2,3}, LONG top-k / SHORT
 *       bottom-k, dollar-neutre par construction, buckets NON CHEVAUCHANTS (leçon du 26 août).
 *       Lecture IS (≤2015) / OOS (≥2016) + hit + t. Signe = momentum ; le signe opposé = réversion.
 *   P3  MONOTONIE DE L'ORDRE des quartiles (leçon du 29 sept : un U donne un contrapositif positif
 *       gratuitement ; on exige l'ordre AVANT de nommer un gradient).
 *   P4  Test de PERMUTATION (rangs mélangés dans chaque date, 500 tirages) → p-value non
 *       paramétrique : c'est le contrôle de tests multiples que le pipeline exige (23 sept).
 *   P5  Ères / décennies / hors-crises (la dérive d'ère est l'artefact #1 du pipeline).
 *   P6  BUDGET DE COÛT ET DE CARRY : le panier dollar-neutre porte-t-il une prime de carry
 *       (le momentum FX est connu pour être chargé en carry) ? Coût fixe 2.14 bp/rebalancement,
 *       carry réel par année via la table du 47e (0.274 pip/lot/jour par 1 % de différentiel).
 *   P7  Spécificité du JOUR d'ancrage (lundi/mercredi/vendredi) — contrôle de dérive du jour.
 *
 * ⚠️ PRÉ-VALIDATION. Aucun verdict de tradeabilité sans backtest AVEC coûts et swap lu séparément.
 *    Le net n'est JAMAIS cité sans son hypothèse de swap (pitfall du 28 sept).
 */
public class RunCrossSectionalFxMomentum {

    static final String[] FX = {
        "GBP_JPY", "GBP_USD", "AUD_USD", "NZD_USD", "EUR_USD", "USD_CHF", "USD_JPY", "USD_CAD"
    };
    static final int[] LOOKBACKS = { 5, 21, 63, 126, 252 };
    static final int[] HOLDS = { 5, 21 };
    static final int[] KS = { 1, 2, 3 };

    /** Loi du seuil de survie (19 sept) : coûts A/R d'UNE unité brute de panier. */
    static final double COST_BP = 2.14;
    static final double THRESHOLD_BP = 3.4;
    /** 1 % de différentiel de taux = 0.274 pip/lot/jour (table de carry réelle du 47e). */
    static final double PIP_PER_PCT = 0.274;

    static final Map<String, TreeMap<LocalDate, Double>> closes = new LinkedHashMap<>();
    static List<LocalDate> dates = new ArrayList<>();     // axe commun aux 8 paires
    static double[][] px;                                  // [date][paire] closes
    static double[][] ret;                                 // [date][paire] rendement journalier (close→close)
    static double[][] pxO, retO;                           // panneau OUVERTURE (contrôle micro-structure)
    static double[][] panel, panelRet;                     // panneau ACTIF

    /** Les 8 devises du panier et leur paire de référence vs USD (signe du rendement de la devise). */
    static final String[] CCY = { "USD", "EUR", "GBP", "JPY", "CHF", "AUD", "NZD", "CAD" };
    static final String[] CCY_PAIR = { null, "EUR_USD", "GBP_USD", "USD_JPY", "USD_CHF", "AUD_USD", "NZD_USD", "USD_CAD" };
    static final double[] CCY_SIGN = { 0, +1, +1, -1, -1, +1, +1, -1 };
    /** Signe de l'exposition USD d'une position LONGUE sur la paire (+1 = la position est longue USD). */
    static final Map<String, Double> USD_SIDE = Map.of(
        "GBP_JPY", -1.0, "GBP_USD", -1.0, "AUD_USD", -1.0, "NZD_USD", -1.0,
        "EUR_USD", -1.0, "USD_CHF", +1.0, "USD_JPY", +1.0, "USD_CAD", +1.0);

    public static void main(String[] args) throws Exception {
        System.out.println("=====================================================================");
        System.out.println("CROSS-SECTIONAL FX MOMENTUM CHECK — lundi 5 oct 2026 (49e, idée NOUVELLE)");
        System.out.println("La dimension jamais testée : le RANG RELATIF des 8 paires.");
        System.out.println("Le momentum de change franchit-il la borne d'amplitude du pipeline ?");
        System.out.println("=====================================================================");

        for (String s : FX) closes.put(s, dailyClosesBars(s, 2006, 2026));

        dates = new ArrayList<>(closes.get(FX[0]).keySet());
        for (String s : FX) dates.retainAll(closes.get(s).keySet());
        Collections.sort(dates);
        int n = dates.size(), m = FX.length;
        px = new double[n][m];
        ret = new double[n][m];
        for (int i = 0; i < n; i++)
            for (int j = 0; j < m; j++) px[i][j] = closes.get(FX[j]).get(dates.get(i));
        for (int i = 1; i < n; i++)
            for (int j = 0; j < m; j++) ret[i][j] = px[i][j] / px[i - 1][j] - 1;
        // panneau OUVERTURE : première barre de chaque journée UTC (contrôle du rebond de clôture)
        pxO = new double[n][m];
        retO = new double[n][m];
        for (int j = 0; j < m; j++) {
            TreeMap<LocalDate, Double> op = dailyOpensBars(FX[j], 2006, 2026);
            for (int i = 0; i < n; i++) pxO[i][j] = op.getOrDefault(dates.get(i), px[i][j]);
        }
        for (int i = 1; i < n; i++)
            for (int j = 0; j < m; j++) retO[i][j] = pxO[i][j] / pxO[i - 1][j] - 1;
        panel = px;
        panelRet = ret;
        System.out.printf("%n[panel] axe commun : %s → %s (%d jours × %d paires)%n",
            dates.get(0), dates.get(n - 1), n, m);

        part0_Calibration();
        part1_Structure();
        part2_MainTable();
        part2b_CurrencySpace();
        part2c_OpenToOpen();
        part3_Quartiles();
        part4_Permutation();
        part5_Eras();
        part6_CostCarry();
        part7_AnchorSpecificity();

        System.out.println("\nDONE");
    }

    // ============================== P0 — CALIBRATION

    private static void part0_Calibration() {
        header("P0 — CALIBRATION (doit reproduire le 37e/42e AU BP)");
        System.out.println("Référence 42e : EUR −0.032 % | GBP −0.054 % | GBP_JPY −0.056 % | USD_CAD +0.005 %");
        System.out.printf("%-9s %5s %12s %8s %12s %8s%n", "PAIRE", "n", "avg ven", "hit", "méd ven", "t");
        for (String s : FX) {
            TreeMap<LocalDate, Double> m = closes.get(s);
            Map<LocalDate, Double> r = retsByDate(m);
            List<Double> fri = new ArrayList<>(), all = new ArrayList<>();
            for (var e : r.entrySet()) {
                all.add(e.getValue());
                if (e.getKey().getDayOfWeek() == DayOfWeek.FRIDAY) fri.add(e.getValue());
            }
            System.out.printf("%-9s %5d %+11.4f%% %7.0f%% %+11.4f%% %7.2f%n",
                s, fri.size(), mean(fri) * 100, hit(fri) * 100, median(fri) * 100, tstat(fri));
            if (s.equals("EUR_USD") || s.equals("GBP_USD") || s.equals("GBP_JPY") || s.equals("USD_CAD"))
                System.out.printf("            (tous jours : n=%d avg %+.4f bp t %5.2f)%n",
                    all.size(), mean(all) * 1e4, tstat(all));
        }
        System.out.println(">> Loader et définition du close quotidien validés si EUR/GBP/GBP_JPY/CAD collent au bp.");
    }

    // ============================== P1 — STRUCTURE

    private static void part1_Structure() {
        header("P1 — LA STRUCTURE CROSS-SECTIONNELLE EXISTE-T-ELLE ?");
        System.out.println("À chaque date, dispersion (écart-type) des rendements traînants sur L jours, en %.");
        System.out.println("Si la dispersion est ~0, il n'y a rien à ranger ; si elle est large, le rang porte de l'info.");
        System.out.printf("%-6s %10s %10s %10s %10s%n", "L", "disp moy%", "disp p10", "disp p90", "ratio p90/p10");
        for (int L : LOOKBACKS) {
            List<Double> d = new ArrayList<>();
            for (int i = L; i < dates.size(); i++) d.add(std(trailing(i, L)) * 100);
            System.out.printf("%-6d %10.3f %10.3f %10.3f %10.2f%n", L, mean(d), pct(d, 10), pct(d, 90),
                pct(d, 10) == 0 ? 0 : pct(d, 90) / pct(d, 10));
        }
        System.out.println(">> La dispersion du momentum 63 j est l'échelle dans laquelle le rang doit produire du bp.");
    }

    // ============================== P2 — TABLE PRINCIPALE

    private static void part2_MainTable() {
        header("P2 — TABLE PRINCIPALE : LONG top-k / SHORT bottom-k (dollar-neutre, buckets NON chevauchants)");
        System.out.println("avg = rendement du panier en bp par rebalancement (PRIX seul, avant coûts et swap).");
        System.out.println("Signe POSITIF = momentum ; NÉGATIF = réversion. IS ≤2015 / OOS ≥2016.");
        for (int H : HOLDS) {
            System.out.printf("%n================ HOLD H = %d jours ================%n", H);
            System.out.printf("%-6s %-4s %6s %10s %7s %7s %9s %9s  %s%n",
                "L", "k", "n", "avg bp", "t", "hit", "IS bp", "OOS bp", "WF");
            for (int L : LOOKBACKS) {
                for (int k : KS) {
                    Res r = measure(L, H, k, -1, null);
                    double is = mean(r.is), oos = mean(r.oos);
                    System.out.printf("%-6d %-4d %6d %+10.2f %7.2f %6.0f%% %+9.2f %+9.2f  %s%n",
                        L, k, r.all.size(), mean(r.all) * 1e4, tstat(r.all), hit(r.all) * 100,
                        is * 1e4, oos * 1e4, wfTag(r.is, r.oos));
                }
            }
        }
        System.out.println("\n>> Seuil mémoire : 3.4 bp par rebalancement est le seuil de SURVIE d'un edge journalier FX.");
        System.out.println(">> Un edge de momentum doit être lu sur PLUSIEURS horizons à la fois (plateau = signal).");
    }

    // ============================== P2b — ESPACE DEVISES

    private static void part2b_CurrencySpace() {
        header("P2b — MOMENTUM EN ESPACE DEVISES (le test canonique, dollar-neutre PAR CONSTRUCTION)");
        System.out.println("Le rang au niveau PAIRE (P2) n'est PAS dollar-neutre : toute paire porte une jambe USD,");
        System.out.println("donc la sélection peut être un pari DOLLAR déguisé (le pipeline sait que le dollar n'est");
        System.out.println("pas tradeable). Le test canonique (Menkhoff et al. 2012) range les 8 DEVISES.");
        System.out.printf("%nExposition USD NETTE moyenne du panier de PAIRES (diagnostic du biais) :%n");
        System.out.printf("%-6s %-4s %10s %10s %10s%n", "L", "k", "|USD| moy", "n(USD>0)", "n(USD<0)");
        for (int[] c : new int[][] { { 21, 1 }, { 21, 2 }, { 63, 2 }, { 63, 3 }, { 252, 2 } }) {
            int L = c[0], k = c[1], H = 21;
            List<Double> net = new ArrayList<>();
            int last = -H;
            for (int i = L; i + H < dates.size(); i++) {
                if (i - last < H) continue;
                last = i;
                Integer[] ord = order(i, L);
                double e = 0;
                for (int j = 0; j < k; j++) {
                    e += USD_SIDE.get(FX[ord[j]]) / (2.0 * k);
                    e -= USD_SIDE.get(FX[ord[FX.length - 1 - j]]) / (2.0 * k);
                }
                net.add(e);
            }
            long pos = net.stream().filter(v -> v > 0.01).count(), neg = net.stream().filter(v -> v < -0.01).count();
            System.out.printf("%-6d %-4d %+10.3f %10d %10d%n", L, k, mean(net), pos, neg);
        }
        System.out.println("Lecture : |USD| = exposition dollar nette en unités de la taille brute (1.0 = 100 % long USD).");
        System.out.printf("%n=== PANIER DE DEVISES (LONG top-k / SHORT bottom-k des 8 devises vs USD) ===%n");
        System.out.printf("%-6s %-4s %-4s %6s %10s %7s %7s %9s %9s  %s%n",
            "L", "H", "k", "n", "avg bp", "t", "hit", "IS bp", "OOS bp", "WF");
        for (int H : HOLDS) {
            for (int L : LOOKBACKS) {
                for (int k : KS) {
                    Res r = measureCcy(L, H, k, -1, null);
                    System.out.printf("%-6d %-4d %-4d %6d %+10.2f %7.2f %6.0f%% %+9.2f %+9.2f  %s%n",
                        L, H, k, r.all.size(), mean(r.all) * 1e4, tstat(r.all), hit(r.all) * 100,
                        mean(r.is) * 1e4, mean(r.oos) * 1e4, wfTag(r.is, r.oos));
                }
            }
        }
        Res ref = measureCcy(63, 21, 2, -1, null);
        System.out.printf("%nDiagnostic : USD retenu dans les 2 extrêmes sur %d / %d rebalancements (%.0f %%).%n",
            ref.usdSel, ref.all.size(), 100.0 * ref.usdSel / Math.max(1, ref.all.size()));
        System.out.println(">> Si l'espace DEVISES est vide lui aussi, ce n'est pas la construction du panier le problème.");
    }

    // ============================== P2c — OUVERTURE

    private static void part2c_OpenToOpen() {
        header("P2c — CONTRÔLE MICRO-STRUCTURE : clôture→clôture vs OUVERTURE→OUVERTURE");
        System.out.println("Une réversion hebdomadaire mesurée sur les CLÔTURES est le candidat-type d'un artefact de");
        System.out.println("rebond bid-ask. Le même test en OUVERTURE→OUVERTURE (première barre UTC de chaque jour)");
        System.out.println("élimine le prix de clôture : si le signal disparaît, il vivait dans la clôture.");
        System.out.printf("%-9s %-6s %-4s %-4s %11s %7s %8s %11s %7s%n",
            "ESPACE", "L", "H", "k", "close bp", "t", "p-value", "open bp", "t");
        Meas[] ms = { RunCrossSectionalFxMomentum::measure, RunCrossSectionalFxMomentum::measureCcy };
        String[] nm = { "PAIRES", "DEVISES" };
        int[][] cfgs = { { 5, 5, 1 }, { 5, 5, 2 }, { 21, 21, 2 }, { 63, 21, 2 }, { 252, 21, 2 } };
        double[][] sp = panel, sr = panelRet;
        for (int s = 0; s < ms.length; s++) {
            for (int[] c : cfgs) {
                int L = c[0], H = c[1], k = c[2];
                panel = px; panelRet = ret;
                Res a = ms[s].run(L, H, k, -1, null);
                double pa = permP(ms[s], L, H, k, 500);
                panel = pxO; panelRet = retO;
                Res b = ms[s].run(L, H, k, -1, null);
                System.out.printf("%-9s %-6d %-4d %-4d %+11.2f %7.2f %8.3f %+11.2f %7.2f%n",
                    nm[s], L, H, k, mean(a.all) * 1e4, tstat(a.all), pa, mean(b.all) * 1e4, tstat(b.all));
            }
        }
        panel = sp;
        panelRet = sr;
        System.out.println(">> Un edge qui survit aux deux ancrages est robuste ; un edge qui meurt en ouverture est un artefact.");
    }

    // ============================== P3 — QUARTILES

    private static void part3_Quartiles() {
        header("P3 — MONOTONIE DE L'ORDRE DES QUARTILES (leçon du 29 sept : un U donne un contrapositif gratuit)");
        System.out.println("Paires rangées par rendement traînant décroissant ; Q1 = les 2 plus FORTES … Q4 = les 2 plus FAIBLES.");
        System.out.println("Rendement AVANT (bp) de chaque quartile. On veut un ORDRE, pas deux queues qui battent le milieu.");
        for (int L : new int[] { 21, 63, 126, 252 }) {
            System.out.printf("%n--- L = %d jours ---%n", L);
            for (int H : HOLDS) {
                List<List<Double>> q = quartileForwards(L, H);
                System.out.printf("  H=%2d : Q1 %+8.2f (t %5.2f) | Q2 %+8.2f | Q3 %+8.2f | Q4 %+8.2f (t %5.2f)  %s%n",
                    H, mean(q.get(0)) * 1e4, tstat(q.get(0)), mean(q.get(1)) * 1e4, mean(q.get(2)) * 1e4,
                    mean(q.get(3)) * 1e4, tstat(q.get(3)), monoTag(q));
            }
        }
        System.out.println("\n>> ORDRE MONOTONE ⇒ gradient réel. U ou croisements ⇒ partition bruitée (rejet).");
    }

    private static List<List<Double>> quartileForwards(int L, int H) {
        List<List<Double>> q = new ArrayList<>();
        for (int i = 0; i < 4; i++) q.add(new ArrayList<>());
        int last = -H;
        for (int i = L; i + H < dates.size(); i++) {
            if (i - last < H) continue;
            last = i;
            Integer[] ord = order(i, L);
            // 8 paires → 4 quartiles de 2
            for (int g = 0; g < 4; g++)
                for (int j = 0; j < 2; j++)
                    q.get(g).add(fwd(ord[g * 2 + j], i, H));
        }
        return q;
    }

    private static String monoTag(List<List<Double>> q) {
        double a = mean(q.get(0)), b = mean(q.get(1)), c = mean(q.get(2)), d = mean(q.get(3));
        if (a >= b && b >= c && c >= d) return "MONOTONE";
        if (a <= b && b <= c && c <= d) return "MONOTONE INVERSE (réversion)";
        if (a > b && c > b && d > c) return "U (contrapositif gratuit)";
        return "CROISÉ / NON MONOTONE";
    }

    // ============================== P4 — PERMUTATION

    private static void part4_Permutation() {
        header("P4 — TEST DE PERMUTATION (500 tirages, rangs mélangés DANS chaque date — les DEUX espaces)");
        System.out.println("Contrôle non paramétrique : la valeur observée est-elle extrême dans la distribution nulle ?");
        System.out.println("Le mélange préserve la dispersion de chaque date et détruit le lien rang → rendement futur.");
        int[][] cfgs = { { 5, 5, 1 }, { 21, 5, 2 }, { 63, 5, 2 }, { 63, 21, 2 }, { 126, 21, 2 }, { 252, 21, 2 }, { 63, 21, 3 } };
        System.out.printf("%-9s %-6s %-4s %-4s %10s %10s %8s%n",
            "ESPACE", "L", "H", "k", "obs bp", "|null| p95", "p-value");
        for (int[] c : cfgs) permRow("PAIRES", c[0], c[1], c[2], RunCrossSectionalFxMomentum::measure);
        for (int[] c : cfgs) permRow("DEVISES", c[0], c[1], c[2], RunCrossSectionalFxMomentum::measureCcy);
        System.out.println(">> p-value = fraction de tirages nuls AUSSI extrêmes que l'observé. < 0.05 = hors du bruit.");
    }

    interface Meas { Res run(int L, int H, int k, int anchor, Random r); }

    private static double permP(Meas m, int L, int H, int k, int reps) {
        Random rnd = new Random(20261005L + L * 100 + H * 10 + k);
        double obs = mean(m.run(L, H, k, -1, null).all) * 1e4;
        int ge = 0;
        for (int p = 0; p < reps; p++)
            if (Math.abs(mean(m.run(L, H, k, -1, rnd).all) * 1e4) >= Math.abs(obs)) ge++;
        return ge / (double) reps;
    }

    private static void permRow(String space, int L, int H, int k, Meas m) {
        Random rnd = new Random(20261005L + L * 100 + H * 10 + k);
        double obs = mean(m.run(L, H, k, -1, null).all) * 1e4;
        double[] nulls = new double[500];
        for (int p = 0; p < 500; p++) nulls[p] = mean(m.run(L, H, k, -1, rnd).all) * 1e4;
        Arrays.sort(nulls);
        int ge = 0;
        for (double v : nulls) if (Math.abs(v) >= Math.abs(obs)) ge++;
        System.out.printf("%-9s %-6d %-4d %-4d %+10.2f %10.2f %8.3f%n",
            space, L, H, k, obs, Math.abs(nulls[475]), ge / 500.0);
    }

    // ============================== P5 — ÈRES

    private static void part5_Eras() {
        header("P5 — ÈRES, DÉCENNIES ET HORS-CRISES (la dérive d'ère est l'artefact #1 du pipeline)");
        System.out.println("Configuration de référence : L=63, H=21, k=2 (le momentum mensuel canonique).");
        int L = 63, H = 21, k = 2;
        Res r = measure(L, H, k, -1, null);
        System.out.printf("FULL   : n=%3d  %+8.2f bp  t %5.2f  hit %.0f%%%n", r.all.size(), mean(r.all) * 1e4, tstat(r.all), hit(r.all) * 100);
        System.out.printf("IS≤15  : n=%3d  %+8.2f bp  t %5.2f%n", r.is.size(), mean(r.is) * 1e4, tstat(r.is));
        System.out.printf("OOS≥16 : n=%3d  %+8.2f bp  t %5.2f%n", r.oos.size(), mean(r.oos) * 1e4, tstat(r.oos));
        System.out.printf("%n%-10s %5s %10s %8s%n", "DÉCENNIE", "n", "avg bp", "t");
        for (int dec = 200; dec <= 202; dec++) {
            List<Double> v = new ArrayList<>();
            for (int i = 0; i < r.datesUsed.size(); i++) {
                int y = r.datesUsed.get(i).getYear();
                if (y / 10 == dec) v.add(r.all.get(i));
            }
            if (!v.isEmpty())
                System.out.printf("%d0-%d9   %5d %+10.2f %8.2f%n", dec, dec, v.size(), mean(v) * 1e4, tstat(v));
        }
        List<Double> ex = new ArrayList<>();
        for (int i = 0; i < r.datesUsed.size(); i++) {
            int y = r.datesUsed.get(i).getYear();
            if (y == 2008 || y == 2011 || y == 2015 || y == 2020 || y == 2022) continue;
            ex.add(r.all.get(i));
        }
        System.out.printf("hors 2008/11/15/20/22 : n=%3d %+8.2f bp  t %5.2f%n", ex.size(), mean(ex) * 1e4, tstat(ex));
        Res rc = measureCcy(L, H, k, -1, null);
        System.out.printf("%nMÊME CONFIG EN ESPACE DEVISES :%n");
        System.out.printf("FULL   : n=%3d  %+8.2f bp  t %5.2f  hit %.0f%%%n", rc.all.size(), mean(rc.all) * 1e4, tstat(rc.all), hit(rc.all) * 100);
        System.out.printf("IS≤15  : n=%3d  %+8.2f bp  t %5.2f%n", rc.is.size(), mean(rc.is) * 1e4, tstat(rc.is));
        System.out.printf("OOS≥16 : n=%3d  %+8.2f bp  t %5.2f%n", rc.oos.size(), mean(rc.oos) * 1e4, tstat(rc.oos));
        System.out.printf("%nRÉVERSION EN ESPACE DEVISES — STABILITÉ PAR DÉCENNIE (le signal le plus fort du run) :%n");
        for (int kk : new int[] { 1, 2 }) {
            for (int[] lh : new int[][] { { 5, 5 }, { 63, 21 } }) {
                Res rw = measureCcy(lh[0], lh[1], kk, -1, null);
                System.out.printf("  L=%-3d H=%-2d k=%d | ", lh[0], lh[1], kk);
                for (int dec = 200; dec <= 202; dec++) {
                    List<Double> v = new ArrayList<>();
                    for (int i = 0; i < rw.datesUsed.size(); i++)
                        if (rw.datesUsed.get(i).getYear() / 10 == dec) v.add(rw.all.get(i));
                    if (!v.isEmpty()) System.out.printf("%d0s %+7.2f (t %5.2f, n %3d)  ", dec, mean(v) * 1e4, tstat(v), v.size());
                }
                System.out.println();
            }
        }
        System.out.println(">> Un signe qui change de décennie n'est pas un facteur (artefact d'ère #1 du pipeline).");
        System.out.println("\n>> Si l'effet vit dans UNE décennie ou meurt hors crises, ce n'est pas un facteur, c'est une ère.");
    }

    // ============================== P6 — COÛT ET CARRY

    private static void part6_CostCarry() {
        header("P6 — BUDGET DE COÛT ET DE CARRY (le panier est-il chargé en carry ?)");
        System.out.println("Le momentum FX est documenté comme corrélé au carry : si les gagnants sont les devises à");
        System.out.println("haut taux, le panier encaisse une prime de carry qui n'est PAS du momentum.");
        System.out.println("Carry réel par année (table du 47e), 0.274 pip/lot/jour par 1 % de différentiel BASE−QUOTE.");
        System.out.printf("%-6s %-4s %10s %12s %12s %12s%n", "L", "H", "brut bp", "carry bp", "brut−coût", "brut−coût+carry");
        for (int[] c : new int[][] { { 21, 5 }, { 63, 5 }, { 63, 21 }, { 126, 21 }, { 252, 21 } }) {
            int L = c[0], H = c[1], k = 2;
            Res r = measure(L, H, k, -1, null);
            double brut = mean(r.all) * 1e4, carry = mean(r.carry) * 1e4;
            System.out.printf("%-6d %-4d %+10.2f %+12.2f %+12.2f %+12.2f%n",
                L, H, brut, carry, brut - COST_BP, brut - COST_BP + carry);
        }
        System.out.printf("%nCoût fixe mémoire : %.2f bp par rebalancement (A/R sur 1 unité brute).%n", COST_BP);
        System.out.println("\nDIAGNOSTIC — carry d'une position LONGUE de 1 unité, bp/jour (valide la table et le signe) :");
        System.out.printf("%-9s %10s %10s %10s%n", "PAIRE", "bp/j moy", "bp/j 06-15", "bp/j 16-26");
        for (int j = 0; j < FX.length; j++) {
            List<Double> a = new ArrayList<>(), b = new ArrayList<>(), c = new ArrayList<>();
            for (int i = 0; i < dates.size(); i++) {
                double v = carryBp(j, i);
                a.add(v);
                (dates.get(i).getYear() <= 2015 ? b : c).add(v);
            }
            System.out.printf("%-9s %+10.3f %+10.3f %+10.3f%n", FX[j], mean(a), mean(b), mean(c));
        }
        System.out.println(">> Lecture : somme des poids signés × ces valeurs × H = le carry du panier ci-dessus.");
        System.out.println(">> Le carry est mesuré sur les jambes RÉELLEMENT sélectionnées, à la date du rebalancement.");
        System.out.println(">> Règle du 28 sept : aucune conclusion 'net' sans les trois hypothèses de swap.");
    }

    // ============================== P7 — ANCRAGE

    private static void part7_AnchorSpecificity() {
        header("P7 — SPÉCIFICITÉ DU JOUR D'ANCRAGE (contrôle de dérive du jour)");
        System.out.println("Configuration L=63, H=21, k=2. Si l'effet n'existe que pour un jour d'ancrage, c'est un artefact.");
        String[] dn = { "LUNDI", "MARDI", "MERCREDI", "JEUDI", "VENDREDI" };
        for (int[] cfg : new int[][] { { 63, 21, 2 }, { 5, 5, 1 }, { 5, 5, 2 } }) {
            System.out.printf("%n  --- L=%d H=%d k=%d ---%n", cfg[0], cfg[1], cfg[2]);
            for (int d = 1; d <= 5; d++) {
                Res r = measure(cfg[0], cfg[1], cfg[2], d, null);
                Res c = measureCcy(cfg[0], cfg[1], cfg[2], d, null);
                System.out.printf("    ancrage %-9s PAIRES n=%3d %+8.2f bp t %5.2f  |  DEVISES n=%3d %+8.2f bp t %5.2f%n",
                    dn[d - 1], r.all.size(), mean(r.all) * 1e4, tstat(r.all),
                    c.all.size(), mean(c.all) * 1e4, tstat(c.all));
            }
        }
        System.out.println(">> Un facteur réel ne dépend pas du calendrier de rebalancement.");
    }

    // ============================== mesure

    static final class Res {
        List<Double> all = new ArrayList<>();
        List<Double> is = new ArrayList<>();
        List<Double> oos = new ArrayList<>();
        List<Double> carry = new ArrayList<>();
        List<LocalDate> datesUsed = new ArrayList<>();
        List<Double> longLeg = new ArrayList<>();
        List<Double> shortLeg = new ArrayList<>();
        int usdSel;
    }

    // ---------- espace DEVISES ----------

    static int pairIdx(String s) {
        for (int j = 0; j < FX.length; j++) if (FX[j].equals(s)) return j;
        return -1;
    }

    /** Rendement cumulé d'une DEVISE vs USD sur L jours (USD = 0 par construction). */
    static double ccyTrail(int c, int i, int L) {
        if (c == 0) return 0;
        int j = pairIdx(CCY_PAIR[c]);
        double v = 1;
        for (int k = i - L + 1; k <= i; k++) v *= (1 + CCY_SIGN[c] * panelRet[k][j]);
        return v - 1;
    }

    static double ccyFwd(int c, int i, int H) {
        if (c == 0) return 0;
        int j = pairIdx(CCY_PAIR[c]);
        double v = 1;
        for (int k = i + 1; k <= i + H; k++) v *= (1 + CCY_SIGN[c] * panelRet[k][j]);
        return v - 1;
    }

    static Res measureCcy(int L, int H, int k, int anchor, Random permRnd) {
        Res r = new Res();
        int last = -H, m = CCY.length;
        for (int i = L; i + H < dates.size(); i++) {
            if (i - last < H) continue;
            if (anchor > 0 && dates.get(i).getDayOfWeek().getValue() != anchor) continue;
            last = i;
            final double[] s = new double[m];
            for (int c = 0; c < m; c++) s[c] = ccyTrail(c, i, L);
            Integer[] ord = new Integer[m];
            for (int c = 0; c < m; c++) ord[c] = c;
            Arrays.sort(ord, (a, b) -> Double.compare(s[b], s[a]));
            for (int g = 0; g < k; g++) if (ord[g] == 0 || ord[m - 1 - g] == 0) r.usdSel++;
            double[] f = new double[m];
            for (int c = 0; c < m; c++) f[c] = ccyFwd(c, i, H);
            if (permRnd != null) {
                List<Double> tmp = new ArrayList<>();
                for (double v : f) tmp.add(v);
                Collections.shuffle(tmp, permRnd);
                for (int c = 0; c < m; c++) f[c] = tmp.get(c);
            }
            double lo = 0, sh = 0;
            for (int g = 0; g < k; g++) { lo += f[ord[g]]; sh += f[ord[m - 1 - g]]; }
            lo /= k;
            sh /= k;
            double port = lo - sh;
            r.all.add(port);
            r.datesUsed.add(dates.get(i));
            (dates.get(i).getYear() <= 2015 ? r.is : r.oos).add(port);
            r.carry.add(0.0);
        }
        return r;
    }

    /**
     * @param anchor  1..5 = n'accepter que ce jour de semaine comme date de rebalancement ; -1 = aucune contrainte.
     * @param permRnd non-null ⇒ les rendements futurs sont mélangés entre paires (distribution nulle).
     */
    static Res measure(int L, int H, int k, int anchor, Random permRnd) {
        Res r = new Res();
        int last = -H;
        for (int i = L; i + H < dates.size(); i++) {
            if (i - last < H) continue;
            if (anchor > 0 && dates.get(i).getDayOfWeek().getValue() != anchor) continue;
            last = i;
            Integer[] ord = order(i, L);
            double[] f = new double[FX.length];
            for (int j = 0; j < FX.length; j++) f[j] = fwd(j, i, H);
            if (permRnd != null) {
                List<Double> tmp = new ArrayList<>();
                for (double v : f) tmp.add(v);
                Collections.shuffle(tmp, permRnd);
                for (int j = 0; j < f.length; j++) f[j] = tmp.get(j);
            }
            double lo = 0, sh = 0, all = 0;
            for (int j = 0; j < k; j++) { lo += f[ord[j]]; sh += f[ord[FX.length - 1 - j]]; }
            lo /= k; sh /= k;
            for (double v : f) all += v;
            all /= FX.length;
            double port = lo - sh;
            r.all.add(port);
            r.longLeg.add(lo);
            r.shortLeg.add(sh);
            r.datesUsed.add(dates.get(i));
            (dates.get(i).getYear() <= 2015 ? r.is : r.oos).add(port);
            double c = 0;
            for (int j = 0; j < k; j++) {
                c += carryBp(ord[j], i) * H / k;
                c -= carryBp(ord[FX.length - 1 - j], i) * H / k;
            }
            r.carry.add(c / 1e4);
        }
        return r;
    }

    /** Carry d'une position LONGUE dans la paire j, en bp par jour (signe inclus). */
    static double carryBp(int j, int i) {
        String sym = FX[j];
        int y = dates.get(i).getYear();
        double[] d = RunFxFridayCarryReal.DIFF_U.get(sym);
        if (d == null) return 0;
        int ix = Math.max(0, Math.min(d.length - 1, y - 2006));
        double pip = PIP_PER_PCT * d[ix];
        double pipSize = sym.endsWith("JPY") ? 0.01 : 0.0001;
        return 1e4 * pip * pipSize / panel[i][j];
    }

    static double[] trailing(int i, int L) {
        double[] out = new double[FX.length];
        for (int j = 0; j < FX.length; j++) out[j] = panel[i][j] / panel[i - L][j] - 1;
        return out;
    }

    static double fwd(int j, int i, int H) { return panel[i + H][j] / panel[i][j] - 1; }

    static Integer[] order(int i, int L) {
        double[] s = trailing(i, L);
        Integer[] ord = new Integer[FX.length];
        for (int j = 0; j < FX.length; j++) ord[j] = j;
        Arrays.sort(ord, (a, b) -> Double.compare(s[b], s[a]));
        return ord;
    }

    // ============================== helpers

    private static void header(String t) {
        System.out.println();
        System.out.println("##################################################################");
        System.out.println("# " + t);
        System.out.println("##################################################################");
    }

    static Map<LocalDate, Double> retsByDate(TreeMap<LocalDate, Double> m) {
        Map<LocalDate, Double> out = new LinkedHashMap<>();
        List<LocalDate> ds = new ArrayList<>(m.keySet());
        for (int i = 1; i < ds.size(); i++) {
            double c0 = m.get(ds.get(i - 1)), c1 = m.get(ds.get(i));
            if (c0 > 0) out.put(ds.get(i), (c1 - c0) / c0);
        }
        return out;
    }

    static TreeMap<LocalDate, Double> dailyClosesBars(String symbol, int from, int to) throws Exception {
        TreeMap<LocalDate, Double> map = new TreeMap<>();
        for (int y = from; y <= to; y++) {
            try {
                List<Bar> bars = HistoricalDataLoader.loadYear(symbol, y, Paths.get("data/historical/bars"));
                if (bars == null) continue;
                for (Bar b : bars) map.put(b.timestamp().atZone(ZoneId.of("UTC")).toLocalDate(), b.close());
            } catch (Exception ignored) { }
        }
        System.out.printf("[load] %-9s %s → %s (%d jours)%n", symbol,
            map.isEmpty() ? "-" : map.firstKey(), map.isEmpty() ? "-" : map.lastKey(), map.size());
        return map;
    }

    static TreeMap<LocalDate, Double> dailyOpensBars(String symbol, int from, int to) throws Exception {
        TreeMap<LocalDate, Double> map = new TreeMap<>();
        Map<LocalDate, Instant> firstTs = new HashMap<>();
        for (int y = from; y <= to; y++) {
            try {
                List<Bar> bars = HistoricalDataLoader.loadYear(symbol, y, Paths.get("data/historical/bars"));
                if (bars == null) continue;
                for (Bar b : bars) {
                    Instant t = b.timestamp();
                    LocalDate d = t.atZone(ZoneId.of("UTC")).toLocalDate();
                    Instant cur = firstTs.get(d);
                    if (cur == null || t.isBefore(cur)) { firstTs.put(d, t); map.put(d, b.open()); }
                }
            } catch (Exception ignored) { }
        }
        System.out.printf("[load-open] %-9s %s → %s (%d jours)%n", symbol,
            map.isEmpty() ? "-" : map.firstKey(), map.isEmpty() ? "-" : map.lastKey(), map.size());
        return map;
    }

    static double mean(List<Double> v) {
        if (v == null || v.isEmpty()) return 0;
        return v.stream().mapToDouble(d -> d).average().orElse(0);
    }

    static double hit(List<Double> v) {
        if (v == null || v.isEmpty()) return 0;
        return v.stream().filter(d -> d > 0).count() / (double) v.size();
    }

    static double median(List<Double> v) {
        if (v == null || v.isEmpty()) return 0;
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    static double pct(List<Double> v, int p) {
        if (v == null || v.isEmpty()) return 0;
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        int ix = (int) Math.round(p / 100.0 * (s.size() - 1));
        return s.get(Math.max(0, Math.min(s.size() - 1, ix)));
    }

    static double std(double[] v) {
        if (v == null || v.length < 3) return 0;
        double m = 0;
        for (double d : v) m += d;
        m /= v.length;
        double var = 0;
        for (double d : v) var += (d - m) * (d - m);
        return Math.sqrt(var / (v.length - 1));
    }

    static double std(List<Double> v) {
        if (v == null || v.size() < 3) return 0;
        double m = mean(v), var = 0;
        for (double d : v) var += (d - m) * (d - m);
        return Math.sqrt(var / (v.size() - 1));
    }

    static double tstat(List<Double> v) {
        if (v == null || v.size() < 3) return 0;
        double sd = std(v);
        if (sd == 0) return 0;
        return mean(v) / (sd / Math.sqrt(v.size()));
    }

    static String wfTag(List<Double> is, List<Double> oos) {
        if (is == null || oos == null || is.isEmpty() || oos.isEmpty()) return "n/a";
        double a = mean(is), b = mean(oos);
        if (a > 0 && b > 0) return a >= b ? "STABLE" : "S'AMÉLIORE";
        if (a < 0 && b < 0) return "NÉGATIF 2 CÔTÉS";
        return a > 0 ? "MEURT EN OOS" : "LATE-BLOOMER";
    }
}
