package com.martinfou.trading.intelligence.research;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import java.nio.file.Paths;
import java.time.*;
import java.util.*;

/**
 * GoldFxLeadLag — Teste le cross-asset or (XAU/USD) → FX (jeudi intermarket, 2026-08-13).
 *
 * Le lead-lag 100% FX (6 août 2026) a montré zéro edge : les paires FX pricent tout en
 * synchrone. L'or est un actif différent (commodity, marché 24h, driver COMEX/physique)
 * et n'a jamais été testé comme SOURCE de signal pour le FX avec la méthodo anti-artefact.
 *
 * Méthodo (identique au 6 août, anti-fenêtres-chevauchantes) :
 *  1. Corrélations contemporaines XAU→FX (validation de la relation, pas prédictive)
 *  2. Lead-lag journalier corr(r_xau[t], r_fx[t+k]), k=1..5
 *  3. Test HEBDOMADAIRE non-chevauchant : momentum 5j XAU (sem S) → rendement 5j FX (sem S+1)
 *  4. Conditionnel extrême : |mom XAU| > 1σ/1.5σ → direction FX semaine suivante
 *  5. Stabilité décennale (2006-2015 vs 2016-2026)
 *
 * Hypothèses testées :
 *  - XAU ↑ → AUD_USD ↑ (Australie producteur d'or, CausalCorrelator.validateGoldAud)
 *  - XAU ↑ → USD_CHF/USD_JPY ? (safe havens concurrents)
 *  - XAU ↑ → EUR_USD/GBP_USD ↑ (inverse USD)
 *  - XAU ↑ → NZD_USD/USD_CAD ? (bloc commodity)
 *
 * Run : mvn -q exec:java -pl trading-intelligence \
 *   -Dexec.mainClass="com.martinfou.trading.intelligence.research.GoldFxLeadLag"
 */
public class GoldFxLeadLag {

    private static final String BARS_DIR = "data/historical/bars";
    private static final int FROM_YEAR = 2006;
    private static final int TO_YEAR = 2026;

    private static final String[] FX_PAIRS = {
        "EUR_USD", "GBP_USD", "USD_JPY", "GBP_JPY",
        "USD_CAD", "AUD_USD", "NZD_USD", "USD_CHF"
    };
    private static final String GOLD = "XAU_USD";

