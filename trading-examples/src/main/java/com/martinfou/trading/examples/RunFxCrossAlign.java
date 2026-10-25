package com.martinfou.trading.examples;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;

import java.time.*;
import java.util.*;

/**
 * RunFxCrossAlign — JEUDI 8 oct 2026 (52e résultat, rotation = INTERMARKET / CROSS-ASSET).
 *
 * CONTEXTE — le 50e (mardi 6 oct) a désigné les CROSSES REFUGES comme les meilleurs porteurs du fade du
 * vendredi : GBP_CHF +9.08 bp (t 5.13) > AUD_CHF +8.27 > GBP_JPY +7.03 > GBP_USD +5.34 (incumbent). Ces
 * crosses étaient des RECONSTRUCTIONS (GBP_CHF = GBP_USD × USD_CHF) et le 50e a lui-même mesuré un
 * « PLANCHER DE BRUIT » de 13.9 bp sur le triangle r(GBP_JPY) − r(GBP_USD) − r(USD_JPY), soit DEUX FOIS
 * l'edge revendiqué — sans en tirer de conséquence sur la validité de son propre classement.
 *
 * QUESTION — ce plancher est-il un fait de MARCHÉ (fourchette / bande d'arbitrage) ou un ARTEFACT DE
 * CONSTRUCTION ? Le pipeline prend comme clôture la « DERNIÈRE BARRE RÉELLE du jour UTC » de chaque paire :
 * sur une barre mince, cette barre peut tomber à 20:00 pour une jambe et à 21:00 pour l'autre. Les trois
 * jambes ne sont donc PAS lues au même instant, et l'écart de prix entre deux instants est comptabilisé
 * comme du « bruit ».
 *
 * MÈTRE ÉTAL — le SEUL cross réellement coté du panel : GBP_JPY. Trois mesures du même triangle :
 *   A (convention 49e/50e) : dernière barre réelle du jour de CHAQUE paire.
 *   B (ALIGNÉE) : la MÊME barre H1 pour toutes les jambes (dernière heure commune du jour).
 *   + résidu de NIVEAU (log) dans les deux constructions, qui isole la part « niveau » de la part « retour ».
 * Puis le classement des 12 crosses du 50e est recalculé sous B, avec la jambe réellement tradée
 * (session + week-end, sortie au lendemain) et le net après carry réel par année.
 *
 * Usage : java -cp "$CP" com.martinfou.trading.examples.RunFxCrossAlign [--all|--p0|--floor|--rank|--leg]
 */
public class RunFxCrossAlign {

    static final String YEAR_SPEC = "2006-2026";
    static final String[] PANEL8 = {"GBP_JPY", "GBP_USD", "AUD_USD", "NZD_USD", "USD_CHF", "EUR_USD", "USD_JPY", "USD_CAD"};
    /** nom → {jambe gauche, jambe droite} : cross = gauche / droite (droite = USD_XXX ⇒ XXX par USD). */
    static final String[][] CROSSES = {
        {"GBP_CHF", "GBP_USD", "USD_CHF"}, {"EUR_CHF", "EUR_USD", "USD_CHF"}, {"AUD_CHF", "AUD_USD", "USD_CHF"},
        {"GBP_JPY", "GBP_USD", "USD_JPY"}, {"AUD_JPY", "AUD_USD", "USD_JPY"}, {"EUR_JPY", "EUR_USD", "USD_JPY"},
        {"GBP_CAD", "GBP_USD", "USD_CAD"}, {"AUD_CAD", "AUD_USD", "USD_CAD"}, {"EUR_CAD", "EUR_USD", "USD_CAD"},
        {"GBP_EUR", "GBP_USD", "EUR_USD"}, {"AUD_NZD", "AUD_USD", "NZD_USD"}, {"NZD_CAD", "NZD_USD", "USD_CAD"},
    };
    static final String[] TRI = {"GBP_JPY", "GBP_USD", "USD_JPY"};
    /** Préfixe des séries de crosses (évite la collision avec le nom de la jambe GBP_JPY). */
    static final String X = "X_";

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
    static final double BP_PER_PCT_DAY = 0.274;
    static final double COST_RT_BP = 2.14;

