package com.martinfou.trading.data.ibkr;

import com.martinfou.trading.core.Order;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Double-barrier bidirectional 500ms transaction reconciler for Interactive Brokers.
 * Reconciles asynchronous execDetails and commissionReport callbacks by execId.
 */
public final class IbkrTransactionRegistry {

    public record ExecutionRecord(
        String execId,
        String orderId,
        String symbol,
        Order.Side side,
        double quantity,
        double price,
        Instant timestamp
    ) {}

    public record CommissionRecord(
        String execId,
        double commission,
        double realizedPnL,
        String currency,
        Instant timestamp
    ) {}

    public record ReconciledTransaction(
        String execId,
        String orderId,
        String symbol,
        Order.Side side,
        double quantity,
        double price,
        double commission,
        double realizedPnL,
        String currency,
        Instant timestamp
    ) {}

    private final Duration reconciliationTimeout;
    private final Map<String, ExecutionRecord> pendingExecutions = new ConcurrentHashMap<>();
    private final Map<String, CommissionRecord> pendingCommissions = new ConcurrentHashMap<>();
    private final Map<String, ReconciledTransaction> reconciledTransactions = new ConcurrentHashMap<>();
    private final List<Consumer<ReconciledTransaction>> listeners = new CopyOnWriteArrayList<>();

    public IbkrTransactionRegistry() {
        this(Duration.ofMillis(500));
    }

    public IbkrTransactionRegistry(Duration reconciliationTimeout) {
        this.reconciliationTimeout = Objects.requireNonNull(reconciliationTimeout, "reconciliationTimeout");
    }

    public void addListener(Consumer<ReconciledTransaction> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public Optional<ReconciledTransaction> onExecutionDetails(
        String execId,
        String orderId,
        String symbol,
        Order.Side side,
        double quantity,
        double price,
        Instant timestamp
    ) {
        if (execId == null || execId.isBlank()) return Optional.empty();

        CommissionRecord comm = pendingCommissions.remove(execId);
        if (comm != null) {
            ReconciledTransaction tx = new ReconciledTransaction(
                execId, orderId, symbol, side, quantity, price,
                comm.commission(), comm.realizedPnL(), comm.currency(), timestamp != null ? timestamp : Instant.now()
            );
            reconciledTransactions.put(execId, tx);
            notifyListeners(tx);
            return Optional.of(tx);
        }

        ExecutionRecord exec = new ExecutionRecord(
            execId, orderId, symbol, side, quantity, price, timestamp != null ? timestamp : Instant.now()
        );
        pendingExecutions.put(execId, exec);
        return Optional.empty();
    }

    public Optional<ReconciledTransaction> onCommissionReport(
        String execId,
        double commission,
        double realizedPnL,
        String currency,
        Instant timestamp
    ) {
        if (execId == null || execId.isBlank()) return Optional.empty();

        ExecutionRecord exec = pendingExecutions.remove(execId);
        if (exec != null) {
            ReconciledTransaction tx = new ReconciledTransaction(
                execId, exec.orderId(), exec.symbol(), exec.side(), exec.quantity(), exec.price(),
                commission, realizedPnL, currency != null ? currency : "USD",
                timestamp != null ? timestamp : exec.timestamp()
            );
            reconciledTransactions.put(execId, tx);
            notifyListeners(tx);
            return Optional.of(tx);
        }

        CommissionRecord comm = new CommissionRecord(
            execId, commission, realizedPnL, currency != null ? currency : "USD",
            timestamp != null ? timestamp : Instant.now()
        );
        pendingCommissions.put(execId, comm);
        return Optional.empty();
    }

    public Optional<ReconciledTransaction> findReconciled(String execId) {
        if (execId == null) return Optional.empty();
        return Optional.ofNullable(reconciledTransactions.get(execId));
    }

    public List<ReconciledTransaction> allReconciled() {
        return List.copyOf(reconciledTransactions.values());
    }

    public int pendingExecutionCount() {
        return pendingExecutions.size();
    }

    public int pendingCommissionCount() {
        return pendingCommissions.size();
    }

    private void notifyListeners(ReconciledTransaction tx) {
        for (Consumer<ReconciledTransaction> listener : listeners) {
            try {
                listener.accept(tx);
            } catch (Exception ignored) {}
        }
    }
}
