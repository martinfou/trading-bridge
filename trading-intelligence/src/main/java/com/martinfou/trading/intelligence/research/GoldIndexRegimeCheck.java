package com.martinfou.trading.intelligence.research;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;

import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * GoldIndexRegimeCheck — Pré-validation (Pattern D) du jeudi 10 septembre 2026 (32e résultat).
 *
 * 📊 QUESTION INTERMARKET : GoldTurtleDxyFilter (28 août, 23e résultat) a montré que
 * l'edge Turtle de l'or (PF 1.17) vit dans les régimes USD FERMES (mode OPPOSITE : long or
 * quand DXY > SMA ~21j → PF 1.34). GoldTurtleRiskIndex (3 sept, 27e) a REJETÉ le gauge
 * risk-off FX (RAI = AUD/JPY synthétique) : ce n'est PAS le risk-off générique, mais
 * 2013-15 falsifiait le test (AUD/JPY en risk-off pendant que l'or baissait).
 *
 * ⚠️ NOUVELLE DONNÉE : le skill répétait depuis le 13 août « un vrai lead-lag cross-asset
 * exigerait le S&P 500 (données absentes) ». FAUX — data/historical/futures/ contient
 * MES_D1 (S&P 500) / MNQ_D1 (Nasdaq) / M2K_D1 (Russell) sur 2006-2026 (5198 jours D1).
 *
 * HYPOTHÈSE À TESTER : le « twin-refuge » (USD ferme + or qui monte) est-il une
 * manifestation de l'AVERSION AU RISQUE ACTION (SPX sous sa tendance) — auquel cas un
 * gauge actions remplacerait/compléterait le DXY — ou un phénomène spécifiquement
 * dollar-or (auquel cas le VRAI actif de risque le falsifie aussi, comme le RAI) ?
 *
 * MÉTHODE (Pattern D, méthode du 5 août) — pur statistique, aucun moteur :
 *   1. Split chronologique IS 2006-2015 / OOS 2016-2026 (obligatoire).
 *   2. 3 gauges en parallèle, tous look-ahead safe (état calculé sur la veille) :
 *        - USD  : DXY synthétique (5 paires) > SMA21j
 *        - RAI  : AUD/JPY synthétique > SMA21j  (reproduction du REJECT du 3 sept)
 *        - SPX  : S&P 500 > SMA200j
 *   3. Rendements forward de l'encre XAU (5j / 21j) par quadrant de gauge.
 *   4. Analyse de RECOUVREMENT des gauges : USD-FIRM et SPX-RISK-OFF coïncident-ils ?
 *      Le bucket de DÉSACCORD (USD ferme + SPX haussier = profil 2013-15 / Fed taper)
 *      est le discriminant clé.
 *   5. Corrélation contemporaine XAU × SPX par quadrant de gauge USD.
 *
 * Usage : java -cp "$CP" com.martinfou.trading.intelligence.research.GoldIndexRegimeCheck
 */
public class GoldIndexRegimeCheck {

    static final Path BARS = Path.of("data/historical/bars");
    static final Path FUT = Path.of("data/historical/futures");

    static final String[] DXY_PAIRS = {"EUR_USD", "USD_JPY", "GBP_USD", "USD_CAD", "USD_CHF"};
    static final double[] DXY_W = {0.576, 0.136, 0.119, 0.091, 0.036};
    static final int[] DXY_SIGN = {-1, +1, -1, +1, +1};

    static final int IS_END = 2015;      // IS 2006-2015 / OOS 2016-2026
    static final int USD_SMA = 21;       // ~500 barres H1 (GoldTurtleDxyFilter)
    static final int SPX_SMA = 200;

    record Quad(String label, List<Double> fwd5, List<Double> fwd21) {
        Quad() { this("", new ArrayList<>(), new ArrayList<>()); }
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=====================================================================");
        System.out.println("GOLD INDEX REGIME CHECK — twin-refuge USD/or vs aversion au risque ACTION");
        System.out.println("XAU_USD D1 (depuis H1) × MES_D1 S&P500 × DXY synthétique × RAI AUD/JPY");
        System.out.println("=====================================================================\n");

        Map<LocalDate, Double> xau = dailyClosesH1("XAU_USD", 2006, 2026);
        Map<LocalDate, Double> spx = dailyClosesCsv(FUT.resolve("MES_D1.csv"));
        Map<LocalDate, Double> ndx = dailyClosesCsv(FUT.resolve("MNQ_D1.csv"));
        Map<LocalDate, Double> dxy = buildDxyDaily(2006, 2026);
        Map<LocalDate, Double> rai = buildRaiDaily(2006, 2026);

        System.out.printf("Séries : XAU %d j | SPX %d j | NDX %d j | DXY %d j | RAI %d j%n",
            xau.size(), spx.size(), ndx.size(), dxy.size(), rai.size());

        // dates communes aux 3 gauges + or
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate d : xau.keySet()) {
            if (spx.containsKey(d) && dxy.containsKey(d) && rai.containsKey(d)) dates.add(d);
        }
        Collections.sort(dates);
        System.out.printf("Dates communes alignées : %d (%s → %s)%n%n",
            dates.size(), dates.get(0), dates.get(dates.size() - 1));

        int n = dates.size();
        double[] px = new double[n], sp = new double[n], dx = new double[n], ra = new double[n];
        for (int i = 0; i < n; i++) {
            LocalDate d = dates.get(i);
            px[i] = xau.get(d); sp[i] = spx.get(d); dx[i] = dxy.get(d); ra[i] = rai.get(d);
        }

        // --- 1. Corrélation contemporaine XAU × SPX (rendements journaliers) ---
        section("1. CORRÉLATION CONTEMPORAINE XAU × SPX (rendements journaliers)");
        System.out.printf("%-18s %10s %10s %10s%n", "PÉRIODE", "corr XAU/SPX", "corr XAU/DXY", "corr SPX/DXY");
        reportCorr(px, sp, dx, 1, n - 1, "FULL");
        reportCorr(px, sp, dx, 1, idx(dates, IS_END), "IS 2006-2015");
        reportCorr(px, sp, dx, idx(dates, IS_END) + 1, n - 1, "OOS 2016-2026");

        System.out.println("\nPar décennie/ère (corr XAU/SPX) :");
        int[] years = {2006, 2010, 2013, 2016, 2020, 2023};
        for (int y : years) {
            int a = idx(dates, y - 1), b = idx(dates, y + 3);
            if (b > a) reportCorr(px, sp, dx, a + 1, b, y + "-" + (y + 3));
        }

        // --- 2. Gauges + quadrants ---
        section("2. QUADRANTS DE RÉGIME — rendement forward XAU (close→close, jours de bourse)");
        Quad[] quads = new Quad[4];
        int[][] combos = {{1, 1}, {1, -1}, {-1, 1}, {-1, -1}};   // (USD firm?, SPX above 200d?)
        String[] names = {"USD-FIRM × SPX-ABOVE", "USD-FIRM × SPX-BELOW", "USD-WEAK × SPX-ABOVE", "USD-WEAK × SPX-BELOW"};
        for (int i = 0; i < 4; i++) quads[i] = new Quad(names[i], new ArrayList<>(), new ArrayList<>());

        Quad[] quadsIs = new Quad[4], quadsOos = new Quad[4];
        for (int i = 0; i < 4; i++) {
            quadsIs[i] = new Quad(names[i], new ArrayList<>(), new ArrayList<>());
            quadsOos[i] = new Quad(names[i], new ArrayList<>(), new ArrayList<>());
        }

        // Gauges RAI (2 états)
        Quad raiFirm = new Quad("RAI risk-ON", new ArrayList<>(), new ArrayList<>());
        Quad raiWeak = new Quad("RAI risk-OFF", new ArrayList<>(), new ArrayList<>());

        int overlap = 0, tot = 0;
        for (int i = Math.max(SPX_SMA, USD_SMA) + 1; i < n - 22; i++) {
            if (px[i - 1] <= 0) continue;
            double fs = dxyFirm(dx, i - 1, USD_SMA);
            double es = spxAbove(sp, i - 1, SPX_SMA);
            if (Double.isNaN(fs) || Double.isNaN(es)) continue;
            double f5 = px[i + 5] / px[i] - 1;
            double f21 = px[i + 21] / px[i] - 1;
            int qi = (fs > 0 ? 0 : 2) + (es > 0 ? 0 : 1);
            quads[qi].fwd5().add(f5); quads[qi].fwd21().add(f21);
            boolean is = dates.get(i).getYear() <= IS_END;
            (is ? quadsIs : quadsOos)[qi].fwd5().add(f5);
            (is ? quadsIs : quadsOos)[qi].fwd21().add(f21);
            tot++;
            if ((fs > 0 && es < 0) || (fs < 0 && es > 0)) overlap++;
            // RAI
            double rs = raiAbove(ra, i - 1, USD_SMA);
            if (!Double.isNaN(rs)) {
                (rs > 0 ? raiFirm : raiWeak).fwd5().add(f5);
                (rs > 0 ? raiFirm : raiWeak).fwd21().add(f21);
            }
        }

        System.out.printf("%-24s %7s %12s %10s %12s %10s%n",
            "QUADRANT", "N", "fwd5 moy%", "hit5%", "fwd21 moy%", "hit21%");
        for (Quad q : quads) printQuad(q);
        System.out.printf("\nDésaccord USD/SPX : %d / %d jours (%.1f%%)%n", overlap, tot, 100.0 * overlap / tot);

        System.out.println("\nIS 2006-2015 :");
        for (Quad q : quadsIs) printQuad(q);
        System.out.println("\nOOS 2016-2026 :");
        for (Quad q : quadsOos) printQuad(q);

        // --- 2b. Décomposition marginale : la valeur ajoutée de chaque condition ---
        section("2b. DÉCOMPOSITION MARGINALE (le twin exige-t-il les DEUX jambes ?)");
        System.out.printf("%-34s %7s %12s %10s %12s %10s%n",
            "CONDITION", "N", "fwd5 moy%", "hit5%", "fwd21 moy%", "hit21%");
        printQuad(mk("AUCUNE (baseline non-conditionnelle)", new Quad[]{quads[0], quads[1], quads[2], quads[3]}));
        printQuad(mk("SPX-BELOW seul (stress actions)", new Quad[]{quads[1], quads[3]}));
        printQuad(mk("SPX-ABOVE seul (calme actions)", new Quad[]{quads[0], quads[2]}));
        printQuad(mk("USD-FIRM seul (DXY-OPPOSITE)", new Quad[]{quads[0], quads[1]}));
        printQuad(mk("USD-WEAK seul", new Quad[]{quads[2], quads[3]}));
        printQuad(quads[1]);
        System.out.println("  → twin IS 2006-2015 :");
        printQuad(quadsIs[1]);
        System.out.println("  → twin OOS 2016-2026 :");
        printQuad(quadsOos[1]);

        section("3. GAUGE RAI (AUD/JPY, reproduction du REJECT du 3 sept)");
        System.out.printf("%-24s %7s %12s %10s %12s %10s%n",
            "RAI ÉTAT", "N", "fwd5 moy%", "hit5%", "fwd21 moy%", "hit21%");
        printQuad(raiFirm); printQuad(raiWeak);

        // --- 4. Corrélation XAU×SPX CONDITIONNELLE (mécanisme) ---
        section("4. MÉCANISME — corrélation XAU × SPX conditionnée par le régime USD");
        for (int state : new int[]{1, -1}) {
            List<Double> a = new ArrayList<>(), b = new ArrayList<>();
            for (int i = Math.max(SPX_SMA, USD_SMA) + 1; i < n; i++) {
                double fs = dxyFirm(dx, i - 1, USD_SMA);
                if (Double.isNaN(fs) || Math.signum(fs) != state) continue;
                a.add(px[i] / px[i - 1] - 1);
                b.add(sp[i] / sp[i - 1] - 1);
            }
            System.out.printf("USD %-6s : n=%5d  corr(XAU,SPX) = %+.3f%n",
                state > 0 ? "FIRME" : "FAIBLE", a.size(), corr(a, b));
        }

        // --- 5. Le bucket discriminant : USD ferme + SPX haussier par ère ---
        section("5. LE BUCKET 2013-15 (Fed taper) — USD-FIRM × SPX-ABOVE par ère");
        Map<String, int[]> eraRange = new LinkedHashMap<>();
        eraRange.put("2006-2012 bull", new int[]{2006, 2012});
        eraRange.put("2013-2015 bear or", new int[]{2013, 2015});
        eraRange.put("2016-2025 bull2", new int[]{2016, 2025});
        System.out.printf("%-20s %-22s %7s %12s %10s%n", "ÈRE", "QUADRANT", "N", "fwd21 moy%", "hit21%");
        for (var e : eraRange.entrySet()) {
            int a = idx(dates, e.getValue()[0] - 1), b = idx(dates, e.getValue()[1]);
            for (int q : new int[]{0, 1}) {
                List<Double> f = new ArrayList<>();
                for (int i = Math.max(a, SPX_SMA + USD_SMA + 1); i < Math.min(b, n - 22); i++) {
                    double fs = dxyFirm(dx, i - 1, USD_SMA), es = spxAbove(sp, i - 1, SPX_SMA);
                    if (Double.isNaN(fs) || Double.isNaN(es)) continue;
                    int qi = (fs > 0 ? 0 : 2) + (es > 0 ? 0 : 1);
                    if (qi == q) f.add(px[i + 21] / px[i] - 1);
                }
                System.out.printf("%-20s %-22s %7d %11.3f%% %9.1f%%%n", e.getKey(), names[q],
                    f.size(), 100 * mean(f), hit(f));
            }
        }

        // --- 7. CONTRÔLE ANTI-ARTEFACT CRISE (méthode du 13 août) ---
        section("7. CONTRÔLE CRISE — les jours twin sont-ils clusterisés en épisodes 2008/2011/2020 ?");
        Set<Integer> crisis = Set.of(2008, 2011, 2020, 2022);
        // (a) nombre d'ÉPISODES distincts (runs de jours consécutifs twin)
        int runs = 0; boolean inRun = false; List<Double> runRet = new ArrayList<>();
        for (int i = Math.max(SPX_SMA, USD_SMA) + 1; i < n - 22; i++) {
            double fs = dxyFirm(dx, i - 1, USD_SMA), es = spxAbove(sp, i - 1, SPX_SMA);
            boolean twin = !Double.isNaN(fs) && !Double.isNaN(es) && fs > 0 && es < 0;
            if (twin && !inRun) { runs++; inRun = true; }
            if (!twin) inRun = false;
            if (twin) runRet.add(px[i + 21] / px[i] - 1);
        }
        System.out.printf("Épisodes twin distincts : %d (pour %d jours twin) — taille moy %.1f jours%n",
            runs, runRet.size(), runRet.size() * 1.0 / Math.max(1, runs));
        System.out.printf("Tous jours twin (non-indépendants)   : N=%d  fwd21 %+.3f%%  hit %.1f%%%n",
            runRet.size(), 100 * mean(runRet), hit(runRet));

        // (b) hors années de crise
        for (String tag : new String[]{"AVEC crises", "SANS crises 2008/11/20/22"}) {
            List<Double> tw = new ArrayList<>(), base = new ArrayList<>();
            for (int i = Math.max(SPX_SMA, USD_SMA) + 1; i < n - 22; i++) {
                int yr = dates.get(i).getYear();
                if (tag.startsWith("SANS") && crisis.contains(yr)) continue;
                double fs = dxyFirm(dx, i - 1, USD_SMA), es = spxAbove(sp, i - 1, SPX_SMA);
                if (Double.isNaN(fs) || Double.isNaN(es)) continue;
                double r = px[i + 21] / px[i] - 1;
                base.add(r);
                if (fs > 0 && es < 0) tw.add(r);
            }
            System.out.printf("%-27s : twin N=%4d fwd21 %+.3f%% hit %.1f%%  |  baseline N=%4d %+.3f%% hit %.1f%%  |  delta %+.3f pp%n",
                tag, tw.size(), 100 * mean(tw), hit(tw), base.size(), 100 * mean(base), hit(base),
                100 * (mean(tw) - mean(base)));
        }

        // (c) échantillonnage NON-CHEVAUCHANT (1 observation / 21 jours de bourse)
        System.out.println("\nNon-chevauchant (toutes les 21 obs, fenêtres indépendantes) :");
        Quad[] nq = new Quad[4];
        for (int i = 0; i < 4; i++) nq[i] = new Quad(names[i], new ArrayList<>(), new ArrayList<>());
        int step = 0;
        for (int i = Math.max(SPX_SMA, USD_SMA) + 1; i < n - 22; i += 21) {
            double fs = dxyFirm(dx, i - 1, USD_SMA), es = spxAbove(sp, i - 1, SPX_SMA);
            if (Double.isNaN(fs) || Double.isNaN(es)) continue;
            step++;
            nq[(fs > 0 ? 0 : 2) + (es > 0 ? 0 : 1)].fwd21().add(px[i + 21] / px[i] - 1);
        }
        System.out.printf("(n total indépendant = %d)%n", step);
        System.out.printf("%-24s %7s %12s %10s%n", "QUADRANT", "N", "fwd21 moy%", "hit21%");
        for (Quad q : nq) {
            System.out.printf("%-24s %7d %11.3f%% %9.1f%%%n",
                q.label(), q.fwd21().size(), 100 * mean(q.fwd21()), hit(q.fwd21()));
        }

        // --- 6. Contrôle secondaire : Nasdaq / Russell (même test, autre index) ---
        section("6. CONTRÔLE — corrélation XAU × NDX / XAU × SPX sur dates communes");
        List<Double> a1 = new ArrayList<>(), b1 = new ArrayList<>(), c1 = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            LocalDate d = dates.get(i), d0 = dates.get(i - 1);
            if (!ndx.containsKey(d) || !ndx.containsKey(d0)) continue;
            a1.add(px[i] / px[i - 1] - 1);
            b1.add(sp[i] / sp[i - 1] - 1);
            c1.add(ndx.get(d) / ndx.get(d0) - 1);
        }
        System.out.printf("n=%d | corr(XAU,SPX)=%+.3f | corr(XAU,NDX)=%+.3f | corr(SPX,NDX)=%+.3f%n",
            a1.size(), corr(a1, b1), corr(a1, c1), corr(b1, c1));

        System.out.println("\nDONE");
    }

    // ---------------- gauges ----------------

    /** DXY de la veille > SMA_N → +1 (USD ferme), sinon -1/NaN. */
    static double dxyFirm(double[] dx, int i, int period) {
        double s = sma(dx, i, period);
        return Double.isNaN(s) ? Double.NaN : (dx[i] > s ? 1 : -1);
    }

    static double spxAbove(double[] sp, int i, int period) {
        double s = sma(sp, i, period);
        return Double.isNaN(s) ? Double.NaN : (sp[i] > s ? 1 : -1);
    }

    static double raiAbove(double[] ra, int i, int period) {
        double s = sma(ra, i, period);
        return Double.isNaN(s) ? Double.NaN : (ra[i] > s ? 1 : -1);
    }

    static double sma(double[] v, int i, int period) {
        if (i - period + 1 < 0) return Double.NaN;
        double s = 0; for (int k = i - period + 1; k <= i; k++) s += v[k];
        return s / period;
    }

    // ---------------- stats ----------------

    static void printQuad(Quad q) {
        System.out.printf("%-24s %7d %11.3f%% %9.1f%% %11.3f%% %9.1f%%%n",
            q.label(), q.fwd5().size(), 100 * mean(q.fwd5()), hit(q.fwd5()),
            100 * mean(q.fwd21()), hit(q.fwd21()));
    }

    /** Agrège plusieurs quadrants en un seul (décomposition marginale). */
    static Quad mk(String label, Quad[] parts) {
        Quad out = new Quad(label, new ArrayList<>(), new ArrayList<>());
        for (Quad p : parts) {
            out.fwd5().addAll(p.fwd5());
            out.fwd21().addAll(p.fwd21());
        }
        return out;
    }

    static double mean(List<Double> v) {
        if (v.isEmpty()) return Double.NaN;
        double s = 0; for (double x : v) s += x; return s / v.size();
    }

    static double hit(List<Double> v) {
        if (v.isEmpty()) return Double.NaN;
        int c = 0; for (double x : v) if (x > 0) c++; return 100.0 * c / v.size();
    }

    static double corr(List<Double> a, List<Double> b) {
        int m = Math.min(a.size(), b.size());
        if (m < 3) return Double.NaN;
        double ma = 0, mb = 0;
        for (int i = 0; i < m; i++) { ma += a.get(i); mb += b.get(i); }
        ma /= m; mb /= m;
        double sab = 0, sa = 0, sb = 0;
        for (int i = 0; i < m; i++) {
            double x = a.get(i) - ma, y = b.get(i) - mb;
            sab += x * y; sa += x * x; sb += y * y;
        }
        return sab / Math.sqrt(sa * sb);
    }

    static void reportCorr(double[] px, double[] sp, double[] dx, int a, int b, String label) {
        if (a < 1 || b >= px.length || b <= a) return;
        List<Double> rp = new ArrayList<>(), rs = new ArrayList<>(), rd = new ArrayList<>();
        for (int i = a; i <= b; i++) {
            rp.add(px[i] / px[i - 1] - 1);
            rs.add(sp[i] / sp[i - 1] - 1);
            rd.add(dx[i] / dx[i - 1] - 1);
        }
        System.out.printf("%-18s %10.3f %10.3f %10.3f   (n=%d)%n",
            label, corr(rp, rs), corr(rp, rd), corr(rs, rd), rp.size());
    }

    static int idx(List<LocalDate> dates, int year) {
        for (int i = 0; i < dates.size(); i++) if (dates.get(i).getYear() > year) return i - 1;
        return dates.size() - 1;
    }

    static void section(String t) {
        System.out.println("\n=====================================================================");
        System.out.println("=== " + t);
        System.out.println("=====================================================================");
    }

    // ---------------- data ----------------

    static Map<LocalDate, Double> dailyClosesH1(String symbol, int y0, int y1) throws Exception {
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        for (int y = y0; y <= y1; y++) {
            List<Bar> bars;
            try {
                bars = HistoricalDataLoader.loadYear(symbol, y, BARS);
            } catch (Exception ex) {
                continue;
            }
            for (Bar b : bars) {
                LocalDate d = b.timestamp().atZone(ZoneId.of("UTC")).toLocalDate();
                if (b.close() > 0) out.put(d, b.close());
            }
        }
        return out;
    }

    /** CSV Date,Open,High,Low,Close,... (Yahoo) → closes journalières. */
    static Map<LocalDate, Double> dailyClosesCsv(Path p) throws Exception {
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        List<String> lines = Files.readAllLines(p);
        for (int i = 1; i < lines.size(); i++) {
            String[] f = lines.get(i).split(",");
            if (f.length < 5) continue;
            try {
                out.put(LocalDate.parse(f[0].trim()), Double.parseDouble(f[4]));
            } catch (Exception ignore) { }
        }
        return out;
    }

    /** DXY synthétique journalier (clôtures H1 de la dernière barre du jour UTC). */
    static Map<LocalDate, Double> buildDxyDaily(int y0, int y1) throws Exception {
        Map<String, Map<LocalDate, Double>> series = new LinkedHashMap<>();
        for (String s : DXY_PAIRS) series.put(s, dailyClosesH1(s, y0, y1));
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        for (LocalDate d : series.get("EUR_USD").keySet()) {
            double logp = 0; boolean ok = true;
            for (int i = 0; i < DXY_PAIRS.length; i++) {
                Double c = series.get(DXY_PAIRS[i]).get(d);
                if (c == null || c <= 0) { ok = false; break; }
                logp += DXY_SIGN[i] * (DXY_W[i] / 0.958) * Math.log(c);
            }
            if (ok) out.put(d, 50.14348112 * Math.exp(logp));
        }
        return out;
    }

    /** AUD/JPY synthétique journalier = AUD_USD × USD_JPY. */
    static Map<LocalDate, Double> buildRaiDaily(int y0, int y1) throws Exception {
        Map<LocalDate, Double> aud = dailyClosesH1("AUD_USD", y0, y1);
        Map<LocalDate, Double> jpy = dailyClosesH1("USD_JPY", y0, y1);
        TreeMap<LocalDate, Double> out = new TreeMap<>();
        for (var e : aud.entrySet()) {
            Double j = jpy.get(e.getKey());
            if (j != null && j > 0) out.put(e.getKey(), e.getValue() * j);
        }
        return out;
    }
}