    public static void main(String[] args) throws Exception {
        var barsDir = Paths.get(BARS_DIR);

        System.out.println("=== Gold (XAU/USD) -> FX cross-asset lead-lag ===");
        System.out.println("Période: " + FROM_YEAR + "-" + TO_YEAR + " (H1, agrégé en closes journalières UTC)\n");

        // 1. Charger + agréger en closes journalières UTC
        Map<String, Map<LocalDate, Double>> closes = new LinkedHashMap<>();
        for (String sym : FX_PAIRS) closes.put(sym, dailyCloses(sym, barsDir));
        closes.put(GOLD, dailyCloses(GOLD, barsDir));
        for (var e : closes.entrySet()) {
            System.out.printf("  %s: %d jours (%s - %s)%n", e.getKey(), e.getValue().size(),
                min(e.getValue()), max(e.getValue()));
        }

        // 2. Jours communs à TOUS (or + 8 paires)
        Set<LocalDate> common = new TreeSet<>(closes.get(GOLD).keySet());
        for (String p : FX_PAIRS) common.retainAll(closes.get(p).keySet());
        List<LocalDate> days = new ArrayList<>(common);
        System.out.printf("%n  Jours communs or+FX: %d%n", days.size());

        // 3. Matrice de prix alignée + rendements journaliers (log)
        Map<String, double[]> ret = new LinkedHashMap<>();
        double[] goldLog = alignedLogPrices(closes.get(GOLD), days);
        double[] goldRet = diff(goldLog);
        ret.put(GOLD, goldRet);
        for (String p : FX_PAIRS) {
            double[] lp = alignedLogPrices(closes.get(p), days);
            ret.put(p, diff(lp));
        }
        int n = days.size() - 1; // nombre de rendements
        System.out.printf("  Rendements journaliers alignés: %d%n", n);

        // ================= 1. Corrélations contemporaines =================
        System.out.println("\n=== 1. Corrélations contemporaines (k=0) — validation de la relation ===");
        for (String p : FX_PAIRS) {
            double c = corr(ret.get(GOLD), ret.get(p));
            System.out.printf("  XAU -> %-8s k0: %+.3f%n", p, c);
        }

        // ================= 2. Lead-lag journalier k=1..5 =================
        System.out.println("\n=== 2. Lead-lag journalier corr(r_xau[t], r_fx[t+k]) ===");
        for (String p : FX_PAIRS) {
            double[] rx = ret.get(GOLD);
            double[] rf = ret.get(p);
            StringBuilder sb = new StringBuilder(String.format("  XAU -> %-8s ", p));
            for (int k : new int[]{1, 2, 3, 5}) {
                double c = corr(Arrays.copyOfRange(rx, 0, rx.length - k),
                                Arrays.copyOfRange(rf, k, rf.length));
                sb.append(String.format("k%d:%+.3f  ", k, c));
            }
            System.out.println(sb);
        }

        // ================= 3. Test hebdo non-chevauchant =================
        System.out.println("\n=== 3. Hebdo non-chevauchant : momentum 5j XAU (sem S) -> 5j FX (sem S+1) ===");
        System.out.println("  (chaque échantillon = une semaine indépendante, ~1030 semaines)");
        for (String p : FX_PAIRS) {
            weeklyTest(ret.get(GOLD), ret.get(p), p, false);
        }
        // inverse : USD_JPY / USD_CHF sont des "safe havens" — tester l'inversion
        System.out.println("  -- variante INVERSÉE (XAU ↑ -> USD_JPY/USD_CHF ↓ ?) --");
        weeklyTest(ret.get(GOLD), ret.get("USD_JPY"), "USD_JPY", true);
        weeklyTest(ret.get(GOLD), ret.get("USD_CHF"), "USD_CHF", true);

        // ================= 4. Conditionnel extrême =================
        System.out.println("\n=== 4. Conditionnel extrême : |mom 5j XAU| > seuil -> 5j suivant FX ===");
        for (double thr : new double[]{1.0, 1.5, 2.0}) {
            System.out.printf("  -- seuil %.1fσ --%n", thr);
            for (String p : FX_PAIRS) {
                extremeTest(ret.get(GOLD), ret.get(p), p, thr);
            }
        }

        // ================= 5. Stabilité décennale =================
        System.out.println("\n=== 5. Stabilité décennale (hebdo non-chevauchant, XAU -> AUD/USD & EUR/USD) ===");
        for (String p : new String[]{"AUD_USD", "EUR_USD", "USD_JPY", "USD_CHF"}) {
            System.out.printf("  XAU -> %s%n", p);
            decadeTest(ret.get(GOLD), ret.get(p), p);
        }

        // ================= 6. Conditionnel extrême 2σ — stabilité décennale (anti-artefact) =================
        System.out.println("\n=== 6. Conditionnel extrême 2σ — stabilité par sous-période (anti-artefact crise) ===");
        System.out.println("  (le signal 2σ vient-il de 2008/2011/2020 seulement, ou est-il stable ?)");
        for (String p : new String[]{"EUR_USD", "GBP_USD", "AUD_USD", "NZD_USD", "USD_CAD", "USD_CHF"}) {
            System.out.printf("  XAU -> %s%n", p);
            extremeDecadeTest(ret.get(GOLD), ret.get(p), p, 2.0);
        }

        // ================= 7. Exclusions crises (2008, 2011, 2020) =================
        System.out.println("\n=== 7. Conditionnel extrême 2σ — hors années crise (2008/2011/2015/2020 exclues) ===");
        for (String p : new String[]{"EUR_USD", "GBP_USD", "AUD_USD", "NZD_USD", "USD_CAD"}) {
            extremeNoCrisisTest(ret.get(GOLD), ret.get(p), p, 2.0, days);
        }
    }

    // ====== Helpers ======

    private static Map<LocalDate, Double> dailyCloses(String symbol, java.nio.file.Path barsDir) throws Exception {
        Map<LocalDate, Double> closes = new TreeMap<>();
        for (int y = FROM_YEAR; y <= TO_YEAR; y++) {
            try {
                List<Bar> bars = HistoricalDataLoader.loadYear(symbol, y, barsDir);
                if (bars == null || bars.isEmpty()) continue;
                for (Bar b : bars) {
                    LocalDate d = b.timestamp().atZone(ZoneOffset.UTC).toLocalDate();
                    closes.put(d, b.close());
                }
            } catch (Exception e) {
                System.err.println("    skip " + symbol + " " + y + ": " + e.getMessage());
            }
        }
        return closes;
    }

    private static double[] alignedLogPrices(Map<LocalDate, Double> closes, List<LocalDate> days) {
        double[] out = new double[days.size()];
        for (int i = 0; i < days.size(); i++) {
            out[i] = Math.log(closes.get(days.get(i)));
        }
        return out;
    }

    private static double[] diff(double[] x) {
        double[] out = new double[x.length - 1];
        for (int i = 0; i < out.length; i++) out[i] = x[i + 1] - x[i];
        return out;
    }

