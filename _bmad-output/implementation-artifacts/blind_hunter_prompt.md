# Blind Hunter Prompt

You are a cynical, jaded reviewer with zero patience for sloppy work. Review this diff strictly in isolation. Assume problems exist. Find issues to fix or improve in the provided content. Focus on logical flaws, potential bugs, type safety, concurrency issues, edge cases, resource leaks, or anything that looks suspicious. Do not editorialize or add filler — list the findings directly.

## Diff to Review
```diff
diff --git a/trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlSummaryService.java b/trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlSummaryService.java
index 4897726..3645e12 100644
--- a/trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlSummaryService.java
+++ b/trading-runtime/src/main/java/com/martinfou/trading/runtime/ControlSummaryService.java
@@ -233,7 +233,8 @@ public final class ControlSummaryService {
         if (record.lastEventAt().isPresent()) {
             return record.lastEventAt();
         }
-        return latestStored.map(stored -> stored.event().timestamp());
+        return latestStored.map(stored -> stored.event().timestamp())
+            .filter(t -> t.isAfter(record.startedAt()));
     }
 
     private Optional<StoredRunEvent> latestStoredEvent(String runId) {
@@ -282,6 +283,7 @@ public final class ControlSummaryService {
 
     private List<Map<String, Object>> getPositions(RunRecord record, ExecutionLabel label) {
         List<Map<String, Object>> positions = new ArrayList<>();
+        boolean querySucceeded = false;
         if (record.status() == RunRecord.Status.RUNNING && label.isBrokerBacked()) {
             try {
                 String accountId = record.configSnapshot().containsKey("brokerAccountId")
@@ -290,50 +292,58 @@ public final class ControlSummaryService {
                 if (runManager != null && runManager.brokerAccountRegistry() != null) {
                     var brokerOpt = runManager.brokerAccountRegistry().broker(accountId, label);
                     if (brokerOpt.isPresent()) {
-                        try (var broker = brokerOpt.get()) {
-                            broker.connect();
-                            java.util.Set<String> runOrderIds = new java.util.HashSet<>();
-                            if (runManager.eventStore() != null) {
-                                for (var e : runManager.eventStore().replayAll(record.runId())) {
-                                    if (e.type() == RunEventType.FILL && e.payload() != null && e.payload().containsKey("orderId")) {
-                                        runOrderIds.add(String.valueOf(e.payload().get("orderId")));
-                                    }
+                        var broker = brokerOpt.get();
+                        broker.connect();
+                        List<com.martinfou.trading.core.Position> brokerPosList = broker.getPositions();
+                        querySucceeded = true;
+                        java.util.Set<String> runOrderIds = new java.util.HashSet<>();
+                        if (runManager.eventStore() != null) {
+                            for (var e : runManager.eventStore().replayAll(record.runId())) {
+                                if ((e.type() == RunEventType.FILL || e.type() == RunEventType.ORDER_SUBMITTED) && e.payload() != null && e.payload().containsKey("orderId")) {
+                                    runOrderIds.add(String.valueOf(e.payload().get("orderId")));
                                 }
                             }
-                            var journalPositions = JournalPositions.fromFills(runManager.eventStore().replayAll(record.runId()));
-                            for (var pos : broker.getPositions()) {
-                                if (pos.symbol().equalsIgnoreCase(record.symbol()) || pos.symbol().replace("/", "_").replace("-", "_").equalsIgnoreCase(record.symbol().replace("/", "_").replace("-", "_"))) {
-                                    java.time.Instant resolvedEntryTime = pos.entryTime();
-                                    if (resolvedEntryTime == null || resolvedEntryTime.equals(java.time.Instant.EPOCH)) {
-                                        String journalKey = pos.symbol() + ":" + pos.side().name();
-                                        var jp = journalPositions.get(journalKey);
-                                        if (jp != null && jp.entryTime() != null) {
-                                            resolvedEntryTime = jp.entryTime();
-                                        }
+                        }
+                        var journalPositions = JournalPositions.fromFills(runManager.eventStore().replayAll(record.runId()));
+                        for (var pos : brokerPosList) {
+                            if (pos.symbol().equalsIgnoreCase(record.symbol()) || pos.symbol().replace("/", "_").replace("-", "_").equalsIgnoreCase(record.symbol().replace("/", "_").replace("-", "_"))) {
+                                java.time.Instant resolvedEntryTime = pos.entryTime();
+                                if (resolvedEntryTime == null || resolvedEntryTime.equals(java.time.Instant.EPOCH)) {
+                                    String journalKey = pos.symbol() + ":" + pos.side().name();
+                                    var jp = journalPositions.get(journalKey);
+                                    if (jp != null && jp.entryTime() != null) {
+                                        resolvedEntryTime = jp.entryTime();
                                     }
-                                    if (pos.clientTag() != null && !pos.clientTag().isBlank()) {
-                                        if (runOrderIds.contains(pos.clientTag())) {
-                                            positions.add(Map.of(
-                                                "symbol", pos.symbol(),
-                                                "side", pos.side().name(),
-                                                "quantity", pos.quantity(),
-                                                "entryTime", resolvedEntryTime != null ? resolvedEntryTime.toString() : "",
-                                                "entryPrice", pos.entryPrice(),
-                                                "stopLoss", pos.stopLoss(),
-                                                "takeProfit", pos.takeProfit()
-                                            ));
+                                }
+                                boolean match = false;
+                                if (pos.clientTag() != null && !pos.clientTag().isBlank()) {
+                                    if (runOrderIds.contains(pos.clientTag())) {
+                                        match = true;
+                                    }
+                                } else {
+                                    int activeRuns = 0;
+                                    for (RunRecord r : runManager.list(null)) {
+                                        if (r.status() == RunRecord.Status.RUNNING) {
+                                            String rs = r.symbol();
+                                            if (pos.symbol().equalsIgnoreCase(rs) || pos.symbol().replace("/", "_").replace("-", "_").equalsIgnoreCase(rs.replace("/", "_").replace("-", "_"))) {
+                                                activeRuns++;
+                                            }
                                         }
-                                    } else {
-                                        positions.add(Map.of(
-                                            "symbol", pos.symbol(),
-                                            "side", pos.side().name(),
-                                            "quantity", pos.quantity(),
-                                            "entryTime", resolvedEntryTime != null ? resolvedEntryTime.toString() : "",
-                                            "entryPrice", pos.entryPrice(),
-                                            "stopLoss", pos.stopLoss(),
-                                            "takeProfit", pos.takeProfit()
-                                        ));
                                     }
+                                    if (activeRuns == 1) {
+                                        match = true;
+                                    }
+                                }
+                                if (match) {
+                                    positions.add(Map.of(
+                                        "symbol", pos.symbol(),
+                                        "side", pos.side().name(),
+                                        "quantity", pos.quantity(),
+                                        "entryTime", resolvedEntryTime != null ? resolvedEntryTime.toString() : "",
+                                        "entryPrice", pos.entryPrice(),
+                                        "stopLoss", pos.stopLoss(),
+                                        "takeProfit", pos.takeProfit()
+                                    ));
                                 }
                             }
                         }
@@ -343,7 +353,7 @@ public final class ControlSummaryService {
                 // fallback to journal fills
             }
         }
-        if (positions.isEmpty()) {
+        if (!querySucceeded) {
             List<Map<String, Object>> journalPositions = JournalPositions.fromFills(runManager.eventStore().replayAll(record.runId())).values().stream()
                 .map(pos -> Map.<String, Object>of(
                     "symbol", pos.symbol(),
diff --git a/trading-runtime/src/main/java/com/martinfou/trading/runtime/OandaStreamingExecutor.java b/trading-runtime/src/main/java/com/martinfou/trading/runtime/OandaStreamingExecutor.java
index 4517079..ea97a06 100644
--- a/trading-runtime/src/main/java/com/martinfou/trading/runtime/OandaStreamingExecutor.java
+++ b/trading-runtime/src/main/java/com/martinfou/trading/runtime/OandaStreamingExecutor.java
@@ -47,6 +47,7 @@ public final class OandaStreamingExecutor implements AutoCloseable {
     private final RunRiskContext riskContext;
     private final RiskEngine riskEngine;
     private final OandaRestClient restClient;
+    private final java.util.function.Supplier<List<String>> activeSymbolsSupplier;
 
     private final AtomicBoolean active = new AtomicBoolean(false);
     private final BarAggregator aggregator;
@@ -77,6 +78,7 @@ public final class OandaStreamingExecutor implements AutoCloseable {
     private volatile double lastMidPrice = 0.0;
     private volatile double lastBid = 0.0;
     private volatile double lastAsk = 0.0;
+    private Instant lastReconciliationTime = Instant.EPOCH;
 
     public OandaStreamingExecutor(
         String runId,
@@ -99,6 +91,22 @@ public final class OandaStreamingExecutor implements AutoCloseable {
         KillSwitchRegistry killSwitchRegistry,
         OandaStreamingClient streamingClient,
         RunRiskContext riskContext
+    ) {
+        this(runId, record, config, strategy, broker, restClient, eventStore, killSwitchRegistry, streamingClient, riskContext, java.util.Collections::emptyList);
+    }
+
+    public OandaStreamingExecutor(
+        String runId,
+        RunRecord record,
+        RunConfigSnapshot config,
+        Strategy strategy,
+        Broker broker,
+        OandaRestClient restClient,
+        EventStore eventStore,
+        KillSwitchRegistry killSwitchRegistry,
+        OandaStreamingClient streamingClient,
+        RunRiskContext riskContext,
+        java.util.function.Supplier<List<String>> activeSymbolsSupplier
     ) {
         this.runId = runId;
         this.record = record;
@@ -125,6 +143,7 @@ public final class OandaStreamingExecutor implements AutoCloseable {
                 log.info("Pricing stream connection state for run {}: active={}", runId, active);
             }
         };
+        this.activeSymbolsSupplier = activeSymbolsSupplier != null ? activeSymbolsSupplier : java.util.Collections::emptyList;
     }
 
     public void start() {
@@ -270,6 +289,12 @@ public final class OandaStreamingExecutor implements AutoCloseable {
             // 2. Perform risk circuit checks (DLL, WLL, Drawdown)
             checkRiskCircuitBreakers(timestamp);
 
+            // 3. Perform position reconciliation (every 60 seconds)
+            if (lastReconciliationTime == null || java.time.Duration.between(lastReconciliationTime, timestamp).toSeconds() >= 60) {
+                reconcilePositions(timestamp);
+                lastReconciliationTime = timestamp;
+            }
+
             double mid = (bid + ask) / 2.0;
             Bar tickBar = new Bar(config.symbol(), timestamp, mid, mid, mid, mid, 1);
 
@@ -397,13 +422,108 @@ public final class OandaStreamingExecutor implements AutoCloseable {
         }
     }
 
+    private void reconcilePositions(Instant timestamp) {
+        ExecutionLabel label = config.resolvedExecutionLabel();
+        if (label == null || !label.isBrokerBacked()) {
+            return;
+        }
+        try {
+            List<com.martinfou.trading.core.Position> brokerPositions = broker.getPositions();
+            
+            List<RunEvent> allEvents = eventStore.replayAll(runId);
+            var runOrderIds = new java.util.HashSet<String>();
+            for (var e : allEvents) {
+                if ((e.type() == RunEventType.FILL || e.type() == RunEventType.ORDER_SUBMITTED) && e.payload() != null && e.payload().containsKey("orderId")) {
+                    runOrderIds.add(String.valueOf(e.payload().get("orderId")));
+                }
+            }
+            
+            var journalPositions = JournalPositions.fromFills(allEvents);
+            
+            boolean brokerHasPosition = false;
+            boolean canDisambiguate = true;
+            for (var pos : brokerPositions) {
+                boolean matchesSymbol = pos.symbol().equalsIgnoreCase(config.symbol()) 
+                    || pos.symbol().replace("/", "_").replace("-", "_").equalsIgnoreCase(config.symbol().replace("/", "_").replace("-", "_"));
+                if (matchesSymbol) {
+                    if (pos.clientTag() != null && !pos.clientTag().isBlank()) {
+                        if (runOrderIds.contains(pos.clientTag())) {
+                            brokerHasPosition = true;
+                            break;
+                        }
+                    } else {
+                        int activeRuns = 0;
+                        if (activeSymbolsSupplier != null) {
+                            List<String> activeSymbols = activeSymbolsSupplier.get();
+                            if (activeSymbols == null || activeSymbols.isEmpty()) {
+                                activeRuns = 1;
+                            } else {
+                                for (String rs : activeSymbols) {
+                                    if (pos.symbol().equalsIgnoreCase(rs) || pos.symbol().replace("/", "_").replace("-", "_").equalsIgnoreCase(rs.replace("/", "_").replace("-", "_"))) {
+                                        activeRuns++;
+                                    }
+                                }
+                            }
+                        } else {
+                            activeRuns = 1;
+                        }
+                        if (activeRuns == 1) {
+                            brokerHasPosition = true;
+                            break;
+                        } else if (activeRuns > 1) {
+                            canDisambiguate = false;
+                        }
+                    }
+                }
+            }
+            
+            if (!brokerHasPosition && canDisambiguate) {
+                for (var jp : journalPositions.values()) {
+                    boolean matchesSymbol = jp.symbol().equalsIgnoreCase(config.symbol()) 
+                        || jp.symbol().replace("/", "_").replace("-", "_").equalsIgnoreCase(jp.symbol().replace("/", "_").replace("-", "_"));
+                    if (matchesSymbol && jp.quantity() > 0.0) {
+                        Order.Side oppositeSide = jp.side() == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
+                        
+                        Map<String, Object> payload = new java.util.LinkedHashMap<>();
+                        payload.put("type", "FILL");
+                        payload.put("timestamp", timestamp.toString());
+                        payload.put("orderId", "reconciliation-" + System.currentTimeMillis());
+                        payload.put("symbol", config.symbol());
+                        payload.put("side", oppositeSide.name());
+                        payload.put("quantity", jp.quantity());
+                        payload.put("price", lastMidPrice);
+                        payload.put("reconciliation", true);
+                        payload.put("reason", "BROKER_POSITION_CLOSED");
+                        
+                        RunEvent correctiveEvent = new RunEvent(
+                            RunEvent.SCHEMA_VERSION,
+                            RunEventType.FILL,
+                            timestamp,
+                            runId,
+                            config.strategyId(),
+                            config.symbol(),
+                            runMode.name(),
+                            Map.copyOf(payload)
+                        );
+                        
+                        eventStore.append(runId, correctiveEvent);
+                        log.warn("Reconciliation closed open local position of {} {} for run {} since broker reports 0 open positions. Appended corrective FILL.", 
+                            jp.quantity(), jp.side(), runId);
+                    }
+                }
+            }
+        } catch (Exception e) {
+            log.error("Failed to run position reconciliation for run {}", runId, e);
+        }
+    }
+
     private void triggerBreachLiquidation(String actionType, String message) {
         // Cancel all pending orders & liquidate active positions
         java.util.Set<String> runOrderIds = new java.util.HashSet<>();
         try {
             if (eventStore != null) {
                 for (var e : eventStore.replayAll(runId)) {
-                    if (e.type() == RunEventType.FILL && e.payload() != null && e.payload().containsKey("orderId")) {
+                    if ((e.type() == RunEventType.FILL || e.type() == RunEventType.ORDER_SUBMITTED) && e.payload() != null && e.payload().containsKey("orderId")) {
                         runOrderIds.add(String.valueOf(e.payload().get("orderId")));
                     }
                 }
@@ -413,21 +533,45 @@ public final class OandaStreamingExecutor implements AutoCloseable {
         }
 
         for (Position pos : broker.getPositions()) {
-            if (pos.symbol().equalsIgnoreCase(config.symbol())) {
+            boolean matchesSymbol = pos.symbol().equalsIgnoreCase(config.symbol())
+                || pos.symbol().replace("/", "_").replace("-", "_").equalsIgnoreCase(config.symbol().replace("/", "_").replace("-", "_"));
+            if (matchesSymbol) {
+                boolean match = false;
                 if (pos.clientTag() != null && !pos.clientTag().isBlank()) {
-                    if (!runOrderIds.contains(pos.clientTag())) {
-                        continue;
+                    if (runOrderIds.contains(pos.clientTag())) {
+                        match = true;
+                    }
+                } else {
+                    int activeRuns = 0;
+                    if (activeSymbolsSupplier != null) {
+                        List<String> activeSymbols = activeSymbolsSupplier.get();
+                        if (activeSymbols == null || activeSymbols.isEmpty()) {
+                            activeRuns = 1;
+                        } else {
+                            for (String rs : activeSymbols) {
+                                if (pos.symbol().equalsIgnoreCase(rs) || pos.symbol().replace("/", "_").replace("-", "_").equalsIgnoreCase(rs.replace("/", "_").replace("-", "_"))) {
+                                    activeRuns++;
+                                }
+                            }
+                        }
+                    } else {
+                        activeRuns = 1;
+                    }
+                    if (activeRuns == 1) {
+                        match = true;
                     }
                 }
-                Order marketClose = new Order(
-                    pos.symbol(),
-                    pos.side() == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY,
-                    Order.Type.MARKET,
-                    pos.quantity(),
-                    0.0
-                ).closeOnly();
-                log.info("Submitting emergency close order due to breach: {}", marketClose);
-                broker.submitOrder(marketClose);
+                if (match) {
+                    Order marketClose = new Order(
+                        pos.symbol(),
+                        pos.side() == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY,
+                        Order.Type.MARKET,
+                        pos.quantity(),
+                        0.0
+                    ).closeOnly();
+                    log.info("Submitting emergency close order due to breach: {}", marketClose);
+                    broker.submitOrder(marketClose);
+                }
             }
         }
         emitOperatorAction(actionType, message);
@@ -536,7 +680,7 @@ public final class OandaStreamingExecutor implements AutoCloseable {
         try {
             if (eventStore != null) {
                 for (var e : eventStore.replayAll(runId)) {
-                    if (e.type() == RunEventType.FILL && e.payload() != null && e.payload().containsKey("orderId")) {
+                    if ((e.type() == RunEventType.FILL || e.type() == RunEventType.ORDER_SUBMITTED) && e.payload() != null && e.payload().containsKey("orderId")) {
                         runOrderIds.add(String.valueOf(e.payload().get("orderId")));
                     }
                 }
@@ -547,21 +691,45 @@ public final class OandaStreamingExecutor implements AutoCloseable {
 
         // Cancel all pending orders
         for (Position pos : broker.getPositions()) {
-            if (pos.symbol().equalsIgnoreCase(config.symbol())) {
+            boolean matchesSymbol = pos.symbol().equalsIgnoreCase(config.symbol())
+                || pos.symbol().replace("/", "_").replace("-", "_").equalsIgnoreCase(config.symbol().replace("/", "_").replace("-", "_"));
+            if (matchesSymbol) {
+                boolean match = false;
                 if (pos.clientTag() != null && !pos.clientTag().isBlank()) {
-                    if (!runOrderIds.contains(pos.clientTag())) {
-                        continue;
+                    if (runOrderIds.contains(pos.clientTag())) {
+                        match = true;
+                    }
+                } else {
+                    int activeRuns = 0;
+                    if (activeSymbolsSupplier != null) {
+                        List<String> activeSymbols = activeSymbolsSupplier.get();
+                        if (activeSymbols == null || activeSymbols.isEmpty()) {
+                            activeRuns = 1;
+                        } else {
+                            for (String rs : activeSymbols) {
+                                if (pos.symbol().equalsIgnoreCase(rs) || pos.symbol().replace("/", "_").replace("-", "_").equalsIgnoreCase(rs.replace("/", "_").replace("-", "_"))) {
+                                    activeRuns++;
+                                }
+                            }
+                        }
+                    } else {
+                        activeRuns = 1;
+                    }
+                    if (activeRuns == 1) {
+                        match = true;
                     }
                 }
-                Order marketClose = new Order(
-                    pos.symbol(),
-                    pos.side() == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY,
-                    Order.Type.MARKET,
-                    pos.quantity(),
-                    0.0
-                ).closeOnly();
-                log.info("Submitting emergency close order: {}", marketClose);
-                broker.submitOrder(marketClose);
+                if (match) {
+                    Order marketClose = new Order(
+                        pos.symbol(),
+                        pos.side() == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY,
+                        Order.Type.MARKET,
+                        pos.quantity(),
+                        0.0
+                    ).closeOnly();
+                    log.info("Submitting emergency close order: {}", marketClose);
+                    broker.submitOrder(marketClose);
+                }
             }
         }
         emitOperatorAction("DRAWDOWN_BREACH", "Max 10% drawdown exceeded.");
@@ -637,7 +805,13 @@ public final class OandaStreamingExecutor implements AutoCloseable {
     }
 
     private void emitEnded() {
-        double currentEquity = broker.getAccountState().equity();
+        double currentEquity;
+        try {
+            currentEquity = broker.getAccountState().equity();
+        } catch (Exception e) {
+            log.warn("Failed to retrieve broker account state during emitEnded for runId {}, falling back to resolved capital: {}", runId, e.getMessage());
+            currentEquity = config.resolvedCapital();
+        }
         double returnPct = (currentEquity - config.resolvedCapital()) / config.resolvedCapital() * 100.0;
 
         var endedPayload = new LinkedHashMap<String, Object>();
diff --git a/trading-runtime/src/test/java/com/martinfou/trading/runtime/ControlSummaryServiceTest.java b/trading-runtime/src/test/java/com/martinfou/trading/runtime/ControlSummaryServiceTest.java
index 7cc4347..60057ee 100644
--- a/trading-runtime/src/test/java/com/martinfou/trading/runtime/ControlSummaryServiceTest.java
+++ b/trading-runtime/src/test/java/com/martinfou/trading/runtime/ControlSummaryServiceTest.java
@@ -1,6 +1,10 @@
 package com.martinfou.trading.runtime;
 
 import org.junit.jupiter.api.Test;
+import com.martinfou.trading.core.Order;
+import com.martinfou.trading.core.Position;
+import com.martinfou.trading.backtest.events.RunEvent;
+import com.martinfou.trading.backtest.events.RunEventType;
 
 import java.time.Clock;
 import java.time.Instant;
@@ -128,6 +132,112 @@ class ControlSummaryServiceTest {
         }
     }
 
+    @Test
+    void getPositions_brokerBackedRun_returnsEmptyWhenQuerySucceedsWithZeroPositions() {
+        var mockBroker = new com.martinfou.trading.broker.Broker() {
+            @Override public boolean isConnected() { return true; }
+            @Override public void connect() {}
+            @Override public void disconnect() {}
+            @Override public void reconnect() {}
+            @Override public com.martinfou.trading.broker.OrderSubmitResult submitOrder(Order order) { return null; }
+            @Override public com.martinfou.trading.broker.OrderSubmitResult cancelOrder(String id) { return null; }
+            @Override public List<Position> getPositions() { return List.of(); }
+            @Override public com.martinfou.trading.broker.AccountState getAccountState() { return new com.martinfou.trading.broker.AccountState(100000, 100000, "USD"); }
+            @Override public void addEventListener(java.util.function.Consumer<com.martinfou.trading.broker.BrokerEvent> l) {}
+        };
+        try (EventStore store = EventStores.inMemory();
+             RunManager manager = new RunManager(store)) {
+
+            manager.brokerAccountRegistry().registerMockBroker("default", mockBroker);
+
+            RunConfigSnapshot config = new RunConfigSnapshot(
+                "LondonOpenRangeBreakout",
+                "EUR_USD",
+                "LIVE",
+                "sample",
+                100,
+                null,
+                100_000.0,
+                null,
+                null,
+                ExecutionLabel.LIVE_OANDA.name());
+
+            RunRecord run = manager.register(config);
+            run.markRunning();
+
+            store.append(run.runId(), new RunEvent(
+                RunEvent.SCHEMA_VERSION,
+                RunEventType.FILL,
+                Instant.now(),
+                run.runId(),
+                "LondonOpenRangeBreakout",
+                "EUR_USD",
+                "LIVE",
+                Map.of("symbol", "EUR_USD", "side", "BUY", "quantity", 1000.0, "price", 1.10)
+            ));
+
+            ControlSummaryService service = new ControlSummaryService(manager);
+            Map<String, Object> summary = service.buildSummary();
+            @SuppressWarnings("unchecked")
+            List<Map<String, Object>> runs = (List<Map<String, Object>>) summary.get("runs");
+            assertEquals(1, runs.size());
+            @SuppressWarnings("unchecked")
+            List<Map<String, Object>> positions = (List<Map<String, Object>>) runs.getFirst().get("positions");
+            assertTrue(positions.isEmpty());
+        }
+    }
+
+    @Test
+    void buildSummary_restoredRun_ignoresOldEventsBeforeStartedAt() throws Exception {
+        try (EventStore store = EventStores.inMemory();
+             RunManager manager = new RunManager(store, config -> new com.martinfou.trading.broker.FakeBroker(100_000.0))) {
+            
+            RunConfigSnapshot config = new RunConfigSnapshot(
+                "LondonOpenRangeBreakout",
+                "EUR_USD",
+                "LIVE",
+                "sample",
+                100,
+                null,
+                100_000.0,
+                null,
+                null,
+                ExecutionLabel.LIVE_OANDA.name());
+            
+            String runId = "restored-run-123";
+            RunRecord run = manager.restoreRun(runId, config);
+            run.markRunning();
+            
+            // Append an event from 1 hour before the run started (previous session)
+            Instant oldEventTime = run.startedAt().minus(java.time.Duration.ofHours(1));
+            store.append(run.runId(), new RunEvent(
+                RunEvent.SCHEMA_VERSION,
+                RunEventType.FILL,
+                oldEventTime,
+                run.runId(),
+                "LondonOpenRangeBreakout",
+                "EUR_USD",
+                "LIVE",
+                Map.of("symbol", "EUR_USD", "side", "BUY", "quantity", 1000.0, "price", 1.10)
+            ));
+
+            // Set clock to 30 seconds after the run started
+            Instant testNow = run.startedAt().plus(java.time.Duration.ofSeconds(30));
+            Clock clock = Clock.fixed(testNow, ZoneOffset.UTC);
+
+            ControlSummaryService service = new ControlSummaryService(manager, 120, clock);
+            Map<String, Object> summary = service.buildSummary();
+
+            @SuppressWarnings("unchecked")
+            List<Map<String, Object>> runs = (List<Map<String, Object>>) summary.get("runs");
+            assertEquals(1, runs.size());
+            Map<String, Object> runItem = runs.getFirst();
+            
+            // The run should NOT be stale because the old event is ignored and it uses the startedAt grace period
+            assertFalse((Boolean) runItem.get("isStale"));
+        }
+    }
+
     private static void waitForCompletion(RunManager manager, String runId) throws InterruptedException {
         for (int i = 0; i < 200; i++) {
             RunRecord record = manager.getRun(runId).orElseThrow();
```
