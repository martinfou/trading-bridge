package com.martinfou.trading.tui;

import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * LLM Quant Research CLI command handlers (Universe, Strategy Inspector, Backtest History,
 * Genetic Strategy Generator, Walk-Forward Analysis, Monte Carlo, Analytics, Exports & Portfolio).
 */
final class TuiResearchCommands {

    private TuiResearchCommands() {}

    // =========================================================================
    // PILLAR 1: UNIVERSE MANAGEMENT (/universe)
    // =========================================================================

    static List<String> universe(ControlPlaneClient client, List<String> parts, boolean jsonMode)
        throws IOException, InterruptedException {
        String sub = parts.size() >= 2 ? parts.get(1).toLowerCase(Locale.ROOT) : "list";
        if (sub.startsWith("--")) {
            sub = "list";
        }
        return switch (sub) {
            case "list", "ls" -> universeList(client, parts, jsonMode);
            case "add" -> universeAdd(client, parts, jsonMode);
            case "delete", "rm", "remove" -> universeDelete(client, parts, jsonMode);
            default -> List.of(
                "Usage: /universe [list|add|delete]",
                "  /universe list [--asset FOREX|FUTURES|EQUITIES]",
                "  /universe add <SYMBOL> <FOREX|FUTURES|EQUITIES> [--pip-size 0.01] [--lot-size 1.0] [--margin 0.05]",
                "  /universe delete <SYMBOL>"
            );
        };
    }

