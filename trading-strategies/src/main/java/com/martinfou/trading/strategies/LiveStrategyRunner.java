package com.martinfou.trading.strategies;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.RiskSizing;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.core.exceptions.BrokerException;
import com.martinfou.trading.core.TimeConventions;
import com.martinfou.trading.data.OandaPriceClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    /**
     * Per-runner liveness. RUNNING is PROCESS-wide and belongs to the shutdown hook only: setting it
     * false from one strategy's failure ended every OTHER strategy's run loop (the main loop is
     * gated on RUNNING) plus the aggregated monitor thread, so a single bad strategy silently
     * stopped the whole swarm. Found by the 2026-09-30 agy review.
     */
    private final AtomicBoolean alive = new AtomicBoolean(true);

    /** Aggregated monitor file for the paper-trading cron — written by the orchestrator. */
    private static final Path AGGREGATED_MONITOR = Paths.get("/tmp/paper-trading-status.json");

    /** Config file path (mounted in Docker at /app/config/ or local) */
    private static final Path CONFIG_PATH = Paths.get("/app/config/live-config.json");
    private static JsonNode LIVE_CONFIG = null;
    private static double DEFAULT_RISK_PCT = 1.5;
    /** Conservative fallback for strategies not in config — no backtest data available. */
    private static final double UNKNOWN_STRATEGY_RISK_PCT = 0.5;

    /**
     * Risk-budget sizing (two-sided): every order is sized so that its loss at the stop equals
     * {@code riskPct}% of the live NAV. The strategy's own quantity becomes intent only. When false,
     * the legacy currency-naive cap is used and a strategy asking for 1,000 units stays at 1,000.
     */
    private static boolean RISK_SIZING_ENABLED = true;
    /** Broker lot step for the sized order. */
    private static long RISK_ROUND_TO_UNITS = RiskSizing.ROUND_TO_UNITS;
    /**
     * Rail against a tiny stop demanding an absurd order (1% risk at a 5-pip stop on GBP/JPY is
     * ~2.2M units). Not a risk parameter: it only fires on stops far tighter than any live strategy
     * uses, and it logs loudly when it binds.
     */
    private static double RISK_MAX_UNITS_PER_ORDER = 1_000_000;
    /**
     * Fail-safe size used whenever no risk budget can be computed: no stop, a zero stop distance, or
     * an undefined target. Deliberately tiny. These paths used to return the strategy's requested
     * units unbudgeted, which is the one direction a risk budget must never fail in.
     */
    static final int NO_RISK_UNITS_CAP = 2000;
    /**
     * Notional rail (policy set by Martin, 2026-09-30). No single order may carry more than this
     * multiple of the live NAV in notional value, expressed in account currency:
     * {@code units x entryPrice x quoteToAccountFactor <= N x NAV}. The absolute unit cap above is a
     * typo guard, not a risk limit: units alone say nothing about exposure. On GBP_JPY with a 96k CAD
     * NAV this holds an order to roughly 2.5 lots (5x leverage) instead of the 10 lots the
     * 1,000,000-unit rail allowed. 0 disables the rail.
     */
    private static double RISK_MAX_NOTIONAL_NAV_MULTIPLE = 5.0;

    /**
     * Version of the realized-P&L ledger.
     * v1 (implicit) mixed a local estimate in the instrument's QUOTE currency with the broker's
     * account-currency value; v2 took the broker value only, once per trade, but still as a scalar
     * accumulator with no per-trade dedupe; v3 stores the broker value keyed by trade id in a
     * ledger, so an overlapping reconciliation can never double-count the same trade.
     * A saved accumulator older than this version is discarded on load rather than carried over.
     */
    static final int PNL_ACCOUNTING_VERSION = 3;
    /** Upper bound on how many previously-unreconciled trades the watchdog re-attempts per pass. */
    private static final int MAX_REATTEMPT_PER_PASS = 50;
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
    private final String runId;
    private final List<Bar> barHistory = new ArrayList<>();
    private final List<ActiveTrade> activeTrades = new ArrayList<>();
    private final List<PendingStop> pendingStops = new ArrayList<>();
    private final Set<String> executedBars = new HashSet<>();
    private volatile long signalCount = 0;
    private volatile long barCount = 0;
    private volatile Instant lastHeartbeatTime = Instant.now();
    private volatile int totalEntries = 0;
    private volatile int totalExits = 0;
    /** Realized-P&L ledger — the ONLY writer of realized P&L (account currency, broker-sourced, once per trade). */
    private final RealizedPnlLedger ledger = new RealizedPnlLedger();
    private Instant lastStateSave = Instant.MIN;
    private Instant lastReconciliationTime = Instant.MIN;
    private Instant lastIntegrityCheck = Instant.MIN;
    private volatile int pnlIntegrityMismatches = 0;
    /** Dedicated daemon executor for the integrity watchdog — never the strategy loop thread. */
    private ExecutorService integrityExecutor = null;
    /** Serializes every state/monitor file write; the writes are also atomic (tmp + move). */
    private final Object stateWriteLock = new Object();
    /** Trades whose broker P&L could not be reconciled after retries — re-attempted by the watchdog. */
    private final List<UnreconciledTrade> unreconciledTrades = new ArrayList<>();
    private Instant lastBarTime = null;

    // ---- Shared orchestrator state ----
    /** All running runners (for aggregated monitor). Populated by main(). */
    private static final Map<String, LiveStrategyRunner> ACTIVE_RUNNERS = new ConcurrentHashMap<>();

    // ---- Persisted state ----
    static final class ActiveTrade {
        String tradeId;
        String symbol;
        String side;
        double entryPrice;
        double quantity;
        double stopLoss;
        double takeProfit;
        Instant entryTime;
        double unrealizedPnl;
        String reconciliationStatus = "CONFIRMED";

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
            this.reconciliationStatus = "CONFIRMED";
        }
    }

    static final class PendingStop {
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

    /**
     * A trade whose broker P&L could not be reconciled (the fallback fired after all retries).
     * Kept so the condition is observable and the watchdog can re-attempt the id later.
     */
    public static final class UnreconciledTrade {
        public final String tradeId;
        public final String symbol;
        public final String reason;
        public final Instant timestamp;

        public UnreconciledTrade(String tradeId, String symbol, String reason, Instant timestamp) {
            this.tradeId = tradeId;
            this.symbol = symbol;
            this.reason = reason;
            this.timestamp = timestamp;
        }
    }

    // ========================================================================
    // Constructor
    // ========================================================================

    public LiveStrategyRunner(String apiKey, String accountId, Strategy strategy,
                              String strategyShortName, String granularity, int intervalSec) {
        this.runId = java.util.UUID.randomUUID().toString();
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

            if (LIVE_CONFIG.has("riskSizing")) {
                JsonNode rs = LIVE_CONFIG.get("riskSizing");
                RISK_SIZING_ENABLED = rs.path("enabled").asBoolean(true);
                RISK_ROUND_TO_UNITS = rs.path("roundToUnits").asLong(RiskSizing.ROUND_TO_UNITS);
                RISK_MAX_UNITS_PER_ORDER = rs.path("maxUnitsPerOrder").asDouble(1_000_000);
                RISK_MAX_NOTIONAL_NAV_MULTIPLE = rs.path("maxNotionalNavMultiple").asDouble(5.0);
                log.info("📐 Risk sizing: {} | round to {} units | max {} units/order | max notional {}x NAV",
                    RISK_SIZING_ENABLED ? "BUDGET (two-sided, per-strategy risk %)" : "OFF (legacy cap only)",
                    RISK_ROUND_TO_UNITS, (int) RISK_MAX_UNITS_PER_ORDER, RISK_MAX_NOTIONAL_NAV_MULTIPLE);
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
     * Sizes an order to the risk budget: the trade risks {@code riskPct}% of the live NAV and the
     * units are DERIVED from the distance to the stop (two-sided — it raises a too-small request as
     * well as trimming a too-large one). The strategy's own quantity is intent only.
     *
     * <pre>units = (NAV × riskPct/100) / (stopDistance × quoteToAccountFactor)</pre>
     *
     * <p>The conversion factor is mandatory (see {@link RiskSizing}): P&amp;L is earned in the
     * instrument's QUOTE currency (JPY for GBP_JPY), not in the account's. Omitting it is what made
     * the old cap 1.38× too loose on USD-quoted pairs and ~100× too tight on JPY-quoted pairs.
     *
     * <p>Two fail-safe paths, both erring SMALL: without a stop there is no risk denominator (2,000-unit
     * safety cap, no scaling up); with an unknown conversion factor the legacy cap applies.
     *
     * @param balance         Current account balance (NAV), in account currency
     * @param riskPct         % of NAV to risk on this trade (e.g. 0.75)
     * @param entryPrice      Order entry price
     * @param stopLoss        Stop loss price (0 if none)
     * @param requestedUnits  Units the strategy asked for (intent)
     * @param oandaSymbol     Instrument, used for the quote→home conversion factor
     * @return Position size in units
     */
    double riskSizedUnits(double balance, double riskPct, double entryPrice,
                          double stopLoss, double requestedUnits, String oandaSymbol) {
        if (stopLoss <= 0 || entryPrice <= 0) {
            double hardCap = Math.min(requestedUnits, NO_RISK_UNITS_CAP);
            log.warn("⚠ No stop loss set — safety cap: {} units → {} units (no risk budget without a stop)",
                (int) requestedUnits, (int) hardCap);
            return hardCap;
        }
        double slDistance = Math.abs(entryPrice - stopLoss);
        if (slDistance <= 0.0) {
            // Entry and stop at the same price: there is no risk denominator. Returning the
            // requested units here would be unbudgeted, which is how a zero-distance stop turns
            // into an arbitrarily large order once the two prices are computed from different
            // sources (a historical stop against a live price). Fail SMALL instead.
            double hardCap = Math.min(requestedUnits, NO_RISK_UNITS_CAP);
            log.warn("⚠ Zero stop distance (entry {} vs stop {}) for {} — safety cap: {} units → {} units",
                formatPrice(entryPrice, oandaSymbol), formatPrice(stopLoss, oandaSymbol),
                oandaSymbol, (int) requestedUnits, (int) hardCap);
            return hardCap;
        }

        if (!RISK_SIZING_ENABLED) {
            return legacyRiskCap(balance, riskPct, slDistance, requestedUnits, oandaSymbol);
        }
        double factor = quoteToAccountFactor(oandaSymbol);
        if (!(factor > 0)) {
            return legacyRiskCap(balance, riskPct, slDistance, requestedUnits, oandaSymbol);
        }

        long target = RiskSizing.unitsForRisk(balance, riskPct, slDistance, factor, RISK_ROUND_TO_UNITS);
        if (target <= 0) {
            double hardCap = Math.min(requestedUnits, NO_RISK_UNITS_CAP);
            log.warn("⚠ Risk sizing undefined for {} (NAV {} × {}% / stop {}) — safety cap: {} units → {} units",
                oandaSymbol, String.format("%.2f", balance), riskPct,
                formatPrice(slDistance, oandaSymbol), (int) requestedUnits, (int) hardCap);
            return hardCap;
        }

        double sized = target;
        if (sized > RISK_MAX_UNITS_PER_ORDER) {
            log.warn("⛔ Max-units rail for {}: {}% of {} at a {} stop wants {} units — capped to {}",
                oandaSymbol, riskPct, String.format("%.2f", balance),
                formatPrice(slDistance, oandaSymbol), target, (int) RISK_MAX_UNITS_PER_ORDER);
            sized = RISK_MAX_UNITS_PER_ORDER;
        }

        // Notional rail: the bound that actually matters. The unit cap above is a typo guard, because
        // units alone say nothing about exposure. cap = N x NAV / (price x quoteToAccountFactor).
        if (RISK_MAX_NOTIONAL_NAV_MULTIPLE > 0 && entryPrice > 0) {
            double maxUnitsByNotional = (RISK_MAX_NOTIONAL_NAV_MULTIPLE * balance) / (entryPrice * factor);
            double notionalCap = Math.floor(maxUnitsByNotional / RISK_ROUND_TO_UNITS) * RISK_ROUND_TO_UNITS;
            if (notionalCap >= RiskSizing.MIN_UNITS && sized > notionalCap) {
                log.warn("⛔ Notional rail for {}: {}x NAV = {} notional allows {} units at {} — capped from {}",
                    oandaSymbol, RISK_MAX_NOTIONAL_NAV_MULTIPLE,
                    String.format("%.0f", RISK_MAX_NOTIONAL_NAV_MULTIPLE * balance),
                    (int) notionalCap, formatPrice(entryPrice, oandaSymbol), (int) sized);
                sized = notionalCap;
            }
        }

        if (Math.abs(sized - requestedUnits) > 1) {
            log.info("📐 Risk sizing: {} → {} units | {}% of {} = {} risked, stop {} (quote→home {}) | strategy asked {}",
                (int) requestedUnits, (int) sized, riskPct, String.format("%.2f", balance),
                String.format("%.2f", RiskSizing.riskAmount((long) sized, slDistance, factor)),
                formatPrice(slDistance, oandaSymbol), String.format("%.8f", factor), (int) requestedUnits);
        }
        return sized;
    }

    /**
     * Legacy currency-naive cap: {@code NAV × risk% / stopDistance}. Kept ONLY as the fail-safe path
     * when risk sizing is disabled or the conversion factor is unavailable — it treats one quote-currency
     * unit as one account unit, so it errs small on JPY-quoted pairs and ~1.4× loose on USD-quoted pairs
     * with a CAD account. Never the primary path.
     */
    private double legacyRiskCap(double balance, double riskPct, double slDistance,
                                 double requestedUnits, String oandaSymbol) {
        if (slDistance <= 0) {
            // Never let the division below produce Infinity: Infinity makes the comparison at the end
            // of this method false, and the requested units pass through completely uncapped.
            log.error("⛔ legacyRiskCap called with slDistance {} — refusing to size, capping to {} units",
                slDistance, NO_RISK_UNITS_CAP);
            return Math.min(requestedUnits, NO_RISK_UNITS_CAP);
        }
        double maxUnits = (balance * (riskPct / 100.0)) / slDistance;
        if (requestedUnits > maxUnits) {
            log.info("📐 Legacy risk cap: {} units requested, {} max ({} × {}% / {}) — capping to {}",
                (int) requestedUnits, (int) maxUnits, String.format("%.0f", balance), riskPct,
                formatPrice(slDistance, oandaSymbol), (int) maxUnits);
            return Math.floor(maxUnits);
        }
        return requestedUnits;
    }

    /**
     * Quote→account-currency factor for {@code oandaSymbol}, from the broker's {@code homeConversions}
     * (click-through: {@code OandaPriceClient.getQuoteToHomeLossFactor}), cached 60s. Returns {@code -1}
     * while unknown so callers fail safe instead of silently assuming 1.0 (which would reintroduce the
     * currency bug).
     */
    private double quoteToAccountFactor(String oandaSymbol) {
        long now = System.currentTimeMillis();
        if (lastConversionFactorMs != 0 && now - lastConversionFactorMs < 60_000) {
            return lastConversionFactor;
        }
        long start = now;
        try {
            double f = priceClient.getQuoteToHomeLossFactor(oandaSymbol);
            lastConversionFactorMs = System.currentTimeMillis();
            if (f > 0) {
                if (lastConversionFactor <= 0) {
                    log.info("💱 {} quote→home loss factor: {} (fetched in {} ms)",
                        oandaSymbol, String.format("%.8f", f), lastConversionFactorMs - start);
                }
                lastConversionFactor = f;
            }
            return lastConversionFactor;
        } catch (Exception e) {
            lastConversionFactorMs = System.currentTimeMillis();  // back off 60s, don't hammer the API
            log.warn("⚠ Could not fetch the quote→home conversion factor for {}: {}. Last known: {}",
                oandaSymbol, e.getMessage(), lastConversionFactor);
            return lastConversionFactor;
        }
    }
    private double lastConversionFactor = -1;
    private long lastConversionFactorMs = 0;

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
                sn.put("running", r.alive.get());
                sn.put("displayName", r.strategy.name());
                sn.put("instrument", r.toOandaSymbol());
                sn.put("granularity", r.granularity);
                sn.put("totalEntries", r.totalEntries);
                sn.put("totalExits", r.totalExits);
                sn.put("totalPnl", r.getTotalPnl());
                synchronized (r.activeTrades) {
                    sn.put("activeTrades", r.activeTrades.size());
                }
                synchronized (r.pendingStops) {
                    sn.put("pendingStops", r.pendingStops.size());
                }
                sn.put("signalCount", r.signalCount);
                sn.put("barCount", r.barCount);
                sn.put("liveness", r.getLivenessStatus());
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
            // hmmregime REMOVED 2026-08-07 — rejected after deep dive with costs (see CreativeStrategyCatalogRegistrar)
            map.put("vwappremium", Class.forName("com.martinfou.trading.strategies.creative.VwapPremiumReversionStrategy")
                .asSubclass(Strategy.class));
            // NFP Week — Short EUR/USD macro play for NFP weeks
            map.put("nfpweek", Class.forName("com.martinfou.trading.strategies.creative.NfpWeekStrategy")
                .asSubclass(Strategy.class));
            // V2 — Backtest-qualified (June 2026)
            map.put("compmomentum", Class.forName("com.martinfou.trading.strategies.creative.CompositeMomentumRankingStrategy")
                .asSubclass(Strategy.class));
            map.put("atrexpansion", Class.forName("com.martinfou.trading.strategies.creative.ATRExpansionMomentumStrategy")
                .asSubclass(Strategy.class));
            map.put("monthweekphase", Class.forName("com.martinfou.trading.strategies.creative.MonthWeekPhaseStrategy")
                .asSubclass(Strategy.class));
            // Long-term strategies
            map.put("ltrsi3", Class.forName("com.martinfou.trading.strategies.longterm.LtRSI3Momentum")
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
        MDC.put("runId", runId);
        MDC.put("strategyId", strategyShortName);
        MDC.put("symbol", toOandaSymbol());
        try {
            runLoop();
        } catch (BrokerException e) {
            log.error("❌ Broker exception in strategy thread '{}': {}. This runner stops; the others keep going.", strategyShortName, e.getMessage(), e);
            alive.set(false);
            saveStateFailed(e.getMessage());
        } catch (Throwable e) {
            log.error("❌ Fatal unhandled exception in strategy thread '{}': {}. This runner stops; the others keep going.", strategyShortName, e.getMessage(), e);
            alive.set(false);
            saveStateFailed(e.getMessage());
        } finally {
            MDC.clear();
        }
    }

    /**
     * Feeds historical bars to the strategy to prime its indicators, then DISCARDS every order it
     * queued while doing so. Warm-up bars are history: they must never reach the broker.
     *
     * <p>Defect (2026-09-30). Strategies queue their orders in {@code getPendingOrders()}, which the
     * runner drains once per tick in {@link #checkPendingOrders(String)}. Nothing drained that queue
     * during warm-up, so a whole history's worth of entry and exit orders sat pending until the first
     * tick that saw a new bar, which then flushed the entire backlog to the broker in one pass. The
     * runner opened and closed the same position repeatedly (7 round trips in 7 seconds, each paying
     * the spread), and because each replayed order carried the stop of the bar that created it, the
     * risk-sized units ranged from 38,200 to 1,000,000. At the old 1,000-unit micro-lot this cost
     * cents and went unnoticed; at risk-budget sizes it realized ~495 CAD.
     */
    void warmUp(List<Bar> initialBars) {
        for (Bar bar : initialBars) {
            barHistory.add(bar);
            strategy.onBar(bar);
        }
        if (!initialBars.isEmpty()) {
            lastBarTime = initialBars.get(initialBars.size() - 1).timestamp();
        }
        // Historical bars must not trade: drop whatever the strategies queued while warming up.
        // Drain in a bounded loop. Most strategies clear their queue on the first call, but a strategy
        // whose getPendingOrders() COMPUTES orders from its position state (GoBigStrategy,
        // CasinoStrategy) never empties. Such a strategy does not honour the drain contract, so a
        // position left over from the historical replay would reach the broker on the first live tick.
        // Detect that and say so loudly, instead of logging a clean drain that never happened.
        int discarded = 0;
        List<Order> drained = strategy.getPendingOrders();
        for (int i = 0; drained != null && !drained.isEmpty() && i < 10; i++) {
            discarded += drained.size();
            drained = strategy.getPendingOrders();
        }
        if (discarded > 0) {
            log.info("🧹 Discarded {} order(s) queued by the {} warm-up bar(s) — historical bars never trade.",
                discarded, initialBars.size());
        }
        if (drained != null && !drained.isEmpty()) {
            log.error("⛔ Strategy '{}' does not honour the drain contract: getPendingOrders() still returns {} "
                + "order(s) after warm-up. Its orders are computed from position state, so one derived from the "
                + "historical replay can reach the broker on the first live tick. Sync the strategy to the broker "
                + "position, or use a strategy that drains, before running it live.",
                strategyShortName, drained.size());
        }
        log.info("Warmed up with {} bars. Last bar: {}", initialBars.size(), lastBarTime);
    }

    private void runLoop() throws Exception {
        log.info("━━━ Starting strategy: {} (instrument: {}) ━━━", strategy.name(), toOandaSymbol());

        // Warm up FIRST, then resume state. The order is deliberate and was inverted until 2026-09-30:
        // the 200-bar replay calls strategy.onBar(), so resuming first meant the restored strategy state
        // (inTrade, direction, cooldown, trades today) was immediately overwritten by the historical view,
        // and a process that came back holding a position believed it was flat. The saved state is the
        // truth and the replay is only history, so the replay must happen first.
        String oandaSymbol = toOandaSymbol();
        log.info("Fetching initial {} candles for {} ...", granularity, oandaSymbol);
        List<Bar> initialBars = priceClient.getCandles(oandaSymbol, granularity, 200);
        if (initialBars.isEmpty()) {
            log.warn("No candles received! Check instrument name and account.");
            return;
        }

        // Warm up: feed historical bars but don't trade them
        warmUp(initialBars);

        // Remember where the replay left the bar cursor. resumeState() also restores lastBarTime, and the
        // saved value can be OLDER than the newest replayed bar: rewinding it would make the main loop
        // process bars the strategy has just been fed, which is exactly how historical signals reach the
        // broker. The cursor may only move forward.
        Instant warmedUpTo = lastBarTime;

        // Restore the saved state AFTER the replay, so it has the last word on the position
        resumeState();
        if (warmedUpTo != null && (lastBarTime == null || lastBarTime.isBefore(warmedUpTo))) {
            log.info("Bar cursor kept at the warm-up end ({} instead of the saved {}): those bars were "
                + "already replayed and must not be processed twice.", warmedUpTo, lastBarTime);
            lastBarTime = warmedUpTo;
        }

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

        // Reconcile the restored trades against the broker BEFORE the loop starts. Without this the first
        // tick calls checkPendingOrders() before updatePositions(), so a position the broker already closed
        // during downtime (its own SL/TP fired) is still treated as live, and an exit derived from the
        // restored state can be sent as a REDUCE_ONLY order against a position that no longer exists.
        try {
            updatePositions(oandaSymbol);
        } catch (Exception e) {
            log.warn("Startup reconciliation pass failed ({}); the main loop will retry.", e.getMessage());
        }

        // Main loop
        log.info("▶ Entering main loop ({}s interval)...", intervalSec);
        while (RUNNING.get() && alive.get()) {
            try {
                lastHeartbeatTime = TimeConventions.now();
                Instant loopStart = TimeConventions.now();
                tick(oandaSymbol);
                saveStatePeriodic();
                runIntegrityCheck();

                // Sleep for the remaining interval
                long elapsedMs = Duration.between(loopStart, TimeConventions.now()).toMillis();
                long sleepMs = Math.max(1000, (intervalSec * 1000L) - elapsedMs);
                Thread.sleep(sleepMs);

            } catch (InterruptedException e) {
                // This runner stops; the others must not. Setting the PROCESS-wide RUNNING here used to
                // end every other strategy's loop for an event that only concerns this thread.
                Thread.currentThread().interrupt();
                alive.set(false);
            } catch (BrokerException e) {
                log.warn("⚠ Broker error in loop for '{}' (will retry in 5s): {}", strategyShortName, e.getMessage());
                try { Thread.sleep(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            } catch (Exception e) {
                log.error("Loop error in '{}': {}", strategyShortName, e.getMessage(), e);
                try { Thread.sleep(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
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
        signalCount++;
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
            barCount++;
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
            barHistory.size(), totalEntries, totalExits, String.format("%.2f", ledger.total()));
    }

    // ========================================================================
    // Pending Orders
    // ========================================================================

    void checkPendingOrders(String oandaSymbol) throws Exception {
        List<Order> orders = strategy.getPendingOrders();
        if (orders == null || orders.isEmpty()) return;

        if (hasUnconfirmedReconciliation()) {
            // Entries stay blocked while a trade awaits reconciliation, but a CLOSE reduces risk and
            // must never be dropped. Clearing the whole queue discarded exit signals along with the
            // entries, leaving the broker position open with nothing managing it (2026-09-30 agy
            // review; the same finding the earlier pass raised).
            List<Order> closes = orders.stream().filter(Order::isCloseOnly).toList();
            if (closes.isEmpty()) {
                log.warn("⚠️ {} entry order(s) blocked because there is a trade awaiting OANDA reconciliation.",
                    orders.size());
                return;
            }
            log.warn("⚠️ {} entry order(s) blocked while awaiting reconciliation; {} close-only order(s) still processed.",
                orders.size() - closes.size(), closes.size());
            orders = closes;
        }

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
            double requestedUnits = Math.abs(order.quantity());
            double units;
            boolean reduceOnly;

            if (order.isCloseOnly()) {
                // Close-only STOP: reduce the tracked position, never risk-size it. A risk-sized
                // close would be sized like an entry and, without REDUCE_ONLY, open a NEW opposite
                // position on this hedging-enabled account instead of reducing.
                double tracked = trackedOpenUnits(oandaSymbol, order.side());
                if (tracked <= 0) {
                    log.error("⛔ Close-only STOP for {} with no tracked position — REFUSING to place it "
                        + "(a REDUCE_ONLY stop with nothing to reduce would be rejected, or open a new "
                        + "position on a hedging account).", oandaSymbol);
                    return;
                }
                units = order.side() == Order.Side.BUY ? tracked : -tracked;
                reduceOnly = true;
            } else {
                // STOP entry: cut-only legacy cap, NOT the two-sided raise. A filled pending STOP is
                // never reconciled into activeTrades, so a raised STOP entry would become a large
                // untracked position with no SL/TP. Only MARKET entries use the two-sided budget.
                double riskPct = riskForStrategy(strategyShortName);
                double balance = getCurrentBalance();
                double sizedUnits;
                double slDistance = (order.price() > 0 && order.stopLoss() > 0)
                    ? Math.abs(order.price() - order.stopLoss())
                    : 0;
                if (slDistance <= 0) {
                    // Guard (2026-09-30 agy review): a zero stop distance makes the budget
                    // (balance × risk / slDistance) divide by zero, yield Infinity, and let the
                    // REQUESTED units through uncapped: the same size-explosion class as the
                    // 2026-09-30 incident, on the pending-STOP path instead of the MARKET path.
                    // There is no risk budget without a stop distance, so fail small.
                    sizedUnits = Math.min(requestedUnits, NO_RISK_UNITS_CAP);
                    log.warn("⚠ No usable stop distance on STOP entry (price {}, stop {}) — safety cap: {} units → {} units",
                        formatPrice(order.price(), oandaSymbol), formatPrice(order.stopLoss(), oandaSymbol),
                        (int) requestedUnits, (int) sizedUnits);
                } else {
                    sizedUnits = legacyRiskCap(balance, riskPct, slDistance, requestedUnits, oandaSymbol);
                    log.info("⏳ STOP entry sized cut-only (two-sided raise disabled for pending stops — "
                        + "a filled STOP is never reconciled into activeTrades): {} units",
                        (int) sizedUnits);
                }
                units = order.side() == Order.Side.BUY ? sizedUnits : -sizedUnits;
                reduceOnly = false;
            }

            String unitsStr = String.valueOf((int) units);
            String priceStr = formatPrice(order.price(), oandaSymbol);
            String slStr = order.stopLoss() > 0 ? formatPrice(order.stopLoss(), oandaSymbol) : null;
            String tpStr = order.takeProfit() > 0 ? formatPrice(order.takeProfit(), oandaSymbol) : null;

            var tag = strategyShortName + "_" + oandaSymbol.replace("_", "");
            var result = executor.placeStopOrder(oandaSymbol, unitsStr, priceStr, tag, reduceOnly, slStr, tpStr);
            log.info("⏳ STOP ORDER PLACED: {} {} @ {} (OANDA ID: {})",
                oandaSymbol, order.side(), priceStr, result.orderId());

            synchronized (pendingStops) {
                pendingStops.add(new PendingStop(
                    result.orderId(), oandaSymbol,
                    order.side().name(), order.price(),
                    units, order.stopLoss(), order.takeProfit()
                ));
            }
        } catch (Exception e) {
            log.error("❌ Failed to place stop order: {}", e.getMessage());
        }
    }

    private void executeTrade(Order order, String oandaSymbol, double execPrice) {
        try {
            // Size to the risk budget (two-sided): the strategy's units are intent, the risk % is the budget
            double requestedUnits = Math.abs(order.quantity());
            double riskPct = riskForStrategy(strategyShortName);
            double balance = getCurrentBalance();

            // Detect if this order closes an existing active trade BEFORE sizing and the margin check
            boolean isClose = order.isCloseOnly();
            if (!isClose) {
                synchronized (activeTrades) {
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
                }
            }

            // A close order carries the size of the position it reduces — NEVER a risk-budget size.
            // Strategies keep their exit quantity at the old 1,000-unit micro-lot, so risk-sizing an exit
            // would send REDUCE_ONLY for 1,000 units against a position of ~192,000 and leave the rest
            // open, unmanaged and without a stop. Entries alone are sized to the risk budget.
            double sizedUnits;
            if (isClose) {
                double tracked = trackedOpenUnits(oandaSymbol, order.side());
                if (tracked > 0) {
                    sizedUnits = tracked;
                    if (Math.abs(tracked - requestedUnits) > 1) {
                        log.info("📐 Close sizing: {} units requested → {} units (the tracked open position)",
                            (int) requestedUnits, (int) tracked);
                    }
                } else {
                    // Nothing tracked, but the broker may still hold the position (opened outside this
                    // runner, or lost state). Sending the strategy's micro-lot REDUCE_ONLY here would
                    // close 1,000 of e.g. 48,000 units and leave the rest open and unmanaged — the
                    // exact failure dc9509a7 set out to fix. Size from the broker's actual open
                    // position; if that is unavailable, refuse to send rather than leak a partial close.
                    double brokerUnits = brokerOpenUnits(oandaSymbol, order.side());
                    if (brokerUnits > 0) {
                        sizedUnits = brokerUnits;
                        log.info("📐 Close sizing (broker truth): {} units requested → {} units (the broker's open position)",
                            (int) requestedUnits, (int) brokerUnits);
                    } else {
                        log.error("⛔ Close order for {} with no tracked position and no broker position to reduce — "
                            + "REFUSING to send the {} unit micro-lot (would leave an untracked position open).",
                            oandaSymbol, (int) requestedUnits);
                        return;
                    }
                }
            } else {
                // Entry: two-sided risk budget — the strategy's units are intent, the risk % is the budget
                // Guard (2026-09-30 incident): the order carries the stop computed on its SIGNAL bar, but
                // it is priced at the LIVE market. When the market has already reached or crossed that
                // stop, the risk distance collapses toward zero and the budget inflates the size without
                // bound (measured: a 1,857,300-unit request, clipped only by the max-units rail). There is
                // no valid risk budget for an entry whose stop is already gone: refuse it instead.
                boolean stopAlreadyCrossed = order.stopLoss() > 0
                    && ((order.side() == Order.Side.BUY && execPrice <= order.stopLoss())
                        || (order.side() == Order.Side.SELL && execPrice >= order.stopLoss()));
                if (stopAlreadyCrossed) {
                    log.error("⛔ Entry refused for {} {}: live price {} has already reached the order's stop {} — "
                        + "the risk distance is zero or inverted, so any size would be unbudgeted.",
                        oandaSymbol, order.side(),
                        formatPrice(execPrice, oandaSymbol), formatPrice(order.stopLoss(), oandaSymbol));
                    return;
                }
                sizedUnits = riskSizedUnits(balance, riskPct, execPrice,
                    order.stopLoss(), requestedUnits, oandaSymbol);
            }

            double units = order.side() == Order.Side.BUY
                ? sizedUnits
                : -sizedUnits;
            String unitsStr = String.valueOf((int) units);

            var tag = strategyShortName + "_" + oandaSymbol.replace("_", "");

            if (isClose) {
                // ─── Exit triggered by the strategy's own signal ───
                //
                // ACCOUNTING RULE (bugfix 2026-09-29). Realized P&L is ALWAYS taken from the broker,
                // in the account's home currency, and exactly once. The local estimate below lives in
                // the instrument's QUOTE currency (JPY for GBP_JPY, USD for EUR_USD) and must NEVER
                // enter the realized-P&L ledger: for GBP_JPY it overstated the counter by ~108x, it was never
                // corrected afterwards (the trade was dropped from activeTrades before reconciliation),
                // and it was double-counted whenever the async reconciliation completed the trade too.
                var price = priceClient.getPrice(oandaSymbol);
                double estimateQuoteCcy = estimateExitPnl(oandaSymbol, price.bid(), price.ask());

                // Guard: every tracked trade for this symbol already awaits broker reconciliation →
                // the position is already being closed. Do not send a second reduce-only order and do
                // not touch the counters.
                if (isAlreadyClosing(oandaSymbol)) {
                    log.info("EXIT signal for {} ignored — position already closing (awaiting broker reconciliation).", oandaSymbol);
                    return;
                }

                // Use REDUCE_ONLY so this closes the existing position on hedging-enabled accounts
                var result = executor.placeMarketOrder(oandaSymbol, unitsStr, tag, true);
                // Keep the trade(s) tracked and queue them: the broker's realizedPL (account
                // currency) lands in the realized-P&L ledger via completeReconciliation().
                int registered = registerSignalExitForReconciliation(oandaSymbol);
                if (registered == 0) {
                    // Closing something we never tracked (e.g. position opened outside this runner):
                    // no broker reconciliation will follow, so count the exit here.
                    totalExits++;
                }
                // Otherwise the exit is NOT counted here: completeReconciliation() counts it exactly
                // once per trade when the broker answers. Counting it here too would double-count the
                // signal-driven exit (the SL/TP exit path increments only at reconciliation).
                log.info("═══════ EXIT {} {} {} @ {} | {} trade(s) pending broker reconciliation (local estimate {} {} NOT counted) ═══════",
                    oandaSymbol, order.side(),
                    String.format("%.2f", units / 100000.0) + " lots",
                    result.fillPrice(),
                    registered,
                    estimateQuoteCcy >= 0 ? "+" : "",
                    String.format("%.2f", estimateQuoteCcy));
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
                synchronized (activeTrades) {
                    activeTrades.add(new ActiveTrade(
                        result.tradeId(), oandaSymbol, order.side().name(), fillPrice,
                        Math.abs(units), order.stopLoss(), order.takeProfit(),
                        TimeConventions.now()
                    ));
                }
            }

        } catch (Exception e) {
            log.error("❌ TRADE EXECUTION FAILED: {} {} @ {} — {}",
                oandaSymbol, order.side(), formatPrice(execPrice, oandaSymbol), e.getMessage());
        }
    }

    // ========================================================================
    // Exit accounting (account-currency only — see the ACCOUNTING RULE above)
    // ========================================================================

    /**
     * Units currently tracked as open for a symbol, on the side an order would reduce — the quantity a
     * close order must carry so its REDUCE_ONLY fill actually flattens the position. Trades awaiting
     * broker reconciliation are excluded: they are already being closed.
     *
     * @param oandaSymbol  instrument being closed
     * @param closingSide  the side of the closing order (reduces the OPPOSITE side)
     * @return sum of |tracked quantity| on the opposite side, or 0 when nothing is tracked
     */
    private double trackedOpenUnits(String oandaSymbol, Order.Side closingSide) {
        double total = 0;
        synchronized (activeTrades) {
            for (ActiveTrade at : activeTrades) {
                if (!at.symbol.equals(oandaSymbol)) continue;
                if ("UNCONFIRMED_RECONCILIATION".equals(at.reconciliationStatus)) continue;
                boolean opposite = (closingSide == Order.Side.BUY && "SELL".equals(at.side))
                    || (closingSide == Order.Side.SELL && "BUY".equals(at.side));
                if (opposite) total += Math.abs(at.quantity);
            }
        }
        return total;
    }

    /**
     * Broker-truth sizing for a close: total |currentUnits| the broker holds for the instrument on
     * the side the close reduces, from a single read of {@code /openTrades}. Returns 0 when the
     * broker has nothing to reduce or the read fails — callers must then refuse to send.
     */
    private double brokerOpenUnits(String oandaSymbol, Order.Side closingSide) {
        try {
            return executor.getOpenPositionUnits(oandaSymbol, closingSide);
        } catch (Exception e) {
            log.warn("⚠ Could not read the broker's open position for {} to size a close: {}",
                oandaSymbol, e.getMessage());
            return 0;
        }
    }

    /** True while at least one tracked trade for the symbol can still be closed by a signal. */
    /**
     * True ONLY when this symbol has tracked trades and none of them can still be closed, meaning the
     * position is already closing and a second reduce-only order would be a duplicate.
     *
     * An EMPTY activeTrades is deliberately NOT "already closing": that is exactly the untracked broker
     * position the close path sizes from brokerOpenUnits(), and the wider test this replaced
     * (!hasClosableTrades, removed as dead code alongside it) dropped those exits silently, leaving
     * the broker position open and unmanaged (2026-09-30 agy review).
     */
    private boolean isAlreadyClosing(String oandaSymbol) {
        boolean anyTracked = false;
        synchronized (activeTrades) {
            for (ActiveTrade at : activeTrades) {
                if (!at.symbol.equals(oandaSymbol)) continue;
                anyTracked = true;
                if (!"UNCONFIRMED_RECONCILIATION".equals(at.reconciliationStatus)) {
                    return false;
                }
            }
        }
        return anyTracked;
    }

    /**
     * DISPLAY-ONLY estimate of the pending exit P&L, in the instrument's QUOTE currency
     * (JPY for GBP_JPY, USD for EUR_USD). Never add this to the realized-P&L ledger: the account
     * currency value comes from the broker at reconciliation.
     */
    private double estimateExitPnl(String oandaSymbol, double currentBid, double currentAsk) {
        double estimate = 0;
        synchronized (activeTrades) {
            for (ActiveTrade at : activeTrades) {
                if (at.symbol.equals(oandaSymbol)
                    && !"UNCONFIRMED_RECONCILIATION".equals(at.reconciliationStatus)) {
                    if (at.side.equals("BUY")) {
                        estimate += (currentBid - at.entryPrice) * at.quantity;
                    } else {
                        estimate += (at.entryPrice - currentAsk) * at.quantity;
                    }
                }
            }
        }
        return estimate;
    }

    /**
     * Marks every closable trade for the symbol as awaiting broker reconciliation and queues it,
     * instead of dropping it locally. The trade stays in {@code activeTrades} until the broker
     * answers; its realizedPL (account currency) is then added exactly once by
     * {@link #completeReconciliation(String, double)}.
     *
     * @return number of trades registered for reconciliation
     */
    int registerSignalExitForReconciliation(String oandaSymbol) {
        List<String> tradeIds = markClosableTradesForReconciliation(oandaSymbol);
        for (String tradeId : tradeIds) {
            AsyncReconciliationQueue.GLOBAL.submit(this, tradeId);
        }
        return tradeIds.size();
    }

    /**
     * Pure state move, no broker call and no queue: flag every closable trade of the symbol as
     * awaiting broker reconciliation (and drop the ones with no broker trade id).
     *
     * @return the broker trade ids to reconcile
     */
    List<String> markClosableTradesForReconciliation(String oandaSymbol) {
        List<String> tradeIds = new ArrayList<>();
        synchronized (activeTrades) {
            Iterator<ActiveTrade> it = activeTrades.iterator();
            while (it.hasNext()) {
                ActiveTrade at = it.next();
                if (!at.symbol.equals(oandaSymbol)
                    || "UNCONFIRMED_RECONCILIATION".equals(at.reconciliationStatus)) {
                    continue;
                }
                if (at.tradeId == null) {
                    // Nothing to reconcile with the broker — drop it rather than park it forever.
                    it.remove();
                    continue;
                }
                at.reconciliationStatus = "UNCONFIRMED_RECONCILIATION";
                tradeIds.add(at.tradeId);
            }
        }
        if (!tradeIds.isEmpty()) {
            saveStateNow();
        }
        return tradeIds;
    }

    /** Realized P&L accumulated in the ACCOUNT currency (broker-sourced, once per trade). */
    public double getTotalPnl() {
        return ledger.total();
    }

    /** Number of trades whose broker P&L is recorded in the ledger (package-private for tests/observability). */
    int getPnlLedgerTrades() {
        return ledger.trades();
    }

    /** Number of local quote-currency estimates ignored (package-private for tests/observability). */
    int getPnlIgnoredLocalEstimates() {
        return ledger.ignoredLocalEstimates();
    }

    /** The bounded ledger itself (package-private for tests/observability). */
    RealizedPnlLedger getPnlLedger() {
        return ledger;
    }

    /** Number of entries currently held individually in the recent window (package-private for tests). */
    int getPnlLedgerRecentSize() {
        return ledger.recentSize();
    }

    /** Trades whose broker P&L is still unknown after the fallback fired (copy; watchdog re-attempts them). */
    public List<UnreconciledTrade> getUnreconciledTrades() {
        synchronized (unreconciledTrades) {
            return new ArrayList<>(unreconciledTrades);
        }
    }

    /** Number of ledger-vs-broker divergences found by the most recent integrity check. */
    public int getPnlIntegrityMismatches() {
        return pnlIntegrityMismatches;
    }

    public int getTotalExits() {
        return totalExits;
    }

    // ========================================================================
    // Position Monitoring
    // ========================================================================

    private void updatePositions(String oandaSymbol) throws Exception {
        synchronized (activeTrades) {
            if (activeTrades.isEmpty()) return;
        }

        Instant now = TimeConventions.now();
        if (Duration.between(lastReconciliationTime, now).toSeconds() >= 60) {
            try {
                Set<String> openTradeIds = executor.getOpenTradeIds();
                synchronized (activeTrades) {
                    boolean changed = false;
                    for (ActiveTrade trade : activeTrades) {
                        if ("CONFIRMED".equals(trade.reconciliationStatus) && trade.tradeId != null && !openTradeIds.contains(trade.tradeId)) {
                            log.warn("⚠️ Trade ID {} for strategy {} is no longer open at OANDA. Triggering async reconciliation.",
                                trade.tradeId, strategyShortName);
                            trade.reconciliationStatus = "UNCONFIRMED_RECONCILIATION";
                            AsyncReconciliationQueue.GLOBAL.submit(this, trade.tradeId);
                            changed = true;
                        }
                    }
                    if (changed) {
                        saveStateNow();
                    }
                }
            } catch (Exception e) {
                log.error("Failed to check open trades with OANDA: {}", e.getMessage());
            }
            lastReconciliationTime = now;
        }

        synchronized (activeTrades) {
            if (activeTrades.isEmpty()) return;
        }

        var price = priceClient.getPrice(oandaSymbol);
        double currentBid = price.bid();
        double currentAsk = price.ask();

        // Trades whose local SL/TP just fired. Their broker calls happen AFTER the lock is released:
        // a synchronous request inside synchronized(activeTrades) would block every other thread that
        // touches the trade list.
        List<ActiveTrade> locallyExited = new ArrayList<>();

        synchronized (activeTrades) {
            Iterator<ActiveTrade> it = activeTrades.iterator();
            while (it.hasNext()) {
                ActiveTrade trade = it.next();
                if ("UNCONFIRMED_RECONCILIATION".equals(trade.reconciliationStatus)) {
                    continue; // Skip already exited trades awaiting OANDA confirmation
                }
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
                    log.info("═══════ EXIT {} {} @ {} (Triggering async reconciliation) ═══════",
                        trade.symbol, trade.side,
                        formatPrice(exitPrice, trade.symbol));
                    trade.reconciliationStatus = "UNCONFIRMED_RECONCILIATION";
                    locallyExited.add(trade);
                    AsyncReconciliationQueue.GLOBAL.submit(this, trade.tradeId);
                    saveStateNow();
                } else {
                // The per-trade PnL below is denominated in the QUOTE currency (JPY for GBP_JPY), not in
                // the account currency. Printed unlabelled it reads as a contradiction: "-25742.80" on
                // this line against "Unrealized P&L: -188.85 CAD" on the account summary, which are the
                // same loss. That is the number a human reads to decide whether to cut a position, so an
                // unlabelled or wrong unit is worse than no number. Label it.
                String quoteCurrency = trade.symbol != null && trade.symbol.contains("_")
                    ? trade.symbol.substring(trade.symbol.indexOf('_') + 1)
                    : "";
                log.info("   {} {} | Entry: {} | Current: {} | PnL: {}{} {} | SL: {} TP: {}",
                    trade.symbol, trade.side,
                    formatPrice(trade.entryPrice, trade.symbol),
                    formatPrice(mid, trade.symbol),
                    pnl >= 0 ? "+" : "",
                    String.format("%.2f", pnl),
                    quoteCurrency,
                    trade.stopLoss > 0 ? formatPrice(trade.stopLoss, trade.symbol) : "—",
                    trade.takeProfit > 0 ? formatPrice(trade.takeProfit, trade.symbol) : "—");
                }
            }
        }

        // Close whatever the local stop or target just triggered. This path used to rely entirely on the
        // broker's own SL/TP: when addStopLoss failed at entry (warn-and-continue) nothing closed the
        // position, reconciliation found the trade still OPEN, reverted it to CONFIRMED, and the next
        // pass fired again, leaving the position open forever in a mark/revert loop (2026-09-30 agy
        // review, BLOCKER). REDUCE_ONLY makes this a no-op when the broker already closed it and never a
        // new opposite position, so it cannot double-close a position.
        String closeTag = strategyShortName + "_" + oandaSymbol.replace("_", "");
        for (ActiveTrade trade : locallyExited) {
            double closeUnits = trade.side.equals("BUY") ? -trade.quantity : trade.quantity;
            try {
                executor.placeMarketOrder(oandaSymbol, String.valueOf((int) closeUnits), closeTag, true);
                log.info("📤 Sent REDUCE_ONLY close for {} {} ({} units) after the local SL/TP fired.",
                    trade.symbol, trade.side, (int) closeUnits);
            } catch (Exception e) {
                log.warn("⚠ Could not send the REDUCE_ONLY close for {} {} ({} units): {}. Expected when the "
                    + "broker's own SL/TP already closed the position; if not, the position is still open "
                    + "and the next reconciliation pass will see it.", trade.symbol, trade.side,
                    (int) closeUnits, e.getMessage());
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
        // V2 — Backtest-qualified (June 2026)
        if (name.contains("COMPOSITEMOMENTUM")) return "USD_JPY";
        if (name.contains("ATREXPANSIONMOMENTUM")) return "GBP_USD";
        if (name.contains("MONTHWEEKPHASE")) return "USD_JPY";
        // Long-term strategies
        if (name.contains("LTRSI3")) return "EUR_USD";
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

    void saveStateNow() {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("strategy", strategyShortName);
            root.put("displayName", strategy.name());
            root.put("instrument", toOandaSymbol());
            root.put("granularity", granularity);
            root.put("intervalSec", intervalSec);
            root.put("totalEntries", totalEntries);
            root.put("totalExits", totalExits);
            root.put("pnlAccountingVersion", PNL_ACCOUNTING_VERSION);
            // Realized-P&L ledger: the recent window keyed by trade id, plus the rolled-up total
            // and the dedupe id set, so the exact total and dedupe survive a restart.
            ObjectNode pnlLedger = root.putObject("pnlLedger");
            for (Map.Entry<String, Double> e : ledger.snapshot().entrySet()) {
                pnlLedger.put(e.getKey(), e.getValue());
            }
            root.put("pnlLedgerConfirmedTotal", ledger.confirmedTotal());
            ArrayNode recordedIdsArray = root.putArray("pnlLedgerRecordedIds");
            for (String id : ledger.recordedIds()) {
                recordedIdsArray.add(id);
            }
            root.put("pnlLedgerTrades", ledger.trades());
            root.put("ignoredLocalEstimates", ledger.ignoredLocalEstimates());
            root.put("pnlIntegrityMismatches", pnlIntegrityMismatches);
            // Unreconciled trades (fallback fired, broker value still unknown) — observable, not silent.
            ArrayNode unreconciledArray = root.putArray("unreconciledTrades");
            synchronized (unreconciledTrades) {
                for (UnreconciledTrade u : unreconciledTrades) {
                    ObjectNode un = unreconciledArray.addObject();
                    un.put("tradeId", u.tradeId);
                    un.put("symbol", u.symbol);
                    un.put("reason", u.reason);
                    un.put("timestamp", u.timestamp.toString());
                }
                root.put("unreconciledTradesCount", unreconciledTrades.size());
            }
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
            synchronized (activeTrades) {
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
                    tn.put("reconciliationStatus", t.reconciliationStatus);
                }
            }

            // Pending stops — snapshot under the monitor so an async save can't race a strategy-thread append.
            ArrayNode stopsArray = root.putArray("pendingStops");
            List<PendingStop> stopsSnapshot;
            synchronized (pendingStops) {
                stopsSnapshot = new ArrayList<>(pendingStops);
            }
            for (PendingStop p : stopsSnapshot) {
                ObjectNode pn = stopsArray.addObject();
                pn.put("orderId", p.orderId);
                pn.put("symbol", p.symbol);
                pn.put("side", p.side);
                pn.put("price", p.price);
                pn.put("quantity", p.quantity);
                pn.put("stopLoss", p.stopLoss);
                pn.put("takeProfit", p.takeProfit);
            }

            writeStateAtomic(root);
            lastStateSave = TimeConventions.now();
        } catch (Exception e) {
            log.warn("Failed to save state for '{}': {}", strategyShortName, e.getMessage());
        }
    }

    /**
     * Serializes the two state files atomically, behind the state-write lock, so concurrent saves
     * (strategy thread + async worker) can never truncate a file. The state file is written without
     * the "running" flag, the monitor file with it — same content as before, only atomic now.
     */
    private void writeStateAtomic(ObjectNode root) throws IOException {
        synchronized (stateWriteLock) {
            writeJsonAtomic(stateFile, root);
            root.put("running", RUNNING.get());
            writeJsonAtomic(monitorFile, root);
            root.remove("running");
        }
    }

    /** Writes JSON to {@code file + ".tmp"} then atomically moves it over the target. */
    private void writeJsonAtomic(Path file, ObjectNode node) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), node);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignore) {
                // best-effort cleanup only
            }
            throw e;
        }
    }

    public boolean hasUnconfirmedReconciliation() {
        synchronized (activeTrades) {
            for (ActiveTrade t : activeTrades) {
                if ("UNCONFIRMED_RECONCILIATION".equals(t.reconciliationStatus)) {
                    return true;
                }
            }
        }
        return false;
    }

    public void reconcileTrade(String tradeId) throws Exception {
        com.fasterxml.jackson.databind.JsonNode details = executor.getTradeDetails(tradeId);
        if (details != null && details.has("trade")) {
            com.fasterxml.jackson.databind.JsonNode tNode = details.get("trade");
            String state = tNode.has("state") ? tNode.get("state").asText() : "";
            if ("CLOSED".equals(state)) {
                reconcileClosedTrade(tradeId, tNode);
            } else {
                reconcileOpenTrade(tradeId);
            }
        } else {
            throw new RuntimeException("Trade details node not found in OANDA response");
        }
    }

    /**
     * Handles a broker-reported CLOSED trade. If the broker response carries no {@code realizedPL}
     * field the value is <em>unknown</em>, so it is postponed (the trade stays pending for a later
     * attempt) rather than silently recorded as 0.0 — a 0.0 would lose a real broker value and,
     * because of first-wins dedupe, could never be corrected.
     */
    void reconcileClosedTrade(String tradeId, com.fasterxml.jackson.databind.JsonNode tNode) {
        if (!tNode.hasNonNull("realizedPL")) {
            // The trade IS CLOSED at the broker but the response carries no usable P&L. Note
            // hasNonNull, not has: has() is TRUE for an explicit JSON null, and asDouble() would
            // read that as 0.0 — which the first-wins ledger could never correct (the M2 bug, in a
            // variant). The value is UNKNOWN, so:
            //   - do NOT record 0;
            //   - EVICT the trade (it is closed at the broker). Keeping it tracked would make the
            //     60s sweep find it missing from the broker's open trades and re-flag it
            //     UNCONFIRMED forever (a re-block loop), and would make later opposite signals be
            //     misread as closes, sending REDUCE_ONLY orders the broker rejects;
            //   - park it so the watchdog's re-attempt pass fetches the real value later.
            log.warn("⚠️ Trade ID {} is CLOSED but has no usable realizedPL — P&L unknown, NOT recorded as 0. "
                + "Trade evicted from tracking (it is closed) and parked for a broker re-attempt.", tradeId);
            parkUnreconciledTrade(tradeId, "CLOSED without usable realizedPL");
            evictFromActiveTrades(tradeId);
            saveStateNow();
            return;
        }
        double realizedPL = tNode.get("realizedPL").asDouble();
        log.info("Reconciled trade ID {} from OANDA. Realized PnL: ${}", tradeId, realizedPL);
        completeReconciliation(tradeId, realizedPL);
    }

    /** Removes a trade from tracking and counts the exit. @return true when it was tracked. */
    private boolean evictFromActiveTrades(String tradeId) {
        synchronized (activeTrades) {
            Iterator<ActiveTrade> it = activeTrades.iterator();
            while (it.hasNext()) {
                ActiveTrade t = it.next();
                if (tradeId.equals(t.tradeId)) {
                    it.remove();
                    totalExits++;
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A queued reconciliation found the trade still open at the broker (the exit never filled).
     * Reverting to CONFIRMED lets the periodic position sweep keep monitoring it instead of leaving
     * it UNCONFIRMED_RECONCILIATION forever — which would block every future entry order.
     */
    void reconcileOpenTrade(String tradeId) {
        log.info("Trade ID {} is still open at OANDA. Reverting to CONFIRMED so the position stays tracked.", tradeId);
        revertToConfirmed(tradeId);
    }

    private void revertToConfirmed(String tradeId) {
        boolean changed = false;
        synchronized (activeTrades) {
            for (ActiveTrade t : activeTrades) {
                if (tradeId.equals(t.tradeId)) {
                    if ("UNCONFIRMED_RECONCILIATION".equals(t.reconciliationStatus)) {
                        t.reconciliationStatus = "CONFIRMED";
                        changed = true;
                    }
                    break;
                }
            }
        }
        if (changed) {
            saveStateNow();
        }
    }

    /**
     * Parks a trade whose broker P&L could not be obtained, so the watchdog's re-attempt pass can
     * fetch it later instead of losing it silently. Deduplicated by trade id.
     */
    private void parkUnreconciledTrade(String tradeId, String reason) {
        String symbol = null;
        synchronized (activeTrades) {
            for (ActiveTrade t : activeTrades) {
                if (tradeId.equals(t.tradeId)) {
                    symbol = t.symbol;
                    break;
                }
            }
        }
        boolean added = false;
        synchronized (unreconciledTrades) {
            for (UnreconciledTrade u : unreconciledTrades) {
                if (tradeId.equals(u.tradeId)) {
                    return; // already pending a re-attempt
                }
            }
            unreconciledTrades.add(new UnreconciledTrade(tradeId, symbol, reason, TimeConventions.now()));
            added = true;
        }
        // No save here on purpose: callers batch the state write, so a single logical change
        // (park + evict) does not trigger two consecutive atomic disk writes.
        if (added) {
            log.debug("Parked unreconciled trade {} ({}) for a later broker re-attempt.", tradeId, reason);
        }
    }

    public void reconcileTradeFallback(String tradeId) {
        String symbol = null;
        double localQuoteCcy = 0.0;
        boolean found = false;
        synchronized (activeTrades) {
            Iterator<ActiveTrade> it = activeTrades.iterator();
            while (it.hasNext()) {
                ActiveTrade t = it.next();
                if (tradeId.equals(t.tradeId)) {
                    localQuoteCcy = t.unrealizedPnl;
                    symbol = t.symbol;
                    it.remove();
                    totalExits++;
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            return;
        }
        // The local value is in the instrument's QUOTE currency, so it must NOT move the total:
        // record it only as an ignored estimate.
        ledger.recordIgnoredLocalEstimate();
        // Persist the fact that this trade's broker P&L is still unknown, so the watchdog can
        // re-attempt it later instead of losing it silently.
        synchronized (unreconciledTrades) {
            unreconciledTrades.add(new UnreconciledTrade(
                tradeId, symbol, "broker value unavailable after 5 attempts", TimeConventions.now()));
        }
        log.warn("⚠️ Fallback reconciliation for trade ID {}: broker value unavailable. "
            + "Local P&L ${} is in QUOTE currency, so the trade's P&L stays UNKNOWN "
            + "(never wrong) — the total is NOT changed. The watchdog will re-attempt the broker value.",
            tradeId, String.format("%.2f", localQuoteCcy));
        saveStateNow();
    }

    public void completeReconciliation(String tradeId, double realizedPL) {
        // Move the total only when the broker value is recorded for the first time (dedupe guard).
        boolean counted = ledger.recordBrokerPnl(tradeId, realizedPL);
        // Reconciled for good: it must not stay queued for a watchdog re-attempt.
        removeUnreconciledTrade(tradeId);
        boolean removed = false;
        synchronized (activeTrades) {
            Iterator<ActiveTrade> it = activeTrades.iterator();
            while (it.hasNext()) {
                ActiveTrade t = it.next();
                if (tradeId.equals(t.tradeId)) {
                    totalExits++;
                    it.remove();
                    removed = true;
                    break;
                }
            }
        }
        if (counted) {
            log.info("Reconciliation complete. Removed trade ID {} from activeTrades. Total exits: {}, Total realized PnL: ${}",
                tradeId, totalExits, String.format("%.2f", ledger.total()));
        } else {
            log.warn("⚠️ Duplicate broker P&L for trade ID {} ignored — value ${} was NOT double-counted. Ledger total stays ${}.",
                tradeId, String.format("%.2f", realizedPL), String.format("%.2f", ledger.total()));
        }
        if (removed || counted) {
            saveStateNow();
        }
    }

    /**
     * Integrity watchdog: at most once every 30 minutes, decide whether to schedule the check. The
     * check itself never runs on the strategy loop thread — it is submitted to a dedicated daemon
     * executor, so a slow broker call cannot stall the trading loop and miss bars.
     */
    private void runIntegrityCheck() {
        Instant now = TimeConventions.now();
        if (Duration.between(lastIntegrityCheck, now).toSeconds() < 1800) return;
        lastIntegrityCheck = now;
        integrityExecutor().submit(() -> {
            try {
                pnlIntegrityMismatches = verifyLedgerAgainstBroker();
                reattemptUnreconciledTrades();
            } catch (Throwable t) {
                log.warn("⚠️ P&L integrity check failed: {}", t.getMessage());
            }
        });
    }

    /** Lazily creates the single-thread daemon executor that runs the watchdog, off the loop thread. */
    private synchronized ExecutorService integrityExecutor() {
        if (integrityExecutor == null) {
            integrityExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "pnl-integrity-" + strategyShortName);
                t.setDaemon(true);
                return t;
            });
        }
        return integrityExecutor;
    }

    /**
     * Compares the bounded recent ledger window (at most {@link RealizedPnlLedger#RECENT_WINDOW}
     * entries) against the broker's realizedPL for each CLOSED trade, pacing one broker call every
     * 50 ms. Divergences are logged per-trade at ERROR plus a summary ERROR, and the count is
     * returned. Broker outages or malformed responses are caught and logged at debug — they never
     * throw and never produce a false positive. A CLOSED trade with no {@code realizedPL} is logged
     * at WARN and skipped (it cannot be compared, and must not be treated as a 0.0 divergence).
     */
    public int verifyLedgerAgainstBroker() {
        int divergences = 0;
        for (Map.Entry<String, Double> entry : ledger.snapshot().entrySet()) {
            divergences += verifyOneLedgerEntry(entry.getKey(), entry.getValue());
            // Pace the broker calls so the check never floods the rate limiter.
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (divergences > 0) {
            log.error("❌ P&L integrity check: {} divergence(s) between broker realizedPL and the ledger.", divergences);
        }
        return divergences;
    }

    /** Verifies a single ledger entry against the broker; never throws. Returns 1 on a divergence. */
    private int verifyOneLedgerEntry(String tradeId, double ledgerValue) {
        try {
            JsonNode details = executor.getTradeDetails(tradeId);
            if (details == null || !details.has("trade")) {
                return 0;
            }
            JsonNode tNode = details.get("trade");
            String state = tNode.has("state") ? tNode.get("state").asText() : "";
            if (!"CLOSED".equals(state)) {
                return 0;
            }
            if (!tNode.hasNonNull("realizedPL")) {
                log.warn("⚠️ P&L integrity: trade ID {} is CLOSED but has no realizedPL at the broker — cannot verify, skipping.", tradeId);
                return 0;
            }
            double brokerValue = tNode.get("realizedPL").asDouble();
            double tolerance = Math.max(0.02, Math.abs(brokerValue) * 0.01);
            if (Math.abs(brokerValue - ledgerValue) > tolerance) {
                log.error("❌ P&L integrity mismatch for trade ID {}: broker realizedPL ${} vs ledger ${} (tolerance ${})",
                    tradeId, String.format("%.2f", brokerValue),
                    String.format("%.2f", ledgerValue), String.format("%.2f", tolerance));
                return 1;
            }
            return 0;
        } catch (Exception e) {
            log.debug("P&L integrity check: cannot fetch trade {} from broker ({}).", tradeId, e.getMessage());
            return 0;
        }
    }

    /**
     * Re-attempts (bounded, paced) the trades that the fallback could not reconcile, and removes
     * them from the unreconciled list once the broker answers with a CLOSED realizedPL. Never throws.
     */
    private void reattemptUnreconciledTrades() {
        List<String> ids;
        synchronized (unreconciledTrades) {
            ids = new ArrayList<>();
            for (UnreconciledTrade u : unreconciledTrades) {
                ids.add(u.tradeId);
            }
        }
        int checked = 0;
        for (String tradeId : ids) {
            if (checked >= MAX_REATTEMPT_PER_PASS) break;
            checked++;
            try {
                JsonNode details = executor.getTradeDetails(tradeId);
                if (details != null && details.has("trade")) {
                    JsonNode tNode = details.get("trade");
                    String state = tNode.has("state") ? tNode.get("state").asText() : "";
                    if ("CLOSED".equals(state) && tNode.hasNonNull("realizedPL")) {
                        double realizedPL = tNode.get("realizedPL").asDouble();
                        completeReconciliation(tradeId, realizedPL);
                        removeUnreconciledTrade(tradeId);
                        log.info("♻ Unreconciled trade {} recovered from the broker: realizedPL ${}.",
                            tradeId, String.format("%.2f", realizedPL));
                    }
                }
            } catch (Exception e) {
                log.debug("Unreconciled trade {} still unavailable from broker: {}", tradeId, e.getMessage());
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void removeUnreconciledTrade(String tradeId) {
        synchronized (unreconciledTrades) {
            unreconciledTrades.removeIf(u -> tradeId.equals(u.tradeId));
        }
    }

    public void markUnconfirmed(String tradeId) {
        synchronized (activeTrades) {
            for (ActiveTrade t : activeTrades) {
                if (tradeId.equals(t.tradeId)) {
                    if (!"UNCONFIRMED_RECONCILIATION".equals(t.reconciliationStatus)) {
                        t.reconciliationStatus = "UNCONFIRMED_RECONCILIATION";
                        saveStateNow();
                    }
                    return;
                }
            }
        }
    }

    private void saveStateFailed(String error) {
        try {
            ObjectNode root = MAPPER.createObjectNode();
            root.put("strategy", strategyShortName);
            root.put("displayName", strategy.name());
            root.put("instrument", toOandaSymbol());
            root.put("status", "FAILED");
            root.put("error", error);
            root.put("savedAt", TimeConventions.now().toString());
            root.put("running", false);
            synchronized (stateWriteLock) {
                writeJsonAtomic(stateFile, root);
                writeJsonAtomic(monitorFile, root);
            }
        } catch (Exception e) {
            log.warn("Failed to save failed state for '{}': {}", strategyShortName, e.getMessage());
        }
    }

    void resumeState() {
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

            // Realized-P&L ledger: only trust a v3+ state file that carries the trade-id-keyed
            // ledger. Older files (v1/v2) mixed quote-currency estimates with account-currency
            // values, so they are discarded rather than carried into the corrected ledger.
            int statePnlVersion = root.has("pnlAccountingVersion")
                ? root.get("pnlAccountingVersion").asInt(0) : 0;
            if (statePnlVersion >= PNL_ACCOUNTING_VERSION && root.has("pnlLedger")) {
                Map<String, Double> savedLedger = new LinkedHashMap<>();
                JsonNode pnlLedgerNode = root.get("pnlLedger");
                pnlLedgerNode.fields().forEachRemaining(e ->
                    savedLedger.put(e.getKey(), e.getValue().asDouble()));
                double confirmedTotal = root.has("pnlLedgerConfirmedTotal")
                    ? root.get("pnlLedgerConfirmedTotal").asDouble() : 0.0;
                List<String> recordedIds = new ArrayList<>();
                if (root.has("pnlLedgerRecordedIds")) {
                    for (JsonNode id : root.get("pnlLedgerRecordedIds")) {
                        recordedIds.add(id.asText());
                    }
                } else {
                    // Backward-compat with a v3 file written before the bounded ledger: the dedupe
                    // ids are exactly the recent-window keys.
                    recordedIds.addAll(savedLedger.keySet());
                }
                int ignoredLocalEstimates = root.has("ignoredLocalEstimates")
                    ? root.get("ignoredLocalEstimates").asInt() : 0;
                ledger.restore(confirmedTotal, savedLedger, recordedIds, ignoredLocalEstimates);
            } else if (statePnlVersion < PNL_ACCOUNTING_VERSION) {
                log.warn("⚠️ Discarding legacy realized-P&L accumulator (state v{}, current v{}): it was "
                    + "accumulated with the quote-currency estimate and is not in the account currency. "
                    + "Realized P&L restarts at 0 and now comes from the broker only (once per trade).",
                    statePnlVersion, PNL_ACCOUNTING_VERSION);
            }
            if (root.has("pnlIntegrityMismatches")) {
                pnlIntegrityMismatches = root.get("pnlIntegrityMismatches").asInt();
            }
            if (root.hasNonNull("lastBarTime")) {
                // has() alone is not enough: an explicit JSON null passes it, asText() then yields the
                // string "null", and Instant.parse throws. That exception escapes to the catch below and
                // aborts the WHOLE restore, so activeTrades, pendingStops and the strategy state are all
                // lost and a process holding a position comes back believing it is flat (2026-09-30 review).
                try {
                    lastBarTime = Instant.parse(root.get("lastBarTime").asText());
                } catch (Exception e) {
                    log.warn("⚠️ Saved state has an unparseable lastBarTime ('{}') — keeping the warm-up "
                        + "cursor instead of aborting the whole state restore.",
                        String.valueOf(root.get("lastBarTime")));
                }
            }

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
                    t.reconciliationStatus = tn.has("reconciliationStatus") ? tn.get("reconciliationStatus").asText() : "CONFIRMED";
                    activeTrades.add(t);

                    // Resume reconciliation task if it was unconfirmed when JVM shut down
                    if ("UNCONFIRMED_RECONCILIATION".equals(t.reconciliationStatus)) {
                        log.info("♻ Resuming unconfirmed reconciliation task for trade ID: {}", t.tradeId);
                        AsyncReconciliationQueue.GLOBAL.submit(this, t.tradeId);
                    }
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

            if (root.has("unreconciledTrades")) {
                for (JsonNode un : root.get("unreconciledTrades")) {
                    unreconciledTrades.add(new UnreconciledTrade(
                        un.get("tradeId").asText(),
                        un.get("symbol").asText(),
                        un.has("reason") ? un.get("reason").asText() : "",
                        Instant.parse(un.get("timestamp").asText())
                    ));
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
                String.format("%.2f", ledger.total()));

        } catch (Exception e) {
            log.warn("Failed to resume state (corrupted?): {}", e.getMessage());
        }
    }

    public static Map<String, LiveStrategyRunner> getActiveRunners() { return ACTIVE_RUNNERS; }
    public long getSignalCount() { return signalCount; }
    public long getBarCount() { return barCount; }
    public Instant getLastHeartbeatTime() { return lastHeartbeatTime; }
    public String getStrategyShortName() { return strategyShortName; }

    public String getLivenessStatus() {
        // A runner that stopped on its own failure must not report ALIVE merely because the PROCESS is
        // still running: the per-runner flag is the truth for this runner (2026-09-30 agy review).
        if (!alive.get()) {
            return "FAILED";
        }
        if (!RUNNING.get()) {
            return "STOPPED";
        }
        Instant now = TimeConventions.now();
        long elapsedSec = Duration.between(lastHeartbeatTime, now).toSeconds();
        if (elapsedSec > intervalSec * 3) {
            return "STUCK";
        }
        return "ALIVE";
    }

    List<ActiveTrade> getActiveTrades() { return activeTrades; }
    List<PendingStop> getPendingStops() { return pendingStops; }
}
