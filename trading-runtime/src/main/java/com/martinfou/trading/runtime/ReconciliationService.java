package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventType;
import com.martinfou.trading.broker.Broker;
import com.martinfou.trading.core.Position;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares broker-reported positions to journal-derived state (Story 16.7 / PS-GR8).
 * Skipped for {@link ExecutionLabel#PAPER_STUB} and other non-broker labels.
 */
public final class ReconciliationService {

    private static final double QUANTITY_TOLERANCE = 1e-6;

    public record Divergence(
        String symbol,
        String side,
        double brokerQuantity,
        double journalQuantity,
        String reason
    ) {}

    public record ReconcileResult(
        boolean skipped,
        boolean aligned,
        List<Divergence> divergences,
        Optional<RunEvent> alertEvent
    ) {
        public static ReconcileResult ofSkipped() {
            return new ReconcileResult(true, true, List.of(), Optional.empty());
        }

        public static ReconcileResult ofAligned() {
            return new ReconcileResult(false, true, List.of(), Optional.empty());
        }

        public static ReconcileResult ofDiverged(List<Divergence> divergences, RunEvent alert) {
            return new ReconcileResult(false, false, List.copyOf(divergences), Optional.of(alert));
        }
    }

    private final Clock clock;

    public ReconciliationService() {
        this(Clock.systemUTC());
    }

    ReconciliationService(Clock clock) {
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public ReconcileResult reconcile(
        String runId,
        RunConfigSnapshot config,
        Broker broker,
        EventStore eventStore
    ) {
        return reconcile(runId, config, broker, eventStore, null);
    }

    public ReconcileResult reconcile(
        String runId,
        RunConfigSnapshot config,
        Broker broker,
        EventStore eventStore,
        java.util.function.Supplier<List<String>> activeSymbolsSupplier
    ) {
        ExecutionLabel label = config.resolvedExecutionLabel();
        if (!isBrokerBacked(label)) {
            return ReconcileResult.ofSkipped();
        }

        Map<String, JournalPositions.Snapshot> journal = JournalPositions.fromFills(eventStore.replayAll(runId));

        java.util.Set<String> runOrderIds = new java.util.HashSet<>();
        for (var e : eventStore.replayAll(runId)) {
            if ((e.type() == RunEventType.FILL || e.type() == RunEventType.ORDER_SUBMITTED) && e.payload() != null && e.payload().containsKey("orderId")) {
                runOrderIds.add(String.valueOf(e.payload().get("orderId")));
            }
        }

        List<Position> filteredBrokerPositions = new ArrayList<>();
        for (var pos : broker.getPositions()) {
            boolean matchesSymbol = pos.symbol().equalsIgnoreCase(config.symbol()) || pos.symbol().replace("/", "_").replace("-", "_").equalsIgnoreCase(config.symbol().replace("/", "_").replace("-", "_"));
            if (matchesSymbol) {
                if (pos.clientTag() != null && !pos.clientTag().isBlank()) {
                    if (runOrderIds.contains(pos.clientTag())) {
                        filteredBrokerPositions.add(pos);
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
                        filteredBrokerPositions.add(pos);
                    }
                }
            }
        }

        Map<String, JournalPositions.Snapshot> brokerPositions = JournalPositions.fromBroker(filteredBrokerPositions);
        List<Divergence> divergences = compare(journal, brokerPositions);

        if (divergences.isEmpty()) {
            return ReconcileResult.ofAligned();
        }

        RunMode runMode = RunMode.valueOf(config.mode().toUpperCase());
        Instant timestamp = Instant.now(clock);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("executionLabel", label.name());
        payload.put("divergenceCount", divergences.size());
        payload.put("divergences", divergences.stream().map(ReconciliationService::divergenceToMap).toList());

        RunEvent alert = RunEvent.reconciliationAlert(
            runId,
            config.strategyId(),
            config.symbol(),
            runMode,
            Map.copyOf(payload),
            timestamp);
        eventStore.append(runId, alert);
        return ReconcileResult.ofDiverged(divergences, alert);
    }

    static boolean isBrokerBacked(ExecutionLabel label) {
        return label.isBrokerBacked();
    }

    private static List<Divergence> compare(
        Map<String, JournalPositions.Snapshot> journal,
        Map<String, JournalPositions.Snapshot> broker
    ) {
        List<Divergence> divergences = new ArrayList<>();
        Set<String> keys = new TreeSet<>();
        keys.addAll(journal.keySet());
        keys.addAll(broker.keySet());

        for (String key : keys) {
            JournalPositions.Snapshot journalPos = journal.get(key);
            JournalPositions.Snapshot brokerPos = broker.get(key);
            if (journalPos == null && brokerPos != null) {
                divergences.add(new Divergence(
                    brokerPos.symbol(),
                    brokerPos.side().name(),
                    brokerPos.quantity(),
                    0.0,
                    "position missing in journal (possible ghost fill at broker)"));
            } else if (brokerPos == null && journalPos != null) {
                divergences.add(new Divergence(
                    journalPos.symbol(),
                    journalPos.side().name(),
                    0.0,
                    journalPos.quantity(),
                    "position missing at broker"));
            } else if (journalPos != null && brokerPos != null
                && Math.abs(journalPos.quantity() - brokerPos.quantity()) > QUANTITY_TOLERANCE) {
                divergences.add(new Divergence(
                    journalPos.symbol(),
                    journalPos.side().name(),
                    brokerPos.quantity(),
                    journalPos.quantity(),
                    "quantity mismatch"));
            }
        }
        return divergences;
    }

    private static Map<String, Object> divergenceToMap(Divergence divergence) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("symbol", divergence.symbol());
        map.put("side", divergence.side());
        map.put("brokerQuantity", divergence.brokerQuantity());
        map.put("journalQuantity", divergence.journalQuantity());
        map.put("reason", divergence.reason());
        return map;
    }
}
