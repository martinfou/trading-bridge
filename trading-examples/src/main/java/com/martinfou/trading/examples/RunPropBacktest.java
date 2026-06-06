package com.martinfou.trading.examples;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.LotSizing;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.prop.PropStrategyCatalog;

import java.io.IOException;
import java.util.List;

/**
 * @deprecated Use {@link RunBacktest} instead. Retained for {@code --all} prop suite runs.
 */
@Deprecated
public class RunPropBacktest {

    public static void main(String[] args) {
        if (args.length == 0 || args[0].equals("--help")) {
            RunBacktest.main(new String[] {"--help"});
            return;
        }

        if (args[0].equals("--list")) {
            RunBacktest.main(new String[] {"--list"});
            return;
        }

        if (args[0].equals("--all")) {
            runAll(args);
            return;
        }

        RunBacktest.main(args);
    }

    private static void runAll(String[] args) {
        double capital = LotSizing.DEFAULT_STARTING_CAPITAL;
        boolean sample = args.length >= 2 && args[1].equals("--sample");

        SuiteDataSpec spec;
        try {
            spec = sample ? null : parseSuiteDataArgs(args);
        } catch (IllegalArgumentException ex) {
            System.err.println(ex.getMessage());
            System.err.println("Usage: --all | --all [--sample] | --all 2025 | --all GBP_JPY 2025");
            System.exit(1);
            return;
        }

        if (sample) {
            System.out.printf("=== Prop Strategy Suite (capital $%,.0f, sample bars) ===%n%n", capital);
        } else if (spec.useFullHistory()) {
            System.out.printf(
                "=== Prop Strategy Suite (capital $%,.0f, full available history, default pair each) ===%n%n",
                capital);
        } else if (spec.unifiedSymbol() == null) {
            System.out.printf(
                "=== Prop Strategy Suite (capital $%,.0f, year %s, default pair each) ===%n%n",
                capital, spec.yearSpec());
        } else {
            System.out.printf(
                "=== Prop Strategy Suite (capital $%,.0f, %s %s, all strategies on that pair) ===%n%n",
                capital, spec.unifiedSymbol(), spec.yearSpec());
        }

        List<Bar> unifiedBars = null;
        String unifiedSource = null;
        if (spec != null && spec.unifiedSymbol() != null) {
            try {
                if (spec.useFullHistory()) {
                    var loaded = HistoricalDataLoader.loadAllAvailable(spec.unifiedSymbol());
                    unifiedBars = loaded.bars();
                    unifiedSource = loaded.source();
                } else {
                    unifiedBars = HistoricalDataLoader.loadYearSpec(
                        spec.unifiedSymbol(), spec.yearSpec(), HistoricalDataLoader.DEFAULT_BARS_DIR);
                    unifiedSource = spec.unifiedSymbol() + " " + spec.yearSpec();
                }
            } catch (IOException e) {
                System.err.println("Failed to load data: " + e.getMessage());
                System.exit(1);
                return;
            }
            if (unifiedBars.isEmpty()) {
                System.err.println("No bars loaded for " + spec.unifiedSymbol());
                System.exit(1);
            }
        }

        for (String key : PropStrategyCatalog.all().keySet()) {
            String sym = spec != null && spec.unifiedSymbol() != null
                ? spec.unifiedSymbol()
                : PropStrategyCatalog.defaultSymbol(key);
            List<Bar> bars;
            String dataLabel;
            try {
                if (sample) {
                    bars = RunBacktest.generateSampleBars(sym, 3000);
                    dataLabel = "sample";
                } else if (unifiedBars != null) {
                    bars = unifiedBars;
                    dataLabel = unifiedSource;
                } else if (spec.useFullHistory()) {
                    var loaded = HistoricalDataLoader.loadAllAvailable(sym);
                    bars = loaded.bars();
                    dataLabel = sym + " " + loaded.source();
                } else {
                    bars = HistoricalDataLoader.loadYearSpec(
                        sym, spec.yearSpec(), HistoricalDataLoader.DEFAULT_BARS_DIR);
                    dataLabel = sym + " " + spec.yearSpec();
                }
            } catch (Exception e) {
                System.err.println("Failed to load data for " + key + ": " + e.getMessage());
                continue;
            }

            if (bars.isEmpty()) {
                System.err.println("Skipping " + key + " — no bars for " + sym);
                continue;
            }

            Strategy strategy = PropStrategyCatalog.create(key, sym);
            System.out.printf("--- %s (%s, %s, %,d bars) ---%n",
                key, sym, dataLabel, bars.size());
            RunBacktest.runStrategy(strategy, bars, capital).printSummary();
            System.out.println();
        }
    }

    /**
     * Parses args after {@code --all}.
     * <ul>
     *   <li>(none) — each strategy on its default symbol, all indexed years</li>
     *   <li>{@code 2025} or {@code 2006-2025} — default symbol, that year range</li>
     *   <li>{@code GBP_JPY 2025} — all strategies on that symbol</li>
     * </ul>
     */
    static SuiteDataSpec parseSuiteDataArgs(String[] args) {
        String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
        if (rest.length == 0) {
            return SuiteDataSpec.allHistory();
        }
        if (rest.length == 1) {
            if (!looksLikeYearSpec(rest[0])) {
                throw new IllegalArgumentException("Expected year (e.g. 2025), got: " + rest[0]);
            }
            return SuiteDataSpec.yearOnly(rest[0]);
        }
        if (looksLikeYearSpec(rest[0])) {
            throw new IllegalArgumentException("Ambiguous args — use SYMBOL YEAR (e.g. GBP_JPY 2025)");
        }
        if (!looksLikeYearSpec(rest[1])) {
            throw new IllegalArgumentException("Expected year after symbol, got: " + rest[1]);
        }
        String symbol = rest[0].contains("_") ? rest[0] : pairToSymbol(rest[0]);
        return SuiteDataSpec.unified(symbol, rest[1]);
    }

    private static boolean looksLikeYearSpec(String token) {
        return token.matches("\\d{4}") || token.matches("\\d{4}-\\d{4}");
    }

    private static String pairToSymbol(String pair) {
        return pair.replace("/", "_").toUpperCase();
    }

    record SuiteDataSpec(String yearSpec, String unifiedSymbol, boolean useFullHistory) {
        static SuiteDataSpec allHistory() {
            return new SuiteDataSpec(null, null, true);
        }

        static SuiteDataSpec yearOnly(String yearSpec) {
            return new SuiteDataSpec(yearSpec, null, false);
        }

        static SuiteDataSpec unified(String unifiedSymbol, String yearSpec) {
            return new SuiteDataSpec(yearSpec, unifiedSymbol, false);
        }
    }
}
