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
    private final java.util.concurrent.atomic.AtomicLong nextOrderId = new java.util.concurrent.atomic.AtomicLong(1);
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
        String orderId = String.valueOf(nextOrderId.getAndIncrement());
        String execId = "E-" + orderId;
        netPosition(symbol, side, quantity, price);
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

    /**
     * Nets a fill into the open-position book instead of blindly appending. A buy then a sell
     * of the same quantity must leave the account flat, not hold [BUY n, SELL n]. Synchronized
     * because the kill-switch flatten path can submit concurrently with the worker thread.
     */
    private synchronized void netPosition(String symbol, Order.Side side, double quantity, double price) {
        int existingIdx = -1;
        IbkrPositionSnapshot existing = null;
        for (int i = 0; i < positions.size(); i++) {
            if (positions.get(i).symbol().equals(symbol)) {
                existingIdx = i;
                existing = positions.get(i);
                break;
            }
        }
        double signedQty = side == Order.Side.BUY ? quantity : -quantity;
        if (existing == null) {
            if (Math.abs(signedQty) > 1e-9) {
                positions.add(new IbkrPositionSnapshot(
                    symbol, signedQty > 0 ? Order.Side.BUY : Order.Side.SELL, Math.abs(signedQty), price));
            }
            return;
        }
        double existingSigned = existing.side() == Order.Side.BUY ? existing.quantity() : -existing.quantity();
        double net = existingSigned + signedQty;
        if (Math.abs(net) < 1e-9) {
            positions.remove(existingIdx);
        } else if (net > 0) {
            positions.set(existingIdx, new IbkrPositionSnapshot(symbol, Order.Side.BUY, net, existing.averagePrice()));
        } else {
            positions.set(existingIdx, new IbkrPositionSnapshot(symbol, Order.Side.SELL, -net, existing.averagePrice()));
        }
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
