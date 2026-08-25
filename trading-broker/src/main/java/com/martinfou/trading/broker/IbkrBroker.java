package com.martinfou.trading.broker;

import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Position;
import com.martinfou.trading.data.ibkr.IbkrAccountSnapshot;
import com.martinfou.trading.data.ibkr.IbkrGatewayClient;
import com.martinfou.trading.data.ibkr.IbkrMarketOrderResult;
import com.martinfou.trading.data.ibkr.IbkrPositionSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * {@link Broker} adapter for IBKR via TWS / IB Gateway (Story 16.10).
 */
public final class IbkrBroker implements Broker {

    private static final Logger log = LoggerFactory.getLogger(IbkrBroker.class);

    private final IbkrGatewayClient client;
    private final List<Consumer<BrokerEvent>> listeners = new CopyOnWriteArrayList<>();
    private final java.util.Map<String, Order> pendingOrdersByClientTag = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Order> pendingOrdersByBrokerId = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean connected;

    public IbkrBroker(IbkrGatewayClient client) {
        if (client == null) {
            throw new IllegalArgumentException("client is required");
        }
        this.client = client;
        this.client.addExecutionListener(this::onExecution);
    }

    /** Handles asynchronous execution callbacks from the IBKR client (fill / reject). */
    private void onExecution(com.martinfou.trading.data.ibkr.IbkrExecution execution) {
        Order order = execution.clientTag() != null
            ? pendingOrdersByClientTag.get(execution.clientTag())
            : null;
        if (order == null && execution.orderId() != null) {
            order = pendingOrdersByBrokerId.get(execution.orderId());
        }
        if (execution.isFill()) {
            if (order != null) {
                log.info("IBKR fill {} {} qty={} @ {} (execId={})",
                    execution.symbol(), execution.side(), execution.quantity(), execution.fillPrice(), execution.execId());
                emit(BrokerEvent.fill(order.fill(), execution.fillPrice()));
            } else {
                log.warn("IBKR fill for unknown order {} (execId={}) — reconciliation needed",
                    execution.orderId(), execution.execId());
            }
        } else if (execution.isRejected()) {
            String reason = execution.errorMessage() != null ? execution.errorMessage() : "IBKR order rejected";
            if (order != null) {
                emit(BrokerEvent.reject(order, reason));
            } else {
                log.warn("IBKR reject for unknown order {}: {}", execution.orderId(), reason);
            }
        }
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void connect() {
        client.connect();
        client.fetchAccountSummary();
        connected = true;
    }

    @Override
    public void disconnect() {
        client.disconnect();
        connected = false;
    }

    @Override
    public void reconnect() {
        disconnect();
        connect();
    }

    @Override
    public OrderSubmitResult submitOrder(Order order) {
        emit(BrokerEvent.submitted(order));

        if (!connected) {
            emit(BrokerEvent.reject(order, "Broker not connected"));
            return OrderSubmitResult.rejected("Broker not connected");
        }
        if (order.type() != Order.Type.MARKET) {
            emit(BrokerEvent.reject(order, "IbkrBroker supports MARKET orders only"));
            return OrderSubmitResult.rejected("IbkrBroker supports MARKET orders only");
        }

        String symbol = toIbkrSymbol(order.symbol());
        // Register the order BEFORE placing it: IBKR may deliver the execution callback
        // (via the stub or a fast venue) synchronously inside placeMarketOrder. Matching by
        // clientTag (order.id()) lets onExecution resolve it regardless of arrival order.
        pendingOrdersByClientTag.put(order.id(), order);
        IbkrMarketOrderResult result = client.placeMarketOrder(
            symbol, order.quantity(), order.side(), order.id());

        if (!result.success()) {
            String reason = result.errorMessage() != null ? result.errorMessage() : "IBKR order rejected";
            log.warn("IBKR reject {} {}: {}", symbol, order.quantity(), reason);
            pendingOrdersByClientTag.remove(order.id());
            emit(BrokerEvent.reject(order, reason));
            return OrderSubmitResult.rejected(reason);
        }

        // IMPORTANT: IBKR market orders do NOT fill synchronously. The broker only *accepted*
        // the order; the fill (and its price) arrives later via execDetails / orderStatus
        // callbacks. We must NOT call order.fill() or emit a FILL event here — doing so would
        // fabricate a fill price and corrupt position/equity accounting.
        if (result.orderId() != null) {
            pendingOrdersByBrokerId.put(result.orderId(), order);
        }
        log.info("IBKR order accepted (awaiting async execution) {} {} qty={} orderId={}",
            symbol, order.side(), order.quantity(), result.orderId());
        return OrderSubmitResult.filled(result.orderId());
    }

    @Override
    public OrderSubmitResult cancelOrder(String brokerOrderId) {
        // The current IBKR gateway client has no cancel/cancelAll capability. Returning
        // "filled" here (the previous behaviour) silently claimed a cancel had completed
        // when nothing happened on the venue — a kill-switch / liquidation hazard.
        // Honest rejection: the caller (kill switch / flatten) must treat this as a failure
        // and escalate instead of assuming the order was cancelled.
        String reason = "IBKR order cancellation not implemented in current gateway client";
        log.warn("IBKR cancelOrder requested for {} but unsupported: {}", brokerOrderId, reason);
        return OrderSubmitResult.rejected(reason);
    }

    @Override
    public List<Position> getPositions() {
        List<Position> out = new ArrayList<>();
        for (IbkrPositionSnapshot row : client.fetchOpenPositions()) {
            out.add(new Position(row.symbol(), row.side(), row.quantity(), row.averagePrice()));
        }
        return List.copyOf(out);
    }

    @Override
    public AccountState getAccountState() {
        IbkrAccountSnapshot account = client.fetchAccountSummary();
        return new AccountState(account.balance(), account.equity(), account.currency());
    }

    @Override
    public void addEventListener(Consumer<BrokerEvent> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    static String toIbkrSymbol(String symbol) {
        if (symbol == null) {
            throw new IllegalArgumentException("symbol is required");
        }
        return symbol.replace("_", "").replace("/", "").replace("-", "").toUpperCase();
    }

    private void emit(BrokerEvent event) {
        for (Consumer<BrokerEvent> listener : listeners) {
            listener.accept(event);
        }
    }
}
