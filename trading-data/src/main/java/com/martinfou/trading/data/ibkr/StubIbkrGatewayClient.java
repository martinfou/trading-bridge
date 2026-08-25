package com.martinfou.trading.data.ibkr;

import com.martinfou.trading.core.Order;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** In-memory IB Gateway stub for unit tests (Story 16.10). */
public final class StubIbkrGatewayClient implements IbkrGatewayClient {

    private final List<IbkrMarketOrderResult> scriptedResults = new CopyOnWriteArrayList<>();
    private final List<IbkrPositionSnapshot> positions = new CopyOnWriteArrayList<>();
    private final List<Consumer<IbkrExecution>> executionListeners = new CopyOnWriteArrayList<>();
    private IbkrAccountSnapshot account = new IbkrAccountSnapshot(100_000, 100_000, "USD");
    private long nextOrderId = 1;
    private volatile boolean connected;
    private volatile boolean asyncFills = true;

    public StubIbkrGatewayClient scriptFailure(String message) {
        scriptedResults.add(IbkrMarketOrderResult.failure(message));
        return this;
    }

    public StubIbkrGatewayClient account(IbkrAccountSnapshot snapshot) {
        this.account = snapshot;
        return this;
    }

    /** When true (default), fills are delivered asynchronously via execution listeners. */
    public StubIbkrGatewayClient asyncFills(boolean enabled) {
        this.asyncFills = enabled;
        return this;
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
    public boolean isConnected() {
        return connected;
    }

    @Override
    public IbkrMarketOrderResult placeMarketOrder(String symbol, double quantity, Order.Side side, String clientTag) {
        if (!connected) {
            return IbkrMarketOrderResult.failure("IB Gateway not connected");
        }
        if (!scriptedResults.isEmpty()) {
            return scriptedResults.removeFirst();
        }
        double price = symbol != null && symbol.contains("JPY") ? 150.0 : 1.10;
        String orderId = String.valueOf(nextOrderId++);
        String execId = "E-" + orderId;
        positions.add(new IbkrPositionSnapshot(symbol, side, quantity, price));
        IbkrMarketOrderResult result = IbkrMarketOrderResult.success(orderId, execId, price);
        if (asyncFills) {
            // Simulate IBKR's asynchronous execution callback. The clientTag is the
            // application order id (order.id()), which the broker can match even when the
            // fill arrives before placeMarketOrder returns.
            for (Consumer<IbkrExecution> listener : executionListeners) {
                listener.accept(IbkrExecution.filled(orderId, execId, clientTag, symbol, side, quantity, price));
            }
        }
        return result;
    }

    @Override
    public void addExecutionListener(Consumer<IbkrExecution> listener) {
        executionListeners.add(listener);
    }

    @Override
    public IbkrAccountSnapshot fetchAccountSummary() {
        return account;
    }

    @Override
    public List<IbkrPositionSnapshot> fetchOpenPositions() {
        return List.copyOf(positions);
    }
}
