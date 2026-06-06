package com.martinfou.trading.tui;

import com.fasterxml.jackson.databind.JsonNode;
import org.jline.reader.LineReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Step-by-step backtest wizard (strategy → symbol → date range). */
final class TuiInteractiveBacktest {

    private TuiInteractiveBacktest() {}

    static List<String> run(
        TuiCommandHandler handler,
        ControlPlaneClient client,
        LineReader reader,
        Consumer<String> liveOutput
    ) throws IOException, InterruptedException {
        List<String> transcript = new ArrayList<>();
        say(transcript, liveOutput, "Interactive backtest — press Ctrl+C to cancel");

        boolean dataCatalogApi = probeDataCatalog(client, transcript, liveOutput);

        JsonNode strategiesRoot = client.listStrategies();
        List<StrategyOption> strategies = parseStrategies(strategiesRoot);
        if (strategies.isEmpty()) {
            say(transcript, liveOutput, "No strategies returned from control plane.");
            return transcript;
        }

        say(transcript, liveOutput, "");
        say(transcript, liveOutput, "Strategies:");
        for (int i = 0; i < strategies.size(); i++) {
            StrategyOption s = strategies.get(i);
            say(transcript, liveOutput,
                "  " + (i + 1) + ". " + s.id() + "  (" + s.family() + ", default " + s.defaultSymbol() + ")");
        }

        List<String> strategyIds = strategies.stream().map(StrategyOption::id).toList();
        String strategyId = TuiPrompts.choose(
            reader, "Strategy [# or id]", strategyIds, null, liveOutput);
        StrategyOption chosen = strategies.stream()
            .filter(s -> s.id().equals(strategyId))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("resolved strategy missing: " + strategyId));
        say(transcript, liveOutput, "→ Strategy: " + strategyId);

        Set<String> symbolOptions = new LinkedHashSet<>();
        symbolOptions.add(chosen.defaultSymbol());
        if (dataCatalogApi) {
            JsonNode symbolsRoot = fetchDataSymbols(client, transcript, liveOutput);
            if (symbolsRoot.path("configured").asBoolean(false) && symbolsRoot.has("symbols")) {
                for (JsonNode node : symbolsRoot.get("symbols")) {
                    symbolOptions.add(node.asText());
                }
            }
        }

        say(transcript, liveOutput, "");
        say(transcript, liveOutput, "Symbols / assets:");
        List<String> symbolList = List.copyOf(symbolOptions);
        int defaultSymbolIndex = TuiSelection.indexOfIgnoreCase(symbolList, chosen.defaultSymbol());
        for (int i = 0; i < symbolList.size(); i++) {
            String marker = (i + 1) == defaultSymbolIndex ? " *" : "";
            say(transcript, liveOutput, "  " + (i + 1) + ". " + symbolList.get(i) + marker);
        }
        if (defaultSymbolIndex > 0) {
            say(transcript, liveOutput, "  (* = strategy default — press Enter to accept)");
        }

        String symbol = TuiPrompts.choose(
            reader,
            "Symbol [# or " + chosen.defaultSymbol() + "]",
            symbolList,
            chosen.defaultSymbol(),
            liveOutput);
        say(transcript, liveOutput, "→ Symbol: " + symbol);

        JsonNode availability = dataCatalogApi
            ? fetchDataAvailability(client, symbol, transcript, liveOutput)
            : client.emptyJsonObject();
        say(transcript, liveOutput, "");
        say(transcript, liveOutput, describeAvailability(availability));

        Map<String, Object> barsSource;
        String dataLabel;
        if (!availability.path("configured").asBoolean(false)
            || availability.path("years").isEmpty()) {
            say(transcript, liveOutput, "No historical files found — using synthetic sample data.");
            barsSource = Map.of("type", "sample", "count", 500);
            dataLabel = "sample 500 bars";
        } else {
            String suggested = availability.path("suggestedRange").asText("");
            say(transcript, liveOutput, "");
            say(transcript, liveOutput, "Data options:");
            say(transcript, liveOutput,
                "  1. Full available range" + (suggested.isBlank() ? "" : " (" + suggested + ")"));
            say(transcript, liveOutput, "  2. Single year");
            say(transcript, liveOutput, "  3. Custom range (e.g. 2006-2012)");
            say(transcript, liveOutput, "  4. Synthetic sample (500 bars, no download)");
            say(transcript, liveOutput, "  5. CI subset (data/ci, fast)");

            String dataChoice = TuiPrompts.choose(
                reader, "Data option", List.of("1", "2", "3", "4", "5"), "1", liveOutput);
            switch (dataChoice) {
                case "2" -> {
                    String year = TuiPrompts.readLine(
                        reader, "Year", String.valueOf(availability.path("maxYear").asInt()));
                    if (year.isBlank()) {
                        year = String.valueOf(availability.path("maxYear").asInt());
                    }
                    barsSource = Map.of("type", "year", "year", year);
                    dataLabel = symbol + " " + year;
                }
                case "3" -> {
                    String range = TuiPrompts.readLine(reader, "Year range", suggested);
                    if (range.isBlank()) {
                        range = suggested;
                    }
                    barsSource = Map.of("type", "year", "year", range);
                    dataLabel = symbol + " " + range;
                }
                case "4" -> {
                    barsSource = Map.of("type", "sample", "count", 500);
                    dataLabel = "sample 500 bars";
                }
                case "5" -> {
                    barsSource = Map.of("type", "ci");
                    dataLabel = "ci subset";
                }
                default -> {
                    barsSource = Map.of("type", "year", "year", suggested);
                    dataLabel = symbol + " " + suggested;
                }
            }
        }

