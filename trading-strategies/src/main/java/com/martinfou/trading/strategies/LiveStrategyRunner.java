package com.martinfou.trading.strategies;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.core.TimeConventions;
import com.martinfou.trading.data.OandaPriceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * LiveStrategyRunner — Execute one or more strategies concurrently on OANDA practice/demo.
 *
 * Usage:
 *   LiveStrategyRunner <apiKey> <accountId> all [granularity] [intervalSec]
 *   LiveStrategyRunner <apiKey> <accountId> vwprevision consecbar [granularity] [intervalSec]
 *   LiveStrategyRunner <apiKey> <accountId> 2_31_177 [granularity] [intervalSec]
 *
 * Examples:
 *   LiveStrategyRunner KEY ACCT all                         → ALL strategies concurrently
 *   LiveStrategyRunner KEY ACCT vwprevision consecbar       → just those two
 *   LiveStrategyRunner KEY ACCT vwprevision H1 60           → single strategy
 *
 * Features:
 *   - Concurrent strategies: each runs in its own thread
 *   - Per-strategy state persistence: /tmp/live-{name}-state.json
 *   - Aggregated monitor file: /tmp/paper-trading-status.json (for monitoring cron)
 *   - Graceful shutdown (SIGTERM/SIGINT saves all state)
 *   - Crash recovery per strategy
 *   - SLF4J logging with trade entry/exit P&L
 */
