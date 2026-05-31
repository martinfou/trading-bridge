package com.martinfou.trading.tui;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Parses slash commands and dispatches to {@link ControlPlaneClient} (Story 13.6). */
public final class TuiCommandHandler {

    private final ControlPlaneClient client;
    private String lastRunId;

    public TuiCommandHandler(ControlPlaneClient client) {
        this.client = client;
    }

    public String lastRunId() {
        return lastRunId;
    }

    public List<String> handle(String line) {
        if (line == null || line.isBlank()) {
            return List.of();
        }
        String trimmed = line.trim();
        if (!trimmed.startsWith("/")) {
            return List.of("Commands start with /. Type /help");
        }
        List<String> parts = tokenize(trimmed.substring(1));
        if (parts.isEmpty()) {
            return List.of("Empty command. Type /help");
        }
        String command = parts.getFirst().toLowerCase(Locale.ROOT);
        try {
            return switch (command) {
                case "help", "h", "?" -> help();
                case "list", "ls" -> listStrategies();
                case "status" -> status(parts);
                case "backtest", "bt" -> backtest(parts);
                case "promote" -> promote(parts);
                case "run" -> showRun(parts);
                case "events" -> showEvents(parts);
                case "kill" -> kill(parts);
                case "quit", "exit", "q" -> List.of("__QUIT__");
                default -> List.of("Unknown command: /" + command + ". Type /help");
            };
        } catch (ControlPlaneClient.ControlPlaneException e) {
            return List.of("API error " + e.statusCode() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of("Request failed: interrupted");
        } catch (IOException e) {
            return List.of("Request failed: " + e.getMessage());
        }
    }

    static List<String> help() {
        return List.of(
            "Trading Bridge TUI — control plane client",
            "  /list              List strategies + deployment mode",
            "  /status [id]       Control summary or promote-readiness",
            "  /backtest <id> [symbol] [bars]   Start BACKTEST (default EUR_USD sample 500)",
            "  /promote <id> PAPER|LIVE [runId]",
            "  /run <runId>       Run status + metrics",
            "  /events <runId>    Last 20 run events",
            "  /kill <id> [reason]",
            "  /quit              Exit",
            "Env: CONTROL_PLANE_URL (default http://localhost:8080)");
    }

    private List<String> listStrategies() throws IOException, InterruptedException {
        JsonNode root = client.listStrategies();
        JsonNode strategies = root.get("strategies");
        List<String> lines = new ArrayList<>();
        lines.add("Strategies (" + strategies.size() + "):");
        for (JsonNode item : strategies) {
            String id = item.get("id").asText();
            String mode = item.has("deployedMode") ? item.get("deployedMode").asText() : "—";
            String label = item.has("executionLabel") ? item.get("executionLabel").asText() : "";
            lines.add("  " + id + "  mode=" + mode
                + (label.isBlank() ? "" : "  label=" + label));
        }
        return lines;
    }

    private List<String> status(List<String> parts) throws IOException, InterruptedException {
        if (parts.size() >= 2) {
            JsonNode readiness = client.promoteReadiness(parts.get(1));
            return List.of(formatJson(readiness));
        }
        JsonNode summary = client.controlSummary();
        List<String> lines = new ArrayList<>();
        lines.add("Control summary:");
        if (summary.has("strategies")) {
            for (JsonNode s : summary.get("strategies")) {
                String id = s.get("strategyId").asText();
                String mode = s.has("deploymentMode") ? s.get("deploymentMode").asText() : "—";
                lines.add("  " + id + "  " + mode);
            }
        }
        return lines;
    }

    private List<String> backtest(List<String> parts) throws IOException, InterruptedException {
        if (parts.size() < 2) {
            return List.of("Usage: /backtest <strategyId> [symbol] [bars]");
        }
        String strategyId = parts.get(1);
        String symbol = parts.size() >= 3 ? parts.get(2) : "EUR_USD";
        int bars = parts.size() >= 4 ? Integer.parseInt(parts.get(3)) : 500;

        JsonNode started = client.startBacktest(strategyId, symbol, bars);
        lastRunId = started.get("runId").asText();
        List<String> lines = new ArrayList<>();
        lines.add("Started run " + lastRunId + " (" + strategyId + " " + symbol + " sample " + bars + ")");

        for (int i = 0; i < 120; i++) {
            Thread.sleep(100);
            JsonNode run = client.getRun(lastRunId);
            String status = run.get("status").asText();
            if (!"RUNNING".equals(status)) {
                lines.add("Run " + status);
                appendMetrics(lines, run);
                lines.addAll(tailEvents(lastRunId, 5));
                return lines;
            }
        }
        lines.add("Run still RUNNING — use /run " + lastRunId);
        return lines;
    }

    private List<String> promote(List<String> parts) throws IOException, InterruptedException {
        if (parts.size() < 3) {
            return List.of("Usage: /promote <strategyId> PAPER|LIVE [runId]");
        }
        String strategyId = parts.get(1);
        String mode = parts.get(2).toUpperCase(Locale.ROOT);
        String runId = parts.size() >= 4 ? parts.get(3) : lastRunId;
        JsonNode response = client.promote(strategyId, mode, runId);
        List<String> lines = new ArrayList<>();
        boolean promoted = response.get("promoted").asBoolean();
        lines.add(promoted ? "Promoted to " + mode : "Promote rejected");
        if (response.has("checks")) {
            for (JsonNode check : response.get("checks")) {
                String name = check.get("name").asText();
                boolean passed = check.get("passed").asBoolean();
                lines.add("  " + (passed ? "✓" : "✗") + " " + name + ": " + check.get("message").asText());
            }
        }
        return lines;
    }

    private List<String> showRun(List<String> parts) throws IOException, InterruptedException {
        String runId = parts.size() >= 2 ? parts.get(1) : lastRunId;
        if (runId == null || runId.isBlank()) {
            return List.of("Usage: /run <runId> (or run a /backtest first)");
        }
        JsonNode run = client.getRun(runId);
        List<String> lines = new ArrayList<>();
        lines.add("Run " + runId + "  status=" + run.get("status").asText()
            + "  strategy=" + run.get("strategyId").asText());
        appendMetrics(lines, run);
        return lines;
    }

    private List<String> showEvents(List<String> parts) throws IOException, InterruptedException {
        String runId = parts.size() >= 2 ? parts.get(1) : lastRunId;
        if (runId == null || runId.isBlank()) {
            return List.of("Usage: /events <runId>");
        }
        return tailEvents(runId, 20);
    }

    private List<String> kill(List<String> parts) throws IOException, InterruptedException {
        if (parts.size() < 2) {
            return List.of("Usage: /kill <strategyId> [reason]");
        }
        String reason = parts.size() >= 3 ? String.join(" ", parts.subList(2, parts.size())) : null;
        JsonNode response = client.kill(parts.get(1), "tui", reason);
        return List.of("Kill accepted: " + formatJson(response));
    }

    private List<String> tailEvents(String runId, int limit) throws IOException, InterruptedException {
        JsonNode page = client.listEvents(runId, 0, limit);
        List<String> lines = new ArrayList<>();
        lines.add("Events (" + runId + "):");
        for (JsonNode item : page.get("items")) {
            JsonNode event = item.get("event");
            String type = event.get("type").asText();
            lines.add("  #" + item.get("sequence").asLong() + " " + type);
        }
        return lines;
    }

    private static void appendMetrics(List<String> lines, JsonNode run) {
        if (run.has("totalTrades")) {
            lines.add("  trades=" + run.get("totalTrades").asInt()
                + "  return=" + fmt(run.get("totalReturnPct").asDouble()) + "%"
                + "  maxDD=" + fmt(run.get("maxDrawdownPct").asDouble()) + "%");
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private static String formatJson(JsonNode node) {
        return node.toPrettyString().lines().limit(12).reduce((a, b) -> a + "\n" + b).orElse("");
    }

    static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            tokens.add(current.toString());
        }
        return tokens;
    }
}
