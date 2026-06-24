package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventType;
import com.martinfou.trading.broker.Broker;
import com.martinfou.trading.broker.BrokerEvent;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Worker-local broker execution for PAPER_OANDA and LIVE (Story 16.3–16.5 / PS-GR2, ADR-13-07).
 * Orders route through {@link Broker} on the worker; the control plane only persists events.
 */
final class BrokerRunExecutor {

    private BrokerRunExecutor() {}

    static BacktestResult execute(
        String runId,
        RunConfigSnapshot config,
        List<Bar> bars,
        double initialCapital,
        Strategy strategy,
        Broker broker,
        EventStore eventStore,
        KillSwitchRegistry killSwitch
    ) {
        return execute(runId, config, bars, initialCapital, strategy, broker, eventStore, killSwitch, null);
    }

    static BacktestResult execute(
        String runId,
        RunConfigSnapshot config,
        List<Bar> bars,
        double initialCapital,
        Strategy strategy,
        Broker broker,
        EventStore eventStore,
        KillSwitchRegistry killSwitch,
        RiskEngine riskEngine
    ) {
        return execute(runId, config, bars, initialCapital, strategy, broker, eventStore, killSwitch, riskEngine, null, java.util.Collections::emptyList);
    }

    static BacktestResult execute(
        String runId,
        RunConfigSnapshot config,
        List<Bar> bars,
        double initialCapital,
        Strategy strategy,
        Broker broker,
        EventStore eventStore,
        KillSwitchRegistry killSwitch,
        RiskEngine riskEngine,
        RunRiskContext riskContext
    ) {
        return execute(runId, config, bars, initialCapital, strategy, broker, eventStore, killSwitch, riskEngine, riskContext, java.util.Collections::emptyList);
    }

    static BacktestResult execute(
        String runId,
        RunConfigSnapshot config,
        List<Bar> bars,
        double initialCapital,
        Strategy strategy,
        Broker broker,
        EventStore eventStore,
        KillSwitchRegistry killSwitch,
        RiskEngine riskEngine,
        RunRiskContext riskContext,
        java.util.function.Supplier<List<String>> activeSymbolsSupplier
    ) {
        RunMode runMode = RunMode.valueOf(config.mode().toUpperCase());
        ExecutionLabel label = config.resolvedExecutionLabel();
        RiskEngine risk = riskEngine != null ? riskEngine : new RiskEngine();
        emitStarted(runId, config, bars, initialCapital, runMode, label, eventStore);

        broker.connect();
        broker.addEventListener(event -> persistBrokerEvent(runId, config, runMode, event, eventStore));

        int submitted = 0;
        int filled = 0;
        int rejected = 0;
        int killBlocked = 0;
        int riskBlocked = 0;
        int dailyDdBlocked = 0;
        boolean dailyDdPaused = false;

        strategy.reset();
        ReconciliationService reconciliation = new ReconciliationService();
        int barIndex = 0;
        for (Bar bar : bars) {
            HeartbeatEvents.emitBarHeartbeat(runId, config, runMode, eventStore, bar, barIndex++);
            if (riskContext != null && ReconciliationService.isBrokerBacked(label)) {
                dailyDdPaused = evaluateDailyDrawdown(
                    runId, config, runMode, label, broker, eventStore, riskContext, bar, dailyDdPaused);
            }
            strategy.onBar(bar);
            for (Order pending : strategy.getPendingOrders()) {
                if (dailyDdPaused) {
                    dailyDdBlocked++;
                    Order order = pending.price() <= 0.0
                        ? new Order(pending.symbol(), pending.side(), pending.type(), pending.quantity(), bar.open())
                        : pending;
                    persistDailyDdReject(runId, config, runMode, order, eventStore);
                    continue;
                }
                if (killSwitch != null && killSwitch.isKilled(config.strategyId())) {
                    killBlocked++;
                    Order order = pending.price() <= 0.0
                        ? new Order(pending.symbol(), pending.side(), pending.type(), pending.quantity(), bar.open())
                        : pending;
                    persistKillReject(runId, config, runMode, order, eventStore);
                    continue;
                }
                Order order = pending.price() <= 0.0
                    ? new Order(pending.symbol(), pending.side(), pending.type(), pending.quantity(), bar.open())
                    : pending;

                RiskCheckResult riskCheck = risk.checkPreTrade(order, broker.getPositions());
                if (!riskCheck.passed()) {
                    riskBlocked++;
                    persistRiskReject(runId, config, runMode, order, riskCheck, eventStore);
                    continue;
                }

                submitted++;
                var result = broker.submitOrder(order);
                if (result.accepted()) {
                    filled++;
                } else {
                    rejected++;
                }
            }
            reconciliation.reconcile(runId, config, broker, eventStore, activeSymbolsSupplier);
        }

        var account = broker.getAccountState();
        double finalEquity = account.equity() > 0 ? account.equity() : initialCapital;
        double returnPct = initialCapital > 0 ? (finalEquity - initialCapital) / initialCapital * 100.0 : 0.0;

        var endedPayload = new LinkedHashMap<String, Object>();
        endedPayload.put("totalTrades", filled);
        endedPayload.put("totalReturnPct", returnPct);
        endedPayload.put("finalEquity", finalEquity);
        endedPayload.put("ordersSubmitted", submitted);
        endedPayload.put("ordersRejected", rejected);
        endedPayload.put("ordersKillBlocked", killBlocked);
        endedPayload.put("ordersRiskBlocked", riskBlocked);
        endedPayload.put("ordersDailyDdBlocked", dailyDdBlocked);
        endedPayload.put("dailyDdBreached", dailyDdPaused);
        if (riskContext != null) {
            endedPayload.put("maxDailyDrawdownPct", riskContext.maxDailyDrawdownPct);
            endedPayload.put("dailyDrawdownPct", riskContext.tracker.drawdownPct(broker.getAccountState().equity()));
        }
        endedPayload.put("executionLabel", label.name());

        RunEvent ended = RunEvent.ended(runId, config.strategyId(), config.symbol(), runMode, Map.copyOf(endedPayload));
        eventStore.append(runId, ended);

        return BacktestResult.builder()
            .initialCapital(initialCapital)
            .finalEquity(finalEquity)
            .totalTrades(filled)
            .totalReturnPct(returnPct)
            .build();
    }

