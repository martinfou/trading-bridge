package com.martinfou.trading.tui;

import org.jline.reader.LineReader;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
        return handle(line, null, null);
    }

    public List<String> handle(String line, LineReader reader) {
        return handle(line, reader, null);
    }

    public List<String> handle(String line, LineReader reader, java.util.function.Consumer<String> liveOutput) {
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
                case "backtest", "bt" -> backtest(parts, reader, liveOutput);
                case "promote" -> promote(parts);
                case "configure-oanda", "oanda-setup" -> configureOanda(reader, liveOutput);
                case "run" -> showRun(parts);
                case "events" -> showEvents(parts);
                case "kill" -> kill(parts);
                case "inbox", "sq" -> sqBridge(parts);
                case "weekly-build", "weekly" -> weeklyBuild(parts);
                case "weekly-status", "wb-status" -> weeklyStatus();
                case "data" -> dataCommand(parts);
                case "quit", "exit", "q" -> List.of("__QUIT__");
                default -> List.of("Unknown command: /" + command + ". Type /help");
            };
        } catch (ControlPlaneClient.ControlPlaneException e) {
            return List.of("API error " + e.statusCode() + ": " + e.getMessage());
        } catch (IllegalArgumentException e) {
            return List.of("Invalid input: " + e.getMessage());
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
            "  /status [id]       Trading Desk summary or promote-readiness",
            "  /backtest [args]   Start BACKTEST (no args = interactive wizard)",
            "      args: <id> [SYMBOL YEAR | SYMBOL 2006-2012 | --sample | --ci | file.csv]",
            "            [--capital 1000] [--lots 0.01]",
            "  /promote <id> PAPER|LIVE [runId]",
            "  /configure-oanda   Interactive wizard to configure OANDA token and account ID",
            "  /run <runId>       Run status + full backtest report",
            "  /events <runId>    Last 20 run events",
            "  /kill <id> [reason]",
            "  /sq | /inbox [process]   SQ bridge status or trigger inbox drain",
            "  /weekly-status           Weekly builder hot-folder counts",
            "  /weekly-build [--plan|--compile|--deploy]   Trigger Job 1/2/3",
            "  /data <args>             Historical data status, download, or delete (type /data for help)",
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
        lines.add("Trading Desk summary:");
        if (summary.has("runs") && summary.get("runs").size() > 0) {
            for (JsonNode run : summary.get("runs")) {
                String id = run.path("strategyId").asText();
                String mode = run.path("mode").asText();
                String status = run.path("status").asText();
                String runId = run.path("runId").asText();
                double pnl = run.path("totalPnL").asDouble(0.0);
                int openTrades = run.path("openTrades").asInt(0);
                lines.add(String.format("  %-25s %-5s %-10s (PnL: $%7.2f, Open: %d) [%s]", 
                    id, mode, status, pnl, openTrades, runId));
            }
        } else {
            lines.add("  No active runs currently in the Trading Desk.");
        }
        return lines;
    }

    private List<String> backtest(List<String> parts, LineReader reader, java.util.function.Consumer<String> liveOutput)
        throws IOException, InterruptedException {
        if (parts.size() < 2) {
            if (reader == null) {
                return List.of("Usage: /backtest (interactive) requires a terminal — use /backtest <strategyId> …");
            }
            return TuiInteractiveBacktest.run(this, client, reader, liveOutput);
        }
        String strategyId = parts.get(1);
        BacktestRequestParser.ParsedBacktest parsed;
        try {
            parsed = BacktestRequestParser.parse(parts);
        } catch (IllegalArgumentException e) {
            return List.of("Backtest data: " + e.getMessage());
        }
        return runBacktest(
            strategyId,
            parsed.symbol(),
            parsed.barsSource(),
            parsed.dataLabel(),
            parsed.capital(),
            parsed.lotSize());
    }

    List<String> runBacktest(
        String strategyId,
        String symbol,
        Map<String, Object> barsSource,
        String dataLabel
    ) throws IOException, InterruptedException {
        return runBacktest(strategyId, symbol, barsSource, dataLabel, null, null);
    }

    List<String> runBacktest(
        String strategyId,
        String symbol,
        Map<String, Object> barsSource,
        String dataLabel,
        Double capital,
        Double lotSize
    ) throws IOException, InterruptedException {
        JsonNode started = client.startBacktest(strategyId, symbol, barsSource, capital, lotSize);
        lastRunId = started.get("runId").asText();
        List<String> lines = new ArrayList<>();
        lines.add("Started run " + lastRunId + " (" + strategyId + " " + dataLabel + ")");

        for (int i = 0; i < 120; i++) {
            Thread.sleep(100);
            JsonNode run = client.getRun(lastRunId);
            String status = run.get("status").asText();
            if (!"RUNNING".equals(status)) {
                lines.add("Run " + status);
                lines.addAll(TuiBacktestReport.format(run));
                if (lines.stream().noneMatch(l -> l.contains("BACKTEST RESULT"))) {
                    appendMetrics(lines, run);
                }
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
        List<String> report = TuiBacktestReport.format(run);
        if (report.isEmpty()) {
            appendMetrics(lines, run);
        } else {
            lines.addAll(report);
        }
        return lines;
    }

    private List<String> showEvents(List<String> parts) throws IOException, InterruptedException {
        String runId = parts.size() >= 2 ? parts.get(1) : lastRunId;
        if (runId == null || runId.isBlank()) {
            return List.of("Usage: /events <runId>");
        }
        return tailEvents(runId, 20);
    }

    private List<String> sqBridge(List<String> parts) throws IOException, InterruptedException {
        if (parts.size() >= 2 && "process".equalsIgnoreCase(parts.get(1))) {
            JsonNode response = client.processSqInbox();
            return List.of(
                response.get("accepted").asBoolean() ? "Inbox processing started" : "Inbox busy",
                response.get("message").asText()
            );
        }
        JsonNode status = client.sqBridgeStatus();
        List<String> lines = new ArrayList<>();
        lines.add("SQ bridge:");
        lines.add("  sqHomeConfigured=" + status.get("sqHomeConfigured").asBoolean()
            + "  sqcliReachable=" + status.get("sqcliReachable").asBoolean());
        lines.add("  pending=" + status.get("inboxPendingCount").asInt()
            + "  processing=" + status.get("inboxProcessing").asBoolean());
        if (status.hasNonNull("lastProbeAt")) {
            lines.add("  lastProbeAt=" + status.get("lastProbeAt").asText());
        }
        if (status.has("lastInboxRun")) {
            JsonNode run = status.get("lastInboxRun");
            lines.add("  lastInboxRun status=" + run.get("status").asText()
                + " processed=" + run.path("processed").asInt(0)
                + " passed=" + run.path("passed").asInt(0)
                + " failed=" + run.path("failed").asInt(0)
                + " dlq=" + run.path("dlq").asInt(0));
        }
        return lines;
    }

    private List<String> weeklyStatus() throws IOException, InterruptedException {
        JsonNode status = client.weeklyBuilderStatus();
        List<String> lines = new ArrayList<>();
        lines.add("Weekly builder:");
        lines.add("  pending=" + status.path("pendingCount").asInt(0)
            + "  compiling=" + status.path("compilingCount").asInt(0)
            + "  compiled=" + status.path("compiledCount").asInt(0)
            + "  deployed=" + status.path("deployedBundleCount").asInt(0)
            + "  failed=" + status.path("failedBundleCount").asInt(0));
        if (status.hasNonNull("lastWeekId")) {
            lines.add("  lastWeekId=" + status.get("lastWeekId").asText());
        }
        if (status.has("templates")) {
            lines.add("  templates=" + status.get("templates").toString());
        }
        if (status.hasNonNull("validUntil")) {
            lines.add("  validUntil=" + status.get("validUntil").asText());
        }
        if (status.hasNonNull("lastFailureReason")) {
            lines.add("  lastFailure=" + status.get("lastFailureReason").asText());
        }
        appendLastJobRun(lines, "plan", status.get("lastPlanRun"));
        appendLastJobRun(lines, "compile", status.get("lastCompileRun"));
        appendLastJobRun(lines, "deploy", status.get("lastDeployRun"));
        lines.add("  planProcessing=" + status.path("planProcessing").asBoolean(false)
            + "  compileProcessing=" + status.path("compileProcessing").asBoolean(false)
            + "  deployProcessing=" + status.path("deployProcessing").asBoolean(false));
        return lines;
    }

    private List<String> weeklyBuild(List<String> parts) throws IOException, InterruptedException {
        String step = "deploy";
        if (parts.size() >= 2) {
            String flag = parts.get(1).toLowerCase(Locale.ROOT);
            if (flag.startsWith("--")) {
                flag = flag.substring(2);
            }
            step = switch (flag) {
                case "plan" -> "plan";
                case "compile" -> "compile";
                case "deploy" -> "deploy";
                default -> throw new IllegalArgumentException("Use --plan, --compile, or --deploy");
            };
        }
        JsonNode response = switch (step) {
            case "plan" -> client.triggerWeeklyPlan();
            case "compile" -> client.triggerWeeklyCompile();
            default -> client.triggerWeeklyDeploy();
        };
        return List.of(response.get("message").asText());
    }

    private static void appendLastJobRun(List<String> lines, String label, JsonNode run) {
        if (run == null || run.isNull() || run.isMissingNode()) {
            return;
        }
        String status = run.path("status").asText("unknown");
        String message = run.path("message").asText("");
        StringBuilder line = new StringBuilder("  last").append(capitalize(label))
            .append("=").append(status);
        if (!message.isBlank()) {
            line.append(" — ").append(message);
        }
        lines.add(line.toString());
    }

    private static String capitalize(String step) {
        return step.substring(0, 1).toUpperCase(Locale.ROOT) + step.substring(1);
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

    static void appendMetrics(List<String> lines, JsonNode run) {
        JsonNode metrics = run.has("result") ? run.get("result") : run;
        if (metrics.has("totalTrades")) {
            lines.add("  trades=" + metrics.get("totalTrades").asInt()
                + "  return=" + fmt(metrics.get("totalReturnPct").asDouble()) + "%"
                + "  maxDD=" + fmt(metrics.get("maxDrawdownPct").asDouble()) + "%");
        }
        JsonNode config = run.get("configSnapshot");
        if (config != null && config.has("barsSourceType")) {
            String source = config.get("barsSourceType").asText();
            String detail = source;
            if (config.has("barsSourceYear")) {
                detail = source + " " + config.get("barsSourceYear").asText();
            } else if (config.has("barsSourceCount")) {
                detail = source + " " + config.get("barsSourceCount").asInt() + " bars";
            } else if (config.has("barsSourcePath")) {
                detail = source + " " + config.get("barsSourcePath").asText();
            }
            lines.add("  data=" + detail);
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private List<String> configureOanda(LineReader reader, java.util.function.Consumer<String> liveOutput)
        throws IOException, InterruptedException {
        if (reader == null) {
            return List.of("This is an interactive command and requires a terminal.");
        }
        List<String> transcript = new ArrayList<>();
        say(transcript, liveOutput, "=== OANDA Credentials Setup ===");
        
        String envChoice = TuiPrompts.choose(
            reader, "Select Environment (1: PAPER/Practice, 2: LIVE/Production)", List.of("1", "2"), "1", liveOutput);
        
        String id;
        String restUrl;
        if ("2".equals(envChoice)) {
            id = "oanda_live";
            restUrl = "https://api-fxtrade.oanda.com";
            say(transcript, liveOutput, "→ Environment: LIVE (Registry ID: " + id + ")");
        } else {
            id = "default";
            restUrl = "https://api-fxpractice.oanda.com";
            say(transcript, liveOutput, "→ Environment: PAPER (Registry ID: " + id + ")");
        }

        say(transcript, liveOutput, "");
        String token = reader.readLine("Enter OANDA API Token: ", '*');
        if (token == null || token.isBlank()) {
            return List.of("Setup cancelled: token cannot be empty.");
        }
        
        say(transcript, liveOutput, "Fetching accounts from OANDA...");
        JsonNode response;
        try {
            response = client.listOandaAccounts(token, restUrl);
        } catch (Exception e) {
            say(transcript, liveOutput, "✗ Failed to fetch accounts: " + e.getMessage());
            return transcript;
        }

        JsonNode accountsNode = response.get("accounts");
        if (accountsNode == null || !accountsNode.isArray() || accountsNode.isEmpty()) {
            say(transcript, liveOutput, "✗ No accounts found associated with this token.");
            return transcript;
        }

        List<String> accountIds = new ArrayList<>();
        say(transcript, liveOutput, "");
        say(transcript, liveOutput, "Available OANDA Accounts:");
        for (int i = 0; i < accountsNode.size(); i++) {
            String accId = accountsNode.get(i).get("id").asText();
            accountIds.add(accId);
            say(transcript, liveOutput, "  " + (i + 1) + ". " + accId);
        }

        say(transcript, liveOutput, "");
        String selectedAccountId = TuiPrompts.choose(
            reader, "Select Account [# or id]", accountIds, null, liveOutput);
        say(transcript, liveOutput, "→ Selected Account: " + selectedAccountId);

        say(transcript, liveOutput, "Testing connection to OANDA with selected account...");
        boolean testOk = false;
        try {
            JsonNode testResponse = client.testBrokerAccount(id, "OANDA", token, selectedAccountId, restUrl);
            if (testResponse.path("success").asBoolean(false)) {
                double balance = testResponse.path("balance").asDouble(0.0);
                String currency = testResponse.path("currency").asText("USD");
                say(transcript, liveOutput, String.format("✓ Connection successful! Balance: %,.2f %s", balance, currency));
                testOk = true;
            } else {
                say(transcript, liveOutput, "✗ Connection check failed: " + testResponse.path("error").asText("Unknown error"));
            }
        } catch (Exception e) {
            say(transcript, liveOutput, "✗ Connection check failed: " + e.getMessage());
        }

        if (!testOk) {
            String saveAnyway = TuiPrompts.choose(reader, "Connection failed. Save anyway? (y/n)", List.of("y", "n"), "n", liveOutput);
            if (!"y".equalsIgnoreCase(saveAnyway)) {
                say(transcript, liveOutput, "Setup cancelled.");
                return transcript;
            }
        }

        say(transcript, liveOutput, "Saving configuration to control plane...");
        try {
            client.updateBrokerAccount(id, "OANDA", token, selectedAccountId, restUrl);
            say(transcript, liveOutput, "✓ OANDA credentials successfully saved for account ID: " + selectedAccountId);
        } catch (Exception e) {
            say(transcript, liveOutput, "✗ Failed to save configuration: " + e.getMessage());
        }

        return transcript;
    }

    private static void say(List<String> transcript, java.util.function.Consumer<String> liveOutput, String line) {
        transcript.add(line);
        if (liveOutput != null) {
            liveOutput.accept(line);
        }
    }

    private List<String> dataCommand(List<String> parts) throws IOException, InterruptedException {
        if (parts.size() < 2) {
            return dataHelp();
        }
        String subcommand = parts.get(1).toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "help", "?" -> dataHelp();
            case "status" -> dataStatus(parts);
            case "download" -> dataDownload(parts);
            case "delete" -> dataDelete(parts);
            default -> List.of("Unknown subcommand: /data " + subcommand + ". Type /data help");
        };
    }

    private List<String> dataHelp() {
        return List.of(
            "Usage: /data <subcommand> [args]",
            "Subcommands:",
            "  status [tf]                 Show downloaded historical data status (default tf: h1)",
            "  download <pair> <year> [tf]  Download historical data for a specific year (e.g. /data download eurusd 2012)",
            "  download <pair> <start>-<end> [tf] Download range of years (e.g. /data download eurusd 2012-2015)",
            "  download --sync [tf]        Trigger full local sync of all pairs and years",
            "  delete <pair> <year> [tf]    Delete local files for a year (e.g. /data delete eurusd 2012)",
            "  delete <pair> <start>-<end> [tf] Delete local files for a range of years"
        );
    }

    private List<String> dataStatus(List<String> parts) throws IOException, InterruptedException {
        String tf = "h1";
        if (parts.size() >= 3) {
            tf = parts.get(2).toLowerCase(Locale.ROOT);
        }
        JsonNode root = client.historicalDataStatus(tf);
        List<String> lines = new ArrayList<>();

        JsonNode activeTasks = root.path("activeTasks");
        if (activeTasks.isArray() && !activeTasks.isEmpty()) {
            lines.add("Active tasks:");
            for (JsonNode task : activeTasks) {
                String key = task.path("key").asText();
                int progress = task.path("progress").asInt(0);
                String action = task.path("currentAction").asText();
                lines.add(String.format("  - %s: %d%% (%s)", key, progress, action));
            }
            lines.add("");
        }

        JsonNode activeDownloads = root.path("activeDownloads");
        if (activeDownloads.isArray() && !activeDownloads.isEmpty() && activeTasks.isEmpty()) {
            lines.add("Active downloads:");
            for (JsonNode dl : activeDownloads) {
                lines.add("  - " + dl.asText());
            }
            lines.add("");
        }

        lines.add("Historical Data status (" + tf.toUpperCase(Locale.ROOT) + "):");
        
        List<String> supportedPairs = List.of("EUR_USD", "GBP_USD", "GBP_JPY", "USD_CAD", "USD_JPY", "AUD_USD", "NZD_USD", "USD_CHF");
        Map<String, List<String>> pairYears = new LinkedHashMap<>();
        for (String sp : supportedPairs) {
            pairYears.put(sp, new ArrayList<>());
        }

        JsonNode statusList = root.path("status");
        if (statusList.isArray()) {
            for (JsonNode item : statusList) {
                String sym = item.path("symbol").asText();
                boolean csvExists = item.path("csvExists").asBoolean(false);
                boolean barsExists = item.path("barsExists").asBoolean(false);
                if (csvExists || barsExists) {
                    int year = item.path("year").asInt();
                    String state;
                    if (csvExists && barsExists) {
                        state = year + " (CSV+BARS)";
                    } else if (csvExists) {
                        state = year + " (CSV)";
                    } else {
                        state = year + " (BARS)";
                    }
                    pairYears.computeIfAbsent(sym, k -> new ArrayList<>()).add(state);
                }
            }
        }

        for (Map.Entry<String, List<String>> entry : pairYears.entrySet()) {
            String sym = entry.getKey();
            List<String> years = entry.getValue();
            if (years.isEmpty()) {
                lines.add("  " + sym + ": [no data]");
            } else {
                Collections.sort(years);
                lines.add("  " + sym + ": " + String.join(", ", years));
            }
        }
        return lines;
    }

    private List<String> dataDownload(List<String> parts) throws IOException, InterruptedException {
        if (parts.size() < 3) {
            return List.of("Usage: /data download <pair> <year-or-range> [tf] OR /data download --sync [tf]");
        }
        String p2 = parts.get(2);
        if ("--sync".equalsIgnoreCase(p2)) {
            String tf = "h1";
            if (parts.size() >= 4) {
                tf = parts.get(3).toLowerCase(Locale.ROOT);
            }
            if (!tf.equalsIgnoreCase("h1") && !tf.equalsIgnoreCase("m1")) {
                return List.of("Invalid timeframe: '" + tf + "'. Supported timeframes are h1 and m1.");
            }
            JsonNode resp = client.downloadHistoricalData(null, null, null, null, tf, true);
            return List.of(resp.path("accepted").asBoolean(false) ? "Sync download started" : "Sync download busy/already running");
        }

        if (parts.size() < 4) {
            return List.of("Usage: /data download <pair> <year-or-range> [tf]");
        }

        String pair = normalizePair(p2);
        ParsedDataArgs parsed = parseDataArgs(parts);
        if (parsed.error != null) {
            return List.of(parsed.error);
        }

        JsonNode resp = client.downloadHistoricalData(pair, parsed.year, parsed.startYear, parsed.endYear, parsed.tf, false);
        String label = (parsed.startYear != null && parsed.endYear != null) ? (parsed.startYear + "-" + parsed.endYear) : String.valueOf(parsed.year);
        return List.of(resp.path("accepted").asBoolean(false)
            ? "Download started for " + toSymbol(pair) + " " + label + " (" + parsed.tf.toUpperCase(Locale.ROOT) + ")"
            : "Download busy/already running");
    }

    private List<String> dataDelete(List<String> parts) throws IOException, InterruptedException {
        if (parts.size() < 4) {
            return List.of("Usage: /data delete <pair> <year-or-range> [tf]");
        }
        String pair = normalizePair(parts.get(2));
        ParsedDataArgs parsed = parseDataArgs(parts);
        if (parsed.error != null) {
            return List.of(parsed.error);
        }

        List<Integer> yearsToDelete = new ArrayList<>();
        if (parsed.startYear != null && parsed.endYear != null) {
            for (int y = parsed.startYear; y <= parsed.endYear; y++) {
                yearsToDelete.add(y);
            }
        } else {
            yearsToDelete.add(parsed.year);
        }

        List<String> lines = new ArrayList<>();
        for (int y : yearsToDelete) {
            client.deleteHistoricalData(pair, y, parsed.tf);
            lines.add("Deleted " + toSymbol(pair) + " " + y + " (" + parsed.tf.toUpperCase(Locale.ROOT) + ")");
        }
        return lines;
    }

    private static class ParsedDataArgs {
        Integer year;
        Integer startYear;
        Integer endYear;
        String tf = "h1";
        String error;
    }

    private static boolean looksLikeYear(String s) {
        return s != null && s.matches("\\d{4}");
    }

    private static ParsedDataArgs parseDataArgs(List<String> parts) {
        ParsedDataArgs args = new ParsedDataArgs();
        if (parts.size() < 4) {
            args.error = "Missing year or range";
            return args;
        }

        if (parts.size() >= 5 && looksLikeYear(parts.get(3)) && looksLikeYear(parts.get(4))) {
            args.startYear = Integer.parseInt(parts.get(3));
            args.endYear = Integer.parseInt(parts.get(4));
            if (parts.size() >= 6) {
                args.tf = parts.get(5).toLowerCase(Locale.ROOT);
            }
        } else {
            String yearSpec = parts.get(3);
            if (parts.size() >= 5) {
                args.tf = parts.get(4).toLowerCase(Locale.ROOT);
            }
            if (yearSpec.contains("-")) {
                String[] split = yearSpec.split("-");
                if (split.length != 2) {
                    args.error = "Invalid year range: " + yearSpec;
                    return args;
                }
                try {
                    args.startYear = Integer.parseInt(split[0]);
                    args.endYear = Integer.parseInt(split[1]);
                } catch (NumberFormatException e) {
                    args.error = "Invalid year range values: " + yearSpec;
                    return args;
                }
            } else {
                try {
                    args.year = Integer.parseInt(yearSpec);
                } catch (NumberFormatException e) {
                    args.error = "Invalid year: " + yearSpec;
                    return args;
                }
            }
        }

        if (!args.tf.equalsIgnoreCase("h1") && !args.tf.equalsIgnoreCase("m1")) {
            args.error = "Invalid timeframe: '" + args.tf + "'. Supported timeframes are h1 and m1.";
        }

        return args;
    }

    private static String toSymbol(String pair) {
        if (pair == null) return "";
        return switch (pair.toLowerCase(Locale.ROOT)) {
            case "eurusd" -> "EUR_USD";
            case "gbpusd" -> "GBP_USD";
            case "gbpjpy" -> "GBP_JPY";
            case "usdcad" -> "USD_CAD";
            case "usdjpy" -> "USD_JPY";
            case "audusd" -> "AUD_USD";
            case "nzdusd" -> "NZD_USD";
            case "usdchf" -> "USD_CHF";
            default -> pair.toUpperCase(Locale.ROOT);
        };
    }

    private static String normalizePair(String pair) {
        if (pair == null) return null;
        return pair.toLowerCase(Locale.ROOT).replace("_", "").replace("/", "");
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