    private static double corr(double[] a, double[] b) {
        int len = Math.min(a.length, b.length);
        if (len < 30) return Double.NaN;
        double ma = mean(a, len), mb = mean(b, len);
        double num = 0, da = 0, db = 0;
        for (int i = 0; i < len; i++) {
            double xa = a[i] - ma, xb = b[i] - mb;
            num += xa * xb; da += xa * xa; db += xb * xb;
        }
        double denom = Math.sqrt(da * db);
        return denom == 0 ? Double.NaN : num / denom;
    }

    private static double mean(double[] x, int len) {
        double s = 0;
        for (int i = 0; i < len; i++) s += x[i];
        return s / len;
    }

    /** Buckets de 5 jours consécutifs NON-CHEVAUCHANTS : sig = somme 5j XAU, fut = somme 5j FX suivant. */
    private static void weeklyTest(double[] rx, double[] rf, String name, boolean inverse) {
        int n = Math.min(rx.length, rf.length);
        int nb = n / 5;
        int m = nb - 1;
        double[] sig = new double[m], fut = new double[m];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < 5; j++) { sig[i] += rx[5 * i + j]; fut[i] += rf[5 * i + 5 + j]; }
        }
        double[] g = new double[m];
        for (int i = 0; i < m; i++) g[i] = inverse ? -fut[i] : fut[i];
        int hit = 0, pos = 0, neg = 0;
        double avgPos = 0, avgNeg = 0;
        int nPos = 0, nNeg = 0;
        for (int i = 0; i < m; i++) {
            int pred = sig[i] > 0 ? 1 : (sig[i] < 0 ? -1 : 0);
            int act = fut[i] > 0 ? 1 : (fut[i] < 0 ? -1 : 0);
            if (pred == act && pred != 0) hit++;
            if (sig[i] > 0) { pos++; avgPos += g[i]; nPos++; }
            else if (sig[i] < 0) { neg++; avgNeg += g[i]; nNeg++; }
        }
        double hitRate = (double) hit / m * 100;
        double spread = (nPos > 0 ? avgPos / nPos : 0) - (nNeg > 0 ? avgNeg / nNeg : 0);
        double c = corr(sig, fut);
        System.out.printf("  XAU->%-8s (inv=%b): n=%d pos=%d neg=%d | hit %5.1f%% | spread %+.4f%% | corr %+.3f%n",
            name, inverse, m, pos, neg, hitRate, spread * 100, c);
    }

    /** Conditionnel extrême : |momentum 5j XAU| > thr*σ → direction 5j suivants FX. */
    private static void extremeTest(double[] rx, double[] rf, String name, double thr) {
        int n = Math.min(rx.length, rf.length);
        int nb = n / 5;
        int m = nb - 1;
        double[] sig = new double[m], fut = new double[m];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < 5; j++) { sig[i] += rx[5 * i + j]; fut[i] += rf[5 * i + 5 + j]; }
        }
        double sd = std(sig);
        int cnt = 0, hit = 0;
        double avgDir = 0;
        for (int i = 0; i < m; i++) {
            if (Math.abs(sig[i]) <= thr * sd) continue;
            cnt++;
            int pred = sig[i] > 0 ? 1 : -1;
            int act = fut[i] > 0 ? 1 : (fut[i] < 0 ? -1 : 0);
            if (pred == act) hit++;
            avgDir += pred * fut[i];
        }
        if (cnt < 20) {
            System.out.printf("    XAU->%-8s |mom|>%.1fσ : n=%d (trop peu)%n", name, thr, cnt);
            return;
        }
        System.out.printf("    XAU->%-8s |mom|>%.1fσ : n=%d hit %5.1f%% avg_dir %+.4f%%%n",
            name, thr, cnt, (double) hit / cnt * 100, avgDir / cnt * 100);
    }

    private static double std(double[] x) {
        double m = mean(x, x.length);
        double s = 0;
        for (double v : x) s += (v - m) * (v - m);
        return Math.sqrt(s / x.length);
    }

    /** Conditionnel extrême 2σ — stabilité par sous-période (2006-2012 / 2013-2019 / 2020-2026). */
    private static void extremeDecadeTest(double[] rx, double[] rf, String name, double thr) {
        int n = Math.min(rx.length, rf.length);
        int nb = n / 5;
        int m = nb - 1;
        double[] sig = new double[m], fut = new double[m];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < 5; j++) { sig[i] += rx[5 * i + j]; fut[i] += rf[5 * i + 5 + j]; }
        }
        double sd = std(sig);
        int[] bounds = {0, m / 3, 2 * m / 3, m};
        String[] labels = {"2006-2012", "2013-2019", "2020-2026"};
        for (int s = 0; s < 3; s++) {
            int cnt = 0, hit = 0;
            double avgDir = 0;
            for (int i = bounds[s]; i < bounds[s + 1]; i++) {
                if (Math.abs(sig[i]) <= thr * sd) continue;
                cnt++;
                int pred = sig[i] > 0 ? 1 : -1;
                int act = fut[i] > 0 ? 1 : (fut[i] < 0 ? -1 : 0);
                if (pred == act) hit++;
                avgDir += pred * fut[i];
            }
            if (cnt < 5) {
                System.out.printf("    %s: n=%d (trop peu)%n", labels[s], cnt);
                continue;
            }
            System.out.printf("    %s: n=%d hit %5.1f%% avg_dir %+.4f%%%n",
                labels[s], cnt, (double) hit / cnt * 100, avgDir / cnt * 100);
        }
    }

    /** Conditionnel extrême 2σ — hors années crise (2008, 2011, 2015, 2020 exclues). */
    private static void extremeNoCrisisTest(double[] rx, double[] rf, String name, double thr, List<LocalDate> days) {
        int n = Math.min(rx.length, rf.length);
        int nb = n / 5;
        int m = nb - 1;
        double[] sig = new double[m], fut = new double[m];
        // La date du dernier jour du bucket S (index 5*i+4) sert de référence année
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < 5; j++) { sig[i] += rx[5 * i + j]; fut[i] += rf[5 * i + 5 + j]; }
        }
        double sd = std(sig);
        Set<Integer> crisis = Set.of(2008, 2011, 2015, 2020);
        int cnt = 0, hit = 0, skipped = 0;
        double avgDir = 0;
        for (int i = 0; i < m; i++) {
            if (Math.abs(sig[i]) <= thr * sd) continue;
            // bucket S couvre days[5*i .. 5*i+4] ; la semaine suivante S+1 couvre days[5*i+5 .. 5*i+9]
            LocalDate refDate = days.get(5 * i + 4);
            int year = refDate.getYear();
            // Skip si la semaine de signal OU la semaine suivante tombe dans une année crise
            LocalDate futDate = days.get(5 * i + 9);
            if (crisis.contains(year) || crisis.contains(futDate.getYear())) { skipped++; continue; }
            cnt++;
            int pred = sig[i] > 0 ? 1 : -1;
            int act = fut[i] > 0 ? 1 : (fut[i] < 0 ? -1 : 0);
            if (pred == act) hit++;
            avgDir += pred * fut[i];
        }
        if (cnt < 20) {
            System.out.printf("    XAU->%-8s hors crises: n=%d (skip %d, trop peu)%n", name, cnt, skipped);
            return;
        }
        System.out.printf("    XAU->%-8s hors crises: n=%d (skip %d) hit %5.1f%% avg_dir %+.4f%%%n",
            name, cnt, skipped, (double) hit / cnt * 100, avgDir / cnt * 100);
    }

    private static void decadeTest(double[] rx, double[] rf, String name) {
        int n = Math.min(rx.length, rf.length);
        int nb = n / 5;
        int m = nb - 1;
        double[] sig = new double[m], fut = new double[m];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < 5; j++) { sig[i] += rx[5 * i + j]; fut[i] += rf[5 * i + 5 + j]; }
        }
        int half = m / 2;
        for (String label : new String[]{"2006-2015", "2016-2026"}) {
            int lo = label.startsWith("2006") ? 0 : half;
            int hi = label.startsWith("2006") ? half : m;
            int hit = 0, cnt = 0;
            double posSum = 0, negSum = 0;
            int nPos = 0, nNeg = 0;
            for (int i = lo; i < hi; i++) {
                cnt++;
                int pred = sig[i] > 0 ? 1 : (sig[i] < 0 ? -1 : 0);
                int act = fut[i] > 0 ? 1 : (fut[i] < 0 ? -1 : 0);
                if (pred == act && pred != 0) hit++;
                if (sig[i] > 0) { posSum += fut[i]; nPos++; }
                else if (sig[i] < 0) { negSum += fut[i]; nNeg++; }
            }
            double spread = (nPos > 0 ? posSum / nPos : 0) - (nNeg > 0 ? negSum / nNeg : 0);
            System.out.printf("    %s: n=%d hit %5.1f%% | spread %+.4f%%%n",
                label, cnt, (double) hit / cnt * 100, spread * 100);
        }
    }

    private static LocalDate min(Map<LocalDate, Double> m) {
        LocalDate out = null;
        for (LocalDate d : m.keySet()) if (out == null || d.isBefore(out)) out = d;
        return out;
    }

    private static LocalDate max(Map<LocalDate, Double> m) {
        LocalDate out = null;
        for (LocalDate d : m.keySet()) if (out == null || d.isAfter(out)) out = d;
        return out;
    }
}