        say(transcript, liveOutput, "");
        say(transcript, liveOutput, "Sizing:");
        double capital = TuiPrompts.readPositiveDouble(
            reader, "Starting capital ($)", TuiDefaults.STARTING_CAPITAL, liveOutput);
        double lotSize = TuiPrompts.readPositiveDouble(
            reader, "Lot size (standard lots)", TuiDefaults.LOT_SIZE, liveOutput);
        say(transcript, liveOutput,
            "→ Capital: $" + String.format("%,.0f", capital) + "  Lot: " + lotSize);

        say(transcript, liveOutput, "");
        say(transcript, liveOutput, "Starting backtest " + strategyId + " on " + dataLabel + " …");
        return handler.runBacktest(strategyId, symbol, barsSource, dataLabel, capital, lotSize);
    }

    private static void say(List<String> transcript, Consumer<String> liveOutput, String line) {
        transcript.add(line);
        if (liveOutput != null) {
            liveOutput.accept(line);
        }
    }

    private static boolean probeDataCatalog(
        ControlPlaneClient client,
        List<String> transcript,
        Consumer<String> liveOutput
    ) {
        try {
            JsonNode health = client.health();
            if (health.path("dataCatalog").asBoolean(false)) {
                return true;
            }
        } catch (IOException | InterruptedException ignored) {
            // fall through to probe
        }
        try {
            client.listDataSymbols();
            return true;
        } catch (IOException | InterruptedException e) {
            say(transcript, liveOutput,
                "Control plane is missing /api/data/* (historical catalog). Restart it from this repo:");
            say(transcript, liveOutput, "  ./scripts/run-tui.sh --with-control-plane");
            say(transcript, liveOutput,
                "  (or stop the old ControlPlaneMain, then mvn -pl trading-runtime -am package && restart)");
            say(transcript, liveOutput,
                "Wizard will continue with strategy default symbol; choose sample or type a year manually.");
            return false;
        }
    }

    private static JsonNode fetchDataSymbols(
        ControlPlaneClient client,
        List<String> transcript,
        Consumer<String> liveOutput
    ) {
        try {
            return client.listDataSymbols();
        } catch (IOException | InterruptedException e) {
            warnDataApi(transcript, liveOutput, e);
            return client.emptyJsonObject();
        }
    }

    private static JsonNode fetchDataAvailability(
        ControlPlaneClient client,
        String symbol,
        List<String> transcript,
        Consumer<String> liveOutput
    ) {
        try {
            return client.dataAvailability(symbol);
        } catch (IOException | InterruptedException e) {
            warnDataApi(transcript, liveOutput, e);
            return client.emptyJsonObject();
        }
    }

    private static void warnDataApi(List<String> transcript, Consumer<String> liveOutput, Exception e) {
        say(transcript, liveOutput, "Note: historical data API unavailable — " + e.getMessage());
        say(transcript, liveOutput, "  Rebuild and restart control plane: ./scripts/run-tui.sh --with-control-plane");
        say(transcript, liveOutput, "  Continuing with strategy default symbol and sample/typed data only.");
    }

    private static String describeAvailability(JsonNode availability) {
        if (!availability.path("configured").asBoolean(false)) {
            return "Historical data: repo root not found on control plane host."
                + " Download with ./scripts/download-data.sh or set TRADING_BRIDGE_ROOT.";
        }
        if (availability.path("years").isEmpty()) {
            return "Historical data: none on disk for this symbol."
                + " Run: ./scripts/download-data.sh --list --tf h1";
        }
        int count = availability.path("yearCount").asInt();
        String range = availability.path("suggestedRange").asText();
        StringBuilder line = new StringBuilder("Historical data: ")
            .append(count).append(" year(s) available");
        if (!range.isBlank()) {
            line.append(", range ").append(range);
        }
        JsonNode gaps = availability.get("gaps");
        if (gaps != null && gaps.isArray() && !gaps.isEmpty()) {
            line.append(" (gaps: ");
            for (int i = 0; i < gaps.size() && i < 8; i++) {
                if (i > 0) {
                    line.append(", ");
                }
                line.append(gaps.get(i).asInt());
            }
            if (gaps.size() > 8) {
                line.append(", …");
            }
            line.append(")");
        }
        return line.toString();
    }

    private static List<StrategyOption> parseStrategies(JsonNode root) {
        List<StrategyOption> list = new ArrayList<>();
        for (JsonNode item : root.get("strategies")) {
            list.add(new StrategyOption(
                item.get("id").asText(),
                item.path("family").asText(""),
                item.path("defaultSymbol").asText("EUR_USD")));
        }
        return list;
    }

    private record StrategyOption(String id, String family, String defaultSymbol) {}
}
