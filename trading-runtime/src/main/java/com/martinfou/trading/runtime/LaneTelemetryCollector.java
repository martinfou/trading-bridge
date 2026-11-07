package com.martinfou.trading.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martinfou.trading.core.FuturesRegistry;
import com.martinfou.trading.data.ibkr.IbkrTradeSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects persisted IBKR executions/round-trips and active strategy state files from the local
 * runtime directories on the Control Plane server.
 *
 * <p>Purely local filesystem based: no remote hostnames or SSH calls are ever used.</p>
 */
public final class LaneTelemetryCollector {

    private static final Logger log = LoggerFactory.getLogger(LaneTelemetryCollector.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** IBKR sentinel threshold (Double.MAX_VALUE ~ 1.7976931348623157e308). */
    private static final double SENTINEL_THRESHOLD = 1.0e290;
    private static final long DEDUP_WINDOW_SECONDS = 20L;
    private static final long DEFAULT_STALE_THRESHOLD_SECONDS = 7200L;

    private static final Pattern REVISION_PATTERN = Pattern.compile("^(.*)\\.(\\d+)$");
    private static final Pattern STRATEGY_SUFFIX_PATTERN =
        Pattern.compile("_(MGC|MNQ|MES|M2K|MCL|GC|NQ|ES)[A-Z0-9]*$", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter IBKR_TIME_FORMAT =
        DateTimeFormatter.ofPattern("yyyyMMdd HH:mm:ss", Locale.ROOT);
    private static final Map<String, String> LEGACY_STRATEGY_ALIASES = Map.of(
        "sq-mgc-m30-breakout-0-24542", "MgcM30Breakout49205",
        "sq-mgc-m30-breakout-1-29-102", "MgcM30Breakout89854",
        "sq-mgc-m30-breakout-0-32921", "MgcM30Breakout92728"
    );

    private final List<Path> fillsDirs;
    private final List<Path> pnlStateFiles;
    private final List<Path> futuresStateDirs;
    private final List<Path> oandaPaperStateDirs;
    private final List<Path> oandaDevStateDirs;

    public LaneTelemetryCollector() {
        this(
            defaultFillsDirs(),
            defaultPnlStateFiles(),
            defaultFuturesStateDirs(),
            defaultOandaPaperStateDirs(),
            defaultOandaDevStateDirs()
        );
    }

    public LaneTelemetryCollector(
        List<Path> fillsDirs,
        List<Path> pnlStateFiles,
        List<Path> futuresStateDirs,
        List<Path> oandaPaperStateDirs,
        List<Path> oandaDevStateDirs
    ) {
        this.fillsDirs = fillsDirs != null ? List.copyOf(fillsDirs) : List.of();
        this.pnlStateFiles = pnlStateFiles != null ? List.copyOf(pnlStateFiles) : List.of();
        this.futuresStateDirs = futuresStateDirs != null ? List.copyOf(futuresStateDirs) : List.of();
        this.oandaPaperStateDirs = oandaPaperStateDirs != null ? List.copyOf(oandaPaperStateDirs) : List.of();
        this.oandaDevStateDirs = oandaDevStateDirs != null ? List.copyOf(oandaDevStateDirs) : List.of();
    }

    private static List<Path> defaultFillsDirs() {
        List<Path> dirs = new ArrayList<>();
        String envDir = System.getenv("TB_FUTURES_FILLS_DIR");
        if (envDir != null && !envDir.isBlank()) {
            dirs.add(Paths.get(envDir.trim()));
        }
        dirs.add(Paths.get("data", "fills"));
        return dirs;
    }

    private static List<Path> defaultPnlStateFiles() {
        List<Path> files = new ArrayList<>();
        String envPath = System.getenv("HERMES_IBKR_STATE_PATH");
        if (envPath != null && !envPath.isBlank()) {
            files.add(Paths.get(envPath.trim()));
        }
        String userHome = System.getProperty("user.home");
        if (userHome != null && !userHome.isBlank()) {
            files.add(Paths.get(userHome, ".local", "share", "hermes-ibkr", "pnl_state.json"));
        }
        files.add(Paths.get("data", "runtime", "hermes-ibkr-state.json"));
        return files;
    }

    private static List<Path> defaultFuturesStateDirs() {
        List<Path> dirs = new ArrayList<>();
        String envDir = System.getenv("TB_FUTURES_STATE_DIR");
        if (envDir != null && !envDir.isBlank()) {
            dirs.add(Paths.get(envDir.trim()));
        }
        dirs.add(Paths.get("data", "state", "ibkr-paper"));
        dirs.add(Paths.get("data", "state", "ibkr-live"));
        return dirs;
    }

    private static List<Path> defaultOandaPaperStateDirs() {
        List<Path> dirs = new ArrayList<>();
        String envDir = System.getenv("TB_STATE_DIR");
        if (envDir != null && !envDir.isBlank()) {
            dirs.add(Paths.get(envDir.trim()));
        }
        dirs.add(Paths.get("data", "state", "oanda-paper"));
        dirs.add(Paths.get("data", "state", "oanda-live"));
        dirs.add(Paths.get("data", "state"));
        return dirs;
    }

    private static List<Path> defaultOandaDevStateDirs() {
        return List.of();
    }

    /**
     * Collects and merges IBKR trades from live Gateway executions, persisted {@code pnl_state.json},
     * and {@code data/fills/*-paper.jsonl} round-trips, deduplicating twins within ±20s.
     */
    public List<Map<String, Object>> collectIbkrTrades(
        String venueId,
        String maskedAccountId,
        String fallbackCurrency,
        List<IbkrTradeSnapshot> liveTrades
    ) {
        Map<String, NativeExecution> nativeByBaseExecId = new LinkedHashMap<>();

        // 1. Load persisted pnl_state.json executions
        for (Path stateFile : pnlStateFiles) {
            if (stateFile == null || !Files.isRegularFile(stateFile)) {
                continue;
            }
            try {
                JsonNode root = MAPPER.readTree(Files.readString(stateFile, StandardCharsets.UTF_8));
                JsonNode execsNode = root != null ? root.get("executions") : null;
                if (execsNode != null && execsNode.isObject()) {
                    var fields = execsNode.fields();
                    while (fields.hasNext()) {
                        var entry = fields.next();
                        JsonNode node = entry.getValue();
                        if (node == null || !node.isObject()) {
                            continue;
                        }
                        String execId = textOrDefault(node, "exec_id", entry.getKey());
                        if (execId.isBlank() || execId.startsWith("jsonl.")) {
                            // jsonl.* entries in pnl_state are also handled or reconstructed below;
                            // if present in pnl_state, only ingest them as fallback round-trips if no JSONL file exists
                            continue;
                        }
                        NativeExecution parsed = parsePnlStateExecution(execId, node);
                        if (parsed != null) {
                            mergeNativeRevision(nativeByBaseExecId, parsed);
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to read IBKR pnl_state file {}: {}", stateFile, e.getMessage());
            }
        }

        // 2. Merge live Gateway executions (if any)
        if (liveTrades != null) {
            for (IbkrTradeSnapshot t : liveTrades) {
                if (t == null || t.execId() == null || t.execId().isBlank()) {
                    continue;
                }
                Instant closeInstant = parseIbkrTime(t.time());
                Double realized = isFiniteNonSentinel(t.realizedPnL()) && Math.abs(t.realizedPnL()) > 1e-9
                    ? t.realizedPnL()
                    : null;
                Double comm = isFiniteNonSentinel(t.commission()) ? t.commission() : 0.0;
                String normSide = normalizeOrderSide(t.side());
                NativeExecution liveExec = new NativeExecution(
                    t.execId(),
                    t.orderId() != null ? t.orderId() : "",
                    t.symbol() != null ? t.symbol() : "MGC",
                    normSide,
                    null,
                    t.quantity(),
                    t.price(),
                    null,
                    closeInstant,
                    null,
                    normalizeStrategyRef(t.strategyTag()),
                    "USD",
                    realized,
                    comm
                );
                mergeNativeRevision(nativeByBaseExecId, liveExec);
            }
        }

        // 3. Reconstruct round-trips from *-paper.jsonl and also any jsonl.* entries in pnl_state.json
        List<RoundTripRecord> jsonlRoundTrips = loadJsonlRoundTrips();
        List<RoundTripRecord> pnlStateJsonlFallback = loadPnlStateJsonlEntries();
        for (RoundTripRecord fallback : pnlStateJsonlFallback) {
            boolean alreadyPresent = jsonlRoundTrips.stream().anyMatch(rt ->
                sameSymbolRoot(rt.symbol, fallback.symbol)
                    && compatibleStrategies(rt.strategyTag, fallback.strategyTag)
                    && rt.closeTime != null
                    && fallback.closeTime != null
                    && Math.abs(Duration.between(rt.closeTime, fallback.closeTime).getSeconds()) <= DEDUP_WINDOW_SECONDS
            );
            if (!alreadyPresent) {
                jsonlRoundTrips.add(fallback);
            }
        }

        // 4. Pair/enrich native executions with JSONL round-trips (deduplicate within ±20s)
        List<RoundTripRecord> unmatchedJsonl = new ArrayList<>();
        Set<String> matchedCloseBaseIds = new HashSet<>();
        Set<String> consumedOpeningBaseIds = new HashSet<>();

        for (RoundTripRecord rt : jsonlRoundTrips) {
            String expectedCloseSide = oppositeSide(rt.entrySide);
            NativeExecution matchedClose = findMatchingNativeExecution(
                nativeByBaseExecId.values(),
                rt.symbol,
                rt.closeTime,
                rt.strategyTag,
                expectedCloseSide,
                matchedCloseBaseIds
            );
            if (matchedClose != null) {
                matchedCloseBaseIds.add(baseExecId(matchedClose.execId));
                matchedClose.entryPrice = rt.entryPrice;
                matchedClose.openTime = rt.openTime;
                matchedClose.entrySide = rt.entrySide;
                if (matchedClose.strategyTag == null || matchedClose.strategyTag.isBlank()) {
                    matchedClose.strategyTag = rt.strategyTag;
                }
                if (matchedClose.realizedPnL == null) {
                    matchedClose.realizedPnL = rt.realizedPnL;
                }
                // Also mark any native opening execution within ±20s of rt.openTime as consumed
                if (rt.openTime != null) {
                    NativeExecution matchedOpen = findMatchingNativeExecution(
                        nativeByBaseExecId.values(),
                        rt.symbol,
                        rt.openTime,
                        rt.strategyTag,
                        rt.entrySide,
                        consumedOpeningBaseIds
                    );
                    if (matchedOpen != null && !matchedOpen.execId.equals(matchedClose.execId) && matchedOpen.realizedPnL == null) {
                        consumedOpeningBaseIds.add(baseExecId(matchedOpen.execId));
                    }
                }
            } else {
                unmatchedJsonl.add(rt);
            }
        }

        // Also check if any native closing execution already carries entry_price + opened_at_utc from pnl_state.json
        // and mark its corresponding opening execution as consumed so opening legs with sentinel P&L don't appear as bogus $0 rows
        for (NativeExecution exec : nativeByBaseExecId.values()) {
            if (exec.realizedPnL != null && exec.openTime != null) {
                for (NativeExecution candidateOpen : nativeByBaseExecId.values()) {
                    if (!candidateOpen.execId.equals(exec.execId)
                        && candidateOpen.realizedPnL == null
                        && candidateOpen.closeTime != null
                        && sameSymbolRoot(candidateOpen.symbol, exec.symbol)
                        && compatibleStrategies(candidateOpen.strategyTag, exec.strategyTag)
                        && Math.abs(Duration.between(candidateOpen.closeTime, exec.openTime).getSeconds()) <= DEDUP_WINDOW_SECONDS) {
                        consumedOpeningBaseIds.add(baseExecId(candidateOpen.execId));
                    }
                }
            }
        }

        // 5. Build unified output trade maps
        List<Map<String, Object>> result = new ArrayList<>();
        String defaultCurr = (fallbackCurrency != null && !fallbackCurrency.isBlank()) ? fallbackCurrency : "USD";

        for (var entry : nativeByBaseExecId.entrySet()) {
            if (consumedOpeningBaseIds.contains(entry.getKey())) {
                continue;
            }
            NativeExecution ex = entry.getValue();
            // Skip pure opening executions that have no realized P&L if a closed trade exists later for the same strategy
            if (ex.realizedPnL == null && hasLaterClosedExecution(nativeByBaseExecId.values(), ex)) {
                continue;
            }
            boolean isClosed = ex.realizedPnL != null;
            String tradeCurrency = (ex.currency != null && !ex.currency.isBlank()) ? ex.currency : defaultCurr;
            String displaySide = ex.entrySide != null && !ex.entrySide.isBlank()
                ? ex.entrySide
                : (isClosed ? oppositeSide(ex.side) : ex.side);
            double entryPx = ex.entryPrice != null && ex.entryPrice > 0.0 ? ex.entryPrice : ex.price;
            Double exitPx = isClosed ? ex.price : null;
            String openIso = ex.openTime != null ? ex.openTime.toString() : (ex.closeTime != null ? ex.closeTime.toString() : "");
            String closeIso = ex.closeTime != null ? ex.closeTime.toString() : openIso;

            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("id", ex.execId);
            tm.put("orderId", ex.orderId);
            tm.put("venueId", venueId);
            tm.put("provider", "IBKR");
            tm.put("maskedAccountId", maskedAccountId != null ? maskedAccountId : "");
            tm.put("currency", tradeCurrency);
            tm.put("symbol", ex.symbol);
            tm.put("state", isClosed ? "CLOSED" : "OPEN");
            tm.put("side", displaySide);
            tm.put("quantity", ex.quantity);
            tm.put("entryPrice", entryPx);
            tm.put("exitPrice", exitPx);
            tm.put("stopLoss", 0.0);
            tm.put("takeProfit", 0.0);
            tm.put("realizedPnL", ex.realizedPnL != null ? ex.realizedPnL : 0.0);
            tm.put("unrealizedPnL", 0.0);
            tm.put("financing", ex.commission != null ? -Math.abs(ex.commission) : 0.0);
            tm.put("openTime", openIso);
            tm.put("closeTime", isClosed ? closeIso : null);
            tm.put("strategyTag", ex.strategyTag != null ? ex.strategyTag : "");
            result.add(tm);
        }

        for (RoundTripRecord rt : unmatchedJsonl) {
            Map<String, Object> tm = new LinkedHashMap<>();
            tm.put("id", rt.id);
            tm.put("orderId", rt.orderId);
            tm.put("venueId", venueId);
            tm.put("provider", "IBKR");
            tm.put("maskedAccountId", maskedAccountId != null ? maskedAccountId : "");
            tm.put("currency", rt.currency != null ? rt.currency : "USD");
            tm.put("symbol", rt.symbol);
            tm.put("state", "CLOSED");
            tm.put("side", rt.entrySide);
            tm.put("quantity", rt.quantity);
            tm.put("entryPrice", rt.entryPrice);
            tm.put("exitPrice", rt.exitPrice);
            tm.put("stopLoss", 0.0);
            tm.put("takeProfit", 0.0);
            tm.put("realizedPnL", rt.realizedPnL);
            tm.put("unrealizedPnL", 0.0);
            tm.put("financing", -Math.abs(rt.commission));
            tm.put("openTime", rt.openTime != null ? rt.openTime.toString() : "");
            tm.put("closeTime", rt.closeTime != null ? rt.closeTime.toString() : "");
            tm.put("strategyTag", rt.strategyTag != null ? rt.strategyTag : "");
            result.add(tm);
        }

        result.sort((a, b) -> {
            String ta = firstNonBlank(strVal(a.get("closeTime")), strVal(a.get("openTime")));
            String tb = firstNonBlank(strVal(b.get("closeTime")), strVal(b.get("openTime")));
            return tb.compareTo(ta);
        });

        return result;
    }

    /**
     * Collects active strategy runs from local state files ({@code data/state/futures/*-cursor.json},
     * {@code data/state/live-strategy-state-*.json}, and {@code data/state/dev/live-strategy-state-*.json})
     * that are not already present in {@code existingStrategyIds}.
     */
    public List<Map<String, Object>> collectLaneStrategyRuns(Set<String> existingStrategyIds, Instant now) {
        Set<String> seenStrategies = new HashSet<>();
        if (existingStrategyIds != null) {
            for (String existing : existingStrategyIds) {
                if (existing == null || existing.isBlank()) continue;
                seenStrategies.add(existing);
                String norm = normalizeStrategyRef(existing);
                seenStrategies.add(norm);
                String canonical = LEGACY_STRATEGY_ALIASES.get(norm);
                if (canonical != null) {
                    seenStrategies.add(canonical);
                }
                for (var aliasEntry : LEGACY_STRATEGY_ALIASES.entrySet()) {
                    if (aliasEntry.getValue().equals(norm)) {
                        seenStrategies.add(aliasEntry.getKey());
                    }
                }
            }
        }
        List<Map<String, Object>> runs = new ArrayList<>();
        Instant refNow = now != null ? now : Instant.now();

        // Pre-compute per-strategy IBKR trade metrics (realized PnL, closed count & open count)
        List<Map<String, Object>> ibkrTrades = collectIbkrTrades("ibkr-paper", "", "USD", List.of());
        Map<String, Double> ibkrPnlByStrategy = new LinkedHashMap<>();
        Map<String, Integer> ibkrCountByStrategy = new LinkedHashMap<>();
        Map<String, Integer> ibkrOpenCountByStrategy = new LinkedHashMap<>();
        for (Map<String, Object> t : ibkrTrades) {
            String strat = normalizeStrategyRef(strVal(t.get("strategyTag")));
            if (!strat.isBlank()) {
                String canonicalAlias = LEGACY_STRATEGY_ALIASES.get(strat);
                if ("CLOSED".equalsIgnoreCase(strVal(t.get("state")))) {
                    double pnl = t.get("realizedPnL") instanceof Number n ? n.doubleValue() : 0.0;
                    ibkrPnlByStrategy.merge(strat, pnl, Double::sum);
                    ibkrCountByStrategy.merge(strat, 1, Integer::sum);
                    if (canonicalAlias != null) {
                        ibkrPnlByStrategy.merge(canonicalAlias, pnl, Double::sum);
                        ibkrCountByStrategy.merge(canonicalAlias, 1, Integer::sum);
                    }
                } else if ("OPEN".equalsIgnoreCase(strVal(t.get("state")))) {
                    ibkrOpenCountByStrategy.merge(strat, 1, Integer::sum);
                    if (canonicalAlias != null) {
                        ibkrOpenCountByStrategy.merge(canonicalAlias, 1, Integer::sum);
                    }
                }
            }
        }

        // 1. IBKR Futures state cursors (data/state/ibkr-paper/*-cursor.json & data/state/ibkr-live/*-cursor.json)
        for (Path dir : futuresStateDirs) {
            if (dir == null || !Files.isDirectory(dir)) {
                continue;
            }
            boolean isLiveDir = dir.getFileName() != null
                && "ibkr-live".equalsIgnoreCase(dir.getFileName().toString());
            String mode = isLiveDir ? "LIVE" : "PAPER";
            String execLabel = isLiveDir ? "LIVE_IBKR" : "PAPER_IBKR";
            String brokerAcctId = isLiveDir ? "ibkr-live" : "ibkr-paper";

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*-cursor.json")) {
                List<Path> cursorFiles = new ArrayList<>();
                stream.forEach(cursorFiles::add);
                // Prefer canonical strategy cursor files before legacy sq-* alias cursors
                cursorFiles.sort(Comparator
                    .comparing((Path p) -> p.getFileName().toString().startsWith("sq-") ? 1 : 0)
                    .thenComparing(p -> p.getFileName().toString()));
                for (Path file : cursorFiles) {
                    try {
                        JsonNode node = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
                        String strategyId = textOrDefault(node, "strategy", "");
                        String normId = normalizeStrategyRef(strategyId);
                        if (strategyId.isBlank() || !seenStrategies.add(strategyId)) {
                            continue;
                        }
                        seenStrategies.add(normId);
                        String canonicalAlias = LEGACY_STRATEGY_ALIASES.get(normId);
                        if (canonicalAlias != null) {
                            seenStrategies.add(canonicalAlias);
                        }
                        for (var aliasEntry : LEGACY_STRATEGY_ALIASES.entrySet()) {
                            if (aliasEntry.getValue().equals(normId)) {
                                seenStrategies.add(aliasEntry.getKey());
                            }
                        }
                        String symbol = textOrDefault(node, "symbol", "MGC");
                        String inferredTf = com.martinfou.trading.runtime.ibkr.SqStrategyEvaluator.toShortTimeframeCode(
                            com.martinfou.trading.runtime.ibkr.SqStrategyEvaluator.resolveTimeframeForStrategy(strategyId, "1 hour")
                        );
                        String rawTf = textOrDefault(node, "timeframe", textOrDefault(node, "granularity", inferredTf));
                        String timeframe = com.martinfou.trading.runtime.ibkr.SqStrategyEvaluator.toShortTimeframeCode(rawTf);
                        String savedAt = textOrDefault(node, "savedAt", textOrDefault(node, "lastProcessedBarTimestamp", ""));
                        Instant lastAt = parseIsoInstant(savedAt);
                        boolean isStale = lastAt != null
                            && Duration.between(lastAt, refNow).getSeconds() > DEFAULT_STALE_THRESHOLD_SECONDS * 12;

                        double realizedPnL = ibkrPnlByStrategy.getOrDefault(normId, ibkrPnlByStrategy.getOrDefault(strategyId, 0.0));
                        int tradesCount = ibkrCountByStrategy.getOrDefault(normId, ibkrCountByStrategy.getOrDefault(strategyId, 0));
                        int openTrades = ibkrOpenCountByStrategy.getOrDefault(normId, ibkrOpenCountByStrategy.getOrDefault(strategyId, 0));

                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("runId", "lane-futures-" + strategyId);
                        item.put("strategyId", strategyId);
                        item.put("symbol", symbol);
                        item.put("mode", mode);
                        item.put("lane", "futures");
                        item.put("executionLabel", execLabel);
                        item.put("status", "RUNNING");
                        item.put("isStale", isStale);
                        if (!savedAt.isBlank()) {
                            item.put("lastEventAt", savedAt);
                        }
                        item.put("openTrades", openTrades);
                        item.put("tradesCount", tradesCount);
                        item.put("openPnL", 0.0);
                        item.put("realizedPnL", round2(realizedPnL));
                        item.put("totalPnL", round2(realizedPnL));
                        item.put("netQuantity", (double) openTrades);
                        item.put("netSide", openTrades > 0 ? "OPEN" : "FLAT");
                        item.put("positions", List.of());
                        item.put("gaps", List.of());
                        item.put("eventCount", tradesCount);
                        item.put("configSnapshot", Map.of(
                            "mode", mode,
                            "brokerAccountId", brokerAcctId,
                            "resolvedExecutionLabel", execLabel,
                            "strategyTimeframe", timeframe,
                            "lane", "futures"
                        ));
                        runs.add(item);
                    } catch (Exception e) {
                        log.debug("Ignoring unreadable futures cursor {}: {}", file, e.getMessage());
                    }
                }
            } catch (IOException e) {
                log.debug("Failed to list futures state dir {}: {}", dir, e.getMessage());
            }
        }

        // 2. OANDA Paper & Dev strategy state files (live-strategy-state-*.json)
        collectOandaStateDirRuns(oandaPaperStateDirs, "paper", "oanda-paper", seenStrategies, refNow, runs);
        collectOandaStateDirRuns(oandaDevStateDirs, "dev", "oanda-012", seenStrategies, refNow, runs);

        runs.sort(Comparator.comparing((Map<String, Object> r) -> strVal(r.get("symbol")))
            .thenComparing(r -> strVal(r.get("strategyId"))));
        return runs;
    }

    private void collectOandaStateDirRuns(
        List<Path> dirs,
        String defaultLane,
        String defaultBrokerAccountId,
        Set<String> seenStrategies,
        Instant refNow,
        List<Map<String, Object>> out
    ) {
        for (Path dir : dirs) {
            if (dir == null || !Files.isDirectory(dir)) {
                continue;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "live-strategy-state-*.json")) {
                for (Path file : stream) {
                    try {
                        JsonNode node = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
                        String strategyId = textOrDefault(node, "strategy", "");
                        if (strategyId.isBlank() || !seenStrategies.add(strategyId)) {
                            continue;
                        }
                        String symbol = textOrDefault(node, "instrument", "GBP_JPY");
                        String granularity = textOrDefault(node, "granularity", "H1");
                        String lane = textOrDefault(node, "lane", defaultLane);
                        boolean paused = node.path("paused").asBoolean(false);
                        boolean killed = node.path("killed").asBoolean(false);
                        boolean inTrade = node.path("inTrade").asBoolean(false);
                        String savedAt = textOrDefault(node, "savedAt", textOrDefault(node, "lastBarTime", ""));
                        Instant lastAt = parseIsoInstant(savedAt);
                        boolean isStale = lastAt != null
                            && Duration.between(lastAt, refNow).getSeconds() > DEFAULT_STALE_THRESHOLD_SECONDS;

                        double realizedPnL = 0.0;
                        int ledgerCount = 0;
                        JsonNode ledger = node.get("pnlLedger");
                        if (ledger != null && ledger.isObject()) {
                            var fields = ledger.fields();
                            while (fields.hasNext()) {
                                var f = fields.next();
                                if (f.getValue() != null && f.getValue().isNumber()) {
                                    realizedPnL += f.getValue().asDouble();
                                    ledgerCount++;
                                }
                            }
                        }

                        List<Map<String, Object>> positions = new ArrayList<>();
                        double signedNetQty = 0.0;
                        JsonNode activeTrades = node.get("activeTrades");
                        if (activeTrades != null && activeTrades.isArray()) {
                            for (JsonNode tr : activeTrades) {
                                String side = textOrDefault(tr, "side", "BUY");
                                double qty = tr.path("quantity").asDouble(0.0);
                                boolean isBuy = "BUY".equalsIgnoreCase(side) || "LONG".equalsIgnoreCase(side);
                                signedNetQty += isBuy ? qty : -qty;
                                Map<String, Object> pos = new LinkedHashMap<>();
                                pos.put("symbol", textOrDefault(tr, "symbol", symbol));
                                pos.put("side", side);
                                pos.put("quantity", qty);
                                pos.put("entryPrice", tr.path("entryPrice").asDouble(0.0));
                                pos.put("stopLoss", tr.path("stopLoss").asDouble(0.0));
                                pos.put("takeProfit", tr.path("takeProfit").asDouble(0.0));
                                pos.put("entryTime", textOrDefault(tr, "entryTime", ""));
                                positions.add(pos);
                            }
                        }
                        double netQty = Math.abs(signedNetQty);
                        String netSide = signedNetQty > 1e-9 ? "LONG" : (signedNetQty < -1e-9 ? "SHORT" : "FLAT");

                        String status = killed ? "STOPPED" : (paused ? "PAUSED" : "RUNNING");
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("runId", "lane-" + lane + "-" + strategyId);
                        item.put("strategyId", strategyId);
                        item.put("symbol", symbol);
                        item.put("mode", "PAPER");
                        item.put("lane", lane);
                        item.put("inTrade", inTrade || !positions.isEmpty());
                        item.put("executionLabel", "PAPER_OANDA");
                        item.put("status", status);
                        item.put("isStale", isStale);
                        if (!savedAt.isBlank()) {
                            item.put("lastEventAt", savedAt);
                        }
                        item.put("openTrades", positions.size());
                        item.put("tradesCount", ledgerCount);
                        item.put("openPnL", 0.0);
                        item.put("realizedPnL", round2(realizedPnL));
                        item.put("totalPnL", round2(realizedPnL));
                        item.put("netQuantity", netQty);
                        item.put("netSide", netSide);
                        item.put("positions", positions);
                        item.put("gaps", List.of());
                        item.put("eventCount", node.path("totalEntries").asInt(ledgerCount));
                        item.put("configSnapshot", Map.of(
                            "mode", "PAPER",
                            "brokerAccountId", "dev".equalsIgnoreCase(lane) ? "oanda-012" : defaultBrokerAccountId,
                            "resolvedExecutionLabel", "PAPER_OANDA",
                            "strategyTimeframe", granularity,
                            "lane", lane
                        ));
                        out.add(item);
                    } catch (Exception e) {
                        log.debug("Ignoring unreadable OANDA state file {}: {}", file, e.getMessage());
                    }
                }
            } catch (IOException e) {
                log.debug("Failed to list OANDA state dir {}: {}", dir, e.getMessage());
            }
        }
    }

    private List<RoundTripRecord> loadJsonlRoundTrips() {
        List<RoundTripRecord> list = new ArrayList<>();
        for (Path dir : fillsDirs) {
            if (dir == null || !Files.isDirectory(dir)) {
                continue;
            }
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*-paper.jsonl")) {
                List<Path> sortedPaths = new ArrayList<>();
                stream.forEach(sortedPaths::add);
                sortedPaths.sort(Comparator.comparing(Path::toString));

                for (Path file : sortedPaths) {
                    parseJsonlFileRoundTrips(file, list);
                }
            } catch (IOException e) {
                log.debug("Failed to scan fills dir {}: {}", dir, e.getMessage());
            }
        }
        return list;
    }

    private void parseJsonlFileRoundTrips(Path file, List<RoundTripRecord> out) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            Map<String, JsonNode> openLegByKey = new LinkedHashMap<>();
            Map<String, Integer> stampCounts = new LinkedHashMap<>();
            int idx = 0;
            for (String raw : lines) {
                idx++;
                String trimmed = raw.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                JsonNode rec;
                try {
                    rec = MAPPER.readTree(trimmed);
                } catch (Exception ignored) {
                    continue;
                }
                String side = textOrDefault(rec, "side", "").toUpperCase(Locale.ROOT);
                String symbol = textOrDefault(rec, "symbol", "").toUpperCase(Locale.ROOT);
                String strategy = normalizeStrategyRef(textOrDefault(rec, "strategy", ""));
                if ((!"BUY".equals(side) && !"SELL".equals(side)) || symbol.isEmpty()) {
                    continue;
                }
                String legKey = strategy + "|" + extractSymbolRoot(symbol);
                JsonNode openLeg = openLegByKey.get(legKey);
                String openSide = openLeg != null ? textOrDefault(openLeg, "side", "").toUpperCase(Locale.ROOT) : "";
                if (openLeg == null || openSide.equals(side)) {
                    openLegByKey.put(legKey, rec);
                    continue;
                }

                double entryPrice = openLeg.path("filledPrice").asDouble(0.0);
                double exitPrice = rec.path("filledPrice").asDouble(0.0);
                double qty = Math.min(openLeg.path("quantity").asDouble(1.0), rec.path("quantity").asDouble(1.0));
                double openComm = openLeg.path("commission").asDouble(0.62);
                double closeComm = rec.path("commission").asDouble(0.62);
                Instant openDt = parseIsoInstant(textOrDefault(openLeg, "timestamp", ""));
                Instant closeDt = parseIsoInstant(textOrDefault(rec, "timestamp", ""));
                openLegByKey.remove(legKey);

                if (entryPrice <= 0.0 || exitPrice <= 0.0 || closeDt == null) {
                    continue;
                }
                double mult = resolveMultiplier(symbol);
                String entrySide = "SELL".equals(side) ? "BUY" : "SELL";
                double pts = "SELL".equals(side) ? (exitPrice - entryPrice) : (entryPrice - exitPrice);
                double realizedUsd = round2(pts * mult * qty - (openComm + closeComm));
                String stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT)
                    .withZone(ZoneOffset.UTC)
                    .format(closeDt);
                String baseRtId = "jsonl." + symbol + "." + stamp;
                int count = stampCounts.merge(baseRtId, 1, Integer::sum);
                String rtId = count > 1 ? baseRtId + "." + count : baseRtId;

                out.add(new RoundTripRecord(
                    rtId,
                    Integer.toString(90000 + idx),
                    symbol,
                    entrySide,
                    qty,
                    entryPrice,
                    exitPrice,
                    realizedUsd,
                    round2(openComm + closeComm),
                    openDt,
                    closeDt,
                    strategy,
                    "USD"
                ));
            }
        } catch (IOException e) {
            log.debug("Failed to read JSONL fills file {}: {}", file, e.getMessage());
        }
    }

    private List<RoundTripRecord> loadPnlStateJsonlEntries() {
        List<RoundTripRecord> list = new ArrayList<>();
        for (Path stateFile : pnlStateFiles) {
            if (stateFile == null || !Files.isRegularFile(stateFile)) {
                continue;
            }
            try {
                JsonNode root = MAPPER.readTree(Files.readString(stateFile, StandardCharsets.UTF_8));
                JsonNode execsNode = root != null ? root.get("executions") : null;
                if (execsNode != null && execsNode.isObject()) {
                    var fields = execsNode.fields();
                    while (fields.hasNext()) {
                        var entry = fields.next();
                        String key = entry.getKey();
                        JsonNode node = entry.getValue();
                        if (!key.startsWith("jsonl.") || node == null || !node.isObject()) {
                            continue;
                        }
                        JsonNode report = node.get("report");
                        Double realized = extractNonSentinelDouble(report, "realized_pnl");
                        if (realized == null) {
                            continue;
                        }
                        Double comm = extractNonSentinelDouble(report, "commission");
                        String symbol = textOrDefault(node, "symbol", "MGC");
                        String rawSide = normalizeOrderSide(textOrDefault(node, "side", "SLD"));
                        String entrySide = textOrDefault(node, "entry_side", oppositeSide(rawSide));
                        double exitPrice = node.path("price").asDouble(0.0);
                        double entryPrice = node.path("entry_price").asDouble(exitPrice);
                        double qty = node.path("shares").asDouble(1.0);
                        Instant closeTime = parseIbkrTime(textOrDefault(node, "time", ""));
                        Instant openTime = parseIsoInstant(textOrDefault(node, "opened_at_utc", ""));
                        String strategyTag = normalizeStrategyRef(textOrDefault(node, "order_ref", ""));
                        String currency = textOrDefault(node, "currency", "USD");

                        list.add(new RoundTripRecord(
                            baseExecId(key),
                            textOrDefault(node, "order_id", ""),
                            symbol,
                            entrySide,
                            qty,
                            entryPrice,
                            exitPrice,
                            round2(realized),
                            comm != null ? round2(comm) : 0.0,
                            openTime,
                            closeTime,
                            strategyTag,
                            currency
                        ));
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to read jsonl entries from {}: {}", stateFile, e.getMessage());
            }
        }
        return list;
    }

    private static NativeExecution parsePnlStateExecution(String execId, JsonNode node) {
        String orderId = textOrDefault(node, "order_id", "");
        String symbol = textOrDefault(node, "symbol", "MGC");
        String side = normalizeOrderSide(textOrDefault(node, "side", "BUY"));
        String entrySide = textOrDefault(node, "entry_side", "");
        double shares = node.path("shares").asDouble(1.0);
        double price = node.path("price").asDouble(0.0);
        Double entryPrice = node.hasNonNull("entry_price") ? node.get("entry_price").asDouble() : null;
        Instant closeTime = parseIbkrTime(textOrDefault(node, "time", ""));
        Instant openTime = parseIsoInstant(textOrDefault(node, "opened_at_utc", ""));
        String strategyTag = normalizeStrategyRef(textOrDefault(node, "order_ref", ""));
        String currency = textOrDefault(node, "currency", "USD");

        JsonNode report = node.get("report");
        Double realizedPnL = extractNonSentinelDouble(report, "realized_pnl");
        Double commission = extractNonSentinelDouble(report, "commission");
        if (currency.isBlank() && report != null) {
            currency = textOrDefault(report, "currency", "USD");
        }

        return new NativeExecution(
            execId,
            orderId,
            symbol,
            side,
            entrySide.isBlank() ? null : entrySide,
            shares,
            price,
            entryPrice,
            closeTime,
            openTime,
            strategyTag,
            currency,
            realizedPnL,
            commission
        );
    }

    private static void mergeNativeRevision(Map<String, NativeExecution> map, NativeExecution candidate) {
        String base = baseExecId(candidate.execId);
        int candidateRev = revisionNumber(candidate.execId);
        NativeExecution existing = map.get(base);
        if (existing == null || candidateRev > revisionNumber(existing.execId)) {
            if (existing != null) {
                if (candidate.entryPrice == null) candidate.entryPrice = existing.entryPrice;
                if (candidate.openTime == null) candidate.openTime = existing.openTime;
                if (candidate.entrySide == null) candidate.entrySide = existing.entrySide;
                if (candidate.realizedPnL == null) candidate.realizedPnL = existing.realizedPnL;
                if ((candidate.strategyTag == null || candidate.strategyTag.isBlank()) && existing.strategyTag != null) {
                    candidate.strategyTag = existing.strategyTag;
                }
            }
            map.put(base, candidate);
        } else {
            if (existing.entryPrice == null && candidate.entryPrice != null) existing.entryPrice = candidate.entryPrice;
            if (existing.openTime == null && candidate.openTime != null) existing.openTime = candidate.openTime;
            if (existing.entrySide == null && candidate.entrySide != null) existing.entrySide = candidate.entrySide;
            if (existing.realizedPnL == null && candidate.realizedPnL != null) existing.realizedPnL = candidate.realizedPnL;
        }
    }

    private static NativeExecution findMatchingNativeExecution(
        Iterable<NativeExecution> natives,
        String symbol,
        Instant targetTime,
        String strategyTag,
        String expectedSide,
        Set<String> alreadyMatchedBaseIds
    ) {
        if (targetTime == null) {
            return null;
        }
        for (NativeExecution ex : natives) {
            if (alreadyMatchedBaseIds != null && alreadyMatchedBaseIds.contains(baseExecId(ex.execId))) {
                continue;
            }
            if (ex.closeTime == null || !sameSymbolRoot(ex.symbol, symbol)) {
                continue;
            }
            if (expectedSide != null && !expectedSide.isBlank()
                && ex.side != null && !ex.side.isBlank()
                && !expectedSide.equalsIgnoreCase(ex.side)) {
                continue;
            }
            if (!compatibleStrategies(strategyTag, ex.strategyTag)) {
                continue;
            }
            long diffSec = Math.abs(Duration.between(ex.closeTime, targetTime).getSeconds());
            if (diffSec <= DEDUP_WINDOW_SECONDS) {
                return ex;
            }
        }
        return null;
    }

    private static boolean hasLaterClosedExecution(Iterable<NativeExecution> all, NativeExecution openExec) {
        if (openExec.closeTime == null) {
            return false;
        }
        for (NativeExecution other : all) {
            if (other == openExec || other.realizedPnL == null || other.closeTime == null) {
                continue;
            }
            if (sameSymbolRoot(other.symbol, openExec.symbol)
                && compatibleStrategies(other.strategyTag, openExec.strategyTag)
                && other.closeTime.isAfter(openExec.closeTime)) {
                return true;
            }
        }
        return false;
    }

    private static boolean compatibleStrategies(String a, String b) {
        String na = normalizeStrategyRef(a);
        String nb = normalizeStrategyRef(b);
        if (na.isBlank() || nb.isBlank()) {
            return true;
        }
        return na.equalsIgnoreCase(nb);
    }

    static String normalizeStrategyRef(String ref) {
        if (ref == null) {
            return "";
        }
        String trimmed = ref.trim();
        return STRATEGY_SUFFIX_PATTERN.matcher(trimmed).replaceAll("");
    }

    static String baseExecId(String execId) {
        if (execId == null) {
            return "";
        }
        Matcher m = REVISION_PATTERN.matcher(execId.trim());
        return m.matches() ? m.group(1) : execId.trim();
    }

    private static int revisionNumber(String execId) {
        if (execId == null) {
            return 0;
        }
        Matcher m = REVISION_PATTERN.matcher(execId.trim());
        if (!m.matches()) {
            return 0;
        }
        try {
            return Integer.parseInt(m.group(2));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static Instant parseIbkrTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.trim().replaceAll("\\s+", " ");
        if (cleaned.contains("T")) {
            Instant iso = parseIsoInstant(cleaned);
            if (iso != null) {
                return iso;
            }
        }
        if (cleaned.endsWith(" UTC") || cleaned.endsWith(" Z")) {
            cleaned = cleaned.substring(0, cleaned.lastIndexOf(' ')).trim();
        }
        try {
            if (cleaned.length() >= 17) {
                String datePart = cleaned.substring(0, 17);
                LocalDateTime ldt = LocalDateTime.parse(datePart, IBKR_TIME_FORMAT);
                return ldt.toInstant(ZoneOffset.UTC);
            }
        } catch (Exception ignored) {
            // fall through
        }
        return parseIsoInstant(raw);
    }

    private static Instant parseIsoInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static final Pattern CONTRACT_EXPIRY_SUFFIX = Pattern.compile("^([A-Z0-9]+?)(?:[FGHJKMNQUVXZ]\\d{1,4})$");

    private static String extractSymbolRoot(String s) {
        if (s == null) {
            return "";
        }
        String u = s.trim().toUpperCase(Locale.ROOT);
        if (u.isEmpty()) {
            return "";
        }
        Matcher m = CONTRACT_EXPIRY_SUFFIX.matcher(u);
        return m.matches() ? m.group(1) : u;
    }

    private static boolean sameSymbolRoot(String a, String b) {
        String ra = extractSymbolRoot(a);
        String rb = extractSymbolRoot(b);
        if (ra.isEmpty() || rb.isEmpty()) {
            return false;
        }
        return ra.equals(rb);
    }

    private static double resolveMultiplier(String symbol) {
        return FuturesRegistry.find(symbol)
            .map(c -> c.multiplier())
            .orElseGet(() -> switch (symbol.toUpperCase(Locale.ROOT)) {
                case "MGC" -> 10.0;
                case "MES", "M2K" -> 5.0;
                case "MNQ" -> 2.0;
                case "MCL" -> 100.0;
                default -> 1.0;
            });
    }

    private static Double extractNonSentinelDouble(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        JsonNode v = node.get(field);
        if (!v.isNumber()) {
            return null;
        }
        double d = v.asDouble();
        return isFiniteNonSentinel(d) ? d : null;
    }

    static boolean isFiniteNonSentinel(double value) {
        return Double.isFinite(value) && Math.abs(value) < SENTINEL_THRESHOLD;
    }

    private static String normalizeOrderSide(String side) {
        if (side == null) {
            return "BUY";
        }
        String upper = side.trim().toUpperCase(Locale.ROOT);
        if ("BOT".equals(upper) || "BUY".equals(upper) || "LONG".equals(upper)) {
            return "BUY";
        }
        if ("SLD".equals(upper) || "SELL".equals(upper) || "SHORT".equals(upper)) {
            return "SELL";
        }
        return upper;
    }

    private static String oppositeSide(String side) {
        return "SELL".equalsIgnoreCase(side) ? "BUY" : "SELL";
    }

    private static String textOrDefault(JsonNode node, String field, String fallback) {
        if (node == null || !node.hasNonNull(field)) {
            return fallback;
        }
        String text = node.get(field).asText();
        return text != null && !text.isBlank() ? text.trim() : fallback;
    }

    private static String strVal(Object o) {
        return o != null ? o.toString() : "";
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : (b != null ? b : "");
    }

    private static double round2(double val) {
        return Math.round(val * 100.0) / 100.0;
    }

    private static final class NativeExecution {
        final String execId;
        final String orderId;
        final String symbol;
        final String side;
        String entrySide;
        final double quantity;
        final double price;
        Double entryPrice;
        final Instant closeTime;
        Instant openTime;
        String strategyTag;
        final String currency;
        Double realizedPnL;
        final Double commission;

        NativeExecution(
            String execId,
            String orderId,
            String symbol,
            String side,
            String entrySide,
            double quantity,
            double price,
            Double entryPrice,
            Instant closeTime,
            Instant openTime,
            String strategyTag,
            String currency,
            Double realizedPnL,
            Double commission
        ) {
            this.execId = execId;
            this.orderId = orderId;
            this.symbol = symbol;
            this.side = side;
            this.entrySide = entrySide;
            this.quantity = quantity;
            this.price = price;
            this.entryPrice = entryPrice;
            this.closeTime = closeTime;
            this.openTime = openTime;
            this.strategyTag = strategyTag;
            this.currency = currency;
            this.realizedPnL = realizedPnL;
            this.commission = commission;
        }
    }

    private record RoundTripRecord(
        String id,
        String orderId,
        String symbol,
        String entrySide,
        double quantity,
        double entryPrice,
        double exitPrice,
        double realizedPnL,
        double commission,
        Instant openTime,
        Instant closeTime,
        String strategyTag,
        String currency
    ) {
    }
}
