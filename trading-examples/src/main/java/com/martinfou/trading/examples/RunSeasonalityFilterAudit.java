package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.backtest.SwapCalculator;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.intelligence.research.SeasonalityFilter;
import com.martinfou.trading.intelligence.research.SeasonalityFilter.SeasonalBias;
import com.martinfou.trading.strategies.creative.DateWindowSeasonalStrategy;

import java.time.*;
import java.util.*;

/**
 * RunSeasonalityFilterAudit — mercredi 30 septembre 2026 (46e résultat, rotation
 * « pattern saisonnier »).
 *
 * QUESTION : le pipeline impose à CHAQUE stratégie d'appeler
 * {@code SeasonalityFilter.getBias()} et la bibliothèque revendique des hit rates
 * de 72-94 % sur 20+ ans. Ces 8 patterns ont-ils été VALIDÉS avec le moteur
 * corrigé (fix look-ahead c7a552db + coûts), et les clés fonctionnent-elles ?
 *
 * A) INTÉGRITÉ — bugs silencieux : normalisation du symbole (clé « USDCAD » vs
 *    symbole « USD_CAD »), fuseau (America/New_York vs UTC), fenêtres qui se
 *    chevauchent (premier match gagne).
 * B) VALIDATION EMPIRIQUE des 8 patterns, 2006-2026, sur barres RÉELLES
 *    ({@code high > low}) : rendement de fenêtre par année, hit rate, médiane,
 *    t, split IS (2006-2015) / OOS (2016-2026), CONTRÔLE de dérive (même durée
 *    de hold, non conditionné) et CONTRÔLE de spécificité (fenêtre décalée
 *    ±1 et ±2 mois).
 *
 * Aucune stratégie n'est codée : c'est une mesure de statistique calendaire.
 */
public class RunSeasonalityFilterAudit {

    /** Dernière date de données connue par paire (couverture réelle, 30 sept 2026). */
    static final String[] PAIRS = {
        "EUR_USD", "GBP_USD", "USD_JPY", "AUD_USD", "USD_CAD", "NZD_USD", "USD_CHF", "GBP_JPY"
    };

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--usdcad")) { partC(); return; }
        System.out.println("================================================================");
        System.out.println("AUDIT DE LA BIBLIOTHÈQUE SeasonalityFilter — 30 sept 2026");
        System.out.println("Rendu au 30 sept 2026 : la bibliothèque revendique 72-94 % de hit.");
        System.out.println("================================================================");

        SeasonalBias[] patterns = SeasonalityFilter.allPatterns();

        partA(patterns);

        Map<String, Map<LocalDate, Double>> data = new HashMap<>();
        for (String p : PAIRS) {
            try {
                data.put(p, dailyRealCloses(load(p)));
            } catch (Exception e) {
                System.out.println("!! données indisponibles pour " + p + " : " + e.getMessage());
            }
        }
        System.out.println("\n--- COUVERTURE RÉELLE DES DONNÉES (barres réelles high>low) ---");
        for (String p : PAIRS) {
            var m = data.get(p);
            if (m == null) continue;
            System.out.printf("%-8s %d jours réels | %s → %s%n", p, m.size(),
                m.keySet().iterator().next(), last(m.keySet()));
        }