    private static void emitStarted(
        String runId,
        RunConfigSnapshot config,
        List<Bar> bars,
        double initialCapital,
        RunMode runMode,
        ExecutionLabel label,
        EventStore eventStore
    ) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("barCount", bars.size());
        payload.put("initialCapital", initialCapital);
        payload.put("executionLabel", label.name());
        if (config.brokerAccountId() != null && !config.brokerAccountId().isBlank()) {
            payload.put("brokerAccountId", config.resolvedBrokerAccountId());
        }
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

    private static void persistBrokerEvent(
        String runId,
        RunConfigSnapshot config,
        RunMode runMode,
        BrokerEvent brokerEvent,
        EventStore eventStore
    ) {
        RunEventType type = switch (brokerEvent.type()) {
            case ORDER_SUBMITTED -> RunEventType.ORDER_SUBMITTED;
            case FILL, PARTIAL_CLOSE, FINANCING -> RunEventType.FILL;
            case REJECT -> RunEventType.REJECT;
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

    private static void persistKillReject(
        String runId,
        RunConfigSnapshot config,
        RunMode runMode,
        Order order,
        EventStore eventStore
    ) {
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

    private static boolean evaluateDailyDrawdown(
        String runId,
        RunConfigSnapshot config,
        RunMode runMode,
        ExecutionLabel label,
        Broker broker,
        EventStore eventStore,
        RunRiskContext riskContext,
        Bar bar,
        boolean alreadyPaused
    ) {
        double equity = broker.getAccountState().equity();
        RiskCheckResult check = riskContext.riskEngine.checkDailyDrawdown(
            riskContext.tracker, equity, bar.timestamp());
        double drawdownPct = riskContext.tracker.drawdownPct(equity);
        boolean breached = alreadyPaused || !check.passed();
        if (riskContext.metricsSink != null) {
            riskContext.metricsSink.accept(new DailyDrawdownMetrics(
                drawdownPct,
                riskContext.maxDailyDrawdownPct,
                breached));
        }
        if (!check.passed() && !riskContext.breachHandled) {
            riskContext.breachHandled = true;
            riskContext.dailyDdPaused = true;
            persistDailyDdOperatorAction(runId, config, runMode, check, eventStore);
            if (riskContext.breachHandler != null) {
                riskContext.breachHandler.onBreach(runId, config, runMode, check);
            }
            return true;
        }
        return breached;
    }

    private static void persistDailyDdOperatorAction(
        String runId,
        RunConfigSnapshot config,
        RunMode runMode,
        RiskCheckResult check,
        EventStore eventStore
    ) {
        RunEvent event = RunEvent.operatorAction(
            runId,
            config.strategyId(),
            config.symbol(),
            runMode,
            "DAILY_DD_BREACH",
            "RISK_ENGINE",
            check.message(),
            java.time.Instant.now());
        eventStore.append(runId, event);
    }

    private static void persistDailyDdReject(
        String runId,
        RunConfigSnapshot config,
        RunMode runMode,
        Order order,
        EventStore eventStore
    ) {
        BrokerEvent reject = BrokerEvent.reject(order, "Daily drawdown limit breached");
        var payload = new LinkedHashMap<String, Object>();
        payload.putAll(reject.toPayload());
        payload.put("rejectSource", "RISK_ENGINE");
        payload.put("limit", "max_daily_drawdown_pct");
        RunEvent event = new RunEvent(
            RunEvent.SCHEMA_VERSION,
            RunEventType.REJECT,
            reject.timestamp(),
            runId,
            config.strategyId(),
            config.symbol(),
            runMode.name(),
            Map.copyOf(payload));
        eventStore.append(runId, event);
    }

    private static void persistRiskReject(
        String runId,
        RunConfigSnapshot config,
        RunMode runMode,
        Order order,
        RiskCheckResult riskCheck,
        EventStore eventStore
    ) {
        var payload = new LinkedHashMap<String, Object>();
        payload.putAll(BrokerEvent.reject(order, riskCheck.message()).toPayload());
        payload.put("rejectSource", "RISK_ENGINE");
        payload.put("limit", riskCheck.limitName());
        if (riskCheck.threshold() != null) {
            payload.put("threshold", riskCheck.threshold());
        }
        if (riskCheck.actual() != null) {
            payload.put("actual", riskCheck.actual());
        }

        RunEvent event = new RunEvent(
            RunEvent.SCHEMA_VERSION,
            RunEventType.REJECT,
            java.time.Instant.now(),
            runId,
            config.strategyId(),
            config.symbol(),
            runMode.name(),
            Map.copyOf(payload));
        eventStore.append(runId, event);
    }
}
