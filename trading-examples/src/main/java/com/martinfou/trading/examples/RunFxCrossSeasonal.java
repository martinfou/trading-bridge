package com.martinfou.trading.examples;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;

import java.time.*;
import java.util.*;

/**
 * RunFxCrossSeasonal — mercredi 7 octobre 2026 (51e résultat, rotation = PATTERN SAISONNIER).
 *
 * PISTE OUVERTE PAR LE 48e/50e :
 *  - 48e : « 0/4 adage classique ne survit au contrôle de dérive apparié » — MESURÉ SUR DES PAIRES.
 *  - 50e : le fade du vendredi est un RISK-OFF de JAMBES, dollar-neutre ; le porteur optimal est la
 *    jambe REFUGE ; 6+ instruments partagent le facteur. La levée du blocage « gate 30 trades /
 *    3-4 paires » passe par l'espace des CROSSES.
 *
 * QUESTION : la saisonnalité mensuelle des paires est-elle de la SAISONNALITÉ, ou la projection d'une
 * dérive du DOLLAR sur des couples choisis ? Une paire n'est pas dollar-neutre : r(paire) = leg(base) −
 * leg(quote). On refait donc la matrice mensuelle dans l'ESPACE DOLLAR-NEUTRE (12 crosses + 7 jambes
 * exactes + le facteur risque→refuge du 50e), avec les contrôles du 48e.
 *
 * Méthode : clôtures journalières sur barres RÉELLES (high > low), jour UTC. Mensuel = fin de mois →
 * fin de mois. Contrôles : t, médiane, hit, IS/OOS (2006-15 / 2016-26), 4 décennies, dérive appariée
 * (hold non conditionné de MÊME longueur), spécificité ±1 mois. Scan de 12×20 = 240 cellules ⇒
 * exiger |t| ≥ 3 (Bonferroni) EN PLUS de la stabilité pour nommer une saison.
 *
 * Aucune stratégie codée : mesure de statistique calendaire (Pattern D comme 38e/40e/41e/48e/49e/50e).
 *
 * Usage : java -cp "$CP" com.martinfou.trading.examples.RunFxCrossSeasonal [--all|--matrix|--cells|--factor]
 */
public class RunFxCrossSeasonal {

    static final String YEAR_SPEC = "2006-2026";
    static final String[] PAIRS = {"EUR_USD", "GBP_USD", "AUD_USD", "NZD_USD", "USD_JPY", "USD_CHF", "USD_CAD", "GBP_JPY"};

    /** 12 crosses dollar-neutres (mêmes définitions que le 50e). b.startsWith("USD_") ⇒ produit. */
    static final String[][] CROSS_DEFS = {
        {"GBP_CHF", "GBP_USD", "USD_CHF"}, {"AUD_CHF", "AUD_USD", "USD_CHF"},
        {"NZD_CHF", "NZD_USD", "USD_CHF"}, {"EUR_CHF", "EUR_USD", "USD_CHF"},
        {"GBP_JPY", "GBP_USD", "USD_JPY"}, {"AUD_JPY", "AUD_USD", "USD_JPY"},
        {"NZD_JPY", "NZD_USD", "USD_JPY"}, {"EUR_JPY", "EUR_USD", "USD_JPY"},
        {"GBP_CAD", "GBP_USD", "USD_CAD"}, {"GBP_EUR", "GBP_USD", "EUR_USD"},
        {"GBP_NZD", "GBP_USD", "NZD_USD"}, {"GBP_AUD", "GBP_USD", "AUD_USD"},
    };

    static final String[] MFR = {"janv", "févr", "mars", "avr", "mai", "juin",
        "juil", "août", "sept", "oct", "nov", "déc"};