    private static List<String> universeList(ControlPlaneClient client, List<String> parts, boolean jsonMode)
        throws IOException, InterruptedException {
        String assetClass = extractFlag(parts, "--asset", "--asset-class");
        JsonNode root = client.listUniverse(assetClass);
        if (jsonMode) {
            return List.of(root.toPrettyString());
        }
        JsonNode arr = root.isArray() ? root : root.path("instruments");
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "%-10s | %-10s | %-10s | %-10s | %-8s | %-28s",
            "Symbol", "AssetClass", "TickSize", "PointVal", "Currency", "Smart Preset (Cap/Size/Comm)"));
        lines.add("-".repeat(90));
        if (arr.isArray()) {
            for (JsonNode inst : arr) {
                String sym = inst.path("symbol").asText("?");
                String ac = inst.path("assetClass").asText("FOREX");
                double tick = inst.has("tickSize")
                    ? inst.path("tickSize").asDouble(0.0001)
                    : inst.path("pipSize").asDouble(0.0001);
                double pointVal = inst.has("pointValue")
                    ? inst.path("pointValue").asDouble(1.0)
                    : inst.path("lotSize").asDouble(1.0);
                String currency = inst.path("currency").asText("USD");
                TuiDefaults.SmartPreset preset = TuiDefaults.resolvePreset(sym);
                String presetStr = String.format(Locale.ROOT, "$%.0f / %.2f / $%.2f",
                    preset.capital(), preset.lotSize(), preset.commissionPerTrade());
                lines.add(String.format(Locale.ROOT, "%-10s | %-10s | %-10.5f | %-10.2f | %-8s | %-28s",
                    sym, ac, tick, pointVal, currency, presetStr));
            }
        }
        return lines;
    }

    private static List<String> universeAdd(ControlPlaneClient client, List<String> parts, boolean jsonMode)
        throws IOException, InterruptedException {
        if (parts.size() < 4) {
            return List.of("Usage: /universe add <SYMBOL> <FOREX|FUTURES|EQUITIES> [--name NAME] [--tick-size 0.25] [--point-value 5.0] [--currency USD]");
        }
        String symbol = parts.get(2).toUpperCase(Locale.ROOT);
        String assetClass = parts.get(3).toUpperCase(Locale.ROOT);
        String name = extractFlag(parts, "--name", null);
        if (name == null || name.isBlank()) {
            name = symbol;
        }
        double tickSize = parseDoubleFlag(parts, "--tick-size",
            parseDoubleFlag(parts, "--pip-size", "FUTURES".equals(assetClass) ? 0.25 : 0.0001));
        double pointValue = parseDoubleFlag(parts, "--point-value",
            parseDoubleFlag(parts, "--lot-size", "FOREX".equals(assetClass) ? 100_000.0 : 1.0));
        String currency = extractFlag(parts, "--currency", null);
        if (currency == null || currency.isBlank()) {
            currency = "USD";
        }
        String providerTicker = extractFlag(parts, "--provider-ticker", null);
        if (providerTicker == null || providerTicker.isBlank()) {
            providerTicker = symbol;
        }

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("symbol", symbol);
        req.put("name", name);
        req.put("assetClass", assetClass);
        req.put("pointValue", pointValue);
        req.put("tickSize", tickSize);
        req.put("currency", currency.toUpperCase(Locale.ROOT));
        req.put("isCustom", true);
        req.put("providerTicker", providerTicker);

        JsonNode resp = client.addInstrument(req);
        if (jsonMode) {
            return List.of(resp.toPrettyString());
        }
        return List.of("Added instrument " + symbol + " (" + assetClass + ")");
    }

    private static List<String> universeDelete(ControlPlaneClient client, List<String> parts, boolean jsonMode)
        throws IOException, InterruptedException {
        if (parts.size() < 3) {
            return List.of("Usage: /universe delete <SYMBOL>");
        }
        String symbol = parts.get(2).toUpperCase(Locale.ROOT);
        JsonNode resp = client.deleteInstrument(symbol);
        if (jsonMode) {
            return List.of(resp.toPrettyString());
        }
        return List.of("Deleted instrument " + symbol);
    }

    // =========================================================================
    // PILLAR 2: RICH STRATEGY CATALOG & DNA/CODE INSPECTOR (/strategies, /inspect)
    // =========================================================================

    static List<String> strategies(ControlPlaneClient client, List<String> parts, boolean jsonMode)
        throws IOException, InterruptedException {
        String sub = parts.size() >= 2 ? parts.get(1).toLowerCase(Locale.ROOT) : "list";
        if (sub.startsWith("--")) {
            sub = "list";
        }
        return switch (sub) {
            case "list", "ls" -> strategiesList(client, parts, jsonMode);
            case "inspect", "show", "info" -> {
                if (parts.size() < 3) {
                    yield List.of("Usage: /strategies inspect <strategyId> [--code]");
                }
                yield inspectStrategyOrCandidate(client, parts.get(2), hasFlag(parts, "--code"), jsonMode);
            }
            default -> inspectStrategyOrCandidate(client, parts.get(1), hasFlag(parts, "--code"), jsonMode);
        };
    }

    static List<String> inspectCommand(ControlPlaneClient client, List<String> parts, boolean jsonMode)
        throws IOException, InterruptedException {
        if (parts.size() < 2) {
            return List.of("Usage: /inspect <strategyId-or-candidateId> [--code]");
        }
        return inspectStrategyOrCandidate(client, parts.get(1), hasFlag(parts, "--code"), jsonMode);
    }

    static List<String> strategiesList(ControlPlaneClient client, List<String> parts, boolean jsonMode)
        throws IOException, InterruptedException {
        String assetFilter = extractFlag(parts, "--asset", "--asset-class");
        String familyFilter = extractFlag(parts, "--family", null);
        JsonNode root = client.listStrategies();
        if (jsonMode) {
            return List.of(root.toPrettyString());
        }
        JsonNode strategies = root.path("strategies");
        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "%-30s | %-12s | %-14s | %-18s | %-10s | %-10s | %s",
            "Strategy ID", "Family", "Assets", "Style", "TFs", "DefSymbol", "Mode"));
        lines.add("-".repeat(115));
        int count = 0;
        for (JsonNode s : strategies) {
            String id = s.path("id").asText();
            String family = s.path("family").asText("EXAMPLE");
            String assets = joinArray(s.path("assetClasses"));
            String style = s.path("tradingStyle").asText("TREND");
            String tfs = joinArray(s.path("timeframeSuitability"));
            String defSym = s.path("defaultSymbol").asText("EUR_USD");
            String mode = s.path("deployedMode").asText("—");

            if (assetFilter != null && !assets.toUpperCase(Locale.ROOT).contains(assetFilter.toUpperCase(Locale.ROOT))) {
                continue;
            }
            if (familyFilter != null && !family.equalsIgnoreCase(familyFilter)) {
                continue;
            }
            count++;
            lines.add(String.format(Locale.ROOT, "%-30s | %-12s | %-14s | %-18s | %-10s | %-10s | %s",
                truncate(id, 30), truncate(family, 12), truncate(assets, 14),
                truncate(style, 18), truncate(tfs, 10), defSym, mode));
        }
        lines.add("Total matching strategies: " + count);
        return lines;
    }

    static List<String> inspectStrategyOrCandidate(
        ControlPlaneClient client,
        String id,
        boolean showCode,
        boolean jsonMode
    ) throws IOException, InterruptedException {
        // 1. Check generator databank first or alongside catalog so DNA rules / Java source are surfaced
        JsonNode databankMatch = null;
        IOException lastIoError = null;
        try {
            JsonNode db = client.generatorDatabank();
            for (JsonNode c : db.path("candidates")) {
                if (id.equalsIgnoreCase(c.path("id").asText())) {
                    databankMatch = c;
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (IOException e) {
            lastIoError = e;
        }

        JsonNode catalogMatch = null;
        try {
            JsonNode cat = client.listStrategies();
            for (JsonNode s : cat.path("strategies")) {
                if (id.equalsIgnoreCase(s.path("id").asText())) {
                    catalogMatch = s;
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (IOException e) {
            if (databankMatch == null && lastIoError != null) {
                throw e;
            }
            lastIoError = e;
        }

        if (catalogMatch == null && databankMatch == null) {
            return List.of("Strategy or Databank candidate not found: " + id);
        }

        if (jsonMode) {
            if (databankMatch != null) {
                return List.of(databankMatch.toPrettyString());
            }
            return List.of(catalogMatch.toPrettyString());
        }

        List<String> lines = new ArrayList<>();
        lines.add("=====================================================================");
        lines.add(" STRATEGY INSPECTION: " + id);
        lines.add("=====================================================================");
        if (catalogMatch != null) {
            lines.add("  Family             : " + catalogMatch.path("family").asText());
            lines.add("  Asset Classes      : " + joinArray(catalogMatch.path("assetClasses")));
            lines.add("  Trading Style      : " + catalogMatch.path("tradingStyle").asText());
            lines.add("  Timeframes         : " + joinArray(catalogMatch.path("timeframeSuitability")));
            lines.add("  Default Symbol     : " + catalogMatch.path("defaultSymbol").asText());
            lines.add("  Recommended Symbols: " + joinArray(catalogMatch.path("recommendedSymbols")));
            lines.add("  Indicators         : " + joinArray(catalogMatch.path("indicators")));
            lines.add("  Complexity         : " + catalogMatch.path("complexity").asText());
            lines.add("  Deployed Mode      : " + catalogMatch.path("deployedMode").asText("NONE"));
            String desc = catalogMatch.path("description").asText("");
            if (!desc.isBlank()) {
                lines.add("  Description        : " + desc);
            }
        }
        if (databankMatch != null) {
            lines.add("");
            lines.add("--- Generator Genome & Validation Metrics ---");
            lines.add(String.format(Locale.ROOT,
                "  Symbol / TF / Years: %s / %s / %s  (Archetype: %s, Direction: %s, Order: %s)",
                databankMatch.path("symbol").asText(),
                databankMatch.path("timeframe").asText(),
                databankMatch.path("yearSpec").asText(),
                databankMatch.path("archetype").asText(),
                databankMatch.path("direction").asText(),
                databankMatch.path("entryOrderType").asText()));
            lines.add(String.format(Locale.ROOT,
                "  Tier / Score       : %s (Composite Score: %.2f, Promoted: %s)",
                databankMatch.path("tier").asText(),
                databankMatch.path("compositeScore").asDouble(),
                databankMatch.path("promoted").asBoolean()));
            lines.add(String.format(Locale.ROOT,
                "  Trades             : Total=%d (IS=%d, Blind OOS=%d)",
                databankMatch.path("totalTrades").asInt(),
                databankMatch.path("isTrades").asInt(),
                databankMatch.path("oosTrades").asInt()));
            lines.add(String.format(Locale.ROOT,
                "  Net Profit         : $%.2f (IS=$%.2f, Blind OOS=$%.2f) | Return: %.2f%%",
                databankMatch.path("netProfit").asDouble(),
                databankMatch.path("isNetProfit").asDouble(),
                databankMatch.path("oosNetProfit").asDouble(),
                databankMatch.path("totalReturnPct").asDouble()));
            lines.add(String.format(Locale.ROOT,
                "  Profit Factor      : %.2f (IS=%.2f, Blind OOS=%.2f)",
                databankMatch.path("profitFactor").asDouble(),
                databankMatch.path("isProfitFactor").asDouble(),
                databankMatch.path("oosProfitFactor").asDouble()));
            lines.add(String.format(Locale.ROOT,
                "  Sharpe / MaxDD     : Sharpe=%.2f (IS=%.2f, OOS=%.2f) | MaxDD=%.2f%% | Ret/DD=%.2f | R²=%.2f",
                databankMatch.path("sharpeRatio").asDouble(),
                databankMatch.path("isSharpeRatio").asDouble(),
                databankMatch.path("oosSharpeRatio").asDouble(),
                databankMatch.path("maxDrawdownPct").asDouble(),
                databankMatch.path("retDdRatio").asDouble(),
                databankMatch.path("rSquared").asDouble()));
            lines.add(String.format(Locale.ROOT,
                "  Monte Carlo        : VaR(95%%)=$%.2f | Loss Probability=%.1f%%",
                databankMatch.path("mcVar95").asDouble(),
                databankMatch.path("mcLossProbPct").asDouble()));
            lines.add("");
            lines.add("--- DNA Trading Rules ---");
            lines.add("  Long Signal : " + databankMatch.path("longSignalSummary").asText("—"));
            lines.add("  Short Signal: " + databankMatch.path("shortSignalSummary").asText("—"));
            lines.add("  Entry Logic : " + databankMatch.path("entrySummary").asText("—"));
            lines.add("  Exit / Risk : " + databankMatch.path("exitSummary").asText("—"));
            if (showCode && databankMatch.hasNonNull("javaSourceCode")) {
                lines.add("");
                lines.add("--- Generated Java Source Code ---");
                lines.add(databankMatch.path("javaSourceCode").asText());
            }
        }
        return lines;
    }

    // =========================================================================
    // PILLAR 3: SQLITE BACKTEST HISTORY & DIAGNOSTICS (/backtests, /retire)
    // =========================================================================

    static List<String> backtests(ControlPlaneClient client, List<String> parts, boolean jsonMode)
        throws IOException, InterruptedException {
        String sub = parts.size() >= 2 ? parts.get(1).toLowerCase(Locale.ROOT) : "list";
        if (sub.startsWith("--")) {
            sub = "list";
        }
        return switch (sub) {
            case "list", "ls" -> {
                String symbol = extractFlag(parts, "--symbol", null);
                String strategy = extractFlag(parts, "--strategy", null);
                Double minSharpe = parseNullableDoubleFlag(parts, "--min-sharpe");
                Double minPf = parseNullableDoubleFlag(parts, "--min-pf");
                int limit = (int) parseDoubleFlag(parts, "--limit", 20);
                JsonNode resp = client.listBacktests(symbol, strategy, minSharpe, minPf, limit);
                if (jsonMode) {
                    yield List.of(resp.toPrettyString());
                }
                JsonNode items = resp.path("items");
                List<String> lines = new ArrayList<>();
                lines.add(String.format(Locale.ROOT, "Backtest History (showing %d of %d):",
                    items.size(), resp.path("total").asInt(items.size())));
                lines.add(String.format(Locale.ROOT, "%-36s | %-26s | %-8s | %-6s | %-10s | %-7s | %-6s | %-6s",
                    "Run ID", "Strategy", "Symbol", "Trades", "Net PnL", "PF", "Sharpe", "MaxDD%"));
                lines.add("-".repeat(120));
                for (JsonNode item : items) {
                    lines.add(String.format(Locale.ROOT, "%-36s | %-26s | %-8s | %6d | $%9.2f | %7.2f | %6.2f | %5.2f%%",
                        item.path("runId").asText("—"),
                        truncate(item.path("strategyId").asText("—"), 26),
                        item.path("symbol").asText("—"),
                        item.path("totalTrades").asInt(0),
                        item.path("totalPnl").asDouble(0.0),
                        item.path("profitFactor").asDouble(0.0),
                        item.path("sharpeRatio").asDouble(0.0),
                        item.path("maxDrawdownPct").asDouble(0.0)));
                }
                yield lines;
            }
            case "show", "inspect", "get" -> {
                if (parts.size() < 3) {
                    yield List.of("Usage: /backtests show <runId>");
                }
                JsonNode details = client.getBacktestDetails(parts.get(2));
                if (jsonMode) {
                    yield List.of(details.toPrettyString());
                }
                List<String> lines = new ArrayList<>();
                lines.add("Backtest Run Details (" + details.path("runId").asText() + "):");
                lines.add(String.format(Locale.ROOT, "  Strategy: %s | Symbol: %s | Capital: $%.2f -> $%.2f (Return: %.2f%%)",
                    details.path("strategyId").asText(),
                    details.path("symbol").asText(),
                    details.path("initialCapital").asDouble(),
                    details.path("finalEquity").asDouble(),
                    details.path("totalReturnPct").asDouble()));
                lines.add(String.format(Locale.ROOT, "  Trades: %d (Wins: %d, Losses: %d, WinRate: %.1f%%) | Avg Trade: $%.2f",
                    details.path("totalTrades").asInt(),
                    details.path("winningTrades").asInt(),
                    details.path("losingTrades").asInt(),
                    details.path("winRatePct").asDouble(),
                    details.path("avgTradePnl").asDouble()));
                lines.add(String.format(Locale.ROOT, "  PF: %.2f | Sharpe: %.2f | Sortino: %.2f | Calmar: %.2f | MaxDD: %.2f%%",
                    details.path("profitFactor").asDouble(),
                    details.path("sharpeRatio").asDouble(),
                    details.path("sortinoRatio").asDouble(),
                    details.path("calmarRatio").asDouble(),
                    details.path("maxDrawdownPct").asDouble()));
                lines.add(String.format(Locale.ROOT, "  Costs: Commission=$%.2f | Slippage=$%.2f | Parameters=%s",
                    details.path("totalCommission").asDouble(),
                    details.path("totalSlippage").asDouble(),
                    details.path("parameters").asText("{}")));
                yield lines;
            }
            case "delete", "rm" -> {
                if (parts.size() < 3) {
                    yield List.of("Usage: /backtests delete <runId|--all>");
                }
                String target = parts.get(2);
                JsonNode resp = "--all".equalsIgnoreCase(target)
                    ? client.deleteAllBacktests()
                    : client.deleteBacktest(target);
                yield jsonMode ? List.of(resp.toPrettyString()) : List.of(resp.path("message").asText("Deleted."));
            }
            case "clear" -> {
                JsonNode resp = client.deleteAllBacktests();
                yield jsonMode ? List.of(resp.toPrettyString()) : List.of(resp.path("message").asText("Cleared all backtests."));
            }
            default -> List.of("Usage: /backtests [list|show|delete|clear] [--symbol S] [--strategy ID] [--min-sharpe X] [--min-pf Y] [--limit N]");
        };
    }

    static List<String> retire(ControlPlaneClient client, List<String> parts, boolean jsonMode)
        throws IOException, InterruptedException {
        if (parts.size() < 2) {
            return List.of("Usage: /retire <runId> [reason]");
        }
        String runId = parts.get(1);
        String reason = parts.size() >= 3 ? String.join(" ", parts.subList(2, parts.size())) : "Retired via CLI";
        JsonNode resp = client.retireRun(runId, reason);
        if (jsonMode) {
            return List.of(resp.toPrettyString());
        }
        return List.of("Run " + runId + " retired (status=" + resp.path("status").asText("RETIRED") + ")");
    }

    static void appendEnhancedRunDiagnostics(
        ControlPlaneClient client,
        String runId,
        List<String> lines,
        boolean showTrades,
        boolean showWeekly,
        boolean showAlignment
    ) {
        if (showWeekly) {
            try {
                JsonNode weekly = client.getRunWeeklyStats(runId).path("weeklyStats");
                if (weekly.isArray() && !weekly.isEmpty()) {
                    lines.add("");
                    lines.add("--- Weekly Performance Breakdown (" + weekly.size() + " active weeks) ---");
                    lines.add(String.format(Locale.ROOT, "  %-10s | %-6s | %-10s | %-7s | %-7s | %-7s",
                        "Week", "Trades", "Net PnL", "WinRate", "PF", "MaxDD%"));
                    int shown = 0;
                    for (JsonNode w : weekly) {
                        if (shown++ >= 15) {
                            lines.add("  ... (" + (weekly.size() - 15) + " more weeks; use --json for full array)");
                            break;
                        }
                        lines.add(String.format(Locale.ROOT, "  %-10s | %6d | $%9.2f | %6.1f%% | %7.2f | %6.2f%%",
                            w.path("weekId").asText(),
                            w.path("totalTrades").asInt(),
                            w.path("totalPnl").asDouble(),
                            w.path("winRatePct").asDouble(),
                            w.path("profitFactor").asDouble(),
                            w.path("maxDrawdownPct").asDouble()));
                    }
                }
            } catch (Exception ignored) {}
        }
        if (showTrades) {
            try {
                JsonNode trades = client.getRunTrades(runId).path("trades");
                if (trades.isArray() && !trades.isEmpty()) {
                    lines.add("");
                    lines.add("--- Trade List (showing up to 25 of " + trades.size() + " trades) ---");
                    lines.add(String.format(Locale.ROOT, "  %-5s | %-20s | %-20s | %-10s | %-10s | %-8s | %s",
                        "Side", "Entry Time", "Exit Time", "Entry", "Exit", "Qty", "PnL"));
                    int shown = 0;
                    for (JsonNode t : trades) {
                        if (shown++ >= 25) {
                            break;
                        }
                        lines.add(String.format(Locale.ROOT, "  %-5s | %-20s | %-20s | %10.5f | %10.5f | %8.2f | $%.2f",
                            t.path("side").asText(),
                            truncate(t.path("entryTime").asText(), 20),
                            truncate(t.path("exitTime").asText(), 20),
                            t.path("entryPrice").asDouble(),
                            t.path("exitPrice").asDouble(),
                            t.path("quantity").asDouble(),
                            t.path("pnl").asDouble()));
                    }
                }
            } catch (Exception ignored) {}
        }
        if (showAlignment) {
            try {
                JsonNode align = client.getRunAlignment(runId);
                lines.add("");
                lines.add("--- Execution / Reconciliation Alignment ---");
                lines.add(align.toPrettyString());
            } catch (Exception ignored) {}
        }
    }

    // =========================================================================
    // PILLAR 4: GENETIC STRATEGY GENERATOR & DATABANK (/generator, /gen)
    // =========================================================================

    static List<String> generator(
        ControlPlaneClient client,
        List<String> parts,
        Consumer<String> liveOutput,
        boolean jsonMode
    ) throws IOException, InterruptedException {
        String sub = parts.size() >= 2 ? parts.get(1).toLowerCase(Locale.ROOT) : "status";
        if (sub.startsWith("--")) {
            sub = "status";
        }
        return switch (sub) {
            case "status" -> generatorStatus(client, jsonMode);
            case "start", "run" -> generatorStart(client, parts, liveOutput, jsonMode);
            case "stop", "cancel" -> {
                JsonNode resp = client.generatorStop();
                yield jsonMode ? List.of(resp.toPrettyString()) : List.of("Generator stop signal sent.");
            }
            case "databank", "db", "list", "ls" -> generatorDatabank(client, parts, jsonMode);
            case "inspect", "show" -> {
                if (parts.size() < 3) {
                    yield List.of("Usage: /generator inspect <candidateId> [--code]");
                }
                yield inspectStrategyOrCandidate(client, parts.get(2), hasFlag(parts, "--code"), jsonMode);
            }
            case "promote" -> {
                if (parts.size() < 3) {
                    yield List.of("Usage: /generator promote <candidateId>");
                }
                JsonNode resp = client.generatorPromote(parts.get(2));
                yield jsonMode
                    ? List.of(resp.toPrettyString())
                    : List.of("Promoted candidate " + resp.path("id").asText(parts.get(2))
                        + " to StrategyCatalog (javaFile=" + resp.path("javaFilePath").asText("generated") + ")");
            }
            case "backtest", "bt" -> {
                if (parts.size() < 3) {
                    yield List.of("Usage: /generator backtest <candidateId>");
                }
                JsonNode resp = client.generatorBacktest(parts.get(2));
                yield jsonMode
                    ? List.of(resp.toPrettyString())
                    : List.of("Started verification backtest runId=" + resp.path("runId").asText()
                        + " for strategy=" + resp.path("strategyId").asText());
            }
            case "delete", "rm", "clear" -> {
                if ("clear".equals(sub) || (parts.size() >= 3 && "--all".equalsIgnoreCase(parts.get(2)))) {
                    JsonNode resp = client.generatorClearDatabank();
                    yield jsonMode ? List.of(resp.toPrettyString()) : List.of("Cleared all candidates from Generator Databank.");
                }
                if (parts.size() < 3) {
                    yield List.of("Usage: /generator delete <candidateId|--all>");
                }
                JsonNode resp = client.generatorDeleteCandidate(parts.get(2));
                yield jsonMode ? List.of(resp.toPrettyString()) : List.of("Deleted candidate " + parts.get(2) + " from Generator Databank.");
            }
            default -> List.of(
                "Usage: /generator <subcommand> [options]",
                "  /generator status",
                "  /generator start [--symbol MES] [--tf H1] [--years 2020-2025] [--oos-pct 25] [--mode GA|RANDOM]",
                "                   [--archetype ALL|TREND|MEAN_REVERSION|BREAKOUT] [--direction BOTH|LONG|SHORT]",
                "                   [--pop 50] [--gens 10] [--blocks RSI,ATR,EMA,BOLLINGER] [--min-trades 30]",
                "                   [--min-pf 1.3] [--min-oos-pf 1.1] [--max-dd 20] [--wait]",
                "  /generator stop",
                "  /generator databank [--top N] [--symbol S]",
                "  /generator inspect <candidateId> [--code]",
                "  /generator promote <candidateId>",
                "  /generator backtest <candidateId>",
                "  /generator delete <candidateId|--all>"
            );
        };
    }

    private static List<String> generatorStatus(ControlPlaneClient client, boolean jsonMode)
        throws IOException, InterruptedException {
        JsonNode st = client.generatorStatus();
        if (jsonMode) {
            return List.of(st.toPrettyString());
        }
        JsonNode cfg = st.path("config");
        String searchMode = cfg.hasNonNull("searchMode") ? cfg.path("searchMode").asText() : st.path("searchMode").asText("GA");
        String symbol = cfg.hasNonNull("symbol") ? cfg.path("symbol").asText() : st.path("symbol").asText("—");
        String timeframe = cfg.hasNonNull("timeframe") ? cfg.path("timeframe").asText() : st.path("timeframe").asText("—");
        String yearSpec = cfg.hasNonNull("yearSpec") ? cfg.path("yearSpec").asText() : st.path("yearSpec").asText("—");
        int maxGens = st.has("maxGenerations") ? st.path("maxGenerations").asInt(0) : st.path("totalGenerations").asInt(0);
        int dbCount = st.has("databankCount") ? st.path("databankCount").asInt(0) : st.path("databankSize").asInt(0);

        List<String> lines = new ArrayList<>();
        lines.add("Strategy Generator Status:");
        lines.add(String.format(Locale.ROOT, "  Running: %s | Job ID: %s | Mode: %s | Symbol: %s (%s, %s)",
            st.path("running").asBoolean(false),
            st.path("jobId").asText("—"),
            searchMode,
            symbol,
            timeframe,
            yearSpec));
        lines.add(String.format(Locale.ROOT, "  Progress: Gen %d/%d (%d%%) | Evaluated: %d | Passed Gates: %d | Databank Size: %d",
            st.path("currentGeneration").asInt(0),
            maxGens,
            st.path("progressPct").asInt(0),
            st.path("evaluatedCount").asInt(0),
            st.path("passedCount").asInt(0),
            dbCount));
        String msg = st.path("message").asText("");
        if (!msg.isBlank()) {
            lines.add("  Message : " + msg);
        }
        return lines;
    }

    private static List<String> generatorStart(
        ControlPlaneClient client,
        List<String> parts,
        Consumer<String> liveOutput,
        boolean jsonMode
    ) throws IOException, InterruptedException {
        String symbol = extractFlag(parts, "--symbol", null);
        if (symbol == null) symbol = "EUR_USD";
        symbol = symbol.toUpperCase(Locale.ROOT);
        String tf = extractFlag(parts, "--tf", "--timeframe");
        if (tf == null) tf = "H1";
        String years = extractFlag(parts, "--years", "--year");
        if (years == null) years = "2020-2025";
        int oosPct = (int) parseDoubleFlag(parts, "--oos-pct", 25);
        String mode = extractFlag(parts, "--mode", null);
        if (mode == null) mode = "GA";
        String archetype = extractFlag(parts, "--archetype", null);
        if (archetype == null) archetype = "ALL";
        String direction = extractFlag(parts, "--direction", null);
        if (direction == null) direction = "BOTH";
        String orderType = extractFlag(parts, "--order-type", null);
        if (orderType == null) orderType = "MARKET";
        int pop = (int) parseDoubleFlag(parts, "--pop", 50);
        int gens = (int) parseDoubleFlag(parts, "--gens", 10);
        int minTrades = (int) parseDoubleFlag(parts, "--min-trades", 30);
        double minPf = parseDoubleFlag(parts, "--min-pf", 1.30);
        double minOosPf = parseDoubleFlag(parts, "--min-oos-pf", 1.10);
        double minSharpe = parseDoubleFlag(parts, "--min-sharpe", 0.50);
        double maxDd = parseDoubleFlag(parts, "--max-dd", 20.0);

        String blocksRaw = extractFlag(parts, "--blocks", null);
        List<String> blocks = (blocksRaw != null && !blocksRaw.isBlank())
            ? Arrays.stream(blocksRaw.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList()
            : List.of();

        TuiDefaults.SmartPreset preset = TuiDefaults.resolvePreset(symbol);
        double capital = parseDoubleFlag(parts, "--capital", preset.capital());
        double lots = parseDoubleFlag(parts, "--lots", preset.lotSize());
        double comm = parseDoubleFlag(parts, "--comm", preset.commissionPerTrade());
        double slip = parseDoubleFlag(parts, "--slip", preset.slippagePct());
        boolean wait = hasFlag(parts, "--wait");

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("symbol", symbol);
        req.put("timeframe", tf.toUpperCase(Locale.ROOT));
        req.put("yearSpec", years);
        req.put("oosSplitPct", oosPct);
        req.put("searchMode", mode.toUpperCase(Locale.ROOT));
        req.put("archetype", archetype.toUpperCase(Locale.ROOT));
        req.put("direction", direction.toUpperCase(Locale.ROOT));
        req.put("entryOrderType", orderType.toUpperCase(Locale.ROOT));
        req.put("populationSize", pop);
        req.put("generations", gens);
        if (!blocks.isEmpty()) {
            req.put("buildingBlocks", blocks);
        }
        req.put("minTrades", minTrades);
        req.put("minProfitFactor", minPf);
        req.put("minOosProfitFactor", minOosPf);
        req.put("minSharpe", minSharpe);
        req.put("maxDrawdownPct", maxDd);
        req.put("initialCapital", capital);
        req.put("lotSize", lots);
        req.put("commissionPerTrade", comm);
        req.put("slippagePct", slip);

        JsonNode started = client.generatorStart(req);
        if (!wait) {
            if (jsonMode) {
                return List.of(started.toPrettyString());
            }
            return List.of(String.format(Locale.ROOT,
                "Started Strategy Generator job %s on %s %s (%s, OOS=%d%%, pop=%d, gens=%d, preset: cap=$%.0f, size=%.2f, comm=$%.2f)",
                started.path("jobId").asText(), symbol, tf, years, oosPct, pop, gens, capital, lots, comm));
        }

        // Poll until generator finishes
        int lastEval = -1;
        for (int i = 0; i < 1200; i++) {
            Thread.sleep(250);
            JsonNode st = client.generatorStatus();
            int eval = st.path("evaluatedCount").asInt(0);
            boolean running = st.path("running").asBoolean(false);
            int maxGens = st.has("maxGenerations") ? st.path("maxGenerations").asInt(0) : st.path("totalGenerations").asInt(0);
            if (liveOutput != null && eval != lastEval) {
                lastEval = eval;
                liveOutput.accept(String.format(Locale.ROOT,
                    "[Generator] Gen %d/%d | Evaluated: %d | Passed: %d | %s",
                    st.path("currentGeneration").asInt(0),
                    maxGens,
                    eval,
                    st.path("passedCount").asInt(0),
                    st.path("message").asText("")));
            }
            if (!running) {
                break;
            }
        }
        return generatorDatabank(client, List.of("generator", "databank", "--top", "10"), jsonMode);
    }

    private static List<String> generatorDatabank(ControlPlaneClient client, List<String> parts, boolean jsonMode)
        throws IOException, InterruptedException {
        JsonNode db = client.generatorDatabank();
        if (jsonMode) {
            return List.of(db.toPrettyString());
        }
        int top = (int) parseDoubleFlag(parts, "--top", 20);
        String symFilter = extractFlag(parts, "--symbol", null);
        JsonNode candidates = db.path("candidates");

        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "Generator Databank (%d total candidates):", candidates.size()));
        lines.add(String.format(Locale.ROOT,
            "%-28s | %-7s | %-4s | %-6s | %-6s | %-11s | %-11s | %-6s | %-6s | %-8s | %s",
            "Candidate ID", "Symbol", "TF", "Tier", "Score", "Trades(I/O)", "PF (IS/OOS)", "Sharpe", "MaxDD%", "MC VaR95", "Promoted"));
        lines.add("-".repeat(122));

        int shown = 0;
        for (JsonNode c : candidates) {
            String sym = c.path("symbol").asText();
            if (symFilter != null && !sym.equalsIgnoreCase(symFilter)) {
                continue;
            }
            if (shown++ >= top) {
                break;
            }
            String tradesStr = c.path("isTrades").asInt() + "/" + c.path("oosTrades").asInt();
            String pfStr = String.format(Locale.ROOT, "%.2f/%.2f",
                c.path("isProfitFactor").asDouble(), c.path("oosProfitFactor").asDouble());
            lines.add(String.format(Locale.ROOT,
                "%-28s | %-7s | %-4s | %-6s | %6.1f | %-11s | %-11s | %6.2f | %5.1f%% | $%7.0f | %s",
                truncate(c.path("id").asText(), 28),
                sym,
                c.path("timeframe").asText(),
                c.path("tier").asText(),
                c.path("compositeScore").asDouble(),
                tradesStr,
                pfStr,
                c.path("sharpeRatio").asDouble(),
                c.path("maxDrawdownPct").asDouble(),
                c.path("mcVar95").asDouble(),
                c.path("promoted").asBoolean() ? "YES" : "NO"));
        }
        return lines;
    }

    // =========================================================================
    // PILLAR 5: ROBUSTNESS & ANTI-OVERFIT SUITE (/wfa, /mc, /analytics, /export)
    // =========================================================================

    static List<String> wfa(
        ControlPlaneClient client,
        List<String> parts,
        Consumer<String> liveOutput,
        boolean jsonMode
    ) throws IOException, InterruptedException {
        String sub = parts.size() >= 2 ? parts.get(1).toLowerCase(Locale.ROOT) : "list";
        if (sub.startsWith("--")) {
            sub = "list";
        }
        return switch (sub) {
            case "list", "ls" -> {
                int limit = (int) parseDoubleFlag(parts, "--limit", 20);
                JsonNode runs = client.listWalkForwardRuns(limit);
                if (jsonMode) {
                    yield List.of(runs.toPrettyString());
                }
                List<String> lines = new ArrayList<>();
                lines.add("Walk-Forward Analysis Runs (" + runs.size() + "):");
                for (JsonNode r : runs) {
                    int totalFolds = r.path("totalFolds").asInt(0);
                    int completedFolds = r.path("completedFolds").asInt(0);
                    int pct = r.has("progressPct")
                        ? r.path("progressPct").asInt(0)
                        : (totalFolds > 0 ? (completedFolds * 100) / totalFolds : ("COMPLETED".equalsIgnoreCase(r.path("status").asText()) ? 100 : 0));
                    lines.add(String.format(Locale.ROOT, "  %-36s | %-24s | %-8s | %-10s | %d%% (folds %d/%d)",
                        r.path("wfaId").asText(r.path("id").asText("—")),
                        r.path("strategyName").asText("—"),
                        r.path("symbol").asText(r.path("instrument").asText("—")),
                        r.path("status").asText("—"),
                        pct,
                        completedFolds,
                        totalFolds));
                }
                yield lines;
            }
            case "status" -> {
                if (parts.size() < 3) {
                    yield List.of("Usage: /wfa status <wfaId>");
                }
                JsonNode prog = client.getWalkForwardProgress(parts.get(2));
                if (jsonMode) {
                    yield List.of(prog.toPrettyString());
                }
                int totalFolds = prog.path("totalFolds").asInt(0);
                int completedFolds = prog.path("completedFolds").asInt(0);
                int pct = prog.has("progressPct")
                    ? prog.path("progressPct").asInt(0)
                    : (totalFolds > 0 ? (completedFolds * 100) / totalFolds : 0);
                yield List.of(String.format(Locale.ROOT,
                    "WFA %s: status=%s progress=%d%% fold=%d/%d oosSharpe=%.2f wfe=%.2f",
                    parts.get(2),
                    prog.path("status").asText(),
                    pct,
                    completedFolds,
                    totalFolds,
                    prog.path("oosSharpe").asDouble(0.0),
                    prog.path("wfe").asDouble(0.0)));
            }
            case "report", "show" -> {
                if (parts.size() < 3) {
                    yield List.of("Usage: /wfa report <wfaId>");
                }
                JsonNode report = client.getWalkForwardReport(parts.get(2));
                if (jsonMode) {
                    yield List.of(report.toPrettyString());
                }
                yield formatWfaReport(parts.get(2), report);
            }
            case "run", "start" -> wfaRun(client, parts, liveOutput, jsonMode);
            default -> List.of(
                "Usage: /wfa <subcommand>",
                "  /wfa run <strategyName> <symbol> [startDate] [endDate] [--tf H1] [--is-days 180] [--oos-days 60] [--anchored] [--param name:min:max:step] [--wait]",
                "  /wfa list [--limit 20]",
                "  /wfa status <wfaId>",
                "  /wfa report <wfaId>"
            );
        };
    }

    private static List<String> wfaRun(
        ControlPlaneClient client,
        List<String> parts,
        Consumer<String> liveOutput,
        boolean jsonMode
    ) throws IOException, InterruptedException {
        if (parts.size() < 4 || parts.get(2).startsWith("-") || parts.get(3).startsWith("-")) {
            return List.of("Usage: /wfa run <strategyName> <symbol> [startDate] [endDate] [--tf H1] [--is-days 180] [--oos-days 60] [--anchored] [--param name:min:max:step] [--wait]");
        }
        String strategyName = parts.get(2);
        String symbol = parts.get(3).toUpperCase(Locale.ROOT);
        boolean hasStart = parts.size() >= 5 && !parts.get(4).startsWith("-");
        String startDate = hasStart ? parts.get(4) : "2020-01-01";
        String endDate = (hasStart && parts.size() >= 6 && !parts.get(5).startsWith("-")) ? parts.get(5) : "2024-12-31";
        String tf = extractFlag(parts, "--tf", "--timeframe");
        if (tf == null) tf = "H1";
        int isDays = (int) parseDoubleFlag(parts, "--is-days", 180);
        int oosDays = (int) parseDoubleFlag(parts, "--oos-days", 60);
        boolean anchored = hasFlag(parts, "--anchored");
        boolean wait = hasFlag(parts, "--wait");

        TuiDefaults.SmartPreset preset = TuiDefaults.resolvePreset(symbol);
        double capital = parseDoubleFlag(parts, "--capital", preset.capital());

        List<Map<String, Object>> paramRanges = new ArrayList<>();
        for (int i = 0; i < parts.size(); i++) {
            if ("--param".equals(parts.get(i))) {
                if (i + 1 >= parts.size() || parts.get(i + 1).startsWith("--")) {
                    throw new IllegalArgumentException("Missing value for --param (expected name:min:max:step)");
                }
                String[] sp = parts.get(i + 1).split(":");
                if (sp.length != 4 || sp[0].isBlank()) {
                    throw new IllegalArgumentException("Invalid --param format '" + parts.get(i + 1) + "', expected name:min:max:step");
                }
                double min = parseFinite(sp[1], "--param min");
                double max = parseFinite(sp[2], "--param max");
                double step = parseFinite(sp[3], "--param step");
                if (min > max || step <= 0.0) {
                    throw new IllegalArgumentException("Invalid --param range '" + parts.get(i + 1) + "': require min <= max and step > 0");
                }
                paramRanges.add(Map.of(
                    "name", sp[0],
                    "min", min,
                    "max", max,
                    "step", step
                ));
            }
        }
        if (paramRanges.isEmpty()) {
            paramRanges.add(Map.of("name", "period", "min", 10.0, "max", 30.0, "step", 5.0));
        }

        Map<String, Object> req = new LinkedHashMap<>();
        req.put("strategyName", strategyName);
        req.put("symbol", symbol);
        req.put("assetClass", preset.assetClass());
        req.put("timeframe", tf.toUpperCase(Locale.ROOT));
        req.put("startDate", startDate);
        req.put("endDate", endDate);
        req.put("inSampleDays", isDays);
        req.put("outOfSampleDays", oosDays);
        req.put("isAnchored", anchored);
        req.put("initialCapital", capital);
        req.put("parameterRanges", paramRanges);

        JsonNode started = client.startWalkForward(req);
        String wfaId = started.path("wfaId").asText();
        if (!wait) {
            return jsonMode ? List.of(started.toPrettyString())
                : List.of("Started Walk-Forward run " + wfaId + " (" + strategyName + " " + symbol + " " + startDate + ".." + endDate + ")");
        }

        for (int i = 0; i < 600; i++) {
            Thread.sleep(200);
            JsonNode prog = client.getWalkForwardProgress(wfaId);
            String st = prog.path("status").asText("");
            int totalFolds = prog.path("totalFolds").asInt(0);
            int completedFolds = prog.path("completedFolds").asInt(0);
            int pct = prog.has("progressPct")
                ? prog.path("progressPct").asInt(0)
                : (totalFolds > 0 ? (completedFolds * 100) / totalFolds : 0);
            if (liveOutput != null && i % 5 == 0) {
                liveOutput.accept(String.format(Locale.ROOT, "[WFA %s] %s (%d%%, fold %d/%d)", wfaId, st, pct, completedFolds, totalFolds));
            }
            if ("COMPLETED".equalsIgnoreCase(st)) {
                JsonNode report = client.getWalkForwardReport(wfaId);
                return jsonMode ? List.of(report.toPrettyString()) : formatWfaReport(wfaId, report);
            }
            if ("FAILED".equalsIgnoreCase(st)) {
                return List.of("WFA run " + wfaId + " FAILED: " + prog.path("errorMessage").asText("unknown error"));
            }
        }
        return List.of("WFA run " + wfaId + " still running — check /wfa report " + wfaId);
    }

    private static List<String> formatWfaReport(String wfaId, JsonNode report) {
        List<String> lines = new ArrayList<>();
        lines.add("=====================================================================");
        lines.add(" WALK-FORWARD ANALYSIS REPORT: " + wfaId);
        lines.add("=====================================================================");
        String symbol = report.hasNonNull("instrument")
            ? report.path("instrument").asText()
            : report.path("symbol").asText("—");
        double oosReturnPct = report.has("oosReturnPct")
            ? report.path("oosReturnPct").asDouble(0.0)
            : report.path("aggregatedOosNetProfit").asDouble(0.0);
        double oosSharpe = report.has("oosSharpe")
            ? report.path("oosSharpe").asDouble(0.0)
            : report.path("aggregatedOosSharpe").asDouble(0.0);
        double oosMaxDd = report.has("oosMaxDrawdownPct")
            ? report.path("oosMaxDrawdownPct").asDouble(0.0)
            : report.path("aggregatedOosMaxDrawdownPct").asDouble(0.0);
        double wfe = report.has("wfe")
            ? report.path("wfe").asDouble(0.0)
            : report.path("wfaEfficiency").asDouble(0.0);

        lines.add(String.format(Locale.ROOT, "  Strategy: %s | Instrument: %s | IS/OOS Days: %d/%d | Anchored: %s",
            report.path("strategyName").asText("—"),
            symbol,
            report.path("inSampleDays").asInt(180),
            report.path("outOfSampleDays").asInt(60),
            report.path("anchored").asBoolean(false)));
        lines.add(String.format(Locale.ROOT, "  Overall OOS Return: %.2f%% | OOS PF: %.2f | OOS Sharpe: %.2f | OOS MaxDD: %.2f%% | WFE: %.2f",
            oosReturnPct,
            report.path("oosProfitFactor").asDouble(0.0),
            oosSharpe,
            oosMaxDd,
            wfe));
        JsonNode folds = report.has("folds") ? report.get("folds") : report.path("windows");
        if (folds.isArray() && !folds.isEmpty()) {
            lines.add("");
            lines.add("  Folds (" + folds.size() + "):");
            for (JsonNode f : folds) {
                int idx = f.has("index") ? f.path("index").asInt(0) : f.path("foldIndex").asInt(0);
                double isMetric = f.has("isReturnPct") ? f.path("isReturnPct").asDouble(0.0) : f.path("isNetProfit").asDouble(0.0);
                double oosMetric = f.has("oosReturnPct") ? f.path("oosReturnPct").asDouble(0.0) : f.path("oosNetProfit").asDouble(0.0);
                JsonNode params = f.has("chosenParameters") ? f.get("chosenParameters") : f.path("bestParameters");
                lines.add(String.format(Locale.ROOT, "    Fold #%d: IS=%.2f (Sharpe=%.2f) -> OOS=%.2f (Sharpe=%.2f) | Params=%s",
                    idx,
                    isMetric,
                    f.path("isSharpe").asDouble(0.0),
                    oosMetric,
                    f.path("oosSharpe").asDouble(0.0),
                    params.toString()));
            }
        }
        return lines;
    }

    static List<String> monteCarlo(ControlPlaneClient client, List<String> parts, String fallbackRunId, boolean jsonMode)
        throws IOException, InterruptedException {
        String runId = (parts.size() >= 2 && !parts.get(1).startsWith("-")) ? parts.get(1) : fallbackRunId;
        if (runId == null || runId.isBlank()) {
            return List.of("Usage: /mc <runId> [--runs 1000] [--block-size 3]");
        }
        int sims = (int) parseDoubleFlag(parts, "--runs", parseDoubleFlag(parts, "--sims", 1000));
        int blockSize = (int) parseDoubleFlag(parts, "--block-size", 3);
        JsonNode mc = client.monteCarlo(runId, sims, blockSize);
        if (jsonMode) {
            return List.of(mc.toPrettyString());
        }

        List<String> lines = new ArrayList<>();
        lines.add("=====================================================================");
        lines.add(String.format(Locale.ROOT, " MONTE CARLO BOOTSTRAP REPORT (Run: %s | Sims: %d | Block: %d)",
            runId, mc.path("runs").asInt(sims), mc.path("blockSize").asInt(blockSize)));
        lines.add("=====================================================================");
        lines.add(String.format(Locale.ROOT, "  VaR (95%% Confidence) : $%.2f", mc.path("var95").asDouble()));
        lines.add(String.format(Locale.ROOT, "  Probability of Loss  : %.2f%%", mc.path("probabilityOfLoss").asDouble() * 100.0));
        lines.add(String.format(Locale.ROOT, "  Worst / Best Sim PnL : $%.2f / $%.2f",
            mc.path("worstPnl").asDouble(), mc.path("bestPnl").asDouble()));
        lines.add("");
        lines.add(String.format(Locale.ROOT, "  %-14s | %-12s | %-12s | %-12s",
            "Percentile", "Net PnL ($)", "Max DD (%)", "Sharpe Ratio"));
        lines.add("  " + "-".repeat(58));
        String[] labels = {"5th (Worst)", "25th", "50th (Median)", "75th", "95th (Best)"};
        JsonNode pnlP = mc.path("pnlPercentiles");
        JsonNode ddP = mc.path("drawdownPercentiles");
        JsonNode shP = mc.path("sharpePercentiles");
        for (int i = 0; i < labels.length; i++) {
            double p = pnlP.size() > i ? pnlP.get(i).asDouble() : 0.0;
            double d = ddP.size() > i ? ddP.get(i).asDouble() : 0.0;
            double s = shP.size() > i ? shP.get(i).asDouble() : 0.0;
            lines.add(String.format(Locale.ROOT, "  %-14s | $%11.2f | %11.2f%% | %12.2f", labels[i], p, d, s));
        }
        return lines;
    }

    static List<String> analytics(ControlPlaneClient client, List<String> parts, boolean jsonMode)
        throws IOException, InterruptedException {
        if (parts.size() < 2) {
            return List.of(
                "Usage: /analytics <heatmap|pareto> ...",
                "  /analytics heatmap <strategyId> <xParam> <yParam> [--symbol S] [--metric sharpeRatio]",
                "  /analytics pareto <strategyId> [--symbol S] [--x maxDrawdownPct] [--y totalReturnPct]"
            );
        }
        String sub = parts.get(1).toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "heatmap" -> {
                if (parts.size() < 5) {
                    yield List.of("Usage: /analytics heatmap <strategyId> <xParam> <yParam> [--symbol S] [--metric sharpeRatio]");
                }
                String strategyId = parts.get(2);
                String xParam = parts.get(3);
                String yParam = parts.get(4);
                String symbol = extractFlag(parts, "--symbol", null);
                String metric = extractFlag(parts, "--metric", null);
                JsonNode resp = client.analyticsHeatmap(strategyId, symbol, xParam, yParam, metric);
                if (jsonMode) {
                    yield List.of(resp.toPrettyString());
                }
                JsonNode data = resp.path("data");
                List<String> lines = new ArrayList<>();
                lines.add(String.format(Locale.ROOT, "Parameter Sensitivity Heatmap: %s (%s vs %s, metric=%s, cells=%d)",
                    strategyId, xParam, yParam, resp.path("metric").asText("sharpeRatio"), data.size()));
                for (JsonNode cell : data) {
                    lines.add(String.format(Locale.ROOT, "  %s=%s, %s=%s -> %.4f (run=%s)",
                        xParam, cell.path("x").asText(),
                        yParam, cell.path("y").asText(),
                        cell.path("value").asDouble(),
                        cell.path("runId").asText()));
                }
                yield lines;
            }
            case "pareto" -> {
                if (parts.size() < 3) {
                    yield List.of("Usage: /analytics pareto <strategyId> [--symbol S] [--x maxDrawdownPct] [--y totalReturnPct]");
                }
                String strategyId = parts.get(2);
                String symbol = extractFlag(parts, "--symbol", null);
                String mx = extractFlag(parts, "--x", null);
                String my = extractFlag(parts, "--y", null);
                JsonNode resp = client.analyticsPareto(strategyId, symbol, mx, my);
                if (jsonMode) {
                    yield List.of(resp.toPrettyString());
                }
                JsonNode frontier = resp.path("frontier");
                List<String> lines = new ArrayList<>();
                lines.add(String.format(Locale.ROOT, "Pareto Frontier: %s (%d optimal runs out of %d):",
                    strategyId, frontier.size(), resp.path("allRunsCount").asInt(0)));
                for (JsonNode r : frontier) {
                    lines.add(String.format(Locale.ROOT, "  %-36s | Sym=%-7s | Return=%.2f%% | MaxDD=%.2f%% | Sharpe=%.2f | PF=%.2f",
                        r.path("runId").asText(),
                        r.path("symbol").asText(),
                        r.path("totalReturnPct").asDouble(),
                        r.path("maxDrawdownPct").asDouble(),
                        r.path("sharpeRatio").asDouble(),
                        r.path("profitFactor").asDouble()));
                }
                yield lines;
            }
            default -> List.of("Unknown analytics subcommand: " + sub + " (use heatmap or pareto)");
        };
    }

    static List<String> export(ControlPlaneClient client, List<String> parts, String fallbackRunId)
        throws IOException, InterruptedException {
        String runId = (parts.size() >= 2 && !parts.get(1).startsWith("-")) ? parts.get(1) : fallbackRunId;
        if (runId == null || runId.isBlank()) {
            return List.of("Usage: /export <runId> [--format jsonl|html] [--out <filePath>]");
        }
        String format = extractFlag(parts, "--format", null);
        if (format == null) format = "jsonl";
        String outPath = extractFlag(parts, "--out", "--output");
        String content = client.exportRun(runId, format);
        if (outPath != null && !outPath.isBlank()) {
            Path p = Path.of(outPath);
            if (p.getParent() != null) {
                Files.createDirectories(p.getParent());
            }
            Files.writeString(p, content);
            return List.of("Exported run " + runId + " (" + format.toUpperCase(Locale.ROOT) + ") to " + p.toAbsolutePath());
        }
        return List.of(content);
    }

    static List<String> gates(ControlPlaneClient client, boolean jsonMode)
        throws IOException, InterruptedException {
        JsonNode g = client.getPromoteThresholds();
        if (jsonMode) {
            return List.of(g.toPrettyString());
        }
        return List.of("Promote Gate Thresholds:", g.toPrettyString());
    }

    static List<String> portfolio(ControlPlaneClient client, boolean jsonMode)
        throws IOException, InterruptedException {
        JsonNode balances = client.brokerBalances();
        JsonNode margins = client.portfolioMargins();
        if (jsonMode) {
            Map<String, Object> combined = new LinkedHashMap<>();
            combined.put("balances", balances.path("balances"));
            combined.put("margins", margins);
            return List.of(new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(combined));
        }
        List<String> lines = new ArrayList<>();
        lines.add("Portfolio & Broker Balances:");
        lines.add(String.format(Locale.ROOT, "  Total Equity: $%.2f | Available Funds: $%.2f | Margin Util: %.2f%% | PDT Restricted: %s",
            margins.path("totalEquity").asDouble(0.0),
            margins.path("availableFunds").asDouble(0.0),
            margins.path("marginUtilizationPct").asDouble(0.0),
            margins.path("pdtRestricted").asBoolean(false)));
        JsonNode balArr = balances.path("balances");
        if (balArr.isArray() && !balArr.isEmpty()) {
            lines.add("  Connected Broker Accounts:");
            for (JsonNode b : balArr) {
                lines.add(String.format(Locale.ROOT, "    - %-14s (%-6s): Balance=%.2f %s | Equity=%.2f",
                    b.path("id").asText(),
                    b.path("provider").asText(),
                    b.path("balance").asDouble(),
                    b.path("currency").asText("USD"),
                    b.path("equity").asDouble()));
            }
        } else {
            lines.add("  No active broker balances returned.");
        }
        return lines;
    }

    // =========================================================================
    // FLAG PARSING HELPERS
    // =========================================================================

    static boolean hasFlag(List<String> parts, String flag) {
        for (String p : parts) {
            if (flag.equalsIgnoreCase(p)) {
                return true;
            }
        }
        return false;
    }

    static String extractFlag(List<String> parts, String flag1, String flag2) {
        for (int i = 0; i < parts.size(); i++) {
            String token = parts.get(i);
            if (token.equalsIgnoreCase(flag1) || (flag2 != null && token.equalsIgnoreCase(flag2))) {
                if (i + 1 >= parts.size() || parts.get(i + 1).startsWith("--")) {
                    throw new IllegalArgumentException("Missing value for flag " + token);
                }
                return parts.get(i + 1);
            }
        }
        return null;
    }

    static double parseDoubleFlag(List<String> parts, String flag, double defaultValue) {
        String raw = extractFlag(parts, flag, null);
        if (raw == null) {
            return defaultValue;
        }
        return parseFinite(raw, flag);
    }

    static Double parseNullableDoubleFlag(List<String> parts, String flag) {
        String raw = extractFlag(parts, flag, null);
        if (raw == null) {
            return null;
        }
        return parseFinite(raw, flag);
    }

    private static double parseFinite(String raw, String label) {
        try {
            double v = Double.parseDouble(raw);
            if (!Double.isFinite(v)) {
                throw new IllegalArgumentException("Non-finite number for " + label + ": " + raw);
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid numeric value for " + label + ": " + raw);
        }
    }

    private static String joinArray(JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.isEmpty()) {
            return "—";
        }
        List<String> items = new ArrayList<>();
        for (JsonNode n : arr) {
            items.add(n.asText());
        }
        return String.join(",", items);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}

