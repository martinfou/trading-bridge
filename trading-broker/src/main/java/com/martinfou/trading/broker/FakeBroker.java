package com.martinfou.trading.broker;

import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Position;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * In-memory broker for tests and PAPER_STUB-free unit scenarios.
 * MARKET orders fill immediately at the order price.
 */
public final class FakeBroker implements Broker {

    private final double initialBalance;
    private final String currency;
    private final Predicate<Order> rejectFilter;
    private final Map<String, Position> positions = new LinkedHashMap<>();
    private final List<Consumer<BrokerEvent>> listeners = new CopyOnWriteArrayList<>();

    private volatile boolean connected = true;
    private volatile double balance;
    private volatile double equity;

    public FakeBroker(double initialBalance) {
        this(initialBalance, "USD", order -> false);
    }

    public FakeBroker(double initialBalance, String currency, Predicate<Order> rejectFilter) {
        this.initialBalance = initialBalance;
        this.currency = currency != null ? currency : "USD";
        this.rejectFilter = rejectFilter != null ? rejectFilter : order -> false;
        this.balance = initialBalance;
        this.equity = initialBalance;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void connect() {
        connected = true;
    }

    @Override
    public void disconnect() {
        connected = false;
    }

    @Override
    public void reconnect() {
        connected = true;
    }

    @Override
    public OrderSubmitResult submitOrder(Order order) {
        emit(BrokerEvent.submitted(order));

        if (!connected) {
            emit(BrokerEvent.reject(order, "Broker not connected"));
            return OrderSubmitResult.rejected("Broker not connected");
        }
        if (rejectFilter.test(order)) {
            emit(BrokerEvent.reject(order, "Order rejected by policy"));
            return OrderSubmitResult.rejected("Order rejected by policy");
        }
        if (order.type() != Order.Type.MARKET) {
            emit(BrokerEvent.reject(order, "FakeBroker supports MARKET orders only"));
            return OrderSubmitResult.rejected("FakeBroker supports MARKET orders only");
        }

        double fillPrice = order.price() > 0 ? order.price() : 1.0;
        order.fill();
        applyFill(order, fillPrice);
        emit(BrokerEvent.fill(order, fillPrice));
        return OrderSubmitResult.filled(order.id());
    }

    @Override
    public OrderSubmitResult cancelOrder(String brokerOrderId) {
        emit(BrokerEvent.reject(brokerOrderId, "", "", 0.0, 0.0, "CANCELLED", null, null));
        return OrderSubmitResult.filled(brokerOrderId);
    }

    @Override
    public List<Position> getPositions() {
        return List.copyOf(positions.values());
    }

    @Override
    public AccountState getAccountState() {
        return new AccountState(balance, equity, currency);
    }

    @Override
    public void addEventListener(Consumer<BrokerEvent> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public double initialBalance() {
        return initialBalance;
    }

    private void applyFill(Order order, double fillPrice) {
        String key = positionKey(order.symbol(), order.side());
        Position existing = positions.get(key);
        if (existing == null) {
            positions.put(key, new Position(order.symbol(), order.side(), order.quantity(), fillPrice));
        } else {
            existing.addQuantity(order.quantity(), fillPrice);
        }
        recalculateEquity(fillPrice);
    }

    private void recalculateEquity(double markPrice) {
        double unrealized = 0.0;
        for (Position position : positions.values()) {
            unrealized += position.currentPnl(markPrice);
        }
        equity = balance + unrealized;
    }

    private static String positionKey(String symbol, Order.Side side) {
        return symbol + ":" + side.name();
    }

    private void emit(BrokerEvent event) {
        for (Consumer<BrokerEvent> listener : listeners) {
            listener.accept(event);
        }
    }
}
