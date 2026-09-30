package com.martinfou.trading.intelligence.research;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;

import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * CrossAssetVolRegimeCheck — Pré-validation (Pattern D) INTERMARKET, jeudi 24 sept 2026 (42e résultat).
 *
 * 📊 CONTEXTE
 *   Le pipeline a utilisé trois dimensions cross-asset : le NIVEAU (quadrant USD × SPX, 10 sept),
 *   la DIRECTION (DXY OPPOSITE, 28 août) et la CORRÉLATION (mesure, 17 sept). La **VOLATILITÉ**
 *   — la variable de régime canonique en cross-asset — n'a JAMAIS été utilisée, ni en FX ni sur l'or.
 *
 *   Parallèlement, le 19 sept a établi la **loi du seuil de survie ≈ 3.4 bp** (2.14 bp de coûts A/R
 *   + ~1.2 bp de swap sur un hold de 24 h traversant le rollover) : un edge FX journalier sous ce
 *   seuil est une statistique, pas une stratégie. Or les DEUX edges FX vivants sont SOUS ou À la
 *   limite de ce seuil (37e : vendredi 1.7-5.6 bp, 2 paires/8 survivent ; 39e : lundi +4.1 bp meurt).
 *
 * 🎯 QUESTION INTERMARKET (tâche du jeudi = cross-asset)
 *   **(A) L'amplitude de l'edge calendaire FX SCALE-t-elle avec la volatilité ?** Le coût est FIXE
 *   (0.07 $ + 0.1 pip) alors que l'amplitude des mouvements est proportionnelle à la vol → si l'edge
 *   est une prime de risque, il doit franchir le seuil de 3.4 bp EXACTEMENT dans les régimes de vol
 *   élevée. C'est la première fois qu'un conditionneur est testé parce qu'il déplace le RATIO
 *   edge/coût, et non parce qu'il porte un signal directionnel.
 *   **(B) Volatilité PROPRES de la paire ou volatilité ACTIONS (cross-asset) ?** Test incrémental :
 *   l'information d'une AUTRE CLASSE D'ACTIFS apporte-t-elle quelque chose au-delà de la vol de
 *   l'actif lui-même ? (question intermarket canonique : « l'info externe est-elle marginale ? »)
 *
 * MÉTHODE ANTI-ARTEFACT
 *   P0  Calibration : reproduire le vendredi du 37e **au bp** (EUR -0.032 %, GBP -0.054 %,
 *       GBP_JPY -0.056 %, USD_CAD +0.005 %) → valide loader + définition de la barre quotidienne.
 *   P1  Baseline : vendredi inconditionnel vs dérive tous-jours (contrôle bêta du jour, Pattern D).
 *   P2  Quintiles de vol ACTIONS **causaux** (rang percentile de traîne 1000 j, calculé sur les vol
 *       JUSQU'À LA VEILLE → look-ahead safe) : lecture **DANS L'ORDRE** (leçon du 23 sept).
 *   P3  Quintiles de vol PROPRE de la paire (même construction) = le CONTRÔLE.
 *   P4  Test incrémental 2×2 vol actions × vol propre → la vol externe ajoute-t-elle sur la vol propre ?
 *   P5  Le CONDITIONNEL EXTRÊME est-il un artefact de crise ? (2008/2011/2015/2020/2022 exclus) +
 *       IS (≤2015) / OOS (≥2016) par quintile + décennies (Pattern D : la dérive d'ère est l'artefact #1).
 *   P6  Spécificité du JOUR : le même conditionnement appliqué à chaque jour de la semaine (si ça
 *       marche tous les jours, ce n'est pas un edge de vendredi).
 *   P7  Application à la réversion du LUNDI long-only (39e : W4 < 0) conditionnée à la vol actions.
 *   P8  Sensibilité de la MESURE de vol : fenêtre 63 j, |rendement| moyen (non-quadratique), Nasdaq.
 *
 * ⚠️ PRÉ-VALIDATION. Aucun verdict de tradeabilité sans backtest AVEC coûts (0.07 $ + 0.01 %
 *    slippage) et lecture du swap séparément. ⚠️ Le seuil de 3.4 bp est un seuil d'AMPLITUDE — toute
 *    conclusion doit rester une conclusion d'amplitude (cf. pitfall gate ≠ amplitude).
 */
public class CrossAssetVolRegimeCheck {

    private static final double THRESHOLD_BP = 3.4;   // coûts A/R 2.14 bp + swap ~1.2 bp (loi du 19 sept)

    private static final String[] FX = {
        "GBP_JPY", "GBP_USD", "AUD_USD", "NZD_USD", "EUR_USD", "USD_CHF", "USD_JPY", "USD_CAD"
    };
    /** Signe de chaque paire dans le composite « dollar fort / risk-off » F (+1 = monte en risk-off). */
    private static final Map<String, Double> F_SIGN = Map.of(
        "GBP_JPY", -1.0, "GBP_USD", -1.0, "AUD_USD", -1.0, "NZD_USD", -1.0,
        "EUR_USD", -1.0, "USD_CHF", -1.0, "USD_JPY", -1.0, "USD_CAD", +1.0);

    private static final String[][] EQ = {
        { "MES", "MES_D1.csv", "S&P 500" },
        { "MNQ", "MNQ_D1.csv", "Nasdaq 100" }
    };

    // ===================== données =====================

    static Map<String, TreeMap<LocalDate, Double>> fx = new LinkedHashMap<>();
    static Map<String, TreeMap<LocalDate, Double>> eq = new LinkedHashMap<>();
    static Map<String, Map<LocalDate, Double>> fxRet = new LinkedHashMap<>();
    static Map<String, Map<LocalDate, Double>> eqRet = new LinkedHashMap<>();
    /** Vol 21 j (std des rendements close→close) par date. */
    static Map<String, TreeMap<LocalDate, Double>> vol21 = new LinkedHashMap<>();
    /** Rang percentile CAUSAL (traîne 1000 obs) de la vol, par date. */
    static Map<String, TreeMap<LocalDate, Double>> volRank = new LinkedHashMap<>();
    static TreeMap<LocalDate, Double> composite;   // F standardisé, dates communes

    public static void main(String[] args) throws Exception {
        System.out.println("=====================================================================");
        System.out.println("CROSS-ASSET VOL REGIME CHECK — jeudi 24 sept 2026 (42e, intermarket)");
        System.out.println("La volatilité (propre vs ACTIONS) conditionne-t-elle l'amplitude des");
        System.out.println("edges calendaires FX — et fait-elle franchir le seuil de survie 3.4 bp ?");
        System.out.println("=====================================================================");

        for (String s : FX) {
            fx.put(s, dailyClosesBars(s, 2006, 2026));
            fxRet.put(s, retsByDate(fx.get(s)));
        }
        for (String[] e : EQ) {
            eq.put(e[0], dailyClosesCsv(e[1]));
            eqRet.put(e[0], retsByDate(eq.get(e[0])));
        }
        for (String s : FX) buildVol(s, fxRet.get(s), 21);
        for (String[] e : EQ) buildVol(e[0], eqRet.get(e[0]), 21);
        buildComposite();

        part0_Calibration();
        part1_Baseline();
        part2_EquityVolQuintiles();
        part3_OwnVolQuintiles();
        part4_Incremental2x2();
        part5_EraRobustness();
        part6_DaySpecificity();
        part7_MondayReversion();
        part8_MeasureSensitivity();
        part9_VolNormalized();
    }

    // ============================== P0 — CALIBRATION

    private static void part0_Calibration() {
        header("P0 — CALIBRATION (doit reproduire le 37e / 41e AU BP)");
        System.out.println("Référence 37e : EUR -0.032 % | GBP -0.054 % | GBP_JPY -0.056 % | CAD +0.005 %");
        System.out.println("Référence 41e : EUR vendredi -0.0320 % (définition « dernière barre du jour »)");
        for (String s : FX) {
            List<Double> fri = sessionsByDOW(fx.get(s), DayOfWeek.FRIDAY);
            System.out.printf("  %-9s n=%4d  avg %+.4f%%  hit %3.0f%%  méd %+.4f%%  t %5.2f%n",
                s, fri.size(), mean(fri) * 100, hit(fri) * 100, median(fri) * 100, tstat(fri));
        }
        System.out.printf("%nVol 21j disponible : ");
        for (String s : FX) System.out.printf("%s=%d ", s, vol21.get(s).size());
        System.out.printf("| MES=%d MNQ=%d%n", vol21.get("MES").size(), vol21.get("MNQ").size());
        System.out.println(">> Loader et définitions validés si les valeurs sont identiques au bp.");
    }

    // ============================== P1 — BASELINE

    private static void part1_Baseline() {
        header("P1 — BASELINE INCONDITIONNELLE (contrôle bêta : la dérive du jour)");
        System.out.println("Rappel de la loi du seuil : un edge journalier FX meurt sous 3.4 bp.");
        System.out.printf("%-9s | %-34s | %-34s%n", "PAIRE", "TOUS JOURS (n/avg bp/t)", "VENDREDI (n/avg bp/t)");
        for (String s : FX) {
            List<Double> all = new ArrayList<>(fxRet.get(s).values());
            List<Double> fri = sessionsByDOW(fx.get(s), DayOfWeek.FRIDAY);
            System.out.printf("%-9s | %4d  %+7.3f bp  t %5.2f          | %4d  %+7.3f bp  t %5.2f%n",
                s, all.size(), mean(all) * 1e4, tstat(all), fri.size(), mean(fri) * 1e4, tstat(fri));
        }
        List<Double> fa = new ArrayList<>(), fo = new ArrayList<>();
        for (var e : composite.entrySet()) {
            if (e.getKey().getDayOfWeek() == DayOfWeek.FRIDAY) fa.add(e.getValue());
            else fo.add(e.getValue());
        }
        System.out.printf("%nCOMPOSITE F (dollar fort/risk-off, standardisé) : vendredi n=%d avg %+.4f t %5.2f"
            + " | autres jours n=%d avg %+.4f t %5.2f%n",
            fa.size(), mean(fa), tstat(fa), fo.size(), mean(fo), tstat(fo));
        System.out.println(">> Le composite F est en unités de σ ; le seuil 3.4 bp ne s'y applique pas directement.");
    }

    // ============================== P2 — QUINTILES VOL ACTIONS

    private static void part2_EquityVolQuintiles() {
        header("P2 — QUINTILES DE VOL ACTIONS (MES, rang percentile CAUSAL) — LEÇON : LIRE DANS L'ORDRE");
        System.out.println("Vendredi classé par le rang de vol 21j du S&P **mesuré jusqu'à la veille** (look-ahead safe).");
        System.out.println("Q1 = 20 % de vendredis les plus CALMES … Q5 = les plus STRESSÉS. avg en bp.");
        for (String s : FX) {
            System.out.printf("%n--- %s ---%n", s);
            printQuintileRow(s, DayOfWeek.FRIDAY, "MES");
        }
        System.out.printf("%n=== COMPOSITE F : vendredi par quintile de vol actions ===%n");
        printCompositeQuintiles("MES");
        System.out.println(">> MONOTONIE = conditionnement réel. Non monotone = tests multiples (leçon du 23 sept).");
    }

    // ============================== P3 — QUINTILES VOL PROPRE

    private static void part3_OwnVolQuintiles() {
        header("P3 — QUINTILES DE VOL PROPRE DE LA PAIRE (le CONTRÔLE de spécificité)");
        System.out.println("Même construction, mais la vol de l'ACTIF LUI-MÊME. Si seul P3 est monotone →");
        System.out.println("l'information est endogène (la vol propre n'est pas un signal intermarket).");
        for (String s : FX) {
            System.out.printf("%n--- %s ---%n", s);
            printQuintileRow(s, DayOfWeek.FRIDAY, s);
        }
        System.out.printf("%n=== COMPOSITE F : vendredi par quintile de vol PROPRE MOYENNE du panier ===%n");
        printCompositeOwnVol();
    }

    // ============================== P4 — INCRÉMENTAL 2×2

    private static void part4_Incremental2x2() {
        header("P4 — TEST INCRÉMENTAL 2×2 : vol ACTIONS × vol PROPRE (l'info externe ajoute-t-elle ?)");
        System.out.println("Seaux = rang percentile causal ≥ 0.60 (haut) / ≤ 0.40 (bas). avg en bp, IS/OOS séparés.");
        System.out.println("Si la vol actions n'ajoute RIEN dans les lignes ET colonnes → information redondante.");
        List<Double> cellHH = new ArrayList<>(), cellHL = new ArrayList<>();
        List<Double> cellLH = new ArrayList<>(), cellLL = new ArrayList<>();
        List<Double> iHH = new ArrayList<>(), oHH = new ArrayList<>();
        List<Double> iLL = new ArrayList<>(), oLL = new ArrayList<>();
        int n = 0;
        for (String s : FX) {
            for (Map.Entry<LocalDate, Double> e : fxRet.get(s).entrySet()) {
                LocalDate d = e.getKey();
                if (d.getDayOfWeek() != DayOfWeek.FRIDAY) continue;
                Double aR = equityVolRank(d), oR = ownVolRank(s, d);
                if (aR == null || oR == null) continue;
                if (aR >= 0.60 && oR >= 0.60) { cellHH.add(e.getValue()); (d.getYear() <= 2015 ? iHH : oHH).add(e.getValue()); }
                if (aR >= 0.60 && oR <= 0.40) cellHL.add(e.getValue());
                if (aR <= 0.40 && oR >= 0.60) cellLH.add(e.getValue());
                if (aR <= 0.40 && oR <= 0.40) { cellLL.add(e.getValue()); (d.getYear() <= 2015 ? iLL : oLL).add(e.getValue()); }
            }
        }
        System.out.printf("%nPOOL 8 PAIRES (rendements FX bruts signés, bp) :%n");
        System.out.printf("  vol actions HAUTE × vol propre HAUTE : n=%4d  avg %+7.3f bp  t %5.2f  | IS %+7.3f → OOS %+7.3f  %s%n",
            cellHH.size(), mean(cellHH) * 1e4, tstat(cellHH), mean(iHH) * 1e4, mean(oHH) * 1e4, wfTag(iHH, oHH));
        System.out.printf("  vol actions HAUTE × vol propre BASSE: n=%4d  avg %+7.3f bp  t %5.2f%n",
            cellHL.size(), mean(cellHL) * 1e4, tstat(cellHL));
        System.out.printf("  vol actions BASSE × vol propre HAUTE: n=%4d  avg %+7.3f bp  t %5.2f%n",
            cellLH.size(), mean(cellLH) * 1e4, tstat(cellLH));
        System.out.printf("  vol actions BASSE × vol propre BASSE: n=%4d  avg %+7.3f bp  t %5.2f  | IS %+7.3f → OOS %+7.3f  %s%n",
            cellLL.size(), mean(cellLL) * 1e4, tstat(cellLL), mean(iLL) * 1e4, mean(oLL) * 1e4, wfTag(iLL, oLL));

        // lecture incrémentale sur le composite F (signé, en σ)
        System.out.printf("%nCOMPOSITE F (vendredi, unités σ) — même 2×2 :%n");
        List<Double> fHH = new ArrayList<>(), fHL = new ArrayList<>(), fLH = new ArrayList<>(), fLL = new ArrayList<>();
        for (var e : composite.entrySet()) {
            if (e.getKey().getDayOfWeek() != DayOfWeek.FRIDAY) continue;
            Double aR = equityVolRank(e.getKey());
            if (aR == null) continue;
            Double oR = basketOwnVolRank(e.getKey());
            if (oR == null) continue;
            if (aR >= 0.60 && oR >= 0.60) fHH.add(e.getValue());
            if (aR >= 0.60 && oR <= 0.40) fHL.add(e.getValue());
            if (aR <= 0.40 && oR >= 0.60) fLH.add(e.getValue());
            if (aR <= 0.40 && oR <= 0.40) fLL.add(e.getValue());
        }
        System.out.printf("  H×H n=%4d avg %+7.4f t %5.2f | H×B n=%4d avg %+7.4f t %5.2f%n",
            fHH.size(), mean(fHH), tstat(fHH), fHL.size(), mean(fHL), tstat(fHL));
        System.out.printf("  B×H n=%4d avg %+7.4f t %5.2f | B×B n=%4d avg %+7.4f t %5.2f%n",
            fLH.size(), mean(fLH), tstat(fLH), fLL.size(), mean(fLL), tstat(fLL));
        System.out.println(">> Comparer H×B (info ACTIONS seule) et B×H (info PROPRE seule) : qui porte ?");
    }

    // ============================== P5 — ROBUSTESSE D'ÈRE

    private static void part5_EraRobustness() {
        header("P5 — LE CONDITIONNEL EXTRÊME EST-IL UN ARTEFACT DE CRISE ? (Pattern D)");
        List<LocalDate> crisis = List.of(
            LocalDate.of(2008, 1, 1), LocalDate.of(2011, 1, 1), LocalDate.of(2015, 1, 1),
            LocalDate.of(2020, 1, 1), LocalDate.of(2022, 1, 1));
        int[] decisions = { 2006, 2010, 2016, 2020 };
        System.out.println("Composite F le vendredi : quintile HAUT (rang ≥ 0.60) vs BAS (≤ 0.40), par ère.");
        System.out.printf("%-16s | %-28s | %-28s%n", "ÈRE", "F | vol actions HAUTE", "F | vol actions BASSE");
        for (int dec : decisions) {
            List<Double> hi = new ArrayList<>(), lo = new ArrayList<>();
            for (var e : composite.entrySet()) {
                LocalDate d = e.getKey();
                if (d.getDayOfWeek() != DayOfWeek.FRIDAY) continue;
                if (d.getYear() < dec || d.getYear() >= dec + 5) continue;
                Double r = equityVolRank(d);
                if (r == null) continue;
                if (r >= 0.60) hi.add(e.getValue());
                else if (r <= 0.40) lo.add(e.getValue());
            }
            System.out.printf("%-16s | n=%4d avg %+7.4f t %5.2f   | n=%4d avg %+7.4f t %5.2f%n",
                dec + "-" + (dec + 4), hi.size(), mean(hi), tstat(hi), lo.size(), mean(lo), tstat(lo));
        }
        // exclusion des années de crise
        for (int mode = 0; mode < 2; mode++) {
            List<Double> hi = new ArrayList<>(), lo = new ArrayList<>();
            for (var e : composite.entrySet()) {
                LocalDate d = e.getKey();
                if (d.getDayOfWeek() != DayOfWeek.FRIDAY) continue;
                if (mode == 1) {
                    boolean inCrisis = false;
                    for (LocalDate c : crisis) if (d.getYear() == c.getYear()) inCrisis = true;
                    if (inCrisis) continue;
                }
                Double r = equityVolRank(d);
                if (r == null) continue;
                if (r >= 0.60) hi.add(e.getValue());
                else if (r <= 0.40) lo.add(e.getValue());
            }
            System.out.printf("F vendredi %-26s : HAUT n=%4d avg %+7.4f t %5.2f | BAS n=%4d avg %+7.4f t %5.2f | Δ %+7.4f%n",
                mode == 0 ? "(toutes années)" : "(hors 2008/11/15/20/22)",
                hi.size(), mean(hi), tstat(hi), lo.size(), mean(lo), tstat(lo), mean(hi) - mean(lo));
        }
        System.out.println(">> Si le conditionnel ne survit PAS hors crises → c'est la crise qui porte, pas la vol.");
    }

    // ============================== P6 — SPÉCIFICITÉ DU JOUR

    private static void part6_DaySpecificity() {
        header("P6 — SPÉCIFICITÉ DU JOUR : le conditionnement par la vol est-il VENDREDI-spécifique ?");
        System.out.println("Composite F par jour de semaine ; HAUT = vol actions ≥ 0.60, BAS = ≤ 0.40 (rang causal).");
        System.out.printf("%-9s | %-30s | %-30s | %s%n", "JOUR", "vol HAUTE (n/avg/t)", "vol BASSE (n/avg/t)", "Δ (t de Welch)");
        for (DayOfWeek dow : new DayOfWeek[]{ DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY }) {
            List<Double> hi = new ArrayList<>(), lo = new ArrayList<>();
            for (var e : composite.entrySet()) {
                if (e.getKey().getDayOfWeek() != dow) continue;
                Double r = equityVolRank(e.getKey());
                if (r == null) continue;
                if (r >= 0.60) hi.add(e.getValue());
                else if (r <= 0.40) lo.add(e.getValue());
            }
            System.out.printf("%-9s | n=%4d avg %+7.4f t %5.2f | n=%4d avg %+7.4f t %5.2f | %+7.4f (t %5.2f)%n",
                dow, hi.size(), mean(hi), tstat(hi), lo.size(), mean(lo), tstat(lo),
                mean(hi) - mean(lo), welchT(hi, lo));
        }
    }

    // ============================== P7 — RÉVERSION DU LUNDI long-only

    private static void part7_MondayReversion() {
        header("P7 — LE CONDITIONNEMENT VOL S'APPLIQUE-T-IL AUSSI À LA RÉVERSION DU LUNDI (39e) ?");
        System.out.println("Config 39e = LONG lundi conditionnel au rendement lundi-jeudi précédent (W4 < 0).");
        System.out.println("Ici : ce LONG du lundi, splité par la vol ACTIONS au vendredi précédent. avg en bp.");
        List<Double> hi = new ArrayList<>(), lo = new ArrayList<>(), all = new ArrayList<>();
        List<Double> iHi = new ArrayList<>(), oHi = new ArrayList<>(), iLo = new ArrayList<>(), oLo = new ArrayList<>();
        for (String s : FX) {
            TreeMap<LocalDate, Double> m = fx.get(s);
            List<LocalDate> sess = sessionDates(m);
            Map<LocalDate, Double> r = fxRet.get(s);
            for (int i = 6; i < sess.size(); i++) {
                LocalDate d = sess.get(i);
                if (d.getDayOfWeek() != DayOfWeek.MONDAY) continue;
                Double x = r.get(d);
                if (x == null) continue;
                double cA = m.get(sess.get(i - 6)), cB = m.get(sess.get(i - 2));
                if (cA <= 0) continue;
                double w4 = (cB - cA) / cA;
                if (w4 >= 0) continue;                      // 39e : long-only, W4 < 0
                Double vol = equityVolRank(d);
                if (vol == null) continue;
                all.add(x);
                if (vol >= 0.60) { hi.add(x); (d.getYear() <= 2015 ? iHi : oHi).add(x); }
                else if (vol <= 0.40) { lo.add(x); (d.getYear() <= 2015 ? iLo : oLo).add(x); }
            }
        }
        System.out.printf("  TOUS RÉGIMES        : n=%4d avg %+7.3f bp t %5.2f%n", all.size(), mean(all) * 1e4, tstat(all));
        System.out.printf("  vol actions HAUTE   : n=%4d avg %+7.3f bp t %5.2f | IS %+7.3f → OOS %+7.3f  %s%n",
            hi.size(), mean(hi) * 1e4, tstat(hi), mean(iHi) * 1e4, mean(oHi) * 1e4, wfTag(iHi, oHi));
        System.out.printf("  vol actions BASSE   : n=%4d avg %+7.3f bp t %5.2f | IS %+7.3f → OOS %+7.3f  %s%n",
            lo.size(), mean(lo) * 1e4, tstat(lo), mean(iLo) * 1e4, mean(oLo) * 1e4, wfTag(iLo, oLo));
        System.out.printf("  >> À comparer au seuil de survie : %.1f bp (coûts 2.14 + swap ~1.2)%n", THRESHOLD_BP);
    }

    // ============================== P8 — SENSIBILITÉ DE LA MESURE

    private static void part8_MeasureSensitivity() {
        header("P8 — SENSIBILITÉ À LA MESURE DE VOL (la conclusion dépend-elle du proxy ?)");
        System.out.println("Trois proxies : (a) std 21j MES [défaut], (b) std 63j MES, (c) |rendement| moyen 21j MES,");
        System.out.println("(d) std 21j NASDAQ (2e indice). Composite F le vendredi, HAUT vs BAS (rang causal).");
        Map<String, TreeMap<LocalDate, Double>> alt = new LinkedHashMap<>();
        alt.put("std63_MES", volSeries(eqRet.get("MES"), 63));
        alt.put("absdev21_MES", absDevSeries(eqRet.get("MES"), 21));
        alt.put("std21_MNQ", volSeries(eqRet.get("MNQ"), 21));
        for (var a : alt.entrySet()) {
            TreeMap<LocalDate, Double> rank = trailingRank(a.getValue());
            List<Double> hi = new ArrayList<>(), lo = new ArrayList<>();
            for (var e : composite.entrySet()) {
                if (e.getKey().getDayOfWeek() != DayOfWeek.FRIDAY) continue;
                Double r = rankFloor(rank, e.getKey());
                if (r == null) continue;
                if (r >= 0.60) hi.add(e.getValue());
                else if (r <= 0.40) lo.add(e.getValue());
            }
            System.out.printf("  %-12s : HAUT n=%4d avg %+7.4f t %5.2f | BAS n=%4d avg %+7.4f t %5.2f | Δ %+7.4f%n",
                a.getKey(), hi.size(), mean(hi), tstat(hi), lo.size(), mean(lo), tstat(lo), mean(hi) - mean(lo));
        }
        System.out.println("  --- fenêtre de traîne du rang causal (TRAIL) : le rang n'existe qu'après TRAIL obs ---");
        for (int trail : new int[]{ 250, 500, 1000, 2000 }) {
            TreeMap<LocalDate, Double> rank = trailingRank(vol21.get("MES"), trail);
            List<Double> hi = new ArrayList<>(), lo = new ArrayList<>();
            for (var e : composite.entrySet()) {
                if (e.getKey().getDayOfWeek() != DayOfWeek.FRIDAY) continue;
                Double r = rankFloor(rank, e.getKey());
                if (r == null) continue;
                if (r >= 0.60) hi.add(e.getValue());
                else if (r <= 0.40) lo.add(e.getValue());
            }
            System.out.printf("  TRAIL=%-5d (1er rang %s) : HAUT n=%4d avg %+7.4f t %5.2f | BAS n=%4d avg %+7.4f t %5.2f | Δ %+7.4f%n",
                trail, rank.isEmpty() ? "-" : rank.firstKey(), hi.size(), mean(hi), tstat(hi),
                lo.size(), mean(lo), tstat(lo), mean(hi) - mean(lo));
        }
    }

    // ============================== helpers de calcul

    private static void buildVol(String key, Map<LocalDate, Double> rets, int win) {
        vol21.put(key, volSeries(rets, win));
        volRank.put(key, trailingRank(vol21.get(key)));
    }

    private static TreeMap<LocalDate, Double> volSeries(Map<LocalDate, Double> rets, int win) {
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        List<LocalDate> ds = new ArrayList<>(rets.keySet());
        List<Double> buf = new ArrayList<>();
        for (LocalDate d : ds) {
            buf.add(rets.get(d));
            if (buf.size() > win) buf.remove(0);
            if (buf.size() == win) out.put(d, std(buf));
        }
        return out;
    }

    private static TreeMap<LocalDate, Double> absDevSeries(Map<LocalDate, Double> rets, int win) {
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        List<Double> buf = new ArrayList<>();
        for (var e : rets.entrySet()) {
            buf.add(Math.abs(e.getValue()));
            if (buf.size() > win) buf.remove(0);
            if (buf.size() == win) out.put(e.getKey(), mean(buf));
        }
        return out;
    }

    /** Rang percentile causal : position de v_t dans les TRAIL 1000 valeurs PRÉCÉDENTES. */
    private static TreeMap<LocalDate, Double> trailingRank(TreeMap<LocalDate, Double> vol) {
        return trailingRank(vol, 1000);
    }

    private static TreeMap<LocalDate, Double> trailingRank(TreeMap<LocalDate, Double> vol, int trail) {
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        List<Double> hist = new ArrayList<>();
        for (var e : vol.entrySet()) {
            if (hist.size() >= trail) {
                int below = 0;
                for (double h : hist) if (h <= e.getValue()) below++;
                out.put(e.getKey(), below / (double) hist.size());
            }
            hist.add(e.getValue());
            if (hist.size() > trail) hist.remove(0);
        }
        return out;
    }

    /** Rang de vol actions applicable à la date d (vol mesurée jusqu'à la VEILLE → look-ahead safe). */
    private static Double equityVolRank(LocalDate d) { return rankFloor(volRank.get("MES"), d); }

    private static Double ownVolRank(String sym, LocalDate d) { return rankFloor(volRank.get(sym), d); }

    private static Double basketOwnVolRank(LocalDate d) {
        double s = 0; int n = 0;
        for (String sym : FX) {
            Double r = rankFloor(volRank.get(sym), d);
            if (r == null) continue;
            s += r; n++;
        }
        return n == FX.length ? s / n : null;
    }

    /** Plus grande date de <rank> STRICTEMENT antérieure à d. */
    private static Double rankFloor(TreeMap<LocalDate, Double> rank, LocalDate d) {
        if (rank == null) return null;
        LocalDate k = rank.lowerKey(d);
        return k == null ? null : rank.get(k);
    }

    private static void buildComposite() {
        Set<LocalDate> common = null;
        for (String s : FX) {
            Set<LocalDate> ks = new TreeSet<>(fx.get(s).keySet());
            if (common == null) common = ks;
            else common.retainAll(ks);
        }
        Map<String, Double> sd = new LinkedHashMap<>();
        for (String s : FX) {
            List<Double> v = new ArrayList<>();
            for (LocalDate d : common) { Double x = fxRet.get(s).get(d); if (x != null) v.add(x); }
            sd.put(s, std(v));
        }
        composite = new TreeMap<>();
        for (LocalDate d : common) {
            double s = 0; int n = 0;
            for (String sym : FX) {
                Double x = fxRet.get(sym).get(d);
                if (x == null || sd.get(sym) == 0) continue;
                s += F_SIGN.get(sym) * x / sd.get(sym);
                n++;
            }
            if (n == FX.length) composite.put(d, s / n);
        }
        System.out.printf("[composite] %s → %s (%d dates communes)%n",
            composite.isEmpty() ? "-" : composite.firstKey(),
            composite.isEmpty() ? "-" : composite.lastKey(), composite.size());
    }

    // ============================== P9 — CONTRÔLE DÉCISIF : NORMALISATION

    /**
     * LE contrôle anti-artefact de ce check. Les quintiles bruts (P2) et le composite F (P5/P6)
     * montrent une amplitude PLUS GRANDE dans les régimes de vol élevée — mais dans un régime de
     * vol élevée, TOUT bouge plus (y compris la dérive). L'amplitude n'est donc pas un edge.
     * Le test décisif : normaliser chaque rendement par la VOL COURANTE de l'actif (z-score, vol
     * 21 j mesurée la veille). Si l'effet « vol actions » survit en z → conditionnement réel ;
     * s'il disparaît → pur effet d'échelle mécanique (plus de bp parce que plus de vol, pas plus
     * d'edge par unité de risque).
     */
    private static void part9_VolNormalized() {
        header("P9 — CONTRÔLE DÉCISIF : NORMALISATION PAR LA VOL COURANTE (z-score)");
        System.out.println("z = rendement / vol 21j de l'actif MESURÉE LA VEILLE. Si l'effet vol actions");
        System.out.println("disparaît en z, l'amplitude brute n'était que de l'échelle (pas un conditionnement).");

        // z du composite F
        TreeMap<LocalDate, Double> fVol = volSeries(new TreeMap<>(composite), 21);
        TreeMap<LocalDate, Double> zF = new TreeMap<>();
        for (var e : composite.entrySet()) {
            Double v = rankLess(fVol, e.getKey());
            if (v != null && v > 0) zF.put(e.getKey(), e.getValue() / v);
        }
        List<Double> friZ = new ArrayList<>(), othZ = new ArrayList<>();
        for (var e : zF.entrySet()) {
            if (e.getKey().getDayOfWeek() == DayOfWeek.FRIDAY) friZ.add(e.getValue());
            else othZ.add(e.getValue());
        }
        System.out.printf("%nz(F) VENDREDI : n=%d avg %+6.3f  t %5.2f | autres jours n=%d avg %+6.3f t %5.2f%n",
            friZ.size(), mean(friZ), tstat(friZ), othZ.size(), mean(othZ), tstat(othZ));

        System.out.printf("%n(a) z(F) le vendredi par quintile de vol ACTIONS :%n");
        TreeMap<LocalDate, Double> rank = volRank.get("MES");
        List<Double>[] q = new List[5];
        for (int i = 0; i < 5; i++) q[i] = new ArrayList<>();
        for (var e : zF.entrySet()) {
            if (e.getKey().getDayOfWeek() != DayOfWeek.FRIDAY) continue;
            Double r = rankFloor(rank, e.getKey());
            if (r == null) continue;
            q[Math.min(4, (int) (r * 5))].add(e.getValue());
        }
        for (int i = 0; i < 5; i++)
            if (!q[i].isEmpty())
                System.out.printf("    Q%d | n=%4d  avg %+6.3f z  hit %3.0f%%  t %5.2f%n",
                    i + 1, q[i].size(), mean(q[i]), hit(q[i]) * 100, tstat(q[i]));

        System.out.printf("%n(b) z(F) par JOUR et par régime de vol actions (spécificité en z) :%n");
        System.out.printf("%-9s | %-24s | %-24s | %s%n", "JOUR", "vol HAUTE", "vol BASSE", "Δ (t)");
        for (DayOfWeek dow : new DayOfWeek[]{ DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY }) {
            List<Double> hi = new ArrayList<>(), lo = new ArrayList<>();
            for (var e : zF.entrySet()) {
                if (e.getKey().getDayOfWeek() != dow) continue;
                Double r = rankFloor(rank, e.getKey());
                if (r == null) continue;
                if (r >= 0.60) hi.add(e.getValue());
                else if (r <= 0.40) lo.add(e.getValue());
            }
            System.out.printf("%-9s | n=%4d avg %+6.3f t %5.2f | n=%4d avg %+6.3f t %5.2f | %+6.3f (t %5.2f)%n",
                dow, hi.size(), mean(hi), tstat(hi), lo.size(), mean(lo), tstat(lo),
                mean(hi) - mean(lo), welchT(hi, lo));
        }

        // z par paire × 2×2 vol actions/propre (rendements FX normalisés)
        System.out.printf("%n(c) POOL 8 PAIRES : vendredi en z (rendement / vol propre de la veille) :%n");
        List<Double> zHH = new ArrayList<>(), zHL = new ArrayList<>(), zLH = new ArrayList<>(), zLL = new ArrayList<>();
        for (String s : FX) {
            TreeMap<LocalDate, Double> pv = vol21.get(s);
            for (var e : fxRet.get(s).entrySet()) {
                LocalDate d = e.getKey();
                if (d.getDayOfWeek() != DayOfWeek.FRIDAY) continue;
                Double v = rankLess(pv, d);
                if (v == null || v <= 0) continue;
                double z = e.getValue() / v;
                Double aR = equityVolRank(d), oR = ownVolRank(s, d);
                if (aR == null || oR == null) continue;
                if (aR >= 0.60 && oR >= 0.60) zHH.add(z);
                else if (aR >= 0.60 && oR <= 0.40) zHL.add(z);
                else if (aR <= 0.40 && oR >= 0.60) zLH.add(z);
                else if (aR <= 0.40 && oR <= 0.40) zLL.add(z);
            }
        }
        System.out.printf("    vol actions H × vol propre H : n=%4d avg %+6.3f z t %5.2f%n",
            zHH.size(), mean(zHH), tstat(zHH));
        System.out.printf("    vol actions H × vol propre B : n=%4d avg %+6.3f z t %5.2f%n",
            zHL.size(), mean(zHL), tstat(zHL));
        System.out.printf("    vol actions B × vol propre H : n=%4d avg %+6.3f z t %5.2f%n",
            zLH.size(), mean(zLH), tstat(zLH));
        System.out.printf("    vol actions B × vol propre B : n=%4d avg %+6.3f z t %5.2f%n",
            zLL.size(), mean(zLL), tstat(zLL));
        System.out.println(">> Lecture : si z(H×H) ≈ z(H×B) ≈ z(L×H) ≈ z(L×B), aucun conditionnement en z.");
        System.out.println(">> Si z augmente UNIQUEMENT avec la vol propre → information endogène (pas intermarket).");
    }

    /** Valeur de <map> à la plus grande date STRICTEMENT antérieure à d (look-ahead safe). */
    private static Double rankLess(TreeMap<LocalDate, Double> map, LocalDate d) {
        if (map == null) return null;
        LocalDate k = map.lowerKey(d);
        return k == null ? null : map.get(k);
    }

    /** t de Welch de la différence de deux moyennes (échantillons indépendants). */
    private static double welchT(List<Double> a, List<Double> b) {
        if (a == null || b == null || a.size() < 3 || b.size() < 3) return 0;
        double sa = std(a) / Math.sqrt(a.size()), sb = std(b) / Math.sqrt(b.size());
        double den = Math.sqrt(sa * sa + sb * sb);
        return den == 0 ? 0 : (mean(a) - mean(b)) / den;
    }

    // ============================== helpers de rapport
    private static void printQuintileRow(String sym, DayOfWeek dow, String volKey) {
        System.out.println("  Q | n   |  avg bp  |  hit  |    t  |  IS bp  | OOS bp | seuil 3.4 bp");
        List<Double>[] q = new List[5];
        List<Double>[] qi = new List[5];
        List<Double>[] qo = new List[5];
        for (int i = 0; i < 5; i++) { q[i] = new ArrayList<>(); qi[i] = new ArrayList<>(); qo[i] = new ArrayList<>(); }
        TreeMap<LocalDate, Double> rank = volRank.get(volKey);
        for (var e : fxRet.get(sym).entrySet()) {
            LocalDate d = e.getKey();
            if (d.getDayOfWeek() != dow) continue;
            Double r = rankFloor(rank, d);
            if (r == null) continue;
            int b = Math.min(4, (int) (r * 5));
            q[b].add(e.getValue());
            (d.getYear() <= 2015 ? qi[b] : qo[b]).add(e.getValue());
        }
        for (int i = 0; i < 5; i++) {
            if (q[i].isEmpty()) continue;
            double a = mean(q[i]) * 1e4;
            System.out.printf("  %d | %4d | %+8.3f | %4.0f%% | %5.2f | %+7.3f | %+6.3f | %s%n",
                i + 1, q[i].size(), a, hit(q[i]) * 100, tstat(q[i]),
                mean(qi[i]) * 1e4, mean(qo[i]) * 1e4, Math.abs(a) >= THRESHOLD_BP ? "FRANCHI" : "-");
        }
    }

    private static void printCompositeQuintiles(String volKey) {
        TreeMap<LocalDate, Double> rank = volRank.get(volKey);
        List<Double>[] q = new List[5];
        List<Double>[] qi = new List[5];
        List<Double>[] qo = new List[5];
        for (int i = 0; i < 5; i++) { q[i] = new ArrayList<>(); qi[i] = new ArrayList<>(); qo[i] = new ArrayList<>(); }
        for (var e : composite.entrySet()) {
            LocalDate d = e.getKey();
            if (d.getDayOfWeek() != DayOfWeek.FRIDAY) continue;
            Double r = rankFloor(rank, d);
            if (r == null) continue;
            int b = Math.min(4, (int) (r * 5));
            q[b].add(e.getValue());
            (d.getYear() <= 2015 ? qi[b] : qo[b]).add(e.getValue());
        }
        for (int i = 0; i < 5; i++) {
            if (q[i].isEmpty()) continue;
            System.out.printf("  Q%d | n=%4d  avg %+7.4f σ  hit %3.0f%%  t %5.2f | IS %+7.4f → OOS %+7.4f  %s%n",
                i + 1, q[i].size(), mean(q[i]), hit(q[i]) * 100, tstat(q[i]),
                mean(qi[i]), mean(qo[i]), wfTag(qi[i], qo[i]));
        }
    }

    private static void printCompositeOwnVol() {
        List<Double>[] q = new List[5];
        for (int i = 0; i < 5; i++) q[i] = new ArrayList<>();
        for (var e : composite.entrySet()) {
            LocalDate d = e.getKey();
            if (d.getDayOfWeek() != DayOfWeek.FRIDAY) continue;
            Double r = basketOwnVolRank(d);
            if (r == null) continue;
            q[Math.min(4, (int) (r * 5))].add(e.getValue());
        }
        for (int i = 0; i < 5; i++) {
            if (q[i].isEmpty()) continue;
            System.out.printf("  Q%d | n=%4d  avg %+7.4f σ  hit %3.0f%%  t %5.2f%n",
                i + 1, q[i].size(), mean(q[i]), hit(q[i]) * 100, tstat(q[i]));
        }
    }

    // ============================== helpers génériques

    private static void header(String t) {
        System.out.println();
        System.out.println("##################################################################");
        System.out.println("# " + t);
        System.out.println("##################################################################");
    }

    private static List<LocalDate> sessionDates(TreeMap<LocalDate, Double> m) {
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate d : m.keySet())
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) out.add(d);
        return out;
    }

    private static List<Double> sessionsByDOW(TreeMap<LocalDate, Double> m, DayOfWeek dow) {
        Map<LocalDate, Double> r = retsByDate(m);
        List<Double> out = new ArrayList<>();
        for (var e : r.entrySet()) if (e.getKey().getDayOfWeek() == dow) out.add(e.getValue());
        return out;
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

    private static TreeMap<LocalDate, Double> dailyClosesBars(String symbol, int from, int to) throws Exception {
        TreeMap<LocalDate, Double> map = new TreeMap<>();
        for (int y = from; y <= to; y++) {
            try {
                List<Bar> bars = HistoricalDataLoader.loadYear(symbol, y, Paths.get("data/historical/bars"));
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
        for (String line : Files.readAllLines(Path.of("data/historical/futures").resolve(csv))) {
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