public class LiveStrategyRunner implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(LiveStrategyRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(true);

    /** Aggregated monitor file for the paper-trading cron — written by the orchestrator. */
    private static final Path AGGREGATED_MONITOR = Paths.get("/tmp/paper-trading-status.json");

    /** Config file path (mounted in Docker at /app/config/ or local) */
    private static final Path CONFIG_PATH = Paths.get("/app/config/live-config.json");
    private static JsonNode LIVE_CONFIG = null;
    private static double DEFAULT_RISK_PCT = 1.5;
    /** Conservative fallback for strategies not in config — no backtest data available. */
    private static final double UNKNOWN_STRATEGY_RISK_PCT = 0.5;
    private static final Map<String, Double> STRATEGY_RISK_PCT = new ConcurrentHashMap<>();

    // ---- Per-instance paths ----
    private final Path stateFile;
    private final Path monitorFile;

    // ---- Components ----
    private final OandaPriceClient priceClient;
    private final OandaExecutor executor;
    private final String apiKey;
    private final String accountId;
    private final Strategy strategy;
    private final String strategyShortName;
    private final String granularity;
    private final int intervalSec;

    // ---- Runtime state ----
    private final List<Bar> barHistory = new ArrayList<>();
    private final List<ActiveTrade> activeTrades = new ArrayList<>();
    private final List<PendingStop> pendingStops = new ArrayList<>();
    private final Set<String> executedBars = new HashSet<>();
    private int totalEntries = 0;
    private int totalExits = 0;
    private double totalPnl = 0.0;
    private Instant lastStateSave = Instant.MIN;
    private Instant lastBarTime = null;

    // ---- Shared orchestrator state ----
    /** All running runners (for aggregated monitor). Populated by main(). */
    private static final Map<String, LiveStrategyRunner> ACTIVE_RUNNERS = new ConcurrentHashMap<>();

    // ---- Persisted state ----
    private static final class ActiveTrade {
        String tradeId;
        String symbol;
        String side;
        double entryPrice;
        double quantity;
        double stopLoss;
        double takeProfit;
        Instant entryTime;
        double unrealizedPnl;

        ActiveTrade() {}

        ActiveTrade(String tradeId, String symbol, String side, double entryPrice,
                    double quantity, double stopLoss, double takeProfit, Instant entryTime) {
            this.tradeId = tradeId;
            this.symbol = symbol;
            this.side = side;
            this.entryPrice = entryPrice;
            this.quantity = quantity;
            this.stopLoss = stopLoss;
            this.takeProfit = takeProfit;
            this.entryTime = entryTime;
            this.unrealizedPnl = 0.0;
        }
    }

    private static final class PendingStop {
        String orderId;
        String symbol;
        String side;
        double price;
        double quantity;
        double stopLoss;
        double takeProfit;

        PendingStop() {}

        PendingStop(String orderId, String symbol, String side, double price,
                    double quantity, double stopLoss, double takeProfit) {
            this.orderId = orderId;
            this.symbol = symbol;
            this.side = side;
            this.price = price;
            this.quantity = quantity;
            this.stopLoss = stopLoss;
            this.takeProfit = takeProfit;
        }
    }

    // ========================================================================
    // Constructor
    // ========================================================================

    public LiveStrategyRunner(String apiKey, String accountId, Strategy strategy,
                              String strategyShortName, String granularity, int intervalSec) {
        this.apiKey = apiKey;
        this.accountId = accountId;
        this.strategy = strategy;
        this.strategyShortName = strategyShortName;
        this.granularity = granularity;
        this.intervalSec = intervalSec;
        this.priceClient = new OandaPriceClient(apiKey, accountId, true);
        this.executor = new OandaExecutor(apiKey, accountId, true);
        // Per-strategy state files
        this.stateFile = Paths.get("/tmp/live-strategy-state-" + strategyShortName + ".json");
        this.monitorFile = Paths.get("/tmp/paper-status-" + strategyShortName + ".json");
    }

    // ========================================================================
    // Main — entry point
    // ========================================================================

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: LiveStrategyRunner <apiKey> <accountId> <strategyName...> [granularity] [intervalSec]");
            System.out.println("  strategyName: 'all', space-separated list, or single name");
            System.out.println("  granularity:  H1 (default), H4, D");
            System.out.println("  intervalSec:  60 (default) — loop interval in seconds");
            System.out.println();
            System.out.println("Examples:");
            System.out.println("  LiveStrategyRunner KEY ACCT all");
            System.out.println("  LiveStrategyRunner KEY ACCT vwprevision consecbar");
            System.out.println("  LiveStrategyRunner KEY ACCT 2_31_177 H1 120");
            listStrategies();
            return;
        }

        String apiKey = args[0];
        String accountId = args[1];
        String granularity = "H1";
        int intervalSec = 60;

        // Parse strategy names + optional granularity/interval
        // args[2..] = strategy names (or 'all') with optional trailing granularity/interval
        int argIdx = 2;
        List<String> strategyNames = new ArrayList<>();
        while (argIdx < args.length) {
            String a = args[argIdx];
            if (a.equalsIgnoreCase("M1") || a.equalsIgnoreCase("M5") || a.equalsIgnoreCase("M15")
                || a.equalsIgnoreCase("M30") || a.equalsIgnoreCase("H1") || a.equalsIgnoreCase("H4")
                || a.equalsIgnoreCase("D") || a.equalsIgnoreCase("W")) {
                granularity = a.toUpperCase();
                // Next arg might be interval
                if (argIdx + 1 < args.length) {
                    try {
                        intervalSec = Integer.parseInt(args[argIdx + 1]);
                        argIdx++;
                    } catch (NumberFormatException e) {
                        // not an interval, leave default
                    }
                }
                argIdx++;
                break;
            }
            if (a.equals("all")) {
                strategyNames.add("all");
                argIdx++;
                // If 'all' is given, no more strategy names follow
                // But check for granularity/interval
                if (argIdx < args.length && (args[argIdx].equalsIgnoreCase("H1") || args[argIdx].equalsIgnoreCase("H4") || args[argIdx].equalsIgnoreCase("D"))) {
                    granularity = args[argIdx].toUpperCase();
                    argIdx++;
                    if (argIdx < args.length) {
                        try { intervalSec = Integer.parseInt(args[argIdx]); argIdx++; } catch (NumberFormatException e) {}
                    }
                }
                break;
            }
            strategyNames.add(a);
            argIdx++;
        }

        if (strategyNames.isEmpty()) {
            System.err.println("ERROR: No strategy names provided.");
            listStrategies();
            System.exit(1);
        }

        // Resolve strategies (deduplicate if "all" resolves the same as individual names)
        Map<String, Class<? extends Strategy>> allStrategies = getStrategyMap();
        List<Map.Entry<String, Strategy>> resolved = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();

        for (String name : strategyNames) {
            if (name.equals("all")) {
                for (var entry : allStrategies.entrySet()) {
                    if (usedNames.add(entry.getKey())) {
                        resolved.add(Map.entry(entry.getKey(), entry.getValue().getDeclaredConstructor().newInstance()));
                    }
                }
            } else {
                Class<? extends Strategy> clazz = allStrategies.get(name);
                if (clazz == null) {
                    System.err.println("WARNING: Unknown strategy '" + name + "' — skipping.");
                    continue;
                }
                if (usedNames.add(name)) {
                    resolved.add(Map.entry(name, clazz.getDeclaredConstructor().newInstance()));
                }
            }
        }

        if (resolved.isEmpty()) {
            System.err.println("ERROR: No valid strategies to run.");
            System.exit(1);
        }

        log.info("╔════════════════════════════════════════════════════╗");
        log.info("║     🚀 LiveStrategyRunner — OANDA Practice        ║");
        log.info("╠════════════════════════════════════════════════════╣");
        log.info("║ Account: {}      ", accountId);
        log.info("║ API:     api-fxpractice.oanda.com                  ");
        log.info("║ Strategies: {} (concurrent)                  ", resolved.size());
        for (var entry : resolved) {
            log.info("║   - {} → {}", entry.getKey(), entry.getValue().name());
        }
        log.info("║ Granularity: {}  Interval: {}s                  ", granularity, intervalSec);
        log.info("╚════════════════════════════════════════════════════╝");

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            RUNNING.set(false);
            log.info("🛑 Shutdown signal received — saving all state...");
        }));

        // Load risk config
        loadConfig();

        // Launch each strategy in its own thread
        List<Thread> threads = new ArrayList<>();
        for (var entry : resolved) {
            var runner = new LiveStrategyRunner(apiKey, accountId, entry.getValue(),
                entry.getKey(), granularity, intervalSec);
            ACTIVE_RUNNERS.put(entry.getKey(), runner);
            Thread t = new Thread(runner, "strat-" + entry.getKey());
            t.setDaemon(false);
            threads.add(t);
            t.start();
            log.info("🧵 Launched thread for '{}' ({})", entry.getKey(), entry.getValue().name());
        }

        // Start aggregated monitor writer (writes combined status every 30s)
        Thread monitorThread = new Thread(() -> {
            while (RUNNING.get()) {
                try {
                    writeAggregatedMonitor();
                    Thread.sleep(30000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.warn("Monitor write error: {}", e.getMessage());
                }
            }
        }, "monitor-writer");
        monitorThread.setDaemon(true);
        monitorThread.start();

        // Wait for all strategy threads to finish (they won't unless shutdown)
        for (Thread t : threads) {
            t.join();
        }

        log.info("✅ All strategy threads terminated.");
        writeAggregatedMonitor();
        System.exit(0);
    }

    // ========================================================================
    // Risk Config — loaded from live-config.json
    // ========================================================================

    private static void loadConfig() {
        if (!Files.exists(CONFIG_PATH)) {
            log.warn("⚠ Config file not found: {}. Using default risk {}% per trade.",
                CONFIG_PATH, DEFAULT_RISK_PCT);
            return;
        }
        try {
            String content = Files.readString(CONFIG_PATH);
            LIVE_CONFIG = MAPPER.readTree(content);

            if (LIVE_CONFIG.has("defaultRiskPct")) {
                DEFAULT_RISK_PCT = LIVE_CONFIG.get("defaultRiskPct").asDouble();
                log.info("📋 Default risk: {}%", DEFAULT_RISK_PCT);
            }

            if (LIVE_CONFIG.has("strategies")) {
                JsonNode strategies = LIVE_CONFIG.get("strategies");
                strategies.fieldNames().forEachRemaining(name -> {
                    JsonNode s = strategies.get(name);
                    double riskPct = DEFAULT_RISK_PCT;
                    if (s.has("computedRiskPct")) {
                        riskPct = s.get("computedRiskPct").asDouble();
                    } else if (s.has("backtestMetrics")) {
                        riskPct = deriveRiskFromMetrics(s.get("backtestMetrics"));
                    }
                    STRATEGY_RISK_PCT.put(name, riskPct);
                    String src = s.has("backtestMetrics")
                        ? s.get("backtestMetrics").get("source").asText("unknown")
                        : "default";
                    log.info("📋 {} → risk {}% (source: {})", name, riskPct, src);
                });
            }
        } catch (Exception e) {
            log.warn("⚠ Failed to load config: {}. Using defaults.", e.getMessage());
        }
    }

    /** Derive suggested risk % from backtest metrics using conservative formula. */
    private static double deriveRiskFromMetrics(JsonNode m) {
        double maxDD = m.has("maxDrawdown") ? m.get("maxDrawdown").asDouble() : 20.0;
        double winRate = m.has("winRate") ? m.get("winRate").asDouble() : 50.0;
        double avgWinLoss = m.has("avgWinLoss") ? m.get("avgWinLoss").asDouble() : 1.5;
        double sharpe = m.has("sharpe") ? m.get("sharpe").asDouble() : 0.0;

        // Max Drawdown method: never risk more than 1/20th of historical maxDD
        double fromMaxDD = maxDD > 0 ? Math.min(DEFAULT_RISK_PCT, maxDD / 20.0) : DEFAULT_RISK_PCT;

        // Half-Kelly: f* = (winRate × avgWinLoss - lossRate) / avgWinLoss / 2
        double lossRate = 100.0 - winRate;
        double halfKelly = 0.3; // conservative floor
        if (avgWinLoss > 0 && winRate > 0) {
            double kelly = (winRate / 100.0 * avgWinLoss - lossRate / 100.0) / avgWinLoss;
            halfKelly = Math.max(0.1, Math.min(DEFAULT_RISK_PCT, kelly / 2.0));
        }

        // Take the more conservative of the two, cap at default
        double suggested = Math.min(DEFAULT_RISK_PCT, Math.min(fromMaxDD, halfKelly));

        // Sanity check: avoid absurdly small values
        return Math.max(0.1, suggested);
    }

    /** Get the risk % configured for a strategy name, or conservative default if unknown. */
    private static double riskForStrategy(String name) {
        Double pct = STRATEGY_RISK_PCT.get(name);
        if (pct == null) {
            log.warn("⚠ Strategy '{}' not found in config — using conservative {}% default. "
                + "Add it to config/live-config.json with backtest metrics to get a proper risk %.",
                name, UNKNOWN_STRATEGY_RISK_PCT);
            return UNKNOWN_STRATEGY_RISK_PCT;
        }
        return pct;
    }

    /**
     * Calculate the maximum position size (in units) that respects the risk % rule.
     * @param balance  Current account balance (NAV)
     * @param riskPct  % of balance to risk per trade (e.g. 1.5)
     * @param entryPrice  Order entry price
     * @param stopLoss    Stop loss price (0 if none)
     * @param requestedUnits  Units the strategy wants to trade
     * @return Capped position size
     */
    private double cappedPositionSize(double balance, double riskPct, double entryPrice,
                                       double stopLoss, double requestedUnits) {
        if (stopLoss <= 0 || entryPrice <= 0) {
            // Safety cap: without a stop loss, limit to 2 micro lots max (2000 units)
            double hardCap = Math.min(requestedUnits, 2000);
            log.warn("⚠ No stop loss set — safety cap: {} units → {} units",
                (int)requestedUnits, (int)hardCap);
            return hardCap;
        }
        double slDistance = Math.abs(entryPrice - stopLoss);
        if (slDistance <= 0.0) return requestedUnits;

        // Max loss in dollar terms
        double maxLoss = balance * (riskPct / 100.0);
        // Max units = maxLoss / SL_distance (in price units)
        double maxUnits = maxLoss / slDistance;

        if (requestedUnits > maxUnits) {
            log.info("📐 Risk cap: {} units requested, {} max (${} × {}% / {} pips) — capping to {}",
                (int)requestedUnits, (int)maxUnits,
                String.format("%.0f", balance), riskPct,
                formatPrice(slDistance, toOandaSymbol()),
                (int)maxUnits);
            return Math.floor(maxUnits);
        }
        return requestedUnits;
    }

    /** Get current account balance from OANDA API. Caches for 60s to avoid rate limits. */
    private double getCurrentBalance() {
        try {
            var acct = priceClient.getAccountSummary();
            return acct.NAV() > 0 ? acct.NAV() : acct.balance();
        } catch (Exception e) {
            log.warn("⚠ Could not fetch balance for risk calc: {}. Using {}.", e.getMessage(),
                String.format("%.0f", lastKnownBalance));
            return lastKnownBalance;
        }
    }
    private double lastKnownBalance = 10000;

    private static void writeAggregatedMonitor() {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("running", RUNNING.get());
            root.put("accountId", ACTIVE_RUNNERS.isEmpty() ? "" : ACTIVE_RUNNERS.values().iterator().next().accountId);
            root.put("timestamp", TimeConventions.now().toString());
            root.put("strategyCount", ACTIVE_RUNNERS.size());

            ArrayNode strategiesArray = root.putArray("strategies");
            for (var entry : ACTIVE_RUNNERS.entrySet()) {
                LiveStrategyRunner r = entry.getValue();
                ObjectNode sn = strategiesArray.addObject();
                sn.put("name", entry.getKey());
                sn.put("displayName", r.strategy.name());
                sn.put("instrument", r.toOandaSymbol());
                sn.put("granularity", r.granularity);
                sn.put("totalEntries", r.totalEntries);
                sn.put("totalExits", r.totalExits);
                sn.put("totalPnl", r.totalPnl);
                sn.put("activeTrades", r.activeTrades.size());
                sn.put("pendingStops", r.pendingStops.size());
            }

            MAPPER.writerWithDefaultPrettyPrinter().writeValue(AGGREGATED_MONITOR.toFile(), root);
        } catch (Exception e) {
            log.warn("Failed to write aggregated monitor: {}", e.getMessage());
        }
    }

    // ========================================================================
    // Strategy Resolution
    // ========================================================================

    private static void listStrategies() {
        System.out.println("\nAvailable strategies:");
        for (var entry : getStrategyMap().entrySet()) {
            System.out.println("  " + entry.getKey() + " → " + entry.getValue().getSimpleName());
        }
        System.out.println("  all → launch every strategy concurrently\n");
    }

    private static Map<String, Class<? extends Strategy>> getStrategyMap() {
        Map<String, Class<? extends Strategy>> map = new LinkedHashMap<>();
        try {
            map.put("2_14_147", Class.forName("com.martinfou.trading.strategies.sqimported.Strategy_2_14_147_Adapted")
                .asSubclass(Strategy.class));
            map.put("2_15_195", Class.forName("com.martinfou.trading.strategies.sqimported.Strategy_2_15_195_Adapted")
                .asSubclass(Strategy.class));
            map.put("2_31_175", Class.forName("com.martinfou.trading.strategies.sqimported.Strategy_2_31_175_Converted")
                .asSubclass(Strategy.class));
            map.put("2_31_177", Class.forName("com.martinfou.trading.strategies.sqimported.Strategy_2_31_177_Converted")
                .asSubclass(Strategy.class));
            map.put("2_32_120", Class.forName("com.martinfou.trading.strategies.sqimported.Strategy_2_32_120_Converted")
                .asSubclass(Strategy.class));
            map.put("2_36_190", Class.forName("com.martinfou.trading.strategies.sqimported.Strategy_2_36_190_Converted")
                .asSubclass(Strategy.class));
            map.put("2_38_112", Class.forName("com.martinfou.trading.strategies.sqimported.Strategy_2_38_112_Converted")
                .asSubclass(Strategy.class));
            // Creative Lab strategies
            map.put("nymid", Class.forName("com.martinfou.trading.strategies.creative.NYMidSessionMomentumStrategy")
                .asSubclass(Strategy.class));
            map.put("gobig", Class.forName("com.martinfou.trading.strategies.creative.GoBigStrategy")
                .asSubclass(Strategy.class));
            map.put("casino", Class.forName("com.martinfou.trading.strategies.creative.CasinoStrategy")
                .asSubclass(Strategy.class));
            map.put("vwpreversion", Class.forName("com.martinfou.trading.strategies.creative.VWPReversionStrategy")
                .asSubclass(Strategy.class));
            map.put("consecbar", Class.forName("com.martinfou.trading.strategies.creative.ConsecutiveBarExhaustionStrategy")
                .asSubclass(Strategy.class));
            // Lab — new strategies (R&D pipeline)
            map.put("hmmregime", Class.forName("com.martinfou.trading.strategies.creative.HmmRegimeMomentumStrategy")
                .asSubclass(Strategy.class));
            map.put("vwappremium", Class.forName("com.martinfou.trading.strategies.creative.VwapPremiumReversionStrategy")
                .asSubclass(Strategy.class));
            map.put("turnofmonth", Class.forName("com.martinfou.trading.strategies.creative.TurnOfMonthFlowStrategy")
                .asSubclass(Strategy.class));
            // NFP Week — Short EUR/USD macro play for NFP weeks
            map.put("nfpweek", Class.forName("com.martinfou.trading.strategies.creative.NfpWeekStrategy")
                .asSubclass(Strategy.class));
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Strategy class not found", e);
        }
        return map;
    }

    // ========================================================================
    // Runnable interface — each strategy runs in its own thread
    // ========================================================================

    @Override
    public void run() {
        try {
            runLoop();
        } catch (Exception e) {
            log.error("❌ Fatal error in strategy thread '{}': {}", strategyShortName, e.getMessage(), e);
        }
    }

    private void runLoop() throws Exception {
        log.info("━━━ Starting strategy: {} (instrument: {}) ━━━", strategy.name(), toOandaSymbol());

        // Resume from saved state if available
        resumeState();

        // Get initial candles to warm up the strategy
        String oandaSymbol = toOandaSymbol();
        log.info("Fetching initial {} candles for {} ...", granularity, oandaSymbol);
        List<Bar> initialBars = priceClient.getCandles(oandaSymbol, granularity, 200);
        if (initialBars.isEmpty()) {
            log.warn("No candles received! Check instrument name and account.");
            return;
        }

        // Warm up: feed historical bars but don't trade them
        for (Bar bar : initialBars) {
            barHistory.add(bar);
            strategy.onBar(bar);
        }
        if (!initialBars.isEmpty()) {
            lastBarTime = initialBars.get(initialBars.size() - 1).timestamp();
        }
        log.info("Warmed up with {} bars. Last bar: {}", initialBars.size(), lastBarTime);

        // Verify account
        try {
            var acct = priceClient.getAccountSummary();
            lastKnownBalance = acct.NAV() > 0 ? acct.NAV() : acct.balance();
            log.info("💰 Account Balance: ${} | NAV: ${} | Unrealized P&L: ${}",
                String.format("%.2f", acct.balance()),
                String.format("%.2f", acct.NAV()),
                String.format("%.2f", acct.unrealizedPL()));
        } catch (Exception e) {
            log.warn("Could not fetch account summary: {}", e.getMessage());
        }

        // Main loop
        log.info("▶ Entering main loop ({}s interval)...", intervalSec);
        while (RUNNING.get()) {
            try {
                Instant loopStart = TimeConventions.now();
                tick(oandaSymbol);
                saveStatePeriodic();

                // Sleep for the remaining interval
                long elapsedMs = Duration.between(loopStart, TimeConventions.now()).toMillis();
                long sleepMs = Math.max(1000, (intervalSec * 1000L) - elapsedMs);
                Thread.sleep(sleepMs);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                RUNNING.set(false);
            } catch (Exception e) {
                log.error("Loop error in '{}': {}", strategyShortName, e.getMessage(), e);
                Thread.sleep(5000);
            }
        }

        // Final state save
        saveStateNow();
        log.info("━━━ Strategy {} stopped ━━━", strategy.name());
    }

    // ========================================================================
    // Tick — One iteration of the loop
    // ========================================================================

    private void tick(String oandaSymbol) throws Exception {
        // 1. Fetch latest candles
        List<Bar> freshBars = priceClient.getCandles(oandaSymbol, granularity, 5);
        if (freshBars.isEmpty()) return;

        // 2. Detect new unprocessed bars
        List<Bar> newBars = new ArrayList<>();
        for (Bar bar : freshBars) {
            String barKey = bar.timestamp().toString() + "|" + bar.close();
            if (lastBarTime == null || bar.timestamp().isAfter(lastBarTime)) {
                if (!executedBars.contains(barKey)) {
                    newBars.add(bar);
                    executedBars.add(barKey);
                }
            }
        }

        if (newBars.isEmpty()) {
            log.debug("No new bars — checking positions...");
            updatePositions(oandaSymbol);
            return;
        }

        // 3. Feed new bars to the strategy
        for (Bar bar : newBars) {
            barHistory.add(bar);
            log.info("📊 New bar: {} {} O={} H={} L={} C={} V={}",
                bar.symbol(), TimeConventions.toDisplayString(bar.timestamp()),
                String.format("%.3f", bar.open()), String.format("%.3f", bar.high()),
                String.format("%.3f", bar.low()), String.format("%.3f", bar.close()),
                bar.volume());

            strategy.onBar(bar);
            lastBarTime = bar.timestamp();
        }

        // 4. Check for pending orders from the strategy
        checkPendingOrders(oandaSymbol);

        // 5. Update open positions
        updatePositions(oandaSymbol);

        // 6. Log summary
        log.info("📈 Bars: {} | Entries: {} | Exits: {} | P&L: ${}",
            barHistory.size(), totalEntries, totalExits, String.format("%.2f", totalPnl));
    }

    // ========================================================================
    // Pending Orders
    // ========================================================================

    private void checkPendingOrders(String oandaSymbol) throws Exception {
        List<Order> orders = strategy.getPendingOrders();
        if (orders == null || orders.isEmpty()) return;

        // Get current price to determine if we should execute
        var price = priceClient.getPrice(oandaSymbol);
        double currentBid = price.bid();
        double currentAsk = price.ask();

        for (Order order : orders) {
            if (order.status() != Order.Status.PENDING) continue;

            boolean shouldExecute = false;
            double execPrice;

            if (order.type() == Order.Type.MARKET) {
                shouldExecute = true;
                execPrice = order.side() == Order.Side.BUY ? currentAsk : currentBid;
            } else if (order.type() == Order.Type.STOP) {
                // BUY STOP: execute when ask >= entry price
                // SELL STOP: execute when bid <= entry price
                if (order.side() == Order.Side.BUY && currentAsk >= order.price()) {
                    shouldExecute = true;
                    execPrice = currentAsk;
                } else if (order.side() == Order.Side.SELL && currentBid <= order.price()) {
                    shouldExecute = true;
                    execPrice = currentBid;
                } else {
                    // Price not yet reached — place a STOP order on OANDA
                    placeOandaStopOrder(order, oandaSymbol);
                    continue;
                }
            } else {
                log.warn("Unsupported order type: {}", order.type());
                continue;
            }

            if (shouldExecute) {
                executeTrade(order, oandaSymbol, execPrice);
            }
        }
    }

    private void placeOandaStopOrder(Order order, String oandaSymbol) {
        try {
            // Risk-cap the position size for stop orders too
            double requestedUnits = Math.abs(order.quantity());
            double riskPct = riskForStrategy(strategyShortName);
            double balance = getCurrentBalance();
            double cappedUnits = cappedPositionSize(balance, riskPct,
                order.price(), order.stopLoss(), requestedUnits);

            double units = order.side() == Order.Side.BUY
                ? cappedUnits
                : -cappedUnits;
            String unitsStr = String.valueOf((int) units);
            int precision = switch (oandaSymbol) {
                case "GBP_JPY", "USD_JPY" -> 3;
                case "XAU_USD", "XAG_USD" -> 1;
                default -> 5;
            };
            String priceStr = String.format("%." + precision + "f", order.price());

            var tag = strategyShortName + "_" + oandaSymbol.replace("_", "");
            var result = executor.placeStopOrder(oandaSymbol, unitsStr, priceStr, tag);
            log.info("⏳ STOP ORDER PLACED: {} {} @ {} (OANDA ID: {})",
                oandaSymbol, order.side(), priceStr, result.orderId());

            pendingStops.add(new PendingStop(
                result.orderId(), oandaSymbol,
                order.side().name(), order.price(),
                units, order.stopLoss(), order.takeProfit()
            ));

        } catch (Exception e) {
            log.error("❌ Failed to place stop order: {}", e.getMessage());
        }
    }

    private void executeTrade(Order order, String oandaSymbol, double execPrice) {
        try {
            // Risk-cap the position size
            double requestedUnits = Math.abs(order.quantity());
            double riskPct = riskForStrategy(strategyShortName);
            double balance = getCurrentBalance();
            double cappedUnits = cappedPositionSize(balance, riskPct, execPrice,
                order.stopLoss(), requestedUnits);

            double units = order.side() == Order.Side.BUY
                ? cappedUnits
                : -cappedUnits;
            String unitsStr = String.valueOf((int) units);

            var tag = strategyShortName + "_" + oandaSymbol.replace("_", "");

            // Detect if this order closes an existing active trade BEFORE margin check
            boolean isClose = false;
            for (ActiveTrade at : activeTrades) {
                if (at.symbol.equals(oandaSymbol)) {
                    boolean isOpposite = (order.side() == Order.Side.BUY && at.side.equals("SELL"))
                        || (order.side() == Order.Side.SELL && at.side.equals("BUY"));
                    if (isOpposite) {
                        isClose = true;
                        break;
                    }
                }
            }

            if (isClose) {
                // This is a close / exit order — calculate P&L before executing
                var price = priceClient.getPrice(oandaSymbol);
                double currentBid = price.bid();
                double currentAsk = price.ask();
                double estimatePnl = 0;
                for (ActiveTrade at : activeTrades) {
                    if (at.symbol.equals(oandaSymbol)) {
                        if (at.side.equals("BUY")) {
                            estimatePnl += (currentBid - at.entryPrice) * at.quantity;
                        } else {
                            estimatePnl += (at.entryPrice - currentAsk) * at.quantity;
                        }
                    }
                }
                var result = executor.placeMarketOrder(oandaSymbol, unitsStr, tag);
                // Remove the closed trade(s) from active list
                activeTrades.removeIf(at -> at.symbol.equals(oandaSymbol));
                totalExits++;
                totalPnl += estimatePnl;
                log.info("═══════ EXIT {} {} {} @ {} PnL: {}{} ═══════",
                    oandaSymbol, order.side(),
                    String.format("%.2f", units / 100000.0) + " lots",
                    result.fillPrice(),
                    estimatePnl >= 0 ? "+" : "",
                    String.format("%.2f", estimatePnl));
                return;
            }

            // ─── New entry — place market order ───
            var result = executor.placeMarketOrder(oandaSymbol, unitsStr, tag);
            totalEntries++;

            log.info("═══════ ENTRY {} {} {} @ {} ═══════",
                oandaSymbol, order.side(),
                String.format("%.2f", units / 100000.0) + " lots",
                result.fillPrice());

            // Attach SL/TP
            double fillPrice = Double.parseDouble(result.fillPrice());
            String slStr = order.stopLoss() > 0
                ? formatPrice(order.stopLoss(), oandaSymbol) : null;
            String tpStr = order.takeProfit() > 0
                ? formatPrice(order.takeProfit(), oandaSymbol) : null;

            if (slStr != null && result.tradeId() != null && !result.tradeId().equals("N/A")) {
                String slResult = executor.addStopLoss(result.tradeId(), slStr, tag);
                if (slResult.equals("OK")) {
                    log.info("   SL set @ {}", slStr);
                } else {
                    log.warn("   SL failed: {}", slResult);
                }
            }

            if (tpStr != null && result.tradeId() != null && !result.tradeId().equals("N/A")) {
                String tpResult = executor.addTakeProfit(result.tradeId(), tpStr, tag);
                if (tpResult.equals("OK")) {
                    log.info("   TP set @ {}", tpStr);
                } else {
                    log.warn("   TP failed: {}", tpResult);
                }
            }

            // Track trade
            if (result.tradeId() != null && !result.tradeId().equals("N/A")) {
                activeTrades.add(new ActiveTrade(
                    result.tradeId(), oandaSymbol, order.side().name(), fillPrice,
                    Math.abs(units), order.stopLoss(), order.takeProfit(),
                    TimeConventions.now()
                ));
            }

        } catch (Exception e) {
            log.error("❌ TRADE EXECUTION FAILED: {} {} @ {} — {}",
                oandaSymbol, order.side(), formatPrice(execPrice, oandaSymbol), e.getMessage());
        }
    }

    // ========================================================================
    // Position Monitoring
    // ========================================================================

    private void updatePositions(String oandaSymbol) throws Exception {
        if (activeTrades.isEmpty()) return;

        var price = priceClient.getPrice(oandaSymbol);
        double currentBid = price.bid();
        double currentAsk = price.ask();

        Iterator<ActiveTrade> it = activeTrades.iterator();
        while (it.hasNext()) {
            ActiveTrade trade = it.next();
            double mid = (currentBid + currentAsk) / 2.0;

            double pnl;
            double exitPrice;
            boolean exited = false;

            if (trade.side.equals("BUY")) {
                pnl = (currentBid - trade.entryPrice) * trade.quantity;
                if (trade.takeProfit > 0 && currentBid >= trade.takeProfit) {
                    exitPrice = trade.takeProfit;
                    exited = true;
                } else if (trade.stopLoss > 0 && currentBid <= trade.stopLoss) {
                    exitPrice = trade.stopLoss;
                    exited = true;
                } else {
                    exitPrice = currentBid;
                }
            } else {
                pnl = (trade.entryPrice - currentAsk) * trade.quantity;
                if (trade.takeProfit > 0 && currentAsk <= trade.takeProfit) {
                    exitPrice = trade.takeProfit;
                    exited = true;
                } else if (trade.stopLoss > 0 && currentAsk >= trade.stopLoss) {
                    exitPrice = trade.stopLoss;
                    exited = true;
                } else {
                    exitPrice = currentAsk;
                }
            }

            trade.unrealizedPnl = pnl;

            if (exited && trade.tradeId != null) {
                totalExits++;
                totalPnl += pnl;
                log.info("═══════ EXIT {} {} @ {} PnL: {}{} ═══════",
                    trade.symbol, trade.side,
                    formatPrice(exitPrice, trade.symbol),
                    pnl >= 0 ? "+" : "",
                    String.format("%.2f", pnl / trade.quantity * 100000));
                it.remove();
            } else {
                log.info("   {} {} | Entry: {} | Current: {} | PnL: {}{} | SL: {} TP: {}",
                    trade.symbol, trade.side,
                    formatPrice(trade.entryPrice, trade.symbol),
                    formatPrice(mid, trade.symbol),
                    pnl >= 0 ? "+" : "",
                    String.format("%.2f", pnl),
                    trade.stopLoss > 0 ? formatPrice(trade.stopLoss, trade.symbol) : "—",
                    trade.takeProfit > 0 ? formatPrice(trade.takeProfit, trade.symbol) : "—");
            }
        }
    }

    // ========================================================================
    // Instrument Resolution
    // ========================================================================

    static String formatPrice(double price, String oandaSymbol) {
        int precision = switch (oandaSymbol) {
            case "GBP_JPY", "USD_JPY" -> 3;
            case "XAU_USD", "XAG_USD" -> 1;
            default -> 5;
        };
        return String.format("%." + precision + "f", price);
    }

    private String toOandaSymbol() {
        return toOandaSymbol(strategy);
    }

    static String toOandaSymbol(Strategy s) {
        String name = s.name().toUpperCase();
        if (name.contains("GBPJPY") || name.contains("GBP_JPY")) return "GBP_JPY";
        if (name.contains("EURUSD") || name.contains("EUR_USD")) return "EUR_USD";
        if (name.contains("GBPUSD") || name.contains("GBP_USD")) return "GBP_USD";
        if (name.contains("USDCAD") || name.contains("USD_CAD")) return "USD_CAD";
        if (name.contains("USDJPY") || name.contains("USD_JPY")) return "USD_JPY";
        if (name.contains("AUDUSD") || name.contains("AUD_USD")) return "AUD_USD";
        if (name.contains("NZDUSD") || name.contains("NZD_USD")) return "NZD_USD";
        if (name.contains("USDCHF") || name.contains("USD_CHF")) return "USD_CHF";
        if (name.contains("XAUUSD") || name.contains("XAU_USD") || name.contains("GOLD")) return "XAU_USD";
        if (name.contains("EURJPY") || name.contains("EUR_JPY")) return "EUR_JPY";
        // Creative lab strategies — match by short name
        if (name.contains("VWPREVERSION")) return "USD_CHF";
        if (name.contains("CONSECBAR")) return "GBP_JPY";
        if (name.contains("NYMID")) return "EUR_USD";
        if (name.contains("GOBIG")) return "GBP_USD";
        if (name.contains("CASINO")) return "USD_JPY";
        // Default — safe pair
        return "GBP_JPY";
    }

    // ========================================================================
    // State Persistence (Crash Recovery)
    // ========================================================================

    private void saveStatePeriodic() {
        Instant now = TimeConventions.now();
        if (Duration.between(lastStateSave, now).toSeconds() < 60) return;
        saveStateNow();
    }

    private void saveStateNow() {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("strategy", strategyShortName);
            root.put("displayName", strategy.name());
            root.put("instrument", toOandaSymbol());
            root.put("granularity", granularity);
            root.put("intervalSec", intervalSec);
            root.put("totalEntries", totalEntries);
            root.put("totalExits", totalExits);
            root.put("totalPnl", totalPnl);
            root.put("savedAt", TimeConventions.now().toString());
            if (lastBarTime != null) root.put("lastBarTime", lastBarTime.toString());
            root.put("inTrade", !activeTrades.isEmpty());

            // Save strategy internal state (crash recovery) via reflection
            try {
                var m = strategy.getClass().getMethod("getTradesToday");
                root.put("strat_tradesToday", (int) m.invoke(strategy));
                m = strategy.getClass().getMethod("getCooldownBars");
                root.put("strat_cooldownBars", (int) m.invoke(strategy));
                m = strategy.getClass().getMethod("isInTrade");
                root.put("strat_inTrade", (boolean) m.invoke(strategy));
                m = strategy.getClass().getMethod("getTradeDirection");
                root.put("strat_tradeDirection", ((Enum<?>) m.invoke(strategy)).name());
                m = strategy.getClass().getMethod("getLastTradeDay");
                root.put("strat_lastTradeDay", (int) m.invoke(strategy));
            } catch (NoSuchMethodException e) {
                // strategy doesn't support state export — fine
            }

            // Active trades
            ArrayNode tradesArray = root.putArray("activeTrades");
            for (ActiveTrade t : activeTrades) {
                ObjectNode tn = tradesArray.addObject();
                tn.put("tradeId", t.tradeId);
                tn.put("symbol", t.symbol);
                tn.put("side", t.side);
                tn.put("entryPrice", t.entryPrice);
                tn.put("quantity", t.quantity);
                tn.put("stopLoss", t.stopLoss);
                tn.put("takeProfit", t.takeProfit);
                tn.put("entryTime", t.entryTime.toString());
            }

            // Pending stops
            ArrayNode stopsArray = root.putArray("pendingStops");
            for (PendingStop p : pendingStops) {
                ObjectNode pn = stopsArray.addObject();
                pn.put("orderId", p.orderId);
                pn.put("symbol", p.symbol);
                pn.put("side", p.side);
                pn.put("price", p.price);
                pn.put("quantity", p.quantity);
                pn.put("stopLoss", p.stopLoss);
                pn.put("takeProfit", p.takeProfit);
            }

            MAPPER.writerWithDefaultPrettyPrinter().writeValue(stateFile.toFile(), root);
            root.put("running", RUNNING.get());
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(monitorFile.toFile(), root);
            lastStateSave = TimeConventions.now();
        } catch (Exception e) {
            log.warn("Failed to save state for '{}': {}", strategyShortName, e.getMessage());
        }
    }

    private void resumeState() {
        if (!Files.exists(stateFile)) {
            log.info("No saved state file found — starting fresh.");
            return;
        }

        try {
            String content = Files.readString(stateFile);
            JsonNode root = MAPPER.readTree(content);

            String savedStrategy = root.has("strategy") ? root.get("strategy").asText() : "";
            if (!savedStrategy.equals(strategyShortName)) {
                log.info("Saved strategy '{}' != current '{}' — ignoring saved state.",
                    savedStrategy, strategyShortName);
                return;
            }

            if (root.has("totalEntries")) totalEntries = root.get("totalEntries").asInt();
            if (root.has("totalExits")) totalExits = root.get("totalExits").asInt();
            if (root.has("totalPnl")) totalPnl = root.get("totalPnl").asDouble();
            if (root.has("lastBarTime")) lastBarTime = Instant.parse(root.get("lastBarTime").asText());

            if (root.has("activeTrades")) {
                for (JsonNode tn : root.get("activeTrades")) {
                    ActiveTrade t = new ActiveTrade();
                    t.tradeId = tn.get("tradeId").asText();
                    t.symbol = tn.get("symbol").asText();
                    t.side = tn.get("side").asText();
                    t.entryPrice = tn.get("entryPrice").asDouble();
                    t.quantity = tn.get("quantity").asDouble();
                    t.stopLoss = tn.has("stopLoss") ? tn.get("stopLoss").asDouble() : 0;
                    t.takeProfit = tn.has("takeProfit") ? tn.get("takeProfit").asDouble() : 0;
                    t.entryTime = Instant.parse(tn.get("entryTime").asText());
                    activeTrades.add(t);
                }
            }

            if (root.has("pendingStops")) {
                for (JsonNode pn : root.get("pendingStops")) {
                    PendingStop p = new PendingStop();
                    p.orderId = pn.get("orderId").asText();
                    p.symbol = pn.get("symbol").asText();
                    p.side = pn.get("side").asText();
                    p.price = pn.get("price").asDouble();
                    p.quantity = pn.get("quantity").asDouble();
                    p.stopLoss = pn.has("stopLoss") ? pn.get("stopLoss").asDouble() : 0;
                    p.takeProfit = pn.has("takeProfit") ? pn.get("takeProfit").asDouble() : 0;
                    pendingStops.add(p);
                }
            }

            // Restore strategy internal state (crash recovery)
            if (root.has("strat_tradesToday")) {
                try {
                    var m = strategy.getClass().getMethod("restoreState",
                        int.class, int.class, boolean.class, Order.Side.class, int.class);
                    int td = root.get("strat_tradesToday").asInt();
                    int ltd = root.get("strat_lastTradeDay").asInt(-1);
                    boolean it = root.get("strat_inTrade").asBoolean();
                    Order.Side dir = Order.Side.valueOf(root.get("strat_tradeDirection").asText("BUY"));
                    int cd = root.get("strat_cooldownBars").asInt(0);
                    m.invoke(strategy, td, ltd, it, dir, cd);
                    log.info("♻ Strategy state restored: tradesToday={}, inTrade={}, cooldown={}", td, it, cd);
                } catch (NoSuchMethodException e) {
                    log.debug("Strategy doesn't support state restore — skipping");
                }
            }

            log.info("♻ Resumed state: {} active trades, {} pending stops, {} entries, ${} P&L",
                activeTrades.size(), pendingStops.size(), totalEntries,
                String.format("%.2f", totalPnl));

        } catch (Exception e) {
            log.warn("Failed to resume state (corrupted?): {}", e.getMessage());
        }
    }
}
