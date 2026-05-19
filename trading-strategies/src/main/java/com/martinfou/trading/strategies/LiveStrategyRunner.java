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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LiveStrategyRunner — Execute strategies on OANDA practice/demo.
 *
 * Usage:
 *   LiveStrategyRunner <apiKey> <accountId> <strategyName> [granularity] [intervalSec]
 *
 * Examples:
 *   LiveStrategyRunner KEY ACCT 2_31_177
 *   LiveStrategyRunner KEY ACCT 2_31_177 H1 60
 *   LiveStrategyRunner KEY ACCT all          → run ALL sqimported strategies
 *
 * Features:
 *   - State persistence to /tmp/live-strategy-state.json (auto-save every minute)
 *   - Graceful shutdown (SIGTERM/SIGINT saves state)
 *   - Crash recovery: resumes open positions from saved state
 *   - SLF4J logging with trade entry/exit P&L
 */
public class LiveStrategyRunner {

    private static final Logger log = LoggerFactory.getLogger(LiveStrategyRunner.class);
    private static final Path STATE_FILE = Paths.get("/tmp/live-strategy-state.json");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AtomicBoolean RUNNING = new AtomicBoolean(true);

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
    }

    // ========================================================================
    // Main
    // ========================================================================

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: LiveStrategyRunner <apiKey> <accountId> <strategyName> [granularity] [intervalSec]");
            System.out.println("  strategyName: '2_31_177', '2_14_147', '2_15_195', '2_31_175', '2_32_120', '2_36_190', '2_38_112', or 'all'");
            System.out.println("  granularity:  H1 (default), H4, D");
            System.out.println("  intervalSec:  60 (default) — loop interval in seconds");
            listStrategies();
            return;
        }

        String apiKey = args[0];
        String accountId = args[1];
        String strategyName = args[2];
        String granularity = args.length > 3 ? args[3] : "H1";
        int intervalSec = args.length > 4 ? Integer.parseInt(args[4]) : 60;

        // Resolve strategy
        List<Strategy> strategies = resolveStrategies(strategyName);
        if (strategies.isEmpty()) {
            System.err.println("ERROR: No strategy found for '" + strategyName + "'");
            listStrategies();
            System.exit(1);
        }

        log.info("╔════════════════════════════════════════════════════╗");
        log.info("║     🚀 LiveStrategyRunner — OANDA Practice        ║");
        log.info("╠════════════════════════════════════════════════════╣");
        log.info("║ Account: {}      ", accountId);
        log.info("║ API:     api-fxpractice.oanda.com                  ");
        log.info("║ Strategy: {} strategies                        ", strategies.size());
        log.info("║ Granularity: {}  Interval: {}s                  ", granularity, intervalSec);
        log.info("╚════════════════════════════════════════════════════╝");

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            RUNNING.set(false);
            log.info("🛑 Shutdown signal received — saving state...");
        }));

        // Run each strategy in sequence (currently single-threaded)
        for (Strategy s : strategies) {
            if (!RUNNING.get()) break;
            String shortName = strategyName.equals("all")
                ? s.name().replaceAll("^.*[._](\\d+[._]\\d+[._]\\d+).*", "$1")
                : strategyName;
            var runner = new LiveStrategyRunner(apiKey, accountId, s, shortName, granularity, intervalSec);
            runner.run();
        }

        log.info("✅ LiveStrategyRunner finished.");
        System.exit(0);
    }

    // ========================================================================
    // Strategy Resolution
    // ========================================================================

    private static void listStrategies() {
        System.out.println("\nAvailable sqimported strategies:");
        for (var entry : getStrategyMap().entrySet()) {
            System.out.println("  " + entry.getKey() + " → " + entry.getValue().getSimpleName());
        }
        System.out.println("  all → run all strategies sequentially\n");
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
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Strategy class not found", e);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static List<Strategy> resolveStrategies(String name) throws Exception {
        List<Strategy> result = new ArrayList<>();
        if (name.equals("all")) {
            for (var clazz : getStrategyMap().values()) {
                result.add(clazz.getDeclaredConstructor().newInstance());
            }
        } else {
            Map<String, Class<? extends Strategy>> map = getStrategyMap();
            Class<? extends Strategy> clazz = map.get(name);
            if (clazz == null) {
                // Try direct class name as fallback
                try {
                    clazz = (Class<? extends Strategy>) Class.forName(name);
                } catch (ClassNotFoundException e) {
                    return result;
                }
            }
            result.add(clazz.getDeclaredConstructor().newInstance());
        }
        return result;
    }

    // ========================================================================
    // Main Loop
    // ========================================================================

    private void run() throws Exception {
        log.info("━━━ Starting strategy: {} ━━━", strategy.name());

        // Resume from saved state if available
        resumeState();

        // Get initial candles to warm up the strategy
        String oandaSymbol = toOandaSymbol(strategy);
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
                log.error("Loop error: {}", e.getMessage(), e);
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
            double units = order.side() == Order.Side.BUY
                ? Math.abs(order.quantity())
                : -Math.abs(order.quantity());
            String unitsStr = String.valueOf((int) units);
            String priceStr = String.format("%.5f", order.price());

            var result = executor.placeStopOrder(oandaSymbol, unitsStr, priceStr);
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
            // Units: positive = BUY, negative = SELL (in base units)
            double units = order.side() == Order.Side.BUY
                ? Math.abs(order.quantity())
                : -Math.abs(order.quantity());
            String unitsStr = String.valueOf((int) units);

            var result = executor.placeMarketOrder(oandaSymbol, unitsStr);
            totalEntries++;

            log.info("═══════ ENTRY {} {} {} @ {} ═══════",
                oandaSymbol, order.side(),
                String.format("%.2f", units / 100000.0) + " lots",
                result.fillPrice());

            // Attach SL/TP
            double fillPrice = Double.parseDouble(result.fillPrice());
            String slStr = order.stopLoss() > 0
                ? String.format("%.5f", order.stopLoss()) : null;
            String tpStr = order.takeProfit() > 0
                ? String.format("%.5f", order.takeProfit()) : null;

            if (slStr != null && result.tradeId() != null && !result.tradeId().equals("N/A")) {
                String slResult = executor.addStopLoss(result.tradeId(), slStr);
                if (slResult.equals("OK")) {
                    log.info("   SL set @ {}", slStr);
                } else {
                    log.warn("   SL failed: {}", slResult);
                }
            }

            if (tpStr != null && result.tradeId() != null && !result.tradeId().equals("N/A")) {
                String tpResult = executor.addTakeProfit(result.tradeId(), tpStr);
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
                oandaSymbol, order.side(), String.format("%.5f", execPrice), e.getMessage());
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
                // Check TP/SL
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
                    String.format("%.5f", exitPrice),
                    pnl >= 0 ? "+" : "",
                    String.format("%.2f", pnl / trade.quantity * 100000));
                it.remove();
            } else {
                log.info("   {} {} | Entry: {} | Current: {} | PnL: {}{} | SL: {} TP: {}",
                    trade.symbol, trade.side,
                    String.format("%.5f", trade.entryPrice),
                    String.format("%.5f", mid),
                    pnl >= 0 ? "+" : "",
                    String.format("%.2f", pnl),
                    trade.stopLoss > 0 ? String.format("%.5f", trade.stopLoss) : "—",
                    trade.takeProfit > 0 ? String.format("%.5f", trade.takeProfit) : "—");
            }
        }
    }

    // ========================================================================
    // Instrument Resolution
    // ========================================================================

    private static String toOandaSymbol(Strategy s) {
        String name = s.name().toUpperCase();
        if (name.contains("GBPJPY") || name.contains("GBP_JPY")) return "GBP_JPY";
        if (name.contains("EURUSD") || name.contains("EUR_USD")) return "EUR_USD";
        if (name.contains("GBPUSD") || name.contains("GBP_USD")) return "GBP_USD";
        if (name.contains("USDCAD") || name.contains("USD_CAD")) return "USD_CAD";
        if (name.contains("USDJPY") || name.contains("USD_JPY")) return "USD_JPY";
        if (name.contains("AUDUSD") || name.contains("AUD_USD")) return "AUD_USD";
        if (name.contains("EURJPY") || name.contains("EUR_JPY")) return "EUR_JPY";
        // All sqimported strategies use SYMBOL = "GBP_JPY"
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
            root.put("granularity", granularity);
            root.put("intervalSec", intervalSec);
            root.put("totalEntries", totalEntries);
            root.put("totalExits", totalExits);
            root.put("totalPnl", totalPnl);
            root.put("savedAt", TimeConventions.now().toString());
            if (lastBarTime != null) root.put("lastBarTime", lastBarTime.toString());

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

            MAPPER.writerWithDefaultPrettyPrinter().writeValue(STATE_FILE.toFile(), root);
            lastStateSave = TimeConventions.now();
        } catch (Exception e) {
            log.warn("Failed to save state: {}", e.getMessage());
        }
    }

    private void resumeState() {
        if (!Files.exists(STATE_FILE)) {
            log.info("No saved state file found — starting fresh.");
            return;
        }

        try {
            String content = Files.readString(STATE_FILE);
            JsonNode root = MAPPER.readTree(content);

            // Verify this is the same strategy
            String savedStrategy = root.has("strategy") ? root.get("strategy").asText() : "";
            if (!savedStrategy.equals(strategyShortName)) {
                log.info("Saved strategy '{}' != current '{}' — ignoring saved state.",
                    savedStrategy, strategyShortName);
                return;
            }

            // Restore counters
            if (root.has("totalEntries")) totalEntries = root.get("totalEntries").asInt();
            if (root.has("totalExits")) totalExits = root.get("totalExits").asInt();
            if (root.has("totalPnl")) totalPnl = root.get("totalPnl").asDouble();
            if (root.has("lastBarTime")) lastBarTime = Instant.parse(root.get("lastBarTime").asText());

            // Restore active trades
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

            // Restore pending stops
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

            log.info("♻ Resumed state: {} active trades, {} pending stops, {} entries, ${} P&L",
                activeTrades.size(), pendingStops.size(), totalEntries,
                String.format("%.2f", totalPnl));

        } catch (Exception e) {
            log.warn("Failed to resume state (corrupted?): {}", e.getMessage());
        }
    }
}
