package com.martinfou.trading.runtime;

import com.martinfou.trading.strategies.StrategyCatalog;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Javalin HTTP control plane — health, strategies, runs, event replay, and WebSocket stream.
 */
public final class ControlPlaneServer implements AutoCloseable {

    public static final String VERSION = "1.0.0-SNAPSHOT";

    private final RunManager runManager;
    private final RunEventHub eventHub;
    private final Javalin app;

    public ControlPlaneServer(RunManager runManager, RunEventHub eventHub, int port) {
        if (runManager == null) {
            throw new IllegalArgumentException("runManager must not be null");
        }
        if (eventHub == null) {
            throw new IllegalArgumentException("eventHub must not be null");
        }
        this.runManager = runManager;
        this.eventHub = eventHub;
        this.app = createApp(runManager, eventHub);
        app.start(port);
    }

    public int port() {
        return app.port();
    }

    @Override
    public void close() {
        app.stop();
    }

    private static Javalin createApp(RunManager runManager, RunEventHub eventHub) {
        return Javalin.create(config -> config.showJavalinBanner = false)
            .get("/api/health", ctx -> ctx.json(Map.of(
                "status", "ok",
                "version", VERSION)))
            .get("/api/strategies", ctx -> {
                List<Map<String, String>> items = StrategyCatalog.entries().stream()
                    .map(e -> Map.of(
                        "id", e.id(),
                        "family", e.family().name(),
                        "defaultSymbol", e.defaultSymbol()))
                    .toList();
                ctx.json(Map.of("strategies", items));
            })
            .post("/api/runs", ctx -> {
                RunManager.StartRunRequest request = ctx.bodyAsClass(RunManager.StartRunRequest.class);
                String runId = runManager.startRun(request);
                ctx.status(HttpStatus.ACCEPTED);
                ctx.json(Map.of(
                    "runId", runId,
                    "status", RunRecord.Status.RUNNING.name()));
            })
            .get("/api/runs/{runId}", ctx -> {
                String runId = ctx.pathParam("runId");
                RunRecord record = runManager.getRun(runId)
                    .orElseThrow(() -> new NotFoundException("Run not found: " + runId));
                ctx.json(toRunJson(runManager, record));
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
        json.put("status", record.status().name());
        json.put("startedAt", record.startedAt().toString());
        record.completedAt().ifPresent(t -> json.put("completedAt", t.toString()));
        record.errorMessage().ifPresent(m -> json.put("error", m));
        record.endedPayload().ifPresent(p -> json.put("result", p));
        json.put("eventCount", runManager.eventStore().count(record.runId()));
        return json;
    }

    private static Map<String, Object> toEventItem(StoredRunEvent stored) {
        Map<String, Object> item = new HashMap<>();
        item.put("sequence", stored.sequence());
        item.put("event", stored.event());
        return item;
    }

    private static long parseLongParam(String value, long defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Long.parseLong(value);
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
