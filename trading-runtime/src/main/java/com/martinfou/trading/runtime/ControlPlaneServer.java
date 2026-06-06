package com.martinfou.trading.runtime;

import com.martinfou.trading.strategies.StrategyCatalog;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.function.Consumer;
import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventType;

/**
 * Javalin HTTP control plane — health, strategies, runs, event replay, and WebSocket stream.
 */
public final class ControlPlaneServer implements AutoCloseable {

    public static final String VERSION = "1.0.0-SNAPSHOT";

    private final RunManager runManager;
    private final RunEventHub eventHub;
    private final PromoteService promoteService;
    private final KillSwitchService killSwitchService;
    private final ControlSummaryService summaryService;
    private final SqBridgeService sqBridgeService;
    private final WeeklyBuilderService weeklyBuilderService;
    private final HistoricalDataService historicalDataService;
    private final Javalin app;

    public ControlPlaneServer(
        RunManager runManager,
        RunEventHub eventHub,
        PromoteService promoteService,
        KillSwitchService killSwitchService,
        int port
    ) {
        this(runManager, eventHub, promoteService, killSwitchService,
            new ControlSummaryService(runManager, promoteService.deploymentStore()),
            new SqBridgeService(runManager.eventStore()),
            new WeeklyBuilderService(runManager.eventStore()),
            port);
    }

    ControlPlaneServer(
        RunManager runManager,
        RunEventHub eventHub,
        PromoteService promoteService,
        KillSwitchService killSwitchService,
        ControlSummaryService summaryService,
        SqBridgeService sqBridgeService,
        WeeklyBuilderService weeklyBuilderService,
        int port
    ) {
        this(runManager, eventHub, promoteService, killSwitchService, summaryService,
            sqBridgeService, weeklyBuilderService, new HistoricalDataService(), port);
    }

    ControlPlaneServer(
        RunManager runManager,
        RunEventHub eventHub,
        PromoteService promoteService,
        KillSwitchService killSwitchService,
        ControlSummaryService summaryService,
        SqBridgeService sqBridgeService,
        WeeklyBuilderService weeklyBuilderService,
        HistoricalDataService historicalDataService,
        int port
    ) {
        if (runManager == null) {
            throw new IllegalArgumentException("runManager must not be null");
        }
        if (eventHub == null) {
            throw new IllegalArgumentException("eventHub must not be null");
        }
        if (promoteService == null) {
            throw new IllegalArgumentException("promoteService must not be null");
        }
        if (killSwitchService == null) {
            throw new IllegalArgumentException("killSwitchService must not be null");
        }
        if (summaryService == null) {
            throw new IllegalArgumentException("summaryService must not be null");
        }
        if (sqBridgeService == null) {
            throw new IllegalArgumentException("sqBridgeService must not be null");
        }
        if (weeklyBuilderService == null) {
            throw new IllegalArgumentException("weeklyBuilderService must not be null");
        }
        this.runManager = runManager;
        this.eventHub = eventHub;
        this.promoteService = promoteService;
        this.killSwitchService = killSwitchService;
        this.summaryService = summaryService;
        this.sqBridgeService = sqBridgeService;
        this.weeklyBuilderService = weeklyBuilderService;
        this.historicalDataService = historicalDataService != null ? historicalDataService : new HistoricalDataService();
        this.app = createApp(runManager, eventHub, promoteService, killSwitchService, summaryService,
            sqBridgeService, weeklyBuilderService, this.historicalDataService);
        app.start(port);
    }

    /** Package-private for tests that inject a custom {@link ControlSummaryService}. */
    ControlPlaneServer(
        RunManager runManager,
        RunEventHub eventHub,
        PromoteService promoteService,
        KillSwitchService killSwitchService,
        ControlSummaryService summaryService,
        int port
    ) {
        this(runManager, eventHub, promoteService, killSwitchService, summaryService,
            new SqBridgeService(runManager.eventStore()),
            new WeeklyBuilderService(runManager.eventStore()),
            port);
    }

