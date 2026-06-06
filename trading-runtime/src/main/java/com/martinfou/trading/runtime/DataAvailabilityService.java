package com.martinfou.trading.runtime;

import com.martinfou.trading.data.HistoricalDataCatalog;
import com.martinfou.trading.data.HistoricalDataLoader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exposes local historical data coverage for the control plane and TUI. */
public final class DataAvailabilityService {

    public Map<String, Object> listSymbols() throws IOException {
        Path root = EventStoreConfig.findRepoRoot();
        if (root == null) {
            return Map.of(
                "configured", false,
                "message", "Repo root not found (set TRADING_BRIDGE_ROOT or run from repo)",
                "symbols", List.of());
        }
        Path barsDir = root.resolve(HistoricalDataLoader.DEFAULT_BARS_DIR);
        Path csvDir = root.resolve(HistoricalDataLoader.DEFAULT_CSV_DIR);
        List<String> symbols = HistoricalDataCatalog.listSymbols(barsDir, csvDir);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("configured", true);
        response.put("repoRoot", root.toString());
        response.put("barsDir", barsDir.toString());
        response.put("csvDir", csvDir.toString());
        response.put("symbols", symbols);
        return Map.copyOf(response);
    }

    public Map<String, Object> availability(String symbol) throws IOException {
        Path root = EventStoreConfig.findRepoRoot();
        if (root == null) {
            return Map.of(
                "configured", false,
                "symbol", symbol,
                "message", "Repo root not found");
        }
        Path barsDir = root.resolve(HistoricalDataLoader.DEFAULT_BARS_DIR);
        Path csvDir = root.resolve(HistoricalDataLoader.DEFAULT_CSV_DIR);
        HistoricalDataCatalog.SymbolAvailability availability =
            HistoricalDataCatalog.availability(symbol, barsDir, csvDir);
        Map<String, Object> response = new LinkedHashMap<>(HistoricalDataCatalog.toMap(availability));
        response.put("configured", true);
        response.put("repoRoot", root.toString());
        if (availability.years().isEmpty()) {
            response.put("message", "No local H1 data for " + symbol
                + " — run ./scripts/download-data.sh --list --tf h1");
        }
        return Map.copyOf(response);
    }
}
