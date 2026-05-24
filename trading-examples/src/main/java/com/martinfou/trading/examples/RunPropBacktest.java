package com.martinfou.trading.examples;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.prop.PropStrategyCatalog;

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
        double capital = 100_000;
        boolean sample = args.length >= 2 && args[1].equals("--sample");

        System.out.println("=== Prop Strategy Suite Backtest ===\n");
        for (String key : PropStrategyCatalog.all().keySet()) {
            String sym = PropStrategyCatalog.defaultSymbol(key);
            List<Bar> bars;
            try {
                if (sample) {
                    bars = RunBacktest.generateSampleBars(sym, 3000);
                } else if (args.length >= 2 && !args[1].startsWith("--")) {
                    var loaded = HistoricalDataLoader.loadFromArgs(sym,
                        java.util.Arrays.copyOfRange(args, 1, args.length));
                    bars = loaded.bars();
                } else {
                    System.err.println("--all requires --sample or SYMBOL YEAR (e.g. EUR_USD 2012)");
                    System.exit(1);
                    return;
                }
            } catch (Exception e) {
                System.err.println("Failed to load data: " + e.getMessage());
                System.exit(1);
                return;
            }

            if (bars.isEmpty()) continue;

            Strategy strategy = PropStrategyCatalog.create(key, sym);
            System.out.printf("--- %s (%s) ---%n", key, sym);
            RunBacktest.runStrategy(strategy, bars, capital).printSummary();
            System.out.println();
        }
    }
}