    /** Clôtures réelles par barre H1 : clé = heures depuis l'époque = floor(epochSecond/3600). */
    static final Map<String, TreeMap<Integer, Double>> HC = new LinkedHashMap<>();
    /** Construction A — dernière barre RÉELLE du jour UTC (convention du pipeline). */
    static final Map<String, TreeMap<LocalDate, Double>> CLOSE_A = new LinkedHashMap<>();
    static final Map<String, TreeMap<LocalDate, Double>> RET_A = new LinkedHashMap<>();
    /** Construction B — même barre H1 pour toutes les jambes concernées. */
    static final Map<String, TreeMap<LocalDate, Double>> CLOSE_B = new LinkedHashMap<>();
    static final Map<String, TreeMap<LocalDate, Double>> RET_B = new LinkedHashMap<>();
    /** Heure médiane (UTC) de l'alignement, par série B. */
    static final Map<String, Integer> HOUR_B = new LinkedHashMap<>();
    /** Carte date → heure commune, par cross (pour le contrôle « même heure pour tous »). */
    static final Map<String, Map<LocalDate, Integer>> HMAP = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "--all";
        System.out.println("========================================================================");
        System.out.println("LE PLANCHER DE BRUIT DU 50e EST-IL UN ARTEFACT D'ALIGNEMENT ?");
        System.out.println("Jeudi 8 oct 2026 (52e) · INTERMARKET / CROSS-ASSET · " + YEAR_SPEC);
        System.out.println("Mètre étalon : le seul cross RÉELLEMENT coté du panel (GBP_JPY) — zéro téléchargement.");
        System.out.println("========================================================================");
        loadAll();
        switch (mode) {
            case "--p0" -> p0();
            case "--floor" -> floor();
            case "--rank" -> rank();
            case "--leg" -> leg();
            case "--hours" -> hours();
            case "--all" -> { p0(); floor(); rank(); hours(); leg(); verdict(); }
            default -> p0();
        }
        System.out.println("\nDONE");
    }

    // ---------------------------------------------------------------- chargement

    static void loadAll() throws Exception {
        for (String sym : PANEL8) {
            List<Bar> bars;
            try {
                bars = HistoricalDataLoader.loadFromArgs(sym, sym, YEAR_SPEC).bars();
            } catch (Exception e) {
                System.out.println("[data] ECHEC " + sym + " : " + e.getMessage());
                continue;
            }
            if (bars == null || bars.isEmpty()) { System.out.println("[data] PAS DE DONNÉES " + sym); continue; }
            bars.sort(Comparator.comparing(Bar::timestamp));
            TreeMap<Integer, Double> hc = new TreeMap<>();
            TreeMap<LocalDate, Double> a = new TreeMap<>();
            for (Bar b : bars) {
                if (b.high() <= b.low()) continue;                              // barre de carry (40e/48e)
                LocalDate d = b.timestamp().atZone(ZoneId.of("UTC")).toLocalDate();
                hc.put((int) (b.timestamp().getEpochSecond() / 3600), b.close());
                a.put(d, b.close());                                            // A : la DERNIÈRE gagne
            }
            HC.put(sym, hc);
            CLOSE_A.put(sym, a);
            RET_A.put(sym, returns(a));
            System.out.printf("[data] %-9s %6d barres réelles · %5d jours · %s → %s%n",
                sym, hc.size(), a.size(), a.firstKey(), a.lastKey());
        }
        // --- B : triangle (les 3 jambes réelles sur la même barre H1)
        Map<LocalDate, Integer> hTri = commonHours(TRI);
        for (String s : TRI) {
            CLOSE_B.put(s, seriesAt(s, hTri));
            RET_B.put(s, returns(CLOSE_B.get(s)));
            HOUR_B.put(s, medianHour(hTri));
        }
        System.out.printf("[aligné] triangle %s : %d jours, heure médiane %d:00 UTC%n",
            Arrays.toString(TRI), hTri.size(), medianHour(hTri));
        // --- B : chaque cross, ses 2 jambes lues sur la même barre H1
        for (String[] c : CROSSES) {
            if (!HC.containsKey(c[1]) || !HC.containsKey(c[2])) continue;
            Map<LocalDate, Integer> h = commonHours(new String[]{c[1], c[2]});
            TreeMap<LocalDate, Double> left = seriesAt(c[1], h), right = seriesAt(c[2], h);
            TreeMap<LocalDate, Double> cross = new TreeMap<>();
            for (var e : left.entrySet()) {
                Double r = right.get(e.getKey());
                if (r == null || r <= 0 || e.getValue() <= 0) continue;
                cross.put(e.getKey(), e.getValue() / r);
            }
            CLOSE_B.put(X + c[0], cross);
            RET_B.put(X + c[0], returns(cross));
            HOUR_B.put(X + c[0], medianHour(h));
            HMAP.put(X + c[0], h);
        }
        System.out.printf("[aligné] %d crosses construits sur barres synchronisées (heure médiane %d:00 UTC)%n",
            CROSSES.length, HOUR_B.get(X + "GBP_CHF"));
        System.out.println("[aligné] part des jours à l'heure modale (23:00 UTC) — le contrôle « même heure » :");
        for (String[] c : CROSSES) {
            Map<LocalDate, Integer> h = HMAP.get(X + c[0]);
            if (h == null) continue;
            int n23 = 0;
            for (int v : h.values()) if (v == 23) n23++;
            System.out.printf("   %-9s %5d jours · %4.0f%% à 23:00 · médiane %02d:00%n",
                c[0], h.size(), 100.0 * n23 / h.size(), medianHour(h));
        }
    }

    /** Dernière heure H1 présente dans TOUTES les jambes, jour par jour. */
    static Map<LocalDate, Integer> commonHours(String[] legs) {
        Map<LocalDate, Integer> out = new HashMap<>();
        TreeMap<Integer, Double> first = HC.get(legs[0]);
        if (first == null) return out;
        for (int k : first.keySet()) {
            boolean all = true;
            for (String l : legs) { TreeMap<Integer, Double> m = HC.get(l); if (m == null || !m.containsKey(k)) { all = false; break; } }
            if (!all) continue;
            LocalDate d = LocalDate.ofEpochDay(k / 24);
            int h = k % 24;
            Integer prev = out.get(d);
            if (prev == null || h > prev) out.put(d, h);
        }
        return out;
    }

    static int medianHour(Map<LocalDate, Integer> h) {
        List<Integer> v = new ArrayList<>(h.values());
        Collections.sort(v);
        return v.isEmpty() ? -1 : v.get(v.size() / 2);
    }

    /** Série d'une jambe lue à l'heure imposée de chaque jour. */
    static TreeMap<LocalDate, Double> seriesAt(String sym, Map<LocalDate, Integer> hours) {
        TreeMap<Integer, Double> hc = HC.get(sym);
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        if (hc == null) return out;
        for (var e : hours.entrySet()) {
            Double v = hc.get((int) (e.getKey().toEpochDay() * 24 + e.getValue()));
            if (v != null) out.put(e.getKey(), v);
        }
        return out;
    }

    static TreeMap<LocalDate, Double> returns(TreeMap<LocalDate, Double> src) {
        TreeMap<LocalDate, Double> r = new TreeMap<>();
        if (src == null) return r;
        LocalDate prev = null;
        for (var e : src.entrySet()) {
            if (prev != null) {
                double pc = src.get(prev);
                if (pc > 0) r.put(e.getKey(), (e.getValue() / pc - 1.0) * 10_000.0);
            }
            prev = e.getKey();
        }
        return r;
    }

    /** Cross synthétique par MULTIPLICATION des retours journaliers (méthode exacte du 49e/50e). */
    static TreeMap<LocalDate, Double> synthA(String left, String right) {
        TreeMap<LocalDate, Double> ra = RET_A.get(left), rb = RET_A.get(right);
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        if (ra == null || rb == null) return out;
        boolean mul = right.startsWith("USD_");
        for (var e : ra.entrySet()) {
            Double y = rb.get(e.getKey());
            if (y == null) continue;
            double x1 = 1 + e.getValue() / 10_000.0, y1 = 1 + y / 10_000.0;
            out.put(e.getKey(), ((mul ? x1 * y1 : x1 / y1) - 1.0) * 10_000.0);
        }
        return out;
    }

    // ---------------------------------------------------------------- stats

    record Stat(int n, double mean, double sd, double t, double median, double hitNeg) {}

    static Stat stat(List<Double> v) {
        List<Double> w = new ArrayList<>();
        for (double d : v) if (!Double.isNaN(d)) w.add(d);
        if (w.isEmpty()) return new Stat(0, 0, 0, 0, 0, 0);
        int n = w.size();
        double m = w.stream().mapToDouble(d -> d).average().orElse(0);
        double s2 = w.stream().mapToDouble(d -> (d - m) * (d - m)).sum() / Math.max(1, n - 1);
        double sd = Math.sqrt(s2);
        List<Double> c = new ArrayList<>(w);
        Collections.sort(c);
        double med = n % 2 == 1 ? c.get(n / 2) : 0.5 * (c.get(n / 2 - 1) + c.get(n / 2));
        long neg = w.stream().filter(d -> d < 0).count();
        return new Stat(n, m, sd, sd == 0 ? 0 : m / (sd / Math.sqrt(n)), med, 100.0 * neg / n);
    }

    static List<Double> pick(TreeMap<LocalDate, Double> s, DayOfWeek dow, int y0, int y1) {
        List<Double> out = new ArrayList<>();
        if (s == null) return out;
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
        if (s == null) return out;
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
        section("A) P0 CALIBRATION — profil VENDREDI du panel (retour de session, construction A)");
        System.out.println("Réf 50e : GBP_JPY −5.61 (t −2.39) · GBP_USD −5.34 (t −2.86) · EUR_USD −3.07 · USD_CAD +0.55");
        System.out.printf("%-9s %10s %7s %10s %8s %7s%n", "PAIRE", "VEN bp", "n", "médiane", "hit<0%", "t");
        for (String sym : PANEL8) {
            Stat s = stat(pick(RET_A.get(sym), DayOfWeek.FRIDAY, 2006, 2026));
            if (s.n() == 0) continue;
            System.out.printf("%-9s %+10.4f %7d %+10.4f %8.0f%% %+7.2f%n", sym, s.mean(), s.n(), s.median(), s.hitNeg(), s.t());
        }
        System.out.printf("%n%-9s %11s %7s %8s %7s%n", "CROSS", "fade A bp", "n", "hit fd%", "t");
        for (String[] c : CROSSES) {
            Stat s = stat(pick(synthA(c[1], c[2]), DayOfWeek.FRIDAY, 2006, 2026));
            if (s.n() == 0) continue;
            System.out.printf("%-9s %+11.4f %7d %8.0f%% %+7.2f%n", c[0], -s.mean(), s.n(), s.hitNeg(), -s.t());
        }
        System.out.println("Réf 50e (crosses refuges) : GBP_CHF +9.08 (t 5.13) · AUD_CHF +8.27 · EUR_CHF +6.93 · GBP_JPY +7.03");
    }

    // ---------------------------------------------------------------- B. plancher

    static void floor() {
        section("B) LE PLANCHER DE BRUIT — l'identité triangle sur le cross RÉELLEMENT COTÉ (GBP_JPY)");
        System.out.println("Résidu de RETOUR = r(GBP_JPY) − [ r(GBP_USD) + r(USD_JPY) ] en bp.");
        System.out.println("Si la cotation est cohérente, il ne reste que la FOURCHETTE d'arbitrage (~1-3 bp).");
        System.out.printf("%-16s %12s %13s %11s %13s %12s%n",
            "CONSTRUCTION", "résidu moy", "résidu é-t", "VEN moy", "VEN é-t", "résidu max");
        for (String frame : new String[]{"A", "B"}) {
            TreeMap<LocalDate, Double> rGj = frame.equals("A") ? RET_A.get("GBP_JPY") : RET_B.get("GBP_JPY");
            TreeMap<LocalDate, Double> rGu = frame.equals("A") ? RET_A.get("GBP_USD") : RET_B.get("GBP_USD");
            TreeMap<LocalDate, Double> rUj = frame.equals("A") ? RET_A.get("USD_JPY") : RET_B.get("USD_JPY");
            TreeMap<LocalDate, Double> res = new TreeMap<>();
            double mx = 0;
            for (var e : rGj.entrySet()) {
                Double a = rGu.get(e.getKey()), b = rUj.get(e.getKey());
                if (a == null || b == null) continue;
                double v = e.getValue() - (a + b);
                res.put(e.getKey(), v);
                mx = Math.max(mx, Math.abs(v));
            }
            Stat s = stat(new ArrayList<>(res.values()));
            Stat fri = stat(pick(res, DayOfWeek.FRIDAY, 2006, 2026));
            System.out.printf("%-16s %+12.4f %13.4f %+11.4f %13.4f %12.2f%n",
                frame.equals("A") ? "A dernières barres" : "B barre alignée", s.mean(), s.sd(), fri.mean(), fri.sd(), mx);
        }
        System.out.println("\nB2) RÉSIDU DE NIVEAU (log) et de RETOUR (différences), par décennie :");
        System.out.printf("%-12s %17s %17s %17s %17s%n", "DÉCENNIE",
            "A: é-t niveau bp", "B: é-t niveau bp", "A: é-t retour bp", "B: é-t retour bp");
        TreeMap<LocalDate, Double> resA = levelResidual("A"), resB = levelResidual("B");
        TreeMap<LocalDate, Double> dA = diff(resA), dB = diff(resB);
        for (int[] span : new int[][]{{2006, 2009}, {2010, 2014}, {2015, 2019}, {2020, 2026}}) {
            System.out.printf("%-12s %17.4f %17.4f %17.4f %17.4f%n", span[0] + "-" + span[1],
                sdOf(resA, span), sdOf(resB, span), sdOf(dA, span), sdOf(dB, span));
        }
        System.out.printf("%nMÉCANISME — le résidu de construction A est-il concentré sur les dates « sales » ?%n");
        TreeMap<LocalDate, Double> rGj = RET_A.get("GBP_JPY"), rGu = RET_A.get("GBP_USD"), rUj = RET_A.get("USD_JPY");
        List<Double> cleanR = new ArrayList<>(), dirtyR = new ArrayList<>();
        for (var e : rGj.entrySet()) {
            LocalDate d = e.getKey();
            Double a = rGu.get(d), b = rUj.get(d);
            if (a == null || b == null) continue;
            LocalDate pg = rGj.lowerKey(d), pu = rGu.lowerKey(d), pj = rUj.lowerKey(d);
            double res = e.getValue() - (a + b);
            if (pg != null && pg.equals(pu) && pg.equals(pj)) cleanR.add(res); else dirtyR.add(res);
        }
        Stat sc = stat(cleanR), sdir = stat(dirtyR);
        int tot = cleanR.size() + dirtyR.size();
        System.out.printf("   dates où les 3 jambes partagent la MÊME date précédente : %d (%.1f %%) · sinon : %d (%.1f %%)%n",
            cleanR.size(), 100.0 * cleanR.size() / tot, dirtyR.size(), 100.0 * dirtyR.size() / tot);
        System.out.printf("   résidu A sur les dates PROPRES : é-t %8.4f bp (n %d, moyenne %+.4f)%n", sc.sd(), sc.n(), sc.mean());
        System.out.printf("   résidu A sur les dates SALES   : é-t %8.4f bp (n %d, moyenne %+.4f)%n", sdir.sd(), sdir.n(), sdir.mean());
        System.out.printf("   résidu B (toutes dates, grille partagée) : é-t %8.4f bp%n", stat(new ArrayList<>(diff(levelResidual("B")).values())).sd());
        System.out.println("Lecture : si le résidu A explose UNIQUEMENT sur les dates sales, le « plancher » de 13.9 bp est");
        System.out.println("un ARTEFACT DE CALENDRIER (une jambe saute un jour, son retour porte 2 jours) et non un fait de");
        System.out.println("marché. Une reconstruction doit donc PARTAGER la grille de dates de ses jambes.");
    }

    /** Différences simples d'une série additive (bp) — pas de division (le résidu croise zéro). */
    static TreeMap<LocalDate, Double> diff(TreeMap<LocalDate, Double> src) {
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        Double prev = null;
        for (var e : src.entrySet()) {
            if (prev != null) out.put(e.getKey(), e.getValue() - prev);
            prev = e.getValue();
        }
        return out;
    }

    static double shareOver(TreeMap<LocalDate, Double> s, double lim) {
        if (s == null || s.isEmpty()) return 0;
        long n = s.values().stream().filter(v -> Math.abs(v) > lim).count();
        return 100.0 * n / s.size();
    }

    static long countOver(TreeMap<LocalDate, Double> s, double lim) {
        if (s == null) return 0;
        return s.values().stream().filter(v -> Math.abs(v) > lim).count();
    }

    /** Résidu de niveau : ln(GBP_JPY) − ln(GBP_USD) − ln(USD_JPY), jour par jour. */
    static TreeMap<LocalDate, Double> levelResidual(String frame) {
        TreeMap<LocalDate, Double> gj = frame.equals("A") ? CLOSE_A.get("GBP_JPY") : CLOSE_B.get("GBP_JPY");
        TreeMap<LocalDate, Double> gu = frame.equals("A") ? CLOSE_A.get("GBP_USD") : CLOSE_B.get("GBP_USD");
        TreeMap<LocalDate, Double> uj = frame.equals("A") ? CLOSE_A.get("USD_JPY") : CLOSE_B.get("USD_JPY");
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        if (gj == null || gu == null || uj == null) return out;
        for (var e : gj.entrySet()) {
            Double a = gu.get(e.getKey()), b = uj.get(e.getKey());
            if (a == null || b == null || a <= 0 || b <= 0 || e.getValue() <= 0) continue;
            out.put(e.getKey(), (Math.log(e.getValue()) - Math.log(a) - Math.log(b)) * 10_000.0);
        }
        return out;
    }

    static double sdOf(TreeMap<LocalDate, Double> s, int[] span) {
        List<Double> v = new ArrayList<>();
        if (s == null) return 0;
        for (var e : s.entrySet()) {
            if (e.getKey().getYear() < span[0] || e.getKey().getYear() > span[1]) continue;
            v.add(e.getValue());
        }
        return stat(v).sd();
    }

    // ---------------------------------------------------------------- C. classement

    static void rank() {
        section("C) LE CLASSEMENT DU 50e SOUS LA CONSTRUCTION ALIGNÉE — le podium tient-il ?");
        System.out.printf("%-9s %11s %11s %8s %8s %16s %28s%n",
            "CROSS", "fade A bp", "fade B bp", "t (B)", "hit fd%", "IS→OOS (B)", "décennies B 06-09/10-14/15-19/20-26");
        List<Object[]> rows = new ArrayList<>();
        for (String[] c : CROSSES) {
            TreeMap<LocalDate, Double> synA = synthA(c[1], c[2]), synB = RET_B.get(X + c[0]);
            Stat sA = stat(pick(synA, DayOfWeek.FRIDAY, 2006, 2026));
            Stat sB = stat(pick(synB, DayOfWeek.FRIDAY, 2006, 2026));
            if (sA.n() == 0 || sB.n() == 0) { System.out.println("  (pas de données pour " + c[0] + ")"); continue; }
            rows.add(new Object[]{c[0], -sA.mean(), -sB.mean(), -sB.t(), sB.hitNeg(),
                -stat(pick(synB, DayOfWeek.FRIDAY, 2006, 2015)).mean(), -stat(pick(synB, DayOfWeek.FRIDAY, 2016, 2026)).mean(),
                new double[]{-stat(pick(synB, DayOfWeek.FRIDAY, 2006, 2009)).mean(), -stat(pick(synB, DayOfWeek.FRIDAY, 2010, 2014)).mean(),
                    -stat(pick(synB, DayOfWeek.FRIDAY, 2015, 2019)).mean(), -stat(pick(synB, DayOfWeek.FRIDAY, 2020, 2026)).mean()}});
        }
        rows.sort((x, y) -> Double.compare((double) y[2], (double) x[2]));
        for (Object[] r : rows) {
            double[] dec = (double[]) r[7];
            System.out.printf("%-9s %+11.4f %+11.4f %+8.2f %7.0f%% %+7.2f→%+7.2f  %+7.3f %+7.3f %+7.3f %+7.3f%n",
                r[0], r[1], r[2], r[3], r[4], r[5], r[6], dec[0], dec[1], dec[2], dec[3]);
        }
        System.out.println("\nC2) SPÉCIFICITÉ DU JOUR sous B (fade bp) — placebo interne = les crosses risque/risque :");
        System.out.printf("%-9s %9s %9s %9s %9s %9s %9s %9s%n", "CROSS", "lundi", "mardi", "mercredi", "jeudi", "VENDREDI", "Mon-Jeu", "ven-mer");
        for (String[] c : CROSSES) {
            TreeMap<LocalDate, Double> s = RET_B.get(X + c[0]);
            if (s == null || s.isEmpty()) continue;
            double fri = -stat(pick(s, DayOfWeek.FRIDAY, 2006, 2026)).mean();
            double wed = -stat(pick(s, DayOfWeek.WEDNESDAY, 2006, 2026)).mean();
            System.out.printf("%-9s %+9.4f %+9.4f %+9.4f %+9.4f %+9.4f %+9.4f %+9.4f%n", c[0],
                -stat(pick(s, DayOfWeek.MONDAY, 2006, 2026)).mean(), -stat(pick(s, DayOfWeek.TUESDAY, 2006, 2026)).mean(),
                wed, -stat(pick(s, DayOfWeek.THURSDAY, 2006, 2026)).mean(), fri,
                -stat(pickMonThu(s, 2006, 2026)).mean(), fri - wed);
        }
        System.out.println("\nC3) CONTRÔLE APPARIÉ — vendredis où les 12 crosses utilisent LA MÊME heure de clôture :");
        System.out.println("    (si le podium ne survit pas à cette restriction, c'est l'HEURE de mesure qui parle)");
        Map<LocalDate, Integer> ref = HMAP.get(X + "GBP_CHF");
        List<LocalDate> clean = new ArrayList<>();
        if (ref != null) {
            for (var e : ref.entrySet()) {
                if (e.getKey().getDayOfWeek() != DayOfWeek.FRIDAY) continue;
                boolean ok = true;
                for (String[] c : CROSSES) {
                    Map<LocalDate, Integer> m = HMAP.get(X + c[0]);
                    Integer hh = m == null ? null : m.get(e.getKey());
                    if (hh == null || !hh.equals(e.getValue())) { ok = false; break; }
                }
                if (ok) clean.add(e.getKey());
            }
        }
        System.out.printf("    vendredis communs à heure identique : %d%n", clean.size());
        System.out.printf("%-9s %13s %9s %7s %8s %13s%n", "CROSS", "fade propre bp", "t", "n", "hit fd%", "vs fade B (bp)");
        List<Object[]> r3 = new ArrayList<>();
        for (String[] c : CROSSES) {
            TreeMap<LocalDate, Double> s = RET_B.get(X + c[0]);
            if (s == null) continue;
            List<Double> v = new ArrayList<>();
            for (LocalDate d : clean) { Double x = s.get(d); if (x != null) v.add(-x); }
            Stat st = stat(v);
            Stat stB = stat(pick(s, DayOfWeek.FRIDAY, 2006, 2026));
            if (st.n() == 0) continue;
            r3.add(new Object[]{c[0], st.mean(), st.t(), st.n(), st.hitNeg(), st.mean() + stB.mean()});
        }
        r3.sort((x, y) -> Double.compare((double) y[1], (double) x[1]));
        for (Object[] o : r3) System.out.printf("%-9s %+13.4f %+9.2f %7d %7.0f%% %+13.4f%n", o[0], o[1], o[2], o[3], o[4], o[5]);

        System.out.println("\nC4) CONTRÔLE FINAL — même HEURE FIXE pour les deux jambes, quatre heures de lecture (fade bp) :");
        System.out.printf("%-9s %10s %10s %10s %10s%n", "CROSS", "18:00", "19:00", "20:00", "21:00");
        List<Object[]> c4 = new ArrayList<>();
        for (String[] c : CROSSES) {
            double[] v = new double[4];
            int h = 0;
            for (int hh : new int[]{18, 19, 20, 21}) {
                Stat st = stat(pick(returns(fixedHourSeries(c[1], c[2], hh)), DayOfWeek.FRIDAY, 2006, 2026));
                v[h++] = -st.mean();
            }
            c4.add(new Object[]{c[0], v[0], v[1], v[2], v[3]});
        }
        c4.sort((x, y) -> Double.compare((double) y[3], (double) x[3]));
        for (Object[] o : c4) System.out.printf("%-9s %+10.4f %+10.4f %+10.4f %+10.4f%n", o[0], o[1], o[2], o[3], o[4]);
        System.out.println("Lecture : le classement ne doit PAS dépendre de l'heure choisie. S'il bascule avec l'heure, c'est");
        System.out.println("que le « podium » mesure la banque d'heures de chaque paire, pas une prime de risque.");
    }

    /** Série brute d'une paire lue à une heure FIXE. */
    static TreeMap<LocalDate, Double> rawAtHour(String sym, int hour) {
        TreeMap<Integer, Double> hc = HC.get(sym);
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        if (hc == null) return out;
        for (var e : hc.entrySet()) {
            if (e.getKey() % 24 != hour) continue;
            out.put(LocalDate.ofEpochDay(e.getKey() / 24), e.getValue());
        }
        return out;
    }

    /** Série d'un cross lue à une heure FIXE (les DEUX jambes doivent avoir une barre réelle à cette heure). */
    static TreeMap<LocalDate, Double> fixedHourSeries(String left, String right, int hour) {
        TreeMap<Integer, Double> la = HC.get(left), lb = HC.get(right);
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        if (la == null || lb == null) return out;
        for (var e : la.entrySet()) {
            int k = e.getKey();
            if (k % 24 != hour) continue;
            Double b = lb.get(k);
            if (b == null || b <= 0 || e.getValue() <= 0) continue;
            out.put(LocalDate.ofEpochDay(k / 24), e.getValue() / b);
        }
        return out;
    }

    // ---------------------------------------------------------------- D. jambe tradée

    record Leg(LocalDate day, double sessionBp, double weekendBp, double totalBp, double hours) {}

    static void leg() {
        section("D) LA JAMBE RÉELLEMENT TRADÉE (barres synchronisées) — session + week-end + net");
        System.out.println("Entrée = 1re barre du vendredi + 1 (fill T+1 du moteur), sortie = 1re barre du jour suivant + 1.");
        System.out.println("NET = total + carry réel par année (3 rollovers, table 3M du 47e) − coûts 2.14 bp.");
        System.out.printf("%-9s %7s %8s %11s %11s %9s %9s %11s %10s %9s%n",
            "CROSS", "n", "heures", "session bp", "week-end bp", "reste bp", "t (net)", "NET bp", "PF proxy", "hit net%");
        for (String[] c : CROSSES) {
            List<Leg> tr = legs(c[1], c[2]);
            if (tr.size() < 50) continue;
            List<Double> se = new ArrayList<>(), we = new ArrayList<>(), tot = new ArrayList<>(), net = new ArrayList<>();
            double hrs = 0;
            for (Leg t : tr) {
                se.add(t.sessionBp()); we.add(t.weekendBp()); tot.add(t.totalBp());
                double diff = diffYear(c[1], c[2], t.day().getYear());
                net.add(t.totalBp() + 3.0 * (-BP_PER_PCT_DAY * diff) - COST_RT_BP);
                hrs += t.hours();
            }
            double gp = 0, gl = 0;
            for (double v : net) { if (v > 0) gp += v; else gl -= v; }
            Stat sn = stat(net);
            long pos = net.stream().filter(d -> d > 0).count();
            System.out.printf("%-9s %7d %8.1f %+11.4f %+11.4f %+9.4f %+9.2f %+11.4f %10.3f %8.0f%%%n",
                c[0], tr.size(), hrs / tr.size(), stat(se).mean(), stat(we).mean(),
                stat(tot).mean() - stat(se).mean() - stat(we).mean(), sn.t(), sn.mean(),
                gl > 0 ? gp / gl : 99.999, 100.0 * pos / net.size());
        }
        System.out.println("\n⚠️ PF proxy en bp (coûts + carry explicites), PAS un backtest moteur : il valide la");
        System.out.println("   DIRECTION et l'ordre de grandeur, pas encore la promotion (gate 30 trades / 3-4 paires).");
    }

    /**
     * Jambes vendredi d'un cross, sur les barres H1 COMMUNES des deux jambes :
     * entrée = 1re barre du vendredi + 1 (fill T+1), sortie = 1re barre du jour suivant + 1.
     */
    static List<Leg> legs(String left, String right) {
        List<Leg> out = new ArrayList<>();
        TreeMap<Integer, Double> la = HC.get(left), lb = HC.get(right);
        if (la == null || lb == null) return out;
        List<Integer> ks = new ArrayList<>();
        for (int k : la.keySet()) if (lb.containsKey(k)) ks.add(k);
        Collections.sort(ks);
        if (ks.size() < 10) return out;
        int i = 0;
        while (i < ks.size()) {
            int day = ks.get(i) / 24;
            int j = i;
            while (j + 1 < ks.size() && ks.get(j + 1) / 24 == day) j++;
            LocalDate d = LocalDate.ofEpochDay(day);
            if (d.getDayOfWeek() == DayOfWeek.FRIDAY && i >= 1 && i + 1 <= j && j + 1 < ks.size()) {
                double e1 = la.get(ks.get(i + 1)), e2 = lb.get(ks.get(i + 1));
                double x1 = la.get(ks.get(j + 1)), x2 = lb.get(ks.get(j + 1));
                double p1 = la.get(ks.get(i - 1)), p2 = lb.get(ks.get(i - 1));
                double c1 = la.get(ks.get(j)), c2 = lb.get(ks.get(j));
                double entry = e1 / e2, exit = x1 / x2, prevC = p1 / p2, lastC = c1 / c2;
                if (entry <= 0 || exit <= 0 || prevC <= 0 || lastC <= 0) { i = j + 1; continue; }
                double session = lastC / prevC - 1.0;
                double weekend = exit / lastC - 1.0;
                double total = -(exit / entry - 1.0);
                out.add(new Leg(d, -session * 10_000.0, -weekend * 10_000.0, total * 10_000.0, ks.get(j + 1) - ks.get(i)));
            }
            i = j + 1;
        }
        return out;
    }

    static double diffYear(String base, String quote, int year) {
        double[] a = DIFF.get(base + "_USD");
        double[] b = DIFF.get("USD_" + quote);
        int k = Math.max(0, Math.min(20, year - 2006));
        if (b == null) {                       // USD_EUR : absent, mais EUR_USD existe (signe inversé)
            double[] rev = DIFF.get(quote + "_USD");
            if (rev == null || a == null) return 0;
            double[] bb = new double[rev.length];
            for (int i = 0; i < rev.length; i++) bb[i] = -rev[i];
            b = bb;
        }
        if (a == null || b == null) return 0;
        return a[k] + b[k];
    }

    // ---------------------------------------------------------------- E. heure de clôture

    static void hours() {
        section("E) L'HEURE DE CLÔTURE DU VENDREDI — d'où vient l'écart entre les deux constructions ?");
        System.out.println("Pour chaque paire : profil de la DERNIÈRE barre réelle du vendredi (convention A) et sensibilité");
        System.out.println("du retour du vendredi à l'heure de lecture (17:00 → 23:00 UTC).");
        for (String sym : new String[]{"GBP_USD", "USD_CHF", "USD_CAD", "USD_JPY", "EUR_USD", "GBP_JPY"}) {
            if (!HC.containsKey(sym)) continue;
            // profil des heures de dernière barre réelle du vendredi
            int[] cnt = new int[24];
            int nFri = 0;
            TreeMap<Integer, Double> hc = HC.get(sym);
            for (int k : hc.keySet()) {
                if (k % 24 == 23) continue;
            }
            Set<LocalDate> fri = new HashSet<>();
            for (var e : RET_A.get(sym).entrySet()) if (e.getKey().getDayOfWeek() == DayOfWeek.FRIDAY) fri.add(e.getKey());
            for (LocalDate d : fri) {
                int day = (int) d.toEpochDay();
                for (int h = 23; h >= 0; h--) {
                    if (hc.containsKey(day * 24 + h)) { cnt[h]++; nFri++; break; }
                }
            }
            StringBuilder prof = new StringBuilder();
            for (int h = 17; h <= 23; h++) prof.append(String.format("%02d:%.0f%% ", h, nFri == 0 ? 0 : 100.0 * cnt[h] / nFri));
            StringBuilder rets = new StringBuilder();
            for (int h = 17; h <= 23; h++) {
                TreeMap<LocalDate, Double> r = returns(rawAtHour(sym, h));
                Stat st = stat(pick(r, DayOfWeek.FRIDAY, 2006, 2026));
                rets.append(String.format("%02d:%+6.2f ", h, st.mean()));
            }
            System.out.printf("%-9s dernière barre %s%n%-9s retour vendredi    %s | convention A %+7.2f (n %d)%n%n",
                sym, prof.toString().trim(), "",
                rets.toString().trim(), stat(pick(RET_A.get(sym), DayOfWeek.FRIDAY, 2006, 2026)).mean(),
                stat(pick(RET_A.get(sym), DayOfWeek.FRIDAY, 2006, 2026)).n());
        }
        System.out.println("Lecture : si le retour du vendredi dépend fortement de l'heure de lecture, alors l'écart A/B est");
        System.out.println("une banque d'heures, pas un edge — et la seule comparaison valide est à HEURE FIXE pour toutes.");
    }

    static void verdict() {
        section("F) VERDICT");
        System.out.println("À lire dans la note Joplin : (1) le plancher de 13.9 bp est-il un fait ou une convention,");
        System.out.println("(2) le podium des crosses refuges du 50e survit-il à la construction alignée,");
        System.out.println("(3) la jambe tradée (avec la dérive de week-end) conserve-t-elle le signe du proxy de session.");
    }
}