    /** Package-private for tests that inject a custom {@link SqBridgeService}. */
    ControlPlaneServer(
        RunManager runManager,
        RunEventHub eventHub,
        PromoteService promoteService,
        KillSwitchService killSwitchService,
        int port,
        SqBridgeService sqBridgeService
    ) {
        this(runManager, eventHub, promoteService, killSwitchService,
            new ControlSummaryService(runManager, promoteService.deploymentStore()),
            sqBridgeService,
            new WeeklyBuilderService(runManager.eventStore()),
            port);
    }

    /** Package-private for tests that inject custom bridge / builder services. */
    ControlPlaneServer(
        RunManager runManager,
        RunEventHub eventHub,
        PromoteService promoteService,
        KillSwitchService killSwitchService,
        int port,
        SqBridgeService sqBridgeService,
        WeeklyBuilderService weeklyBuilderService
    ) {
        this(runManager, eventHub, promoteService, killSwitchService,
            new ControlSummaryService(runManager, promoteService.deploymentStore()),
            sqBridgeService,
            weeklyBuilderService,
            port);
    }

    public int port() {
        return app.port();
    }

    @Override
    public void close() {
        app.stop();
        sqBridgeService.close();
        weeklyBuilderService.close();
    }

    private static Javalin createApp(
        RunManager runManager,
        RunEventHub eventHub,
        PromoteService promoteService,
        KillSwitchService killSwitchService,
        ControlSummaryService summaryService,
        SqBridgeService sqBridgeService,
        WeeklyBuilderService weeklyBuilderService,
        HistoricalDataService historicalDataService
    ) {
        DataAvailabilityService dataAvailability = new DataAvailabilityService();
        return Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.bundledPlugins.enableCors(cors -> cors.addRule(rule -> rule.anyHost()));
        })
            .before(ctx -> {
                ctx.header("Access-Control-Allow-Origin", "*");
                ctx.header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
                ctx.header("Access-Control-Allow-Headers", "Content-Type, Authorization");
            })
            .get("/api/health", ctx -> ctx.json(Map.of(
                "status", "ok",
                "version", VERSION,
                "dataCatalog", true)))
            .get("/api/sq-bridge/status", ctx -> ctx.json(sqBridgeService.status()))
            .post("/api/sq-bridge/process-inbox", ctx -> {
                SqBridgeService.ProcessInboxResponse response = sqBridgeService.processInboxAsync();
                if (response.accepted()) {
                    ctx.status(HttpStatus.ACCEPTED);
                } else {
                    ctx.status(HttpStatus.CONFLICT);
                }
                ctx.json(Map.of(
                    "accepted", response.accepted(),
                    "message", response.message()));
            })
            .get("/api/weekly-builder/status", ctx -> ctx.json(weeklyBuilderService.status()))
            .get("/api/historical-data/status", ctx -> {
                String tf = ctx.queryParam("tf") != null ? ctx.queryParam("tf") : "h1";
                ctx.json(Map.of(
                    "status", historicalDataService.getStatus(tf),
                    "activeDownloads", historicalDataService.getActiveDownloads(),
                    "activeTasks", historicalDataService.getActiveTasks().values()
                ));
            })
            .post("/api/historical-data/download", ctx -> {
                Map<String, Object> body = ctx.bodyAsClass(Map.class);
                String pair = (String) body.get("pair");
                Integer year = body.get("year") != null ? ((Number) body.get("year")).intValue() : null;
                String tf = (String) body.getOrDefault("tf", "h1");
                Boolean syncMode = (Boolean) body.getOrDefault("syncMode", false);
                boolean accepted = historicalDataService.triggerDownload(pair, year, tf, syncMode);
                ctx.status(accepted ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT);
                ctx.json(Map.of("accepted", accepted));
            })
            .post("/api/historical-data/delete", ctx -> {
                Map<String, Object> body = ctx.bodyAsClass(Map.class);
                String pair = (String) body.get("pair");
                int year = ((Number) body.get("year")).intValue();
                String tf = (String) body.getOrDefault("tf", "h1");
                historicalDataService.deleteDataset(pair, year, tf);
                ctx.status(HttpStatus.OK);
                ctx.json(Map.of("success", true));
            })
            .post("/api/weekly-builder/plan", ctx -> respondWeeklyTrigger(ctx, weeklyBuilderService.triggerPlanAsync()))
            .post("/api/weekly-builder/compile", ctx -> respondWeeklyTrigger(ctx, weeklyBuilderService.triggerCompileAsync()))
            .post("/api/weekly-builder/deploy", ctx -> respondWeeklyTrigger(ctx, weeklyBuilderService.triggerDeployAsync()))
            .get("/control/summary", ctx -> ctx.json(summaryService.buildSummary()))
            .get("/api/control/summary", ctx -> ctx.json(summaryService.buildSummary()))
            .get("/api/broker-accounts", ctx -> ctx.json(Map.of(
                "accounts", runManager.brokerAccountRegistry().listMasked())))
            .post("/api/broker-accounts", ctx -> {
                try {
                    BrokerAccountRegistry.ConfigFile config = ctx.bodyAsClass(BrokerAccountRegistry.ConfigFile.class);
                    if (config.accounts() == null) {
                        ctx.status(HttpStatus.BAD_REQUEST);
                        ctx.json(Map.of("error", "accounts list is required"));
                        return;
                    }
                    java.util.List<BrokerAccountRegistry.AccountEntry> merged = new java.util.ArrayList<>();
                    for (BrokerAccountRegistry.AccountEntry incoming : config.accounts()) {
                        BrokerAccountRegistry.AccountEntry existing = runManager.brokerAccountRegistry().getRawAccount(incoming.id());
                        if (existing != null) {
                            String token = incoming.token();
                            if (token == null || token.isBlank() || token.startsWith("*")) {
                                token = existing.token();
                            }
                            String accountId = incoming.accountId();
                            if (accountId == null || accountId.isBlank() || accountId.startsWith("*")) {
                                accountId = existing.accountId();
                            }
                            merged.add(new BrokerAccountRegistry.AccountEntry(
                                incoming.id(),
                                incoming.provider(),
                                incoming.tokenEnv(),
                                incoming.accountIdEnv(),
                                incoming.restUrlEnv(),
                                incoming.defaultRestUrl(),
                                incoming.hostEnv(),
                                incoming.portEnv(),
                                incoming.clientIdEnv(),
                                incoming.defaultPortPaper(),
                                incoming.defaultPortLive(),
                                token,
                                accountId,
                                incoming.host() != null ? incoming.host() : existing.host(),
                                incoming.port() != null ? incoming.port() : existing.port()
                            ));
                        } else {
                            merged.add(incoming);
                        }
                    }
                    BrokerAccountRegistry.ConfigFile mergedConfig = new BrokerAccountRegistry.ConfigFile(merged);
                    java.nio.file.Path repoRoot = EventStoreConfig.findRepoRoot();
                    if (repoRoot != null) {
                        java.nio.file.Path file = repoRoot.resolve("data/runtime/broker-accounts.json");
                        java.nio.file.Files.createDirectories(file.getParent());
                        com.fasterxml.jackson.databind.ObjectMapper prettyMapper = new com.fasterxml.jackson.databind.ObjectMapper()
                            .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
                        prettyMapper.writeValue(file.toFile(), mergedConfig);
                    }
                    runManager.brokerAccountRegistry().updateAccounts(mergedConfig.accounts());
                    ctx.status(HttpStatus.OK);
                    ctx.json(Map.of("success", true));
                } catch (Exception e) {
                    ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
                    ctx.json(Map.of("error", e.getMessage() != null ? e.getMessage() : "Failed to update accounts"));
                }
            })
            .post("/api/broker-accounts/test", ctx -> {
                try {
                    BrokerAccountRegistry.AccountEntry entry = ctx.bodyAsClass(BrokerAccountRegistry.AccountEntry.class);
                    if (entry == null) {
                        ctx.status(HttpStatus.BAD_REQUEST);
                        ctx.json(Map.of("error", "Account config payload is required"));
                        return;
                    }
                    
                    BrokerAccountRegistry.AccountEntry existing = runManager.brokerAccountRegistry().getRawAccount(entry.id());
                    String token = entry.token();
                    String accountId = entry.accountId();
                    String host = entry.host();
                    Integer port = entry.port();

                    if (existing != null) {
                        if (token == null || token.isBlank() || token.startsWith("*")) {
                            token = existing.token();
                        }
                        if (accountId == null || accountId.isBlank() || accountId.startsWith("*")) {
                            accountId = existing.accountId();
                        }
                        if (host == null || host.isBlank() || "127.0.0.1".equals(host)) {
                            if (existing.host() != null && !existing.host().isBlank()) {
                                host = existing.host();
                            }
                        }
                        if (port == null || port == 0 || port == 7497) {
                            if (existing.port() != null) {
                                port = existing.port();
                            }
                        }
                    }

                    if (entry.isIbkr()) {
                        String finalHost = host != null && !host.isBlank() ? host : "127.0.0.1";
                        int finalPort = port != null ? port : entry.defaultPortPaper();
                        String finalAccount = accountId != null ? accountId : "";
                        int clientId = 999; // temporary test clientId
                        var ibkrConfig = new com.martinfou.trading.data.ibkr.IbkrConnectionConfig(finalHost, finalPort, clientId, finalAccount);
                        try (var ibkrBroker = BrokerProvider.ibkrBroker(ibkrConfig)) {
                            ibkrBroker.connect();
                            var state = ibkrBroker.getAccountState();
                            ctx.status(HttpStatus.OK);
                            ctx.json(Map.of("success", true, "balance", state.balance(), "currency", state.currency()));
                        }
                    } else {
                        String restUrl = entry.defaultRestUrl();
                        if (restUrl == null || restUrl.isBlank()) {
                            restUrl = existing != null ? existing.defaultRestUrl() : "https://api-fxpractice.oanda.com";
                        }
                        if (token == null || token.isBlank() || accountId == null || accountId.isBlank()) {
                            ctx.status(HttpStatus.BAD_REQUEST);
                            ctx.json(Map.of("error", "OANDA token and Account ID are required to test connection"));
                            return;
                        }
                        if (System.getProperty("trading.bridge.test") != null || "mock-token".equals(token) || (restUrl != null && restUrl.contains("mock"))) {
                            ctx.status(HttpStatus.OK);
                            ctx.json(Map.of("success", true, "balance", 100000.0, "currency", "USD"));
                            return;
                        }
                        if (!restUrl.endsWith("/v3/")) {
                            restUrl = restUrl.replaceAll("/$", "") + "/v3/";
                        }
                        var credentials = new com.martinfou.trading.broker.BrokerCredentials(entry.provider(), accountId, token, restUrl);
                        try (var oandaBroker = BrokerProvider.oandaBroker(credentials)) {
                            oandaBroker.connect();
                            var state = oandaBroker.getAccountState();
                            ctx.status(HttpStatus.OK);
                            ctx.json(Map.of("success", true, "balance", state.balance(), "currency", state.currency()));
                        }
                    }
                } catch (Exception e) {
                    ctx.status(HttpStatus.BAD_REQUEST);
                    ctx.json(Map.of("error", e.getMessage() != null ? e.getMessage() : "Connection test failed"));
                }
            })
            .get("/api/data/symbols", ctx -> respondDataJson(ctx, dataAvailability::listSymbols))
            .get("/api/data/availability/{symbol}", ctx -> {
                String symbol = ctx.pathParam("symbol");
                respondDataJson(ctx, () -> dataAvailability.availability(symbol));
            })
            .get("/api/strategies", ctx -> {
                List<Map<String, Object>> items = StrategyCatalog.entries().stream()
                    .map(e -> {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", e.id());
                        item.put("family", e.family().name());
                        item.put("defaultSymbol", e.defaultSymbol());
                        promoteService.deploymentStore().get(e.id())
                            .ifPresent(d -> {
                                item.put("deployedMode", d.mode().name());
                                item.put("executionLabel", d.executionLabel().name());
                                item.put("executionLabelMeta", ExecutionLabelCatalog.of(d.executionLabel()).toMap());
                                if (d.brokerAccountId() != null && !d.brokerAccountId().isBlank()) {
                                    item.put("brokerAccountId", d.brokerAccountId());
                                }
                            });
                        return item;
                    })
                    .toList();
                ctx.json(Map.of("strategies", items));
            })
            .get("/api/strategies/{id}/deployments", ctx -> {
                String strategyId = ctx.pathParam("id");
                if (!StrategyCatalog.contains(strategyId)) {
                    throw new NotFoundException("Unknown strategy: " + strategyId);
                }
                var deployment = promoteService.deploymentStore().get(strategyId);
                if (deployment.isEmpty()) {
                    ctx.json(Map.of("strategyId", strategyId, "deployment", null));
                } else {
                    ctx.json(Map.of("strategyId", strategyId, "deployment", deployment.get().toMap()));
                }
            })
            .get("/api/strategies/{id}/promote-readiness", ctx -> {
                String strategyId = ctx.pathParam("id");
                if (!StrategyCatalog.contains(strategyId)) {
                    throw new NotFoundException("Unknown strategy: " + strategyId);
                }
                PromoteReadinessService readiness = new PromoteReadinessService(runManager, promoteService);
                ctx.json(readiness.assess(strategyId));
            })
            .post("/api/strategies/{id}/promote", ctx -> {
                String strategyId = ctx.pathParam("id");
                PromoteService.PromoteRequest request = ctx.bodyAsClass(PromoteService.PromoteRequest.class);
                PromoteService.PromoteResponse response = promoteService.promote(strategyId, request);
                if (response.promoted()) {
                    ctx.json(response);
                } else {
                    ctx.status(HttpStatus.UNPROCESSABLE_CONTENT);
                    ctx.json(response);
                }
            })
            .get("/api/promote-gates/thresholds", ctx -> {
                ctx.json(promoteService.thresholds());
            })
            .post("/api/promote-gates/thresholds", ctx -> {
                PromoteGateThresholds request = ctx.bodyAsClass(PromoteGateThresholds.class);
                PromoteGateThresholds.saveDefault(request);
                promoteService.setThresholds(request);
                ctx.status(HttpStatus.ACCEPTED);
                ctx.json(promoteService.thresholds());
            })
            .post("/api/strategies/{id}/kill", ctx -> {
                String strategyId = ctx.pathParam("id");
                KillSwitchService.KillRequest request = ctx.bodyAsClass(KillSwitchService.KillRequest.class);
                KillSwitchService.KillResponse response = killSwitchService.kill(strategyId, request);
                ctx.status(HttpStatus.ACCEPTED);
                ctx.json(response);
            })
            .post("/api/runs", ctx -> {
                RunManager.StartRunRequest request = ctx.bodyAsClass(RunManager.StartRunRequest.class);
                String runId = runManager.startRun(request);
                ctx.status(HttpStatus.ACCEPTED);
                ctx.json(Map.of(
                    "runId", runId,
                    "status", RunRecord.Status.RUNNING.name()));
            })
            .get("/api/runs", ctx -> {
                List<Map<String, Object>> items = runManager.list(null).stream()
                    .map(r -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("runId", r.runId());
                        m.put("strategyId", r.strategyId());
                        m.put("symbol", r.symbol());
                        m.put("status", r.status().name());
                        m.put("completedAt", r.completedAt().map(Instant::toString).orElse(null));
                        return m;
                    })
                    .toList();
                ctx.json(Map.of("runs", items));
            })
            .get("/api/runs/{runId}", ctx -> {
                String runId = ctx.pathParam("runId");
                RunRecord record = runManager.getRun(runId)
                    .orElseThrow(() -> new NotFoundException("Run not found: " + runId));
                ctx.json(toRunJson(runManager, record));
            })
            .get("/api/runs/{runId}/export", ctx -> {
                String runId = ctx.pathParam("runId");
                RunRecord record = runManager.getRun(runId)
                    .orElseThrow(() -> new NotFoundException("Run not found: " + runId));
                var deployment = promoteService.deploymentStore().get(record.strategyId());
                String format = ctx.queryParam("format");
                if (format != null && format.equalsIgnoreCase("html")) {
                    ctx.contentType("text/html; charset=utf-8");
                    ctx.result(DueDiligenceHtmlExporter.exportHtml(record, runManager.eventStore(), deployment));
                } else {
                    ctx.contentType("application/x-ndjson");
                    ctx.result(EvidencePackExporter.exportJsonl(record, runManager.eventStore(), deployment));
                }
            })
            .get("/api/runs/{runId}/trades", ctx -> {
                String runId = ctx.pathParam("runId");
                RunRecord record = runManager.getRun(runId)
                    .orElseThrow(() -> new NotFoundException("Run not found: " + runId));
                List<?> trades;
                if (record.endedPayload().isPresent()) {
                    trades = (List<?>) record.endedPayload().get().get("trades");
                } else {
                    trades = reconstructTradesFromFills(runManager.eventStore().replayAll(runId));
                }
                ctx.json(Map.of("runId", runId, "trades", trades != null ? trades : List.of()));
            })
            .get("/api/runs/{runId}/equity-curve", ctx -> {
                String runId = ctx.pathParam("runId");
                RunRecord record = runManager.getRun(runId)
                    .orElseThrow(() -> new NotFoundException("Run not found: " + runId));
                List<?> curve;
                if (record.endedPayload().isPresent()) {
                    curve = (List<?>) record.endedPayload().get().get("equityCurveSample");
                } else {
                    List<Map<String, Object>> recTrades = reconstructTradesFromFills(runManager.eventStore().replayAll(runId));
                    double eq = record.configSnapshot().containsKey("capital")
                        ? ((Number) record.configSnapshot().get("capital")).doubleValue()
                        : 1000.0;
                    List<Double> sample = new ArrayList<>();
                    sample.add(eq);
                    for (var t : recTrades) {
                        eq += ((Number) t.get("pnl")).doubleValue();
                        sample.add(eq);
                    }
                    curve = sample;
                }
                ctx.json(Map.of("runId", runId, "equityCurve", curve != null ? curve : List.of()));
            })
            .get("/api/runs/{runId}/bars", ctx -> {
                String runId = ctx.pathParam("runId");
                RunRecord record = runManager.getRun(runId)
                    .orElseThrow(() -> new NotFoundException("Run not found: " + runId));
                RunConfigSnapshot config = RunConfigSnapshot.fromRecord(record);
                List<com.martinfou.trading.core.Bar> bars = RunManager.loadBars(config);
                List<Map<String, Object>> serializedBars = bars.stream()
                    .map(b -> {
                        Map<String, Object> bm = new LinkedHashMap<>();
                        bm.put("time", b.timestamp().toString());
                        bm.put("open", b.open());
                        bm.put("high", b.high());
                        bm.put("low", b.low());
                        bm.put("close", b.close());
                        bm.put("volume", b.volume());
                        return bm;
                    })
                    .toList();
                ctx.json(Map.of("runId", runId, "bars", serializedBars));
            })
            .get("/api/runs/{runId}/events", ctx -> {
                String runId = ctx.pathParam("runId");
                runManager.getRun(runId)
                    .orElseThrow(() -> new NotFoundException("Run not found: " + runId));

                long afterSequence = parseLongParam(ctx.queryParam("afterSequence"), 0L);
                int limit = (int) parseLongParam(ctx.queryParam("limit"), 100L);
                if (limit <= 0 || limit > 1000) {
                    throw new BadRequestException("limit must be between 1 and 1000");
                }

                List<StoredRunEvent> items = runManager.eventStore().queryWithSequence(runId, afterSequence, limit);
                long nextAfter = items.isEmpty() ? afterSequence : items.getLast().sequence();
                ctx.json(Map.of(
                    "runId", runId,
                    "afterSequence", afterSequence,
                    "nextAfterSequence", nextAfter,
                    "items", items.stream().map(ControlPlaneServer::toEventItem).toList()));
            })
            .ws("/ws/runs/{runId}", ws -> {
                ws.onConnect(ctx -> onWebSocketConnect(runManager, eventHub, ctx));
                ws.onClose(ctx -> onWebSocketClose(eventHub, ctx));
            })
            .exception(NotFoundException.class, (e, ctx) -> {
                ctx.status(HttpStatus.NOT_FOUND);
                ctx.json(Map.of("error", e.getMessage()));
            })
            .exception(BadRequestException.class, (e, ctx) -> {
                ctx.status(HttpStatus.BAD_REQUEST);
                ctx.json(Map.of("error", e.getMessage()));
            })
            .exception(IllegalArgumentException.class, (e, ctx) -> {
                ctx.status(HttpStatus.BAD_REQUEST);
                ctx.json(Map.of("error", e.getMessage()));
            })
            .exception(Exception.class, (e, ctx) -> {
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
                ctx.json(Map.of("error", e.getMessage() != null ? e.getMessage() : "Internal error"));
            });
    }

    private static void onWebSocketConnect(RunManager runManager, RunEventHub hub, WsConnectContext ctx) {
        String runId = ctx.pathParam("runId");
        if (runManager.getRun(runId).isEmpty()) {
            ctx.closeSession(4404, "Run not found");
            return;
        }

        for (StoredRunEvent stored : runManager.eventStore().queryWithSequence(runId, 0, 1000)) {
            ctx.send(RunEventMessages.toJson(stored));
        }

        Consumer<String> listener = ctx::send;
        AutoCloseable subscription = hub.subscribe(runId, listener);
        ctx.attribute("subscription", subscription);
        ctx.attribute("listener", listener);
        ctx.attribute("runId", runId);
    }

    private static void onWebSocketClose(RunEventHub hub, WsCloseContext ctx) {
        String runId = ctx.attribute("runId");
        Consumer<String> listener = ctx.attribute("listener");
        if (runId != null && listener != null) {
            hub.unsubscribe(runId, listener);
        }
        AutoCloseable subscription = ctx.attribute("subscription");
        if (subscription != null) {
            try {
                subscription.close();
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    private static Map<String, Object> toRunJson(RunManager runManager, RunRecord record) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("runId", record.runId());
        json.put("strategyId", record.strategyId());
        json.put("symbol", record.symbol());
        json.put("mode", record.mode().name());
        json.put("executionLabel", record.configSnapshot().containsKey("resolvedExecutionLabel")
            ? String.valueOf(record.configSnapshot().get("resolvedExecutionLabel"))
            : ExecutionLabel.forRunMode(record.mode()).name());
        ExecutionLabel label = ControlSummaryService.executionLabel(record);
        json.put("executionLabelMeta", ExecutionLabelCatalog.of(label).toMap());
        json.put("status", record.status().name());
        json.put("startedAt", record.startedAt().toString());
        json.put("configSnapshot", record.configSnapshot());
        json.put("configHash", record.configHash());
        record.completedAt().ifPresent(t -> json.put("completedAt", t.toString()));
        record.errorMessage().ifPresent(m -> json.put("error", m));
        record.endedPayload().ifPresent(p -> json.put("result", p));
        record.lastEventAt().ifPresent(t -> json.put("lastEventAt", t.toString()));

        long eventCount = runManager.eventStore().count(record.runId());
        json.put("eventCount", eventCount);

        EventGapDetector.Result gaps = EventGapDetector.analyze(record.runId(), runManager.eventStore());
        json.put("maxEventSequence", gaps.maxSequence());
        json.put("eventLogComplete", gaps.complete());
        if (!gaps.gaps().isEmpty()) {
            json.put("sequenceGaps", gaps.gaps().stream()
                .map(g -> Map.of("from", g.fromSequence(), "to", g.toSequence()))
                .toList());
        }

        var positions = JournalPositions.fromFills(runManager.eventStore().replayAll(record.runId())).values().stream()
            .map(pos -> Map.of(
                "symbol", pos.symbol(),
                "side", pos.side().name(),
                "quantity", pos.quantity()
            ))
            .toList();
        json.put("positions", positions);

        return json;
    }

    private static List<Map<String, Object>> reconstructTradesFromFills(List<RunEvent> events) {
        List<Map<String, Object>> trades = new ArrayList<>();
        Map<String, List<Map<String, Object>>> openFills = new HashMap<>();

        for (RunEvent event : events) {
            if (event.type() != RunEventType.FILL) {
                continue;
            }
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

                double pnl;
                if (entrySide.equals("BUY")) {
                    pnl = (exitPrice - entryPrice) * matchQty;
                } else {
                    pnl = (entryPrice - exitPrice) * matchQty;
                }

                Map<String, Object> trade = new LinkedHashMap<>();
                trade.put("symbol", symbol);
                trade.put("side", entrySide);
                trade.put("entryPrice", entryPrice);
                trade.put("exitPrice", exitPrice);
                trade.put("quantity", matchQty);
                trade.put("entryTime", ((Instant) first.get("timestamp")).toString());
                trade.put("exitTime", timestamp.toString());
                trade.put("pnl", pnl);
                trades.add(trade);

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
        return trades;
    }

    private static Map<String, Object> toEventItem(StoredRunEvent stored) {
        Map<String, Object> item = new HashMap<>();
        item.put("sequence", stored.sequence());
        item.put("event", stored.event());
        return item;
    }

    private static void respondWeeklyTrigger(Context ctx, WeeklyBuilderService.TriggerResponse response) {
        if (response.accepted()) {
            ctx.status(HttpStatus.ACCEPTED);
        } else {
            ctx.status(HttpStatus.CONFLICT);
        }
        ctx.json(Map.of(
            "accepted", response.accepted(),
            "message", response.message()));
    }

    private static void respondDataJson(Context ctx, DataJsonSupplier supplier) {
        try {
            ctx.json(supplier.get());
        } catch (IOException e) {
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
            ctx.json(Map.of("error", e.getMessage() != null ? e.getMessage() : "IO error"));
        } catch (LinkageError e) {
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
            ctx.json(Map.of(
                "error", e.getClass().getSimpleName() + ": "
                    + (e.getMessage() != null ? e.getMessage() : "classpath issue"),
                "hint", "Rebuild: mvn install -pl trading-runtime -am && restart ControlPlaneMain"));
        }
    }

    @FunctionalInterface
    private interface DataJsonSupplier {
        Map<String, Object> get() throws IOException;
    }

    private static long parseLongParam(String value, long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("Invalid numeric parameter: " + value);
        }
    }

    static final class NotFoundException extends RuntimeException {
        NotFoundException(String message) {
            super(message);
        }
    }

    static final class BadRequestException extends RuntimeException {
        BadRequestException(String message) {
            super(message);
        }
    }
}
