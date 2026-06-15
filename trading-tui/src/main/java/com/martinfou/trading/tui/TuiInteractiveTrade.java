package com.martinfou.trading.tui;

import com.fasterxml.jackson.databind.JsonNode;
import org.jline.reader.LineReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Step-by-step trading run wizard (strategy → broker account → mode → symbol → capital → lot size).
 */
final class TuiInteractiveTrade {

    private TuiInteractiveTrade() {}

    static List<String> run(
        TuiCommandHandler handler,
        ControlPlaneClient client,
        LineReader reader,
        Consumer<String> liveOutput
    ) throws IOException, InterruptedException {
        List<String> transcript = new ArrayList<>();
        say(transcript, liveOutput, "Interactive trade run setup — press Ctrl+C to cancel");

        // 1. Get strategies
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
            String deployed = s.deployedMode() != null && !s.deployedMode().isBlank() ? " [mode=" + s.deployedMode() + "]" : "";
            say(transcript, liveOutput,
                "  " + (i + 1) + ". " + s.id() + "  (" + s.family() + ", default " + s.defaultSymbol() + ")" + deployed);
        }

        List<String> strategyIds = strategies.stream().map(StrategyOption::id).toList();
        String strategyId = TuiPrompts.choose(
            reader, "Strategy [# or id]", strategyIds, null, liveOutput);
        StrategyOption chosenStrategy = strategies.stream()
            .filter(s -> s.id().equals(strategyId))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("resolved strategy missing: " + strategyId));
        say(transcript, liveOutput, "→ Strategy: " + strategyId);

        // 2. Get broker accounts
        JsonNode accountsRoot = client.listBrokerAccounts();
        List<AccountOption> accounts = parseAccounts(accountsRoot);

        say(transcript, liveOutput, "");
        say(transcript, liveOutput, "Broker accounts:");
        for (int i = 0; i < accounts.size(); i++) {
            AccountOption a = accounts.get(i);
            String status = a.configured() ? "configured" : "not configured";
            say(transcript, liveOutput, "  " + (i + 1) + ". " + a.id() + " (" + a.provider() + ", " + status + ")");
        }
        say(transcript, liveOutput, "  " + (accounts.size() + 1) + ". None (No broker, stub mode)");

        List<String> accountIds = new ArrayList<>(accounts.stream().map(AccountOption::id).toList());
        accountIds.add("None");

        // Pre-select default account from strategy's deployment if present
        String defaultAccount = chosenStrategy.brokerAccountId();
        if (defaultAccount == null || defaultAccount.isBlank()) {
            if (accountIds.contains("oanda-paper")) {
                defaultAccount = "oanda-paper";
            } else if (!accounts.isEmpty()) {
                defaultAccount = accounts.get(0).id();
            } else {
                defaultAccount = "None";
            }
        }

        String chosenAccountId = TuiPrompts.choose(
            reader, "Broker account [# or " + defaultAccount + "]", accountIds, defaultAccount, liveOutput);
        say(transcript, liveOutput, "→ Broker account: " + chosenAccountId);

        // 3. Select Mode
        String defaultMode = chosenStrategy.deployedMode();
        if (defaultMode == null || defaultMode.isBlank() || defaultMode.equals("—")) {
            defaultMode = "PAPER";
        }
        String mode = TuiPrompts.choose(
            reader, "Mode (PAPER or LIVE)", List.of("PAPER", "LIVE"), defaultMode, liveOutput);
        say(transcript, liveOutput, "→ Mode: " + mode);

        // 4. Select Symbol
        String symbol = TuiPrompts.readLine(reader, "Symbol", chosenStrategy.defaultSymbol());
        if (symbol.isBlank()) {
            symbol = chosenStrategy.defaultSymbol();
        }
        say(transcript, liveOutput, "→ Symbol: " + symbol);

        // 5. Sizing
        say(transcript, liveOutput, "");
        say(transcript, liveOutput, "Sizing:");
        String capInput = TuiPrompts.readLine(reader, "Starting capital ($) (Enter to auto-fetch account equity)", "");
        Double capital = null;
        if (!capInput.isBlank()) {
            capital = TuiPrompts.parsePositiveDouble(capInput, "Starting capital");
        }
        double lotSize = TuiPrompts.readPositiveDouble(reader, "Lot size (standard lots)", 1.0, liveOutput);
        say(transcript, liveOutput,
            "→ Capital: " + (capital != null ? "$" + String.format("%,.0f", capital) : "Auto-fetch") + "  Lot: " + lotSize);

        // Determine execution label
        String executionLabel = null;
        String finalAccountId = chosenAccountId;
        if ("None".equalsIgnoreCase(chosenAccountId)) {
            executionLabel = "PAPER_STUB";
            chosenAccountId = null; // null maps to no broker
        } else {
            String provider = accounts.stream()
                .filter(a -> a.id().equals(finalAccountId))
                .map(AccountOption::provider)
                .findFirst()
                .orElse("OANDA");
            if ("PAPER".equalsIgnoreCase(mode)) {
                executionLabel = "OANDA".equalsIgnoreCase(provider) ? "PAPER_OANDA" : "PAPER_IBKR";
            } else {
                executionLabel = "OANDA".equalsIgnoreCase(provider) ? "LIVE_OANDA" : "LIVE_IBKR";
            }
        }

        say(transcript, liveOutput, "");
        say(transcript, liveOutput, "Starting trading run for " + strategyId + " (" + mode + ") on account: " + (chosenAccountId != null ? chosenAccountId : "None") + " …");

        try {
            JsonNode response = client.startRun(strategyId, mode, symbol, chosenAccountId, executionLabel, capital, lotSize);
            String runId = response.get("runId").asText();
            String status = response.get("status").asText();
            say(transcript, liveOutput, "✓ Successfully started run: " + runId + " (status: " + status + ")");
        } catch (Exception e) {
            say(transcript, liveOutput, "✗ Failed to start run: " + e.getMessage());
        }

        return transcript;
    }

    private static void say(List<String> transcript, Consumer<String> liveOutput, String line) {
        transcript.add(line);
        if (liveOutput != null) {
            liveOutput.accept(line);
        }
    }

    private static List<StrategyOption> parseStrategies(JsonNode root) {
        List<StrategyOption> list = new ArrayList<>();
        for (JsonNode item : root.get("strategies")) {
            list.add(new StrategyOption(
                item.get("id").asText(),
                item.path("family").asText(""),
                item.path("defaultSymbol").asText("EUR_USD"),
                item.has("deployedMode") ? item.get("deployedMode").asText() : null,
                item.has("brokerAccountId") ? item.get("brokerAccountId").asText() : null
            ));
        }
        return list;
    }

    private static List<AccountOption> parseAccounts(JsonNode root) {
        List<AccountOption> list = new ArrayList<>();
        if (root.has("accounts")) {
            for (JsonNode item : root.get("accounts")) {
                list.add(new AccountOption(
                    item.get("id").asText(),
                    item.get("provider").asText(),
                    item.path("configured").asBoolean(false)
                ));
            }
        }
        return list;
    }

    private record StrategyOption(String id, String family, String defaultSymbol, String deployedMode, String brokerAccountId) {}
    private record AccountOption(String id, String provider, boolean configured) {}
}