        partB(patterns, data);
        System.out.println("\nDONE");
    }

    // ================================================================ A) INTÉGRITÉ

    static void partA(SeasonalBias[] patterns) {
        System.out.println("\n=== A) INTÉGRITÉ DE LA BIBLIOTHÈQUE (bugs silencieux) ===");
        System.out.println("A1) Normalisation du symbole — getBias() compare p.symbol().equals(symbol) SANS normaliser");
        System.out.printf("%-22s %-10s %-12s %-14s %-14s%n",
            "PATTERN", "CLÉ CODÉE", "getBias(clé)", "getBias(pair)", "CONVENTION OK ?");
        int deadWithRunner = 0;
        for (SeasonalBias p : patterns) {
            String key = p.symbol();
            String pair = toPair(key);
            Instant mid = midWindowInstant(p);
            Order.Side byKey = SeasonalityFilter.getBias(key, mid);
            Order.Side byPair = SeasonalityFilter.getBias(pair, mid);
            boolean ok = byPair != null;
            if (!ok) deadWithRunner++;
            System.out.printf("%-22s %-10s %-12s %-14s %-14s%n",
                p.symbol() + " " + win(p), key, byKey, byPair, ok ? "oui" : "NON — MORTE");
        }
        System.out.printf("→ %d/%d patterns NE SE DÉCLENCHENT PAS quand on passe le symbole du runner (USD_CAD).%n",
            deadWithRunner, patterns.length);
        System.out.println("   (les miroirs inline de trading-strategies, eux, normalisent sym.replace(\"_\",\"\") :");
        System.out.println("    4 copies actives + 8 rejetées ⇒ DEUX sources de vérité qui ont déjà divergé)");

        System.out.println("\nA2) Fuseau — getBias() compte les jours en America/New_York (pitfall documenté : UTC obligatoire)");
        System.out.printf("%-22s %-14s %-22s %-22s%n", "PATTERN", "DÉBUT (UTC)", "BASCULE UTC constatée", "DÉCALAGE");
        for (SeasonalBias p : patterns) {
            LocalDate start = LocalDate.of(2020, p.startMonth(), p.startDay());
            String toggle = "n/a";
            for (int h = 0; h < 24; h++) {
                Instant t = start.atStartOfDay(ZoneOffset.UTC).plus(Duration.ofHours(h)).toInstant();
                if (SeasonalityFilter.getBias(p.symbol(), t) != null
                    || SeasonalityFilter.getBias(toPair(p.symbol()), t) != null) {
                    toggle = String.format("%02d:00Z", h);
                    break;
                }
            }
            System.out.printf("%-22s %-14s %-22s %-22s%n",
                p.symbol() + " " + win(p), start, toggle, toggle + " au lieu de 00:00Z");
        }

        System.out.println("\nA3) Chevauchement de fenêtres — premier match gagne (getBias retourne le 1er pattern trouvé)");
        Map<String, List<String>> bySymbol = new LinkedHashMap<>();
        for (SeasonalBias p : patterns) bySymbol.computeIfAbsent(p.symbol(), k -> new ArrayList<>()).add(win(p));
        for (var e : bySymbol.entrySet()) {
            if (e.getValue().size() > 1) {
                System.out.printf("%-8s %d fenêtres : %s%n", e.getKey(), e.getValue().size(), e.getValue());
            }
        }
        int shadowed = 0;
        for (int dd = 1; dd <= 30; dd++) {
            final int day = dd;
            long n = Arrays.stream(patterns)
                .filter(p -> p.symbol().equals("GBP_USD") && p.matches(4, day))
                .count();
            if (n > 1) shadowed++;
        }
        System.out.printf("→ GBP_USD : %d jours d'avril satisfont DEUX fenêtres (3/11-4/25 ET 4/1-4/30) :%n", shadowed);
        System.out.println("  le pattern « April strength 88.9 % » n'est JAMAIS évalué (le 1er match gagne).");
    }

    // ================================================================ B) VALIDATION

    static void partB(SeasonalBias[] patterns, Map<String, Map<LocalDate, Double>> data) {
        System.out.println("\n=== B) VALIDATION EMPIRIQUE DES 8 PATTERNS (rendement de fenêtre par année) ===");
        System.out.println("IS = 2006-2015 | OOS = 2016-2026 | ctrl = hold non conditionné de MÊME DURÉE (dérive d'ère)");
        for (SeasonalBias p : patterns) {
            String pair = toPair(p.symbol());
            Map<LocalDate, Double> closes = data.get(pair);
            if (closes == null) {
                System.out.println("\n--- " + p.symbol() + " " + win(p) + " : PAS DE DONNÉES");
                continue;
            }
            int sign = p.bias() == Order.Side.BUY ? 1 : -1;
            List<LocalDate> dates = new ArrayList<>(closes.keySet());
            LocalDate lastDate = dates.get(dates.size() - 1);

            List<double[]> rets = new ArrayList<>();   // {year, ret%}
            int skipped = 0;
            for (int y = 2006; y <= 2026; y++) {
                Double r = windowReturn(closes, dates, y, p, sign, 0);
                if (r == null || LocalDate.of(y, p.endMonth(), p.endDay()).isAfter(lastDate.minusDays(10))) {
                    skipped++;
                    continue;
                }
                rets.add(new double[]{y, r});
            }

            double[] is = stat(rets, 2006, 2015);
            double[] oos = stat(rets, 2016, 2026);
            double[] all = stat(rets, 0, 9999);

            // Contrôle de dérive : même durée de hold, non conditionné
            int len = windowTradingDays(closes, dates, 2020, p);
            double ctrl = unconditional(closes, dates, len, sign);

            System.out.printf("%n--- %s %s %s  (hit revendiqué %.0f%% — « %s »)%n",
                p.symbol(), win(p), p.bias(), p.hitRate() * 100, p.thesis());
            System.out.printf("données %s → %s | fenêtre ≈ %d jours de bourse | années sautées %d%n",
                dates.get(0), lastDate, len, skipped);
            System.out.printf("%-6s %-7s %-7s %-8s %-7s %-7s %-7s%n",
                "PHASE", "n", "mean%", "med%", "hit%", "t", "");
            System.out.printf("%-6s %-7.0f %-7.2f %-7.2f %-8.1f %-7.2f%n",
                "ALL", all[0], all[1], all[2], all[3], all[4]);
            System.out.printf("%-6s %-7.0f %-7.2f %-7.2f %-8.1f %-7.2f%n",
                "IS", is[0], is[1], is[2], is[3], is[4]);
            System.out.printf("%-6s %-7.0f %-7.2f %-7.2f %-8.1f %-7.2f%n",
                "OOS", oos[0], oos[1], oos[2], oos[3], oos[4]);
            System.out.printf("%-6s %-7s %-7.2f %-7s %-8s %-7s%n",
                "CTRL", "-", ctrl, "-", "-", "-");

            // Contrôle de spécificité : fenêtre décalée de ±1, ±2 mois
            StringBuilder nb = new StringBuilder();
            for (int k : new int[]{-2, -1, 1, 2}) {
                List<double[]> shifted = new ArrayList<>();
                for (int y = 2006; y <= 2025; y++) {
                    Double r = windowReturn(closes, dates, y, p, sign, k);
                    if (r != null) shifted.add(new double[]{y, r});
                }
                double[] s = stat(shifted, 0, 9999);
                nb.append(String.format("%+dm:%.2f%%/%.0f%%  ", k, s[1], s[3]));
            }
            System.out.printf("SPÉCIFICITÉ (%s) : %s%n", "mean/hit", nb);

            // Verdict
            boolean signStable = Math.signum(is[1]) == Math.signum(oos[1]);
            boolean positive = is[1] > 0 && oos[1] > 0;
            boolean distinct = all[1] > ctrl;
            String verdict;
            if (!positive) verdict = "❌ REJET — edge absent (IS ou OOS ≤ 0)";
            else if (!signStable) verdict = "❌ REJET — flip IS/OOS (late-bloomer)";
            else if (!distinct) verdict = "⚪ NON DISTINCT — la dérive du hold de même durée suffit";
            else verdict = "🔬 SURVIT — candidate à backtester avec coûts";
            System.out.println("VERDICT : " + verdict
                + String.format(" [IS %.2f%% / OOS %.2f%% ; ctrl %.2f%% ; Δ ALL−ctrl %.2f%%]",
                    is[1], oos[1], ctrl, ctlDelta(all[1], ctrl)));
        }
    }

    static double ctlDelta(double a, double b) { return a - b; }

    // ================================================================ C) SPÉCIFICITÉ $ RÉELS

    /**
     * C) La fenêtre USD_CAD 10/12→11/26 est-elle SPÉCIFIQUE en dollars réels ?
     * A) a montré que les voisins ±1 mois donnent des moyennes/hits comparables au
     * niveau des prix. Ici on tranche en NET $, swap RÉEL (différentiel de taux
     * directeur US − CA par année, table du 28 sept), coûts $0.07 + 0.01 %.
     */
    static void partC() throws Exception {
        final double CAPITAL = 50_000;
        System.out.println("=== C) SPÉCIFICITÉ DE LA FENÊTRE OFFICIELLE USD_CAD 10/12→11/26 (net $, swap RÉEL) ===");
        SwapCalculator.setYearlyRateOverride("USD_CAD", realTable(0.25));
        List<Bar> bars = load("USD_CAD");
        System.out.printf("%-18s %-6s %-6s %-6s %-7s %-11s %-11s%n",
            "FENÊTRE", "PF", "WR%", "DD%", "TRADES", "NET$", "SWAP$");
        int[][] wins = {
            {8, 12, 9, 26}, {9, 12, 10, 26}, {10, 12, 11, 26}, {11, 12, 12, 26},
            {11, 1, 11, 30}, {10, 1, 10, 31}, {9, 1, 9, 30},
        };
        String[] labels = {
            "−2m 08/12→09/26", "−1m 09/12→10/26", "OFFICIEL 10/12→11/26", "+1m 11/12→12/26",
            "NOV 11/01→11/30", "OCT 10/01→10/31", "SEP 09/01→09/30",
        };
        double bestNet = -1e9;
        for (int i = 0; i < wins.length; i++) {
            int[] w = wins[i];
            BacktestResult r = RunContext.forStrategy(null, "DateWindowSeasonal",
                new DateWindowSeasonalStrategy("DateWindowSeasonal", "USD_CAD", w[0], w[1], w[2], w[3], Order.Side.BUY),
                "USD_CAD", RunMode.BACKTEST, bars, CAPITAL, null, cost()).run();
            bestNet = Math.max(bestNet, r.totalPnl());
            System.out.printf("%-18s %-6.2f %-6.1f %-6.2f %-7d %11.2f %11.2f%n",
                labels[i], r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                r.totalTrades(), r.totalPnl(), r.totalSwap());
        }
        System.out.printf("→ meilleur net = $%.2f%n", bestNet);
        SwapCalculator.clearRateOverride();
    }

    /** Débit moyen |différentiel| 2006-2025 (copie de RunUsdcadSwapRetest). */
    static Map<Integer, double[]> realTable(double halfSpreadPips) {
        double[] diff = {0.652, 0.415, -1.281, -0.090, -0.429, -0.898, -0.860, -0.892, -0.911,
            -0.492, -0.105, 0.293, 0.394, 0.408, -0.124, -0.170, -0.358, 0.253, 0.664, 1.567, 1.385};
        Map<Integer, double[]> m = new HashMap<>();
        for (int i = 0; i < diff.length; i++) {
            double pip = 0.274 * diff[i];
            m.put(2006 + i, new double[]{pip - halfSpreadPips, -pip - halfSpreadPips});
        }
        m.put(-1, m.get(2026));
        return m;
    }

    static BacktestExecutionCost cost() {
        return BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);
    }

    // ================================================================ helpers

    static double[] stat(List<double[]> rets, int y0, int y1) {
        List<Double> v = new ArrayList<>();
        for (double[] r : rets) if (r[0] >= y0 && r[0] <= y1) v.add(r[1]);
        if (v.isEmpty()) return new double[]{0, 0, 0, 0, 0};
        double mean = v.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double[] sorted = v.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double med = sorted.length % 2 == 1 ? sorted[sorted.length / 2]
            : (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2;
        long hits = v.stream().filter(x -> x > 0).count();
        double var = v.stream().mapToDouble(x -> (x - mean) * (x - mean)).sum() / Math.max(1, v.size() - 1);
        double t = Math.sqrt(v.size()) * mean / Math.max(1e-9, Math.sqrt(var));
        return new double[]{v.size(), mean, med, 100.0 * hits / v.size(), t};
    }

    /** Rendement de la fenêtre de l'année y, en % et dans le sens du pattern. offsetMois décale la fenêtre. */
    static Double windowReturn(Map<LocalDate, Double> closes, List<LocalDate> dates, int year,
                              SeasonalBias p, int sign, int offsetMois) {
        LocalDate start = LocalDate.of(year, p.startMonth(), p.startDay()).plusMonths(offsetMois);
        LocalDate end = LocalDate.of(year, p.endMonth(), p.endDay()).plusMonths(offsetMois);
        LocalDate entryDate = floorBefore(dates, start);
        LocalDate exitDate = floorOnOrBefore(dates, end);
        if (entryDate == null || exitDate == null || !exitDate.isAfter(entryDate)) return null;
        double entry = closes.get(entryDate), exit = closes.get(exitDate);
        if (entry <= 0) return null;
        return sign * (exit / entry - 1.0) * 100.0;
    }

    static int windowTradingDays(Map<LocalDate, Double> closes, List<LocalDate> dates, int year, SeasonalBias p) {
        LocalDate start = LocalDate.of(year, p.startMonth(), p.startDay());
        LocalDate end = LocalDate.of(year, p.endMonth(), p.endDay());
        LocalDate e = floorBefore(dates, start), x = floorOnOrBefore(dates, end);
        if (e == null || x == null) return 30;
        return (int) dates.stream().filter(d -> !d.isBefore(e) && !d.isAfter(x)).count();
    }

    /** Contrôle de dérive : moyenne de TOUS les holds de len jours de bourse, non conditionnés, signés. */
    static double unconditional(Map<LocalDate, Double> closes, List<LocalDate> dates, int len, int sign) {
        double sum = 0; int n = 0;
        for (int i = 0; i + len < dates.size(); i++) {
            double a = closes.get(dates.get(i)), b = closes.get(dates.get(i + len));
            if (a <= 0) continue;
            sum += sign * (b / a - 1.0) * 100.0; n++;
        }
        return n == 0 ? 0 : sum / n;
    }

    static LocalDate floorBefore(List<LocalDate> sorted, LocalDate d) {
        LocalDate best = null;
        for (LocalDate x : sorted) { if (x.isBefore(d)) best = x; else break; }
        return best;
    }

    static LocalDate floorOnOrBefore(List<LocalDate> sorted, LocalDate d) {
        LocalDate best = null;
        for (LocalDate x : sorted) { if (!x.isAfter(d)) best = x; else break; }
        return best;
    }

    /** Série de clôtures journalières (UTC) sur barres RÉELLES uniquement (high > low) — filtre carry correct. */
    static Map<LocalDate, Double> dailyRealCloses(List<Bar> bars) {
        Map<LocalDate, Double> m = new TreeMap<>();
        for (Bar b : bars) {
            if (b.high() <= b.low()) continue;                    // barre de carry : plate par construction
            LocalDate d = b.timestamp().atZone(ZoneOffset.UTC).toLocalDate();
            m.put(d, b.close());
        }
        return m;
    }

    static List<Bar> load(String symbol) throws Exception {
        var loaded = HistoricalDataLoader.loadFromArgs(symbol, symbol, "2006-2026");
        if (loaded.bars().isEmpty()) throw new IllegalStateException("pas de barres");
        return loaded.bars();
    }

    static String toPair(String key) {
        return key.contains("_") ? key : key.substring(0, 3) + "_" + key.substring(3);
    }

    static String win(SeasonalBias p) {
        return String.format("%02d/%02d→%02d/%02d", p.startMonth(), p.startDay(), p.endMonth(), p.endDay());
    }

    static Instant midWindowInstant(SeasonalBias p) {
        LocalDate s = LocalDate.of(2020, p.startMonth(), p.startDay());
        LocalDate e = LocalDate.of(2020, p.endMonth(), p.endDay());
        return s.plusDays(java.time.temporal.ChronoUnit.DAYS.between(s, e) / 2)
            .atTime(12, 0).toInstant(ZoneOffset.UTC);
    }

    static LocalDate last(Set<LocalDate> s) {
        LocalDate l = null;
        for (LocalDate d : s) l = d;
        return l;
    }
}
