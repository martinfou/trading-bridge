package com.martinfou.trading.data;

import com.martinfou.trading.core.Bar;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Data proxy that reads from local binary .bars files.
 * Used by the backtester — same {@link DataProxy} interface as live trading,
 * but reads from disk instead of OANDA API.
 *
 * <p>File naming convention: {@code <dataDir>/<INSTRUMENT>_<TIMEFRAME>.bars}
 * <br>Example: {@code data/historical/bars/EUR_USD_H1.bars}</p>
 *
 * <p>The proxy returns bars in newest-first order (same as
 * {@link OandaPriceClient#getCandles}) so the backtest engine
 * receives data in the identical format regardless of source.</p>
 */
public class LocalDataProxy implements DataProxy {

    private final Path dataDir;

    public LocalDataProxy(Path dataDir) {
        this.dataDir = dataDir;
    }

    public LocalDataProxy(String dataDir) {
        this(Paths.get(dataDir));
    }

    @Override
    public List<Bar> getCandles(String instrument, String granularity, int count) throws Exception {
        // Build filename: e.g. "EUR_USD_H1.bars" or "GBP_JPY_M15.bars"
        // Normalize instrument: "GBP/JPY" → "GBP_JPY"
        String normSymbol = instrument.replace('/', '_');
        // Normalize granularity to short form
        String normGran = normalizeGranularity(granularity);
        String fileName = normSymbol + "_" + normGran + ".bars";
        Path barFile = dataDir.resolve(fileName);

        if (!Files.exists(barFile)) {
            throw new java.io.FileNotFoundException(
                "Local proxy: .bars file not found: " + barFile.toAbsolutePath()
                + "\n  Convert CSV first: BarStore --convert <csv-dir> <bars-dir>");
        }

        // Use filename stem as symbol inside BarStore
        String stem = normSymbol + "_" + normGran;
        var store = new BarStore(stem, normGran, dataDir);
        store.open();

        int totalBars = store.count();
        int take = Math.min(count, totalBars);

        // OANDA returns candles newest-first; match that convention
        List<Bar> bars = new ArrayList<>(take);
        for (int i = totalBars - take; i < totalBars; i++) {
            bars.add(store.get(i));
        }

        return bars;
    }

    @Override
    public String tag() {
        return "local → " + dataDir;
    }

    /** Total bars available for this instrument/granularity (for display). */
    public int totalBars(String instrument, String granularity) throws Exception {
        String normSymbol = instrument.replace('/', '_');
        String normGran = normalizeGranularity(granularity);
        String fileName = normSymbol + "_" + normGran + ".bars";
        Path barFile = dataDir.resolve(fileName);
        if (!Files.exists(barFile)) return 0;
        var store = new BarStore(normSymbol + "_" + normGran, normGran, dataDir);
        store.open();
        return store.count();
    }

    /** Converts various timeframe names to the short form used in filenames. */
    private static String normalizeGranularity(String g) {
        if (g == null) return "H1";
        return switch (g.toUpperCase()) {
            case "M1", "M5", "M15", "M30" -> g.toUpperCase();
            case "H", "H1", "HOUR", "60"  -> "H1";
            case "H4", "240"              -> "H4";
            case "D", "D1", "DAY", "1440" -> "D";
            case "W", "W1", "WEEK"        -> "W";
            default -> {
                System.err.println("WARNING: Unknown granularity '" + g + "', defaulting to H1");
                yield "H1";
            }
        };
    }
}
