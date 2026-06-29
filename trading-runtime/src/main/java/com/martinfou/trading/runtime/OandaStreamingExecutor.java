package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventType;
import com.martinfou.trading.broker.Broker;
import com.martinfou.trading.broker.BrokerEvent;
import com.martinfou.trading.broker.OrderSubmitResult;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.BarAggregator;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Position;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.data.oanda.OandaRestClient;
import com.martinfou.trading.data.oanda.OandaStreamingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared execution engine for OANDA paper (practice) and live trading runs using live price streams.
 */
public final class OandaStreamingExecutor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OandaStreamingExecutor.class);

    private final String runId;
    private final RunRecord record;
    private final RunConfigSnapshot config;
    private final Strategy strategy;
    private final Broker broker;
    private final EventStore eventStore;
    private final KillSwitchRegistry killSwitchRegistry;
    private final OandaStreamingClient streamingClient;
    private final RunRiskContext riskContext;
    private final RiskEngine riskEngine;
    private final OandaRestClient restClient;
    private final java.util.function.Supplier<List<String>> activeSymbolsSupplier;

    private final AtomicBoolean active = new AtomicBoolean(false);
    private final BarAggregator aggregator;
    private final OandaStreamingClient.OandaTickListener tickListener;
    private final RunMode runMode;

    private double peakEquity;
    private int filledCount = 0;
    private int submittedCount = 0;
    private int rejectedCount = 0;
    
    private final CountDownLatch stopLatch = new CountDownLatch(1);
    /** Non-null when the run was stopped due to an internal error (set before stop() is called). */
    private final java.util.concurrent.atomic.AtomicReference<String> pendingFailure = new java.util.concurrent.atomic.AtomicReference<>();

    // Advanced Risk Fields
    private double dailyStartEquity;
    private double weeklyStartEquity;
    private Instant lastDailyRollover;
    private Instant lastWeeklyRollover;

    private boolean suspendedDaily = false;
    private boolean suspendedWeekly = false;

    // Cooldown logic fields
    private final List<Instant> recentLosses = new ArrayList<>();
    private Instant cooldownUntil = null;
    private volatile double lastMidPrice = 0.0;
    private volatile double lastBid = 0.0;
    private volatile double lastAsk = 0.0;
    private Instant lastReconciliationTime = Instant.EPOCH;
    private volatile double lastPriceVal = 0.0;
    private volatile Instant lastPriceValChangeTime = Instant.now();
    private volatile boolean stalePriceWarningFlag = false;
    private volatile Instant lastFillTime = null;

    public OandaStreamingExecutor(
        String runId,
        RunRecord record,
        RunConfigSnapshot config,
        Strategy strategy,
        Broker broker,
        OandaRestClient restClient,
        EventStore eventStore,
        KillSwitchRegistry killSwitchRegistry,
        OandaStreamingClient streamingClient,
        RunRiskContext riskContext
    ) {
        this(runId, record, config, strategy, broker, restClient, eventStore, killSwitchRegistry, streamingClient, riskContext, java.util.Collections::emptyList);
    }

    public OandaStreamingExecutor(
        String runId,
        RunRecord record,
        RunConfigSnapshot config,
        Strategy strategy,
        Broker broker,
        OandaRestClient restClient,
        EventStore eventStore,
        KillSwitchRegistry killSwitchRegistry,
        OandaStreamingClient streamingClient,
        RunRiskContext riskContext,
        java.util.function.Supplier<List<String>> activeSymbolsSupplier
    ) {
        this.runId = runId;
        this.record = record;
        this.config = config;
        this.strategy = strategy;
        this.broker = broker;
        this.restClient = restClient;
        this.eventStore = eventStore;
        this.killSwitchRegistry = killSwitchRegistry;
        this.streamingClient = streamingClient;
        this.riskContext = riskContext;

        double customMaxDd = config.maxDailyDrawdownPct() != null ? config.maxDailyDrawdownPct() : 5.0;
        double customDll = config.dailyLossLimitPct() != null ? config.dailyLossLimitPct() : 5.0;
        double customWll = config.weeklyLossLimitPct() != null ? config.weeklyLossLimitPct() : 10.0;
        this.riskEngine = new RiskEngine(new RiskLimits(1_000_000, 2_000_000, customMaxDd, customDll, customWll));

        this.runMode = RunMode.valueOf(config.mode().toUpperCase());

        String tf = config.strategyTimeframe() != null ? config.strategyTimeframe() : "M1";
        this.aggregator = new BarAggregator(config.symbol(), tf);
        this.peakEquity = config.resolvedCapital();

        this.tickListener = new OandaStreamingClient.OandaTickListener() {
            @Override
            public void onTick(String instrument, Instant timestamp, double bid, double ask) {
                if (instrument.equalsIgnoreCase(config.symbol())) {
                    org.slf4j.MDC.put("runId", runId);
                    org.slf4j.MDC.put("strategyId", config.strategyId());
                    org.slf4j.MDC.put("stale", String.valueOf(stalePriceWarningFlag));
                    try {
                        processTick(timestamp, bid, ask);
                    } finally {
                        org.slf4j.MDC.remove("runId");
                        org.slf4j.MDC.remove("strategyId");
                        org.slf4j.MDC.remove("stale");
                    }
                }
            }

            @Override
            public void onConnectionStateChange(boolean active) {
                org.slf4j.MDC.put("runId", runId);
                org.slf4j.MDC.put("strategyId", config.strategyId());
                org.slf4j.MDC.put("stale", String.valueOf(stalePriceWarningFlag));
                try {
                    log.info("Pricing stream connection state for run {}: active={}", runId, active);
                } finally {
                    org.slf4j.MDC.remove("runId");
                    org.slf4j.MDC.remove("strategyId");
                    org.slf4j.MDC.remove("stale");
                }
            }
        };
        this.activeSymbolsSupplier = activeSymbolsSupplier != null ? activeSymbolsSupplier : java.util.Collections::emptyList;
    }

    public void start() {
        if (active.getAndSet(true)) return;

        emitStarted();

        // Initialize rollovers starting equity
        double initialEquity = broker.getAccountState().equity();
        this.dailyStartEquity = initialEquity;
        this.weeklyStartEquity = initialEquity;
        this.peakEquity = initialEquity;
        this.lastDailyRollover = Instant.now();
        this.lastWeeklyRollover = Instant.now();

        // 1. Warm-up Phase: load history bars and replay them
        bootstrapHistory();

        // 2. Register to live prices
        broker.connect();
        broker.addEventListener(event -> {
            org.slf4j.MDC.put("runId", runId);
            org.slf4j.MDC.put("strategyId", config.strategyId());
            org.slf4j.MDC.put("stale", String.valueOf(stalePriceWarningFlag));
            try {
                persistBrokerEvent(event);
                if (event.type() == com.martinfou.trading.broker.BrokerEventType.FILL) {
                    lastFillTime = event.timestamp();
                    // If it is a sell or close that results in a loss, we could track it.
                    // For simplicity, let's track execution events in the fill callback if PnL is negative.
                    // Since BrokerEvent does not contain PnL, we can query recent transactions or calculate it from orders.
                    // Let's check if the trade was a loss by calculating the difference or re-reading positions/transaction history.
                    // Let's implement consecutive loss tracking via event store scans.
                    checkConsecutiveLosses(event.timestamp());
                }
            } finally {
                org.slf4j.MDC.remove("runId");
                org.slf4j.MDC.remove("strategyId");
                org.slf4j.MDC.remove("stale");
            }
        });
        streamingClient.addListener(tickListener);
        streamingClient.subscribe(config.symbol());
        streamingClient.start();

        log.info("OandaStreamingExecutor started for strategy {} on symbol {} in mode {}", config.strategyId(), config.symbol(), runMode);
    }

    public void stop() {
        if (active.getAndSet(false)) {
            doStop();
        } else {
            try { stopLatch.await(); } catch (InterruptedException e) {}
        }
    }

    private void doStop() {
        try {
            streamingClient.removeListener(tickListener);
            streamingClient.unsubscribe(config.symbol());
            broker.disconnect();

            emitEnded();
            log.info("OandaStreamingExecutor stopped for strategy {} on symbol {} in mode {}", config.strategyId(), config.symbol(), runMode);
        } finally {
            stopLatch.countDown();
        }
    }

    public boolean isActive() {
        return active.get();
    }

    public void reconnectBroker() {
        log.info("Triggering broker reconnect for run {}", runId);
        broker.reconnect();
        reconcilePositions();
    }

    /**
     * Returns the error message that caused an error-driven stop, or {@code null} if the stop
     * was operator-initiated. Set before {@link #stop()} is called; safe to read after stop.
     */
    public String getPendingFailure() {
        return pendingFailure.get();
    }

    public void liquidateAndStop() {
        triggerActiveLiquidation();
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isSuspendedDaily() {
        return suspendedDaily;
    }

    public boolean isSuspendedWeekly() {
        return suspendedWeekly;
    }

    public Instant getCooldownUntil() {
        return cooldownUntil;
    }

    public double getLastMidPrice() {
        return lastMidPrice;
    }

    public int getFilledCount() {
        return filledCount;
    }

    public Broker getBroker() {
        return broker;
    }

    public double getLastBid() {
        return lastBid;
    }

    public double getLastAsk() {
        return lastAsk;
    }

    private void bootstrapHistory() {
        try {
            log.info("Bootstrapping indicator warm-up with last 500 history bars...");
            // Load history bars (e.g. 500 bars)
            List<Bar> historyBars = RunManager.loadBars(config, null, null);
            strategy.reset();
            int limit = Math.min(historyBars.size(), 500);
            for (int i = historyBars.size() - limit; i < historyBars.size(); i++) {
                strategy.onBar(historyBars.get(i));
            }
            // Discard any pending orders generated during indicator warm-up
            strategy.getPendingOrders();
            if (!historyBars.isEmpty()) {
                lastMidPrice = historyBars.getLast().close();
            }
            
            // Restore active position state if any exists in event log
            List<RunEvent> allEvents = eventStore.replayAll(runId);
            Map<String, JournalPositions.Snapshot> journalPositions = JournalPositions.fromFills(allEvents);
            Order.Side activeSide = null;
            double activeQty = 0.0;
            for (var snapshot : journalPositions.values()) {
                if (snapshot == null || snapshot.symbol() == null) {
                    continue;
                }
                if (snapshot.symbol().equalsIgnoreCase(config.symbol())
                    || snapshot.symbol().replace("/", "_").replace("-", "_").equalsIgnoreCase(config.symbol().replace("/", "_").replace("-", "_"))) {
                    activeSide = snapshot.side();
                    activeQty = snapshot.quantity();
                    break;
                }
            }
            double sl = 0.0;
            double tp = 0.0;
            if (activeSide != null && activeQty > 0.0) {
                for (int i = allEvents.size() - 1; i >= 0; i--) {
                    RunEvent e = allEvents.get(i);
                    if (e.type() == RunEventType.FILL || e.type() == RunEventType.ORDER_SUBMITTED) {
                        Map<String, Object> payload = e.payload();
                        if (payload != null && activeSide.name().equalsIgnoreCase(String.valueOf(payload.get("side"))) 
                            && config.symbol().equalsIgnoreCase(String.valueOf(payload.get("symbol")))) {
                            if (payload.containsKey("stopLoss")) {
                                sl = ((Number) payload.get("stopLoss")).doubleValue();
                            }
                            if (payload.containsKey("takeProfit")) {
                                tp = ((Number) payload.get("takeProfit")).doubleValue();
                            }
                            break;
                        }
                    }
                }
                log.info("Restoring strategy position tracking state on restart: {} {} (SL: {}, TP: {})", activeSide, activeQty, sl, tp);
                strategy.syncPosition(activeSide, activeQty, sl, tp);
            } else {
                strategy.syncPosition(null, 0.0, 0.0, 0.0);
            }
            
            log.info("Warm-up complete. Replayed {} bars.", limit);
        } catch (IOException e) {
            log.warn("Failed to load history bootstrap bars: {}. Indicators starting dry.", e.getMessage());
        }
    }

    private void processTick(Instant timestamp, double bid, double ask) {
        try {
            if (isWeekend(timestamp)) {
                return;
            }

            if (record != null) {
                record.noteEventAt(timestamp);
            }

            lastBid = bid;
            lastAsk = ask;
            lastMidPrice = (bid + ask) / 2.0;

            if (lastPriceVal == 0.0) {
                lastPriceVal = lastMidPrice;
                lastPriceValChangeTime = timestamp;
            } else if (lastMidPrice != lastPriceVal) {
                lastPriceVal = lastMidPrice;
                lastPriceValChangeTime = timestamp;
                if (stalePriceWarningFlag) {
                    stalePriceWarningFlag = false;
                }
            } else if (java.time.Duration.between(lastPriceValChangeTime, timestamp).toMinutes() >= 5) {
                if (!stalePriceWarningFlag) {
                    stalePriceWarningFlag = true;
                    log.warn("Price stream is STALE for {}. No price change for 5 minutes.", config.symbol());
                    emitStalePriceWarningEvent(timestamp);
                }
            }

            // 1. Perform rollover checks
            checkRollovers(timestamp);

            // 2. Perform risk circuit checks (DLL, WLL, Drawdown)
            checkRiskCircuitBreakers(timestamp);

            // 3. Perform position reconciliation (every 60 seconds)
            if (lastReconciliationTime == null || java.time.Duration.between(lastReconciliationTime, timestamp).toSeconds() >= 60) {
                reconcilePositions(timestamp);
                lastReconciliationTime = timestamp;
            }

            double mid = (bid + ask) / 2.0;
            Bar tickBar = new Bar(config.symbol(), timestamp, mid, mid, mid, mid, 1);

            if (aggregator.isNewPeriod(tickBar)) {
                aggregator.completePeriod();
                Bar completed = aggregator.getLastCompletedBar();
                if (completed != null) {
                    int runningTradeCount = 0;
                    double openPnL = 0.0;
                    try {
                        for (var pos : broker.getPositions()) {
                            if (pos.symbol().equalsIgnoreCase(config.symbol())
                                || pos.symbol().replace("/", "_").replace("-", "_").equalsIgnoreCase(config.symbol().replace("/", "_").replace("-", "_"))) {
                                runningTradeCount = pos.quantity() > 0 ? 1 : 0;
                                openPnL = pos.currentPnl(lastMidPrice);
                            }
                        }
                    } catch (Exception ex) {
                        log.warn("Failed to retrieve positions/openPnL for heartbeat: {}", ex.getMessage());
                    }
                    HeartbeatEvents.emitBarHeartbeat(runId, config, runMode, eventStore, completed, 0, runningTradeCount, lastFillTime, openPnL);
                    if (!suspendedDaily && !suspendedWeekly && (cooldownUntil == null || timestamp.isAfter(cooldownUntil))) {
                        strategy.onBar(completed);
                        executePendingOrders(completed);
                    } else {
                        // Discard pending orders
                        strategy.getPendingOrders();
                    }
                }
            }
            aggregator.add(tickBar);

            Bar currentBar = aggregator.getInProgressBar();
            if (currentBar != null) {
                RunEvent barEvent = RunEvent.bar(runId, config.strategyId(), config.symbol(), runMode, currentBar, timestamp, bid, ask);
                eventStore.publishEphemeral(runId, barEvent);
            }
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.error("Run {} failed during tick processing", runId, e);
            RunEvent errorEvent = RunEvent.error(runId, config.strategyId(), config.symbol(), runMode, errorMsg);
            eventStore.append(runId, errorEvent);
            // Signal the failure to RunManager via pendingFailure — RunManager owns all status transitions.
            pendingFailure.compareAndSet(null, errorMsg);
            Thread.ofVirtual().start(this::stop);
        }
    }

    private void checkRollovers(Instant now) {
        // Daily Rollover: 5:00 PM EST is 22:00 UTC
        ZonedDateTime zdtNow = now.atZone(ZoneOffset.UTC);
        ZonedDateTime zdtLastDaily = lastDailyRollover.atZone(ZoneOffset.UTC);
        if (zdtNow.toLocalDate().isAfter(zdtLastDaily.toLocalDate()) || (zdtNow.getHour() >= 22 && zdtLastDaily.getHour() < 22)) {
            double currentEquity = broker.getAccountState().equity();
            dailyStartEquity = currentEquity;
            lastDailyRollover = now;
            if (suspendedDaily) {
                suspendedDaily = false;
                log.info("Daily loss limit reset at rollover. Resuming strategy execution.");
                emitOperatorAction("DAILY_RESET", "Daily loss limit reset at 5:00 PM EST.");
            }
        }

        // Weekly Rollover: Sunday 22:00 UTC
        ZonedDateTime zdtLastWeekly = lastWeeklyRollover.atZone(ZoneOffset.UTC);
        boolean isNewWeek = false;
        if (zdtNow.getDayOfWeek() == DayOfWeek.SUNDAY && zdtNow.getHour() >= 22) {
            if (zdtLastWeekly.getDayOfWeek() != DayOfWeek.SUNDAY || zdtLastWeekly.getHour() < 22) {
                isNewWeek = true;
            }
        } else if (zdtNow.getDayOfWeek().getValue() < zdtLastWeekly.getDayOfWeek().getValue()) {
            isNewWeek = true;
        }

        if (isNewWeek) {
            double currentEquity = broker.getAccountState().equity();
            weeklyStartEquity = currentEquity;
            lastWeeklyRollover = now;
            if (suspendedWeekly) {
                suspendedWeekly = false;
                log.info("Weekly loss limit reset at rollover. Resuming strategy execution.");
                emitOperatorAction("WEEKLY_RESET", "Weekly loss limit reset.");
            }
        }
    }

    private void checkRiskCircuitBreakers(Instant now) {
        // Fetch account equity — treat transient network errors as non-fatal for a single tick.
        // A GOAWAY / connection reset from the OANDA REST API must not crash the streaming run;
        // we simply skip the risk check for this tick and rely on the next tick to retry.
        // We only catch IllegalStateException — the unchecked wrapper the broker layer uses for
        // all network errors (IOException → IllegalStateException("Failed to fetch OANDA account summary")).
        // Programming errors (NPE, ClassCastException, etc.) are intentionally left to propagate so
        // they surface in processTick's catch and terminate the run as FAILED.
        double currentEquity;
        try {
            currentEquity = broker.getAccountState().equity();
        } catch (IllegalStateException e) {
            log.warn("Run {} — transient error fetching account equity; skipping risk check for this tick: {}",
                runId, e.getMessage());
            return;
        }

        if (currentEquity > peakEquity) {
            peakEquity = currentEquity;
        }

        // Drawdown Check
        double drawdownPct = 0.0;
        if (peakEquity > 0) {
            drawdownPct = (peakEquity - currentEquity) / peakEquity * 100.0;
        }
        if (drawdownPct >= 10.0) {
            log.warn("AUTOMATED KILL SWITCH BREACHED: Drawdown is {}% (Peak: {}, Current: {}). Liquidating all positions.",
                drawdownPct, peakEquity, currentEquity);
            triggerActiveLiquidation();
            return;
        }

        // Daily Loss Limit Check
        if (!suspendedDaily) {
            RiskCheckResult dllResult = riskEngine.checkDailyLossLimit(dailyStartEquity, currentEquity);
            if (!dllResult.passed()) {
                suspendedDaily = true;
                log.warn("DAILY LOSS LIMIT BREACHED: Liquidating positions and locking strategy.");
                triggerBreachLiquidation("DAILY_LOSS_LIMIT", dllResult.message());
            }
        }

        // Weekly Loss Limit Check
        if (!suspendedWeekly) {
            RiskCheckResult wllResult = riskEngine.checkWeeklyLossLimit(weeklyStartEquity, currentEquity);
            if (!wllResult.passed()) {
                suspendedWeekly = true;
                log.warn("WEEKLY LOSS LIMIT BREACHED: Liquidating positions and locking strategy.");
                triggerBreachLiquidation("WEEKLY_LOSS_LIMIT", wllResult.message());
            }
        }
    }

    private void reconcilePositions(Instant timestamp) {
        ExecutionLabel label = config.resolvedExecutionLabel();
        if (label == null || !label.isBrokerBacked()) {
            return;
        }
        try {
            List<com.martinfou.trading.core.Position> brokerPositions = broker.getPositions();
            
            List<RunEvent> allEvents = eventStore.replayAll(runId);
            var runOrderIds = new java.util.HashSet<String>();
            for (var e : allEvents) {
                if ((e.type() == RunEventType.FILL || e.type() == RunEventType.ORDER_SUBMITTED) && e.payload() != null && e.payload().containsKey("orderId")) {
                    runOrderIds.add(String.valueOf(e.payload().get("orderId")));
                }
            }
            
            var journalPositions = JournalPositions.fromFills(allEvents);
            
            boolean brokerHasPosition = false;
            boolean canDisambiguate = true;
            for (var pos : brokerPositions) {
                boolean matchesSymbol = pos.symbol().equalsIgnoreCase(config.symbol()) 
                    || pos.symbol().replace("/", "_").replace("-", "_").equalsIgnoreCase(config.symbol().replace("/", "_").replace("-", "_"));
                if (matchesSymbol) {
                    if (pos.clientTag() != null && !pos.clientTag().isBlank()) {
                        if (runOrderIds.contains(pos.clientTag())) {
                            brokerHasPosition = true;
                            break;
                        }
                    } else {
                        int activeRuns = 0;
                        if (activeSymbolsSupplier != null) {
                            List<String> activeSymbols = activeSymbolsSupplier.get();
                            if (activeSymbols == null || activeSymbols.isEmpty()) {
                                activeRuns = 1;
                            } else {
                                for (String rs : activeSymbols) {
                                    if (pos.symbol().equalsIgnoreCase(rs) || pos.symbol().replace("/", "_").replace("-", "_").equalsIgnoreCase(rs.replace("/", "_").replace("-", "_"))) {
                                        activeRuns++;
                                    }
                                }
                            }
                        } else {
                            activeRuns = 1;
                        }
                        if (activeRuns == 1) {
                            brokerHasPosition = true;
                            break;
                        } else if (activeRuns > 1) {
                            canDisambiguate = false;
                        }
                    }
                }
            }
            
            if (!brokerHasPosition && canDisambiguate) {
                for (var jp : journalPositions.values()) {
                    boolean matchesSymbol = jp.symbol().equalsIgnoreCase(config.symbol()) 
                        || jp.symbol().replace("/", "_").replace("-", "_").equalsIgnoreCase(jp.symbol().replace("/", "_").replace("-", "_"));
                    if (matchesSymbol && jp.quantity() > 0.0) {
                        Order.Side oppositeSide = jp.side() == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
                        
                        Map<String, Object> payload = new java.util.LinkedHashMap<>();
                        payload.put("type", "FILL");
                        payload.put("timestamp", timestamp.toString());
                        payload.put("orderId", "reconciliation-" + System.currentTimeMillis());
                        payload.put("symbol", config.symbol());
                        payload.put("side", oppositeSide.name());
                        payload.put("quantity", jp.quantity());
                        payload.put("price", lastMidPrice);
                        payload.put("reconciliation", true);
                        payload.put("reason", "BROKER_POSITION_CLOSED");
                        
                        RunEvent correctiveEvent = new RunEvent(
                            RunEvent.SCHEMA_VERSION,
                            RunEventType.FILL,
                            timestamp,
                            runId,
                            config.strategyId(),
                            config.symbol(),
                            runMode.name(),
                            Map.copyOf(payload)
                        );
                        
                        eventStore.append(runId, correctiveEvent);
                        log.warn("Reconciliation closed open local position of {} {} for run {} since broker reports 0 open positions. Appended corrective FILL.", 
                            jp.quantity(), jp.side(), runId);
                    }
                }
            }
        } catch (IllegalStateException e) {
            log.warn("Run {} — transient error fetching positions; skipping reconciliation for this tick: {}",
                runId, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to run position reconciliation for run {}", runId, e);
        }
    }

    private void triggerBreachLiquidation(String actionType, String message) {
        // Cancel all pending orders & liquidate active positions
        java.util.Set<String> runOrderIds = new java.util.HashSet<>();
        try {
            if (eventStore != null) {
                for (var e : eventStore.replayAll(runId)) {
                    if ((e.type() == RunEventType.FILL || e.type() == RunEventType.ORDER_SUBMITTED) && e.payload() != null && e.payload().containsKey("orderId")) {
                        runOrderIds.add(String.valueOf(e.payload().get("orderId")));
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to replay fills for liquidation filtering in run {}", runId, e);
        }

        for (Position pos : broker.getPositions()) {
            boolean matchesSymbol = pos.symbol().equalsIgnoreCase(config.symbol())
                || pos.symbol().replace("/", "_").replace("-", "_").equalsIgnoreCase(config.symbol().replace("/", "_").replace("-", "_"));
            if (matchesSymbol) {
                boolean match = false;
                if (pos.clientTag() != null && !pos.clientTag().isBlank()) {
                    if (runOrderIds.contains(pos.clientTag())) {
                        match = true;
                    }
                } else {
                    int activeRuns = 0;
                    if (activeSymbolsSupplier != null) {
                        List<String> activeSymbols = activeSymbolsSupplier.get();
                        if (activeSymbols == null || activeSymbols.isEmpty()) {
                            activeRuns = 1;
                        } else {
                            for (String rs : activeSymbols) {
                                if (pos.symbol().equalsIgnoreCase(rs) || pos.symbol().replace("/", "_").replace("-", "_").equalsIgnoreCase(rs.replace("/", "_").replace("-", "_"))) {
                                    activeRuns++;
                                }
                            }
                        }
                    } else {
                        activeRuns = 1;
                    }
                    if (activeRuns == 1) {
                        match = true;
                    }
                }
                if (match) {
                    Order marketClose = new Order(
                        pos.symbol(),
                        pos.side() == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY,
                        Order.Type.MARKET,
                        pos.quantity(),
                        0.0
                    ).closeOnly();
                    log.info("Submitting emergency close order due to breach: {}", marketClose);
                    broker.submitOrder(marketClose);
                }
            }
        }
        emitOperatorAction(actionType, message);
    }

    private void checkConsecutiveLosses(Instant fillTime) {
        // Query recent transactions from OANDA or reconstruct from filled trades in eventStore.
        // For local simplicity, we query transaction history through OandaRestClient if available.
        if (restClient == null) return;
        try {
            // Scan transaction logs or check last fills.
            // Let's calculate from events or transaction history.
            // Let's assume we can fetch transactions from HttpOandaRestClient or reconstruct.
            // A professional implementation inspects the transaction events.
            // Let's scan our eventStore for recent FILL events.
            List<RunEvent> fills = eventStore.replayAll(runId).stream()
                .filter(e -> e.type() == RunEventType.FILL)
                .toList();

            // Find last trades P&L. If 3 consecutive losses happened within 2 hours:
            // Since fills represent buy/sell transactions, we can reconstruct the trades
            // and count consecutive losses.
            // Reconstructing trades:
            List<Double> tradePnLs = new ArrayList<>();
            List<Instant> tradeTimes = new ArrayList<>();
            reconstructTradePnLs(fills, tradePnLs, tradeTimes);

            if (tradePnLs.size() >= 3) {
                int n = tradePnLs.size();
                double p1 = tradePnLs.get(n - 1);
                double p2 = tradePnLs.get(n - 2);
                double p3 = tradePnLs.get(n - 3);
                Instant t1 = tradeTimes.get(n - 1);
                Instant t3 = tradeTimes.get(n - 3);

                if (p1 < 0 && p2 < 0 && p3 < 0) {
                    long durationSec = java.time.Duration.between(t3, t1).getSeconds();
                    if (durationSec <= 7200) { // 2 hours
                        cooldownUntil = fillTime.plus(java.time.Duration.ofHours(4));
                        log.warn("3 consecutive losses within 2 hours. Strategy entering 4-hour COOLDOWN until {}", cooldownUntil);
                        emitOperatorAction("COOLDOWN_LOCK", "3 consecutive losses in 2 hours. Cooldown until " + cooldownUntil);
                        triggerBreachLiquidation("COOLDOWN_LOCK", "3 consecutive losses in 2 hours. Cooldown active.");
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to check consecutive losses: {}", e.getMessage());
        }
    }

    private void reconstructTradePnLs(List<RunEvent> fills, List<Double> tradePnLs, List<Instant> tradeTimes) {
        // Matches matching buy/sell fills to calculate realized trade P&Ls
        Map<String, List<Map<String, Object>>> openFills = new LinkedHashMap<>();
        for (RunEvent event : fills) {
            Map<String, Object> payload = event.payload();
            String symbol = String.valueOf(payload.get("symbol"));
            String side = String.valueOf(payload.get("side"));
            double qty = ((Number) payload.get("quantity")).doubleValue();
            double price = ((Number) payload.get("price")).doubleValue();
            Instant timestamp = event.timestamp();

            List<Map<String, Object>> list = openFills.computeIfAbsent(symbol, k -> new ArrayList<>());

            double remainingQty = qty;
            while (remainingQty > 0 && !list.isEmpty() && !list.get(0).get("side").equals(side)) {
                Map<String, Object> first = list.get(0);
                double firstQty = ((Number) first.get("quantity")).doubleValue();
                double matchQty = Math.min(remainingQty, firstQty);

                double entryPrice = ((Number) first.get("price")).doubleValue();
                double exitPrice = price;
                String entrySide = String.valueOf(first.get("side"));

                double pnl = entrySide.equals("BUY") ? (exitPrice - entryPrice) * matchQty : (entryPrice - exitPrice) * matchQty;
                tradePnLs.add(pnl);
                tradeTimes.add(timestamp);

                remainingQty -= matchQty;
                double newFirstQty = firstQty - matchQty;
                if (newFirstQty <= 0) {
                    list.remove(0);
                } else {
                    first.put("quantity", newFirstQty);
                }
            }

            if (remainingQty > 0) {
                Map<String, Object> newFill = new LinkedHashMap<>();
                newFill.put("side", side);
                newFill.put("price", price);
                newFill.put("quantity", remainingQty);
                newFill.put("timestamp", timestamp);
                list.add(newFill);
            }
        }
    }

    private void triggerActiveLiquidation() {
        if (!active.getAndSet(false)) {
            return;
        }
        streamingClient.removeListener(tickListener);
        streamingClient.unsubscribe(config.symbol());

        java.util.Set<String> runOrderIds = new java.util.HashSet<>();
        try {
            if (eventStore != null) {
                for (var e : eventStore.replayAll(runId)) {
                    if ((e.type() == RunEventType.FILL || e.type() == RunEventType.ORDER_SUBMITTED) && e.payload() != null && e.payload().containsKey("orderId")) {
                        runOrderIds.add(String.valueOf(e.payload().get("orderId")));
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to replay fills for active liquidation filtering in run {}", runId, e);
        }

        // Cancel all pending orders
        for (Position pos : broker.getPositions()) {
            boolean matchesSymbol = pos.symbol().equalsIgnoreCase(config.symbol())
                || pos.symbol().replace("/", "_").replace("-", "_").equalsIgnoreCase(config.symbol().replace("/", "_").replace("-", "_"));
            if (matchesSymbol) {
                boolean match = false;
                if (pos.clientTag() != null && !pos.clientTag().isBlank()) {
                    if (runOrderIds.contains(pos.clientTag())) {
                        match = true;
                    }
                } else {
                    int activeRuns = 0;
                    if (activeSymbolsSupplier != null) {
                        List<String> activeSymbols = activeSymbolsSupplier.get();
                        if (activeSymbols == null || activeSymbols.isEmpty()) {
                            activeRuns = 1;
                        } else {
                            for (String rs : activeSymbols) {
                                if (pos.symbol().equalsIgnoreCase(rs) || pos.symbol().replace("/", "_").replace("-", "_").equalsIgnoreCase(rs.replace("/", "_").replace("-", "_"))) {
                                    activeRuns++;
                                }
                            }
                        }
                    } else {
                        activeRuns = 1;
                    }
                    if (activeRuns == 1) {
                        match = true;
                    }
                }
                if (match) {
                    Order marketClose = new Order(
                        pos.symbol(),
                        pos.side() == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY,
                        Order.Type.MARKET,
                        pos.quantity(),
                        0.0
                    ).closeOnly();
                    log.info("Submitting emergency close order: {}", marketClose);
                    broker.submitOrder(marketClose);
                }
            }
        }
        emitOperatorAction("DRAWDOWN_BREACH", "Max 10% drawdown exceeded.");
        doStop();
    }

    private void executePendingOrders(Bar lastBar) {
        for (Order pending : strategy.getPendingOrders()) {
            if (killSwitchRegistry != null && killSwitchRegistry.isKilled(config.strategyId())) {
                persistKillReject(pending);
                continue;
            }

            Order order;
            if (pending.price() <= 0.0) {
                order = new Order(pending.symbol(), pending.side(), pending.type(), pending.quantity(), lastBar.close());
                if (pending.isCloseOnly()) {
                    order.closeOnly();
                }
                order.withStopLoss(pending.stopLoss())
                     .withTakeProfit(pending.takeProfit())
                     .withTrailingStop(pending.trailingStop())
                     .withGuaranteed(pending.guaranteed());
            } else {
                order = pending;
            }

            if (MarketSessionResolver.isClosed(order.symbol(), runMode.name(), Instant.now())) {
                log.warn("Forex market is closed for symbol {}. Rejecting order submission.", order.symbol());
                persistMarketClosedReject(order);
                rejectedCount++;
                continue;
            }

            RiskCheckResult riskCheck = riskEngine.checkPreTrade(order, broker.getPositions());
            if (!riskCheck.passed()) {
                persistRiskReject(order, riskCheck);
                continue;
            }

            submittedCount++;
            OrderSubmitResult result = broker.submitOrder(order);
            if (result.accepted()) {
                filledCount++;
                // Track realized slippage if fill price differs from target limit price
                if (order.type() != Order.Type.MARKET && result.brokerOrderId() != null) {
                    // Log slippage or print info
                    log.info("Order {} filled. Target: {}, Fill price logged.", result.brokerOrderId(), order.price());
                }
            } else {
                rejectedCount++;
            }
        }
    }

    private boolean isWeekend(Instant instant) {
        ZonedDateTime zdt = instant.atZone(ZoneOffset.UTC);
        DayOfWeek day = zdt.getDayOfWeek();
        int hour = zdt.getHour();
        if (day == DayOfWeek.FRIDAY && hour >= 22) return true;
        if (day == DayOfWeek.SATURDAY) return true;
        if (day == DayOfWeek.SUNDAY && hour < 22) return true;
        return false;
    }

    private void emitStarted() {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("initialCapital", config.resolvedCapital());
        payload.put("executionLabel", config.resolvedExecutionLabel().name());
        payload.put("configSnapshot", config.toMap());
        payload.put("configHash", config.hash());

        RunEvent started = RunEvent.started(
            runId,
            config.strategyId(),
            config.symbol(),
            runMode,
            Map.copyOf(payload));
        eventStore.append(runId, started);
    }

    private void emitEnded() {
        double currentEquity;
        try {
            currentEquity = broker.getAccountState().equity();
        } catch (Exception e) {
            log.warn("Failed to retrieve broker account state during emitEnded for runId {}, falling back to resolved capital: {}", runId, e.getMessage());
            currentEquity = config.resolvedCapital();
        }
        double returnPct = (currentEquity - config.resolvedCapital()) / config.resolvedCapital() * 100.0;

        int closedTradesCount = 0;
        try {
            var replayedEvents = eventStore.replayAll(runId);
            var reconstructed = com.martinfou.trading.backtest.persistence.TradeReconstructor.reconstruct(replayedEvents);
            closedTradesCount = reconstructed.size();
        } catch (Exception e) {
            log.warn("Failed to reconstruct closed trades for run {}: {}", runId, e.getMessage());
        }

        var endedPayload = new LinkedHashMap<String, Object>();
        endedPayload.put("totalTrades", filledCount);      // fills submitted and accepted
        endedPayload.put("closedTrades", closedTradesCount); // matched open/close pairs
        endedPayload.put("totalReturnPct", returnPct);
        endedPayload.put("finalEquity", currentEquity);
        endedPayload.put("ordersSubmitted", submittedCount);
        endedPayload.put("ordersRejected", rejectedCount);
        endedPayload.put("executionLabel", config.resolvedExecutionLabel().name());

        RunEvent ended = RunEvent.ended(runId, config.strategyId(), config.symbol(), runMode, Map.copyOf(endedPayload));
        eventStore.append(runId, ended);
    }

    private void emitOperatorAction(String type, String message) {
        RunEvent event = RunEvent.operatorAction(
            runId,
            config.strategyId(),
            config.symbol(),
            runMode,
            type,
            "RISK_ENGINE",
            message,
            Instant.now());
        eventStore.append(runId, event);
    }

    private void persistBrokerEvent(BrokerEvent brokerEvent) {
        RunEventType type = switch (brokerEvent.type()) {
            case ORDER_SUBMITTED -> RunEventType.ORDER_SUBMITTED;
            case FILL, PARTIAL_CLOSE, FINANCING -> RunEventType.FILL;
            case REJECT -> RunEventType.REJECT;
            case CONNECTION -> RunEventType.CONNECTION;
            case RATE_LIMIT -> RunEventType.RATE_LIMIT_TRIGGERED;
            case STALE_PRICE -> RunEventType.STALE_PRICE_DETECTED;
        };
        RunEvent event = new RunEvent(
            RunEvent.SCHEMA_VERSION,
            type,
            brokerEvent.timestamp(),
            runId,
            config.strategyId(),
            config.symbol(),
            runMode.name(),
            brokerEvent.toPayload());
        eventStore.append(runId, event);
    }

    private void persistKillReject(Order order) {
        BrokerEvent reject = BrokerEvent.reject(order, "Kill switch active");
        RunEvent event = new RunEvent(
            RunEvent.SCHEMA_VERSION,
            RunEventType.REJECT,
            reject.timestamp(),
            runId,
            config.strategyId(),
            config.symbol(),
            runMode.name(),
            reject.toPayload());
        eventStore.append(runId, event);
    }

    private void persistRiskReject(Order order, RiskCheckResult riskCheck) {
        var payload = new LinkedHashMap<String, Object>();
        payload.putAll(BrokerEvent.reject(order, riskCheck.message()).toPayload());
        payload.put("rejectSource", "RISK_ENGINE");
        payload.put("limit", riskCheck.limitName());

        RunEvent event = new RunEvent(
            RunEvent.SCHEMA_VERSION,
            RunEventType.REJECT,
            Instant.now(),
            runId,
            config.strategyId(),
            config.symbol(),
            runMode.name(),
            Map.copyOf(payload));
        eventStore.append(runId, event);
    }

    void reconcilePositions() {
        try {
            List<RunEvent> allEvents = eventStore.replayAll(runId);
            var expectedMap = JournalPositions.fromFills(allEvents);
            List<com.martinfou.trading.core.Position> brokerPositions = broker.getPositions();
            var actualMap = JournalPositions.fromBroker(brokerPositions);
            
            String targetSymbol = config.symbol();
            double expectedQty = 0.0;
            Order.Side expectedSide = null;
            for (var snap : expectedMap.values()) {
                if (snap.symbol().equalsIgnoreCase(targetSymbol)) {
                    expectedQty = snap.quantity();
                    expectedSide = snap.side();
                }
            }
            
            double actualQty = 0.0;
            Order.Side actualSide = null;
            for (var snap : actualMap.values()) {
                if (snap.symbol().equalsIgnoreCase(targetSymbol)) {
                    actualQty = snap.quantity();
                    actualSide = snap.side();
                }
            }
            
            boolean sideMismatch = (expectedSide != actualSide && expectedQty > 0 && actualQty > 0);
            boolean qtyMismatch = Math.abs(expectedQty - actualQty) > 1e-6;
            
            if (sideMismatch || qtyMismatch) {
                log.error("POSITION RECONCILIATION DELTA DETECTED for run {} on {}! Expected: {} {}, Actual: {} {}",
                    runId, targetSymbol, expectedSide, expectedQty, actualSide, actualQty);
                
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("runId", runId);
                payload.put("symbol", targetSymbol);
                payload.put("expectedSide", expectedSide != null ? expectedSide.name() : "NONE");
                payload.put("expectedQty", expectedQty);
                payload.put("actualSide", actualSide != null ? actualSide.name() : "NONE");
                payload.put("actualQty", actualQty);
                
                RunEvent alertEvent = new RunEvent(
                    RunEvent.SCHEMA_VERSION,
                    RunEventType.RECONCILIATION_ALERT,
                    Instant.now(),
                    runId,
                    config.strategyId(),
                    config.symbol(),
                    runMode.name(),
                    payload);
                eventStore.append(runId, alertEvent);
            } else {
                log.info("Position reconciliation check passed for run {} on {}.", runId, targetSymbol);
            }
        } catch (IllegalStateException e) {
            log.warn("Run {} — transient error fetching positions during manual check; skipping: {}",
                runId, e.getMessage());
        } catch (Exception e) {
            log.error("Failed to run position reconciliation for run {}", runId, e);
        }
    }

    void persistMarketClosedReject(Order order) {
        Instant nextOpen = getNextForexOpen(Instant.now());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("symbol", order.symbol());
        payload.put("side", order.side().name());
        payload.put("quantity", order.quantity());
        payload.put("reason", "MARKET_CLOSED");
        payload.put("nextOpenEstimate", nextOpen.toString());

        RunEvent event = new RunEvent(
            RunEvent.SCHEMA_VERSION,
            RunEventType.REJECT,
            Instant.now(),
            runId,
            config.strategyId(),
            config.symbol(),
            runMode.name(),
            payload);
        eventStore.append(runId, event);
    }

    private Instant getNextForexOpen(Instant now) {
        java.time.ZoneId NY = java.time.ZoneId.of("America/New_York");
        java.time.ZonedDateTime nyTime = now.atZone(NY);
        java.time.ZonedDateTime nextSunday = nyTime;
        if (nyTime.getDayOfWeek() == java.time.DayOfWeek.SUNDAY && nyTime.getHour() >= 17) {
            nextSunday = nextSunday.plusDays(1);
        }
        while (nextSunday.getDayOfWeek() != java.time.DayOfWeek.SUNDAY) {
            nextSunday = nextSunday.plusDays(1);
        }
        java.time.ZonedDateTime openTime = nextSunday.withHour(17).withMinute(0).withSecond(0).withNano(0);
        return openTime.toInstant();
    }

    void emitStalePriceWarningEvent(Instant timestamp) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("symbol", config.symbol());
        payload.put("lastPrice", lastPriceVal);
        payload.put("lastChangeTime", lastPriceValChangeTime.toString());
        payload.put("status", "STALE");

        RunEvent event = new RunEvent(
            RunEvent.SCHEMA_VERSION,
            RunEventType.STALE_PRICE_DETECTED,
            timestamp,
            runId,
            config.strategyId(),
            config.symbol(),
            runMode.name(),
            payload);
        eventStore.append(runId, event);
    }
}