    static final Map<String, TreeMap<LocalDate, Double>> CLOSE = new LinkedHashMap<>();
    static final Map<String, TreeMap<LocalDate, Double>> SERIES = new LinkedHashMap<>();   // prix → matrice
    static final double[] LEG_MEAN_DAY = {1.000, +1, -1, -1, +1, +1, -1};                  // EUR GBP JPY CHF AUD NZD CAD
    static final String[] LEG_CCY = {"EUR", "GBP", "JPY", "CHF", "AUD", "NZD", "CAD"};
    static final String[] LEG_PAIR = {"EUR_USD", "GBP_USD", "USD_JPY", "USD_CHF", "AUD_USD", "NZD_USD", "USD_CAD"};

    // ================================================================== main

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "--all";
        System.out.println("========================================================================");
        System.out.println("LA SAISONNALITÉ FX SURVIT-ELLE AU RETRAIT DU DOLLAR ? (51e)");
        System.out.println("Mercredi 7 oct 2026 · rotation PATTERN SAISONNIER · " + YEAR_SPEC);
        System.out.println("Espace dollar-neutre : 12 crosses + 7 jambes exactes + facteur risque→refuge (50e).");
        System.out.println("Diagnostic : série DOLLAR (moyenne des 7 jambes) pour séparer saison du dollar / saison de devise.");
        System.out.println("Base : clôtures journalières sur BARRES RÉELLES (high > low), jour UTC.");
        System.out.println("Contrôles du 48e : dérive appariée, IS/OOS, 4 décennies, spécificité ±1 mois.");
        System.out.println("========================================================================");
        load();
        switch (mode) {
            case "--matrix" -> matrix();
            case "--cells" -> cells();
            case "--factor" -> factor();
            case "--all" -> { inventory(); matrix(); factor(); cells(); specificity(); monthDecomp(); decompTopMonth(); verdict(); }
            default -> inventory();
        }
        System.out.println("\nDONE");
    }

    // ================================================================== données

    static void load() throws Exception {
        for (String sym : PAIRS) {
            TreeMap<LocalDate, Double> closes = new TreeMap<>();
            List<Bar> bars = HistoricalDataLoader.loadFromArgs(sym, sym, YEAR_SPEC).bars();
            if (bars == null || bars.isEmpty()) { System.out.println("PAS DE DONNÉES " + sym); continue; }
            for (Bar b : bars) {
                if (b.high() <= b.low()) continue;                          // barre de carry (40e/48e)
                closes.put(b.timestamp().atZone(ZoneOffset.UTC).toLocalDate(), b.close());
            }
            CLOSE.put(sym, closes);
        }
        for (String[] d : CROSS_DEFS) SERIES.put(d[0], cross(d[1], d[2]));
        SERIES.put("FACTEUR(refuge−risque)", factorSeries());
        SERIES.put("DOLLAR(force_USD)", dollarSeries());
        // jambes exactes (les 6 paires contre-USD définissent leur jambe ; GBP_JPY est l'équation redondante)
        for (int i = 0; i < LEG_CCY.length; i++) SERIES.put("leg_" + LEG_CCY[i], legSeries(i));
    }

    static void inventory() {
        System.out.println("\n=== A) DONNÉES ===");
        for (String sym : PAIRS) {
            TreeMap<LocalDate, Double> c = CLOSE.get(sym);
            if (c == null) continue;
            System.out.printf("  %-9s %5d jours réels · %s → %s%n", sym, c.size(), c.firstKey(), c.lastKey());
        }
        System.out.printf("  %d séries construites : 12 crosses + 1 facteur + 7 jambes%n", SERIES.size());
    }

    /** cross(a/b) = prix(a/USD) × prix(USD/b) si b est quoté USD_X, sinon prix(a/USD) / prix(b/USD). */
    static TreeMap<LocalDate, Double> cross(String a, String b) {
        boolean multiply = b.startsWith("USD_");
        TreeMap<LocalDate, Double> ra = CLOSE.get(a), rb = CLOSE.get(b), out = new TreeMap<>();
        if (ra == null || rb == null) return out;
        for (var e : ra.entrySet()) {
            Double y = rb.get(e.getKey());
            if (y == null || y <= 0) continue;
            out.put(e.getKey(), multiply ? e.getValue() * y : e.getValue() / y);
        }
        return out;
    }

    /** Jambe exacte : leg(X) = +r(X_USD) pour X ∈ {EUR,GBP,AUD,NZD}, −r(USD_X) pour X ∈ {JPY,CHF,CAD}. */
    static TreeMap<LocalDate, Double> legSeries(int i) {
        String pair = LEG_PAIR[i];
        double sign = LEG_MEAN_DAY[i];
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        TreeMap<LocalDate, Double> px = CLOSE.get(pair);
        if (px == null) return out;
        LocalDate prev = null;
        double idx = 1.0;
        for (var e : px.entrySet()) {
            if (prev != null) {
                double p = px.get(prev);
                if (p > 0) idx *= (1.0 + sign * (e.getValue() / p - 1.0));
            }
            out.put(e.getKey(), idx);
            prev = e.getKey();
        }
        return out;
    }

    /** Facteur = moyenne(jambes refuge CHF,JPY) − moyenne(jambes risque EUR,GBP,AUD,NZD), dollar-neutre. */
    static TreeMap<LocalDate, Double> factorSeries() {
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        Map<String, TreeMap<LocalDate, Double>> leg = new LinkedHashMap<>();
        for (int i = 0; i < LEG_CCY.length; i++) leg.put(LEG_CCY[i], legSeries(i));
        List<LocalDate> dates = new ArrayList<>(leg.get("EUR").keySet());
        double idx = 1.0;
        LocalDate prev = null;
        Map<String, Double> prevV = new HashMap<>();
        for (LocalDate d : dates) {
            double risk = 0, refuge = 0; int nr = 0, nf = 0;
            for (String c : List.of("EUR", "GBP", "AUD", "NZD")) {
                Double v = leg.get(c).get(d);
                if (v == null) continue;
                if (prev != null) { double pv = prevV.getOrDefault(c, v); if (pv > 0) { risk += v / pv - 1.0; nr++; } }
            }
            for (String c : List.of("CHF", "JPY")) {
                Double v = leg.get(c).get(d);
                if (v == null) continue;
                if (prev != null) { double pv = prevV.getOrDefault(c, v); if (pv > 0) { refuge += v / pv - 1.0; nf++; } }
            }
            if (prev != null && nr > 0 && nf > 0) idx *= (1.0 + refuge / nf - risk / nr);
            out.put(d, idx);
            for (String c : LEG_CCY) { Double v = leg.get(c).get(d); if (v != null) prevV.put(c, v); }
            prev = d;
        }
        return out;
    }

    /** Force du dollar = moyenne des 7 jambes, signe inversé (index de prix). */
    static TreeMap<LocalDate, Double> dollarSeries() {
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        Map<String, TreeMap<LocalDate, Double>> leg = new LinkedHashMap<>();
        for (int i = 0; i < LEG_CCY.length; i++) leg.put(LEG_CCY[i], legSeries(i));
        List<LocalDate> dates = new ArrayList<>(leg.get("EUR").keySet());
        double idx = 1.0;
        LocalDate prev = null;
        Map<String, Double> prevV = new HashMap<>();
        for (LocalDate d : dates) {
            double mean = 0; int n = 0;
            for (String c : LEG_CCY) {
                Double v = leg.get(c).get(d);
                if (v == null) continue;
                if (prev != null) {
                    double pv = prevV.getOrDefault(c, v);
                    if (pv > 0) { mean += v / pv - 1.0; n++; }
                }
            }
            if (prev != null && n > 0) idx *= (1.0 - mean / n);     // USD fort = −(appréciation moyenne)
            out.put(d, idx);
            for (String c : LEG_CCY) { Double v = leg.get(c).get(d); if (v != null) prevV.put(c, v); }
            prev = d;
        }
        return out;
    }

    // ================================================================== stats

    record Cell(String inst, int month, int n, double meanBp, double medBp, double hit, double t,
                double is, double oos, double[] dec, double ctrl) {}

    static Cell cell(String inst, int month, List<double[]> obs, double ctrl) {
        List<Double> v = new ArrayList<>();
        List<Double> is = new ArrayList<>(), oos = new ArrayList<>();
        double[] dec = new double[4];
        int[] decN = new int[4];
        for (double[] o : obs) {
            double r = o[1] * 10_000.0;                    // unité → bp (1 % = 100 bp)
            v.add(r);
            (o[0] <= 2015 ? is : oos).add(r);
            int di = decadeIndex((int) o[0]);
            dec[di] += r; decN[di]++;
        }
        for (int i = 0; i < 4; i++) if (decN[i] > 0) dec[i] /= decN[i];
        double[] s = stat(v);
        return new Cell(inst, month, v.size(), s[1], s[2], s[3], s[4], avg(is), avg(oos), dec, ctrl);
    }

    static int decadeIndex(int y) {
        if (y <= 2009) return 0;
        if (y <= 2014) return 1;
        if (y <= 2019) return 2;
        return 3;
    }

    /** Statistiques : {n, moyenne, médiane, hit%, t}. */
    static double[] stat(List<Double> v) {
        if (v.isEmpty()) return new double[]{0, 0, 0, 0, 0};
        int n = v.size();
        double m = v.stream().mapToDouble(d -> d).average().orElse(0);
        List<Double> c = new ArrayList<>(v);
        Collections.sort(c);
        double med = c.get(n / 2);
        double s2 = v.stream().mapToDouble(d -> (d - m) * (d - m)).sum() / Math.max(1, n - 1);
        double t = n > 1 ? Math.sqrt(n) * m / Math.max(1e-9, Math.sqrt(s2)) : 0;
        long hits = v.stream().filter(x -> x > 0).count();
        return new double[]{n, m, med, 100.0 * hits / n, t};
    }

    static double avg(List<Double> v) {
        return v.isEmpty() ? 0 : v.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    /** Rendements mensuels vrais (fin de mois → fin de mois) : {année, %}. */
    static Map<Integer, List<double[]>> monthly(TreeMap<LocalDate, Double> px) {
        Map<YearMonth, Double> me = new TreeMap<>();
        for (var e : px.entrySet()) me.put(YearMonth.from(e.getKey()), e.getValue());
        Map<Integer, List<double[]>> out = new LinkedHashMap<>();
        YearMonth prev = null;
        for (var e : me.entrySet()) {
            if (prev != null && prev.plusMonths(1).equals(e.getKey())) {
                double a = me.get(prev), b = e.getValue();
                if (a > 0) out.computeIfAbsent(e.getKey().getMonthValue(), k -> new ArrayList<>())
                    .add(new double[]{e.getKey().getYear(), b / a - 1.0});
            }
            prev = e.getKey();
        }
        return out;
    }

    /** Dérive appariée : moyenne de TOUS les holds non conditionnés de L jours de bourse (bp). */
    static double drift(TreeMap<LocalDate, Double> px, int L) {
        List<LocalDate> d = new ArrayList<>(px.keySet());
        double s = 0; int n = 0;
        for (int i = 0; i + L < d.size(); i++) {
            double a = px.get(d.get(i)), b = px.get(d.get(i + L));
            if (a > 0) { s += (b / a - 1.0) * 10_000.0; n++; }
        }
        return n == 0 ? 0 : s / n;
    }

    static int monthLen(TreeMap<LocalDate, Double> px) {
        Map<YearMonth, Integer> cnt = new TreeMap<>();
        for (LocalDate d : px.keySet()) cnt.merge(YearMonth.from(d), 1, Integer::sum);
        return (int) Math.round(cnt.values().stream().mapToInt(Integer::intValue).average().orElse(21));
    }

    // ================================================================== B) MATRICE

    static Map<String, Map<Integer, Cell>> CELLS = new LinkedHashMap<>();

    /** Dollar-neutre = cross reconstruit ou facteur. Une JAMBE (leg_X) est X contre USD ⇒ porte le dollar. */
    static boolean isNeutral(String key) {
        return !CLOSE.containsKey(key) && !key.startsWith("leg_") && !key.startsWith("DOLLAR");
    }

    static void matrix() {
        System.out.println("\n=== B) MATRICE MENSUELLE — ESPACE DOLLAR-NEUTRE (bp, fin de mois → fin de mois) ===");
        System.out.println("12 crosses + 8 paires (référence 48e) + 7 jambes + 1 facteur. Marqueur * = |t| ≥ 3 ET IS/OOS de même signe.");
        StringBuilder head = new StringBuilder(String.format("%-24s", "SÉRIE"));
        for (String m : MFR) head.append(String.format("%9s", m));
        System.out.println(head);
        for (var e : SERIES.entrySet()) {
            TreeMap<LocalDate, Double> px = e.getValue();
            if (px.size() < 500) continue;
            int L = monthLen(px);
            double ctrl = drift(px, L);
            Map<Integer, List<double[]>> ret = monthly(px);
            Map<Integer, Cell> row = new LinkedHashMap<>();
            StringBuilder sb = new StringBuilder(String.format("%-24s", e.getKey()));
            for (int m = 1; m <= 12; m++) {
                List<double[]> obs = ret.getOrDefault(m, List.of());
                if (obs.isEmpty()) { sb.append(String.format("%9s", "·")); continue; }
                Cell c = cell(e.getKey(), m, obs, ctrl);
                row.put(m, c);
                boolean strong = Math.abs(c.t()) >= 3.0 && Math.signum(c.is()) == Math.signum(c.oos());
                sb.append(String.format("%8.1f%s", c.meanBp(), strong ? "*" : " "));
            }
            CELLS.put(e.getKey(), row);
            System.out.println(sb);
        }
        // paires en référence (le 48e les a déjà mesurées : contraste dollar vs dollar-neutre)
        System.out.println("\n  -- RÉFÉRENCE PAIRES (non dollar-neutres) --");
        for (String sym : PAIRS) {
            TreeMap<LocalDate, Double> px = CLOSE.get(sym);
            if (px == null || px.size() < 500) continue;
            int L = monthLen(px);
            double ctrl = drift(px, L);
            Map<Integer, List<double[]>> ret = monthly(px);
            Map<Integer, Cell> row = new LinkedHashMap<>();
            StringBuilder sb = new StringBuilder(String.format("%-24s", sym));
            for (int m = 1; m <= 12; m++) {
                List<double[]> obs = ret.getOrDefault(m, List.of());
                if (obs.isEmpty()) { sb.append(String.format("%9s", "·")); continue; }
                Cell c = cell(sym, m, obs, ctrl);
                row.put(m, c);
                boolean strong = Math.abs(c.t()) >= 3.0 && Math.signum(c.is()) == Math.signum(c.oos());
                sb.append(String.format("%8.1f%s", c.meanBp(), strong ? "*" : " "));
            }
            CELLS.put(sym, row);
            System.out.println(sb);
        }
        System.out.println("\n  dérive appariée par série (hold non conditionné de même longueur, bp) — les crosses doivent être ~0 :");
        for (var e : SERIES.entrySet()) System.out.printf("    %-24s ctrl %+7.1f bp%n", e.getKey(), drift(e.getValue(), monthLen(e.getValue())));
    }

    // ================================================================== C) FACTEUR

    static void factor() {
        System.out.println("\n=== C) LE FACTEUR RISQUE→REFUGE (50e) — profil mensuel ===");
        System.out.println("Facteur = moyenne(jambes CHF,JPY) − moyenne(jambes EUR,GBP,AUD,NZD). Positif = le refuge gagne.");
        TreeMap<LocalDate, Double> px = SERIES.get("FACTEUR(refuge−risque)");
        int L = monthLen(px);
        double ctrl = drift(px, L);
        Map<Integer, List<double[]>> ret = monthly(px);
        System.out.printf("%-7s %-6s %-10s %-10s %-7s %-7s %-10s %-26s%n",
            "MOIS", "n", "mean bp", "médiane", "hit%", "t", "IS→OOS", "décennies 06-09/10-14/15-19/20-26");
        for (int m = 1; m <= 12; m++) {
            List<double[]> obs = ret.getOrDefault(m, List.of());
            if (obs.isEmpty()) continue;
            Cell c = cell("FACTEUR", m, obs, ctrl);
            System.out.printf("%-7s %-6d %+10.2f %+10.2f %6.0f%% %+7.2f %+5.1f→%+5.1f  %+7.2f / %+7.2f / %+7.2f / %+7.2f%n",
                MFR[m - 1], c.n(), c.meanBp(), c.medBp(), c.hit(), c.t(), c.is(), c.oos(),
                c.dec()[0], c.dec()[1], c.dec()[2], c.dec()[3]);
        }
        System.out.printf("dérive appariée (%d j) : %+.2f bp%n", L, ctrl);
    }

    // ================================================================== D) CELLULES

    static void cells() {
        System.out.println("\n=== D) LES 15 CELLULES LES PLUS FORTES (|t|) — tous les contrôles ===");
        List<Cell> all = new ArrayList<>();
        for (var row : CELLS.values()) all.addAll(row.values());
        all.sort(Comparator.comparingDouble(c -> -Math.abs(c.t())));
        System.out.printf("%-22s %-6s %-6s %-9s %-8s %-7s %-7s %-9s %-8s %-22s %-8s%n",
            "SÉRIE", "MOIS", "n", "mean bp", "médiane", "hit%", "t", "IS→OOS", "ctrl", "décennies", "verdict");
        for (int i = 0; i < Math.min(15, all.size()); i++) {
            Cell c = all.get(i);
            int decPos = 0;
            for (double d : c.dec()) if (d > 0) decPos++;
            boolean stable = Math.signum(c.is()) == Math.signum(c.oos());
            boolean aboveDrift = Math.abs(c.meanBp()) > Math.abs(c.ctrl());
            boolean medianSame = Math.signum(c.medBp()) == Math.signum(c.meanBp());
            String v;
            if (Math.abs(c.t()) < 3.0) v = "⚪ |t|<3 (test multiple)";
            else if (!stable) v = "❌ flip IS/OOS";
            else if (!aboveDrift) v = "⚪ sous la dérive";
            else if (!medianSame) v = "❌ queue (médiane ≠)";
            else if (isNeutral(c.inst)) v = "🔬 dollar-neutre";
            else v = "⚠️ porte le dollar (jambe/paire)";
            System.out.printf("%-22s %-6s %-6d %+9.1f %+8.1f %6.0f%% %+7.2f %+5.1f→%+5.1f %+8.1f %-22s %s%n",
                c.inst, MFR[c.month() - 1], c.n(), c.meanBp(), c.medBp(), c.hit(), c.t(), c.is(), c.oos(),
                c.ctrl, String.format("%+.1f/%+.1f/%+.1f/%+.1f (%d/4+)", c.dec()[0], c.dec()[1], c.dec()[2], c.dec()[3], decPos), v);
        }
    }

    // ================================================================== E) SPÉCIFICITÉ

    static void specificity() {
        System.out.println("\n=== E) SPÉCIFICITÉ ±1 MOIS sur les 5 meilleures cellules DOLLAR-NEUTRES ===");
        List<Cell> all = new ArrayList<>();
        for (var e : CELLS.entrySet()) {
            if (!isNeutral(e.getKey())) continue;
            for (var c : e.getValue().values()) all.add(c);
        }
        all.removeIf(c -> Math.abs(c.t()) < 2.0);
        all.sort(Comparator.comparingDouble(c -> -Math.abs(c.t())));
        if (all.isEmpty()) { System.out.println("  aucune cellule dollar-neutre avec |t| ≥ 2 : rien à tester."); return; }
        int shown = 0;
        for (Cell c : all) {
            if (shown++ >= 5) break;
            TreeMap<LocalDate, Double> px = SERIES.get(c.inst);
            if (px == null) continue;
            System.out.printf("\n  %s / %s  (référence : %+.1f bp, t %+.2f)%n", c.inst, MFR[c.month() - 1], c.meanBp(), c.t());
            Map<Integer, List<double[]>> ret = monthly(px);
            for (int off = -1; off <= 1; off++) {
                int m = ((c.month() - 1 + off) % 12 + 12) % 12 + 1;
                List<double[]> obs = ret.getOrDefault(m, List.of());
                List<Double> v = new ArrayList<>();
                for (double[] o : obs) v.add(o[1] * 10_000.0);
                double[] s = stat(v);
                System.out.printf("    %+d mois (%s) : %+8.2f bp  t %+6.2f  n %d%s%n",
                    off, MFR[m - 1], s[1], s[4], (int) s[0], off == 0 ? "   ← cellule testée" : "");
            }
        }
    }

    // ================================================================== F) VERDICT

    static void verdict() {
        System.out.println("\n=== F) VERDICT ===");
        int[] n = new int[2], fort = new int[2], stable = new int[2];
        for (var e : CELLS.entrySet()) {
            int k = isNeutral(e.getKey()) ? 0 : 1;
            for (var c : e.getValue().values()) {
                n[k]++;
                if (Math.abs(c.t()) >= 3.0) { fort[k]++; if (Math.signum(c.is()) == Math.signum(c.oos())) stable[k]++; }
            }
        }
        System.out.printf("DOLLAR-NEUTRE (12 crosses + facteur) : %d cellules · %d avec |t| ≥ 3 · %d stable IS/OOS.%n",
            n[0], fort[0], stable[0]);
        System.out.printf("PORTE LE DOLLAR (8 paires + 7 jambes) : %d cellules · %d avec |t| ≥ 3 · %d stable IS/OOS.%n",
            n[1], fort[1], stable[1]);
        System.out.println("Rappel du 48e : un adage ne survit que s'il bat la dérive APPARIÉE et la spécificité ±1 mois.");
    }

    // ================================================================== G) DÉCOMPOSITION D'UN MOIS

    /** Décompose un mois sur les 20 séries : le motif est-il un effet DOLLAR (toutes les paires ensemble) ? */
    static void monthDecomp() {
        System.out.println("\n=== G) DÉCOMPOSITION MOIS PAR MOIS — un motif de PAIRES est-il un motif de DEVISE ? ===");
        System.out.println("Si un mois bouge TOUTES les paires contre-USD dans le même sens, c'est le DOLLAR ;");
        System.out.println("dans l'espace dollar-neutre il ne doit rester que les couples réellement concernés.");
        for (int m = 1; m <= 12; m++) {
            System.out.printf("%n--- %s ---%n", MFR[m - 1]);
            int usdSame = 0, usdTot = 0; double usdSum = 0;
            for (String sym : PAIRS) {
                TreeMap<LocalDate, Double> px = CLOSE.get(sym);
                if (px == null) continue;
                Map<Integer, List<double[]>> ret = monthly(px);
                List<double[]> obs = ret.getOrDefault(m, List.of());
                if (obs.isEmpty()) continue;
                double[] s = stat(bpOf(obs));
                // « contre-USD quoté X/USD » : positive = USD faible. USD_X : positive = USD fort.
                double usdMove = sym.startsWith("USD_") ? -s[1] : s[1];
                usdSum += usdMove; usdTot++;
                if (usdMove > 0) usdSame++;
            }
            System.out.printf("  paires : %d/%d bougent dans le même sens USD (mouvement moyen %+.0f bp) ⇒ %s%n",
                usdSame, usdTot, usdTot == 0 ? 0 : usdSum / usdTot,
                (usdSame >= 6) ? "MOTIF DOLLAR PROBABLE" : "pas de motif dollar unanime");
            List<Cell> neut = new ArrayList<>();
            for (var e : CELLS.entrySet()) {
                if (!isNeutral(e.getKey())) continue;
                Cell c = e.getValue().get(m);
                if (c != null) neut.add(c);
            }
            neut.sort(Comparator.comparingDouble(c -> -Math.abs(c.t())));
            System.out.printf("  dollar-neutre — 3 plus fortes : ");
            for (int i = 0; i < Math.min(3, neut.size()); i++)
                System.out.printf("%s %+.0f bp (t %+.2f)   ", neut.get(i).inst(), neut.get(i).meanBp(), neut.get(i).t());
            System.out.println();
        }
    }

    static List<Double> bpOf(List<double[]> obs) {
        List<Double> v = new ArrayList<>();
        for (double[] o : obs) v.add(o[1] * 10_000.0);
        return v;
    }

    // ================================================================== H) DÉCOMPOSITION DU MOIS LE PLUS FORT

    /** Pour le mois le plus fort de l'espace PAIRES, sépare la part DOLLAR de la part DEVISE. */
    static void decompTopMonth() {
        System.out.println("\n=== H) LE MEILLEUR MOIS DE L'ESPACE PAIRES EST-IL UNE SAISON DE DEVISE OU DU DOLLAR ? ===");
        Cell top = null;
        for (var e : CELLS.entrySet()) {
            if (isNeutral(e.getKey()) || e.getKey().startsWith("leg_") || e.getKey().startsWith("DOLLAR")) continue;
            for (var c : e.getValue().values())
                if (top == null || Math.abs(c.t()) > Math.abs(top.t())) top = c;
        }
        if (top == null) { System.out.println("  aucune cellule de paire."); return; }
        int m = top.month();
        System.out.printf("Cellule la plus forte de tout le scan : %s / %s — %+.0f bp, t %+.2f, hit %.0f%%, n %d%n",
            top.inst(), MFR[m - 1], top.meanBp(), top.t(), top.hit(), top.n());
        TreeMap<LocalDate, Double> dpx = SERIES.get("DOLLAR(force_USD)");
        List<double[]> dObs = monthly(dpx).getOrDefault(m, List.of());
        double dollarStrength = avg(bpOf(dObs));      // > 0 = le dollar MONTE
        System.out.printf("Force du dollar sur %s : %+.0f bp (moyenne des 7 jambes, signe inversé) ⇒ part dollar d'une paire X/USD = %+.0f bp%n",
            MFR[m - 1], dollarStrength, -dollarStrength);
        System.out.printf("%-10s %10s %12s %14s %10s%n", "PAIRE", "r (bp)", "part DOLLAR", "part DEVISE", "t du mois");
        for (String sym : PAIRS) {
            Map<Integer, List<double[]>> ret = monthly(CLOSE.get(sym));
            List<double[]> obs = ret.getOrDefault(m, List.of());
            if (obs.isEmpty()) continue;
            double[] s = stat(bpOf(obs));
            boolean usdQuote = sym.startsWith("USD_");
            double partDollar = usdQuote ? dollarStrength : -dollarStrength;
            System.out.printf("%-10s %+10.0f %+12.0f %+14.0f %+10.2f%n",
                sym, s[1], partDollar, s[1] - partDollar, s[4]);
        }
        System.out.println("Lecture : « part DOLLAR » est le même chiffre pour toutes les paires du mois ; si elle");
        System.out.println("épuise le r de la paire, le motif est une SAISON DU DOLLAR, pas une saison de la devise.");
    }

    // ================================================================== divers

    static void print(String s) { System.out.print(s); }
}
