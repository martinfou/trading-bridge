package com.martinfou.trading.data.ibkr;

import com.martinfou.trading.core.Order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-memory IB Gateway stub for unit tests and {@code IBKR_USE_STUB=true} (Story 16.10).
 *
 * <p>Mirrors the real client's event model: MARKET orders fill immediately at {@link #price(String, double)}
 * (emitting {@code EXECUTION} then {@code STATUS Filled}); LIMIT/STOP orders and bracket children stay
 * working until {@link #fillWorkingOrder(String, double)} or {@link #cancelOrder(String)}.</p>
 */
public final class StubIbkrGatewayClient implements IbkrGatewayClient {

    private final List<String> scriptedFailures = new CopyOnWriteArrayList<>();
    private final List<Consumer<IbkrOrderUpdate>> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Double> prices = new ConcurrentHashMap<>();
    private final Map<String, Working> working = new ConcurrentHashMap<>();
    private final Map<String, IbkrPositionSnapshot> positions = new ConcurrentHashMap<>();
    private final List<IbkrOrderRequest> placed = new CopyOnWriteArrayList<>();
    private IbkrAccountSnapshot account = new IbkrAccountSnapshot(100_000, 100_000, "USD");
    private long nextOrderId = 1;
    private long nextExecId = 1;
    private volatile boolean connected;

    private record Working(String brokerOrderId, String parentId, String clientOrderId, String symbol,
                           Order.Side side, double quantity, String ocaKey) {}

    public StubIbkrGatewayClient scriptFailure(String message) {
        scriptedFailures.add(message);
        return this;
    }

    public StubIbkrGatewayClient account(IbkrAccountSnapshot snapshot) {
        this.account = snapshot;
        return this;
    }

    /** Sets the MARKET fill price for a symbol (default 1.10, or 150.0 for JPY pairs). */
    public StubIbkrGatewayClient price(String symbol, double price) {
        prices.put(symbol, price);
        return this;
    }

    public List<IbkrOrderRequest> placedOrders() {
        return List.copyOf(placed);
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
    public void addOrderListener(Consumer<IbkrOrderUpdate> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public synchronized IbkrOrderAck placeOrder(IbkrOrderRequest req) {
        if (!connected) {
            return IbkrOrderAck.rejected(null, "IB Gateway not connected");
        }
        if (!scriptedFailures.isEmpty()) {
            return IbkrOrderAck.rejected(null, scriptedFailures.remove(0));
        }
        placed.add(req);
        String parentId = String.valueOf(nextOrderId++);
        List<String> children = new ArrayList<>();
        Order.Side exit = req.side() == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
        String ref = req.clientOrderId() == null ? parentId : req.clientOrderId();
        if (req.takeProfit() > 0) {
            String id = String.valueOf(nextOrderId++);
            working.put(id, new Working(id, parentId, ref + "-TP", req.symbol(), exit, req.quantity(), parentId));
            children.add(id);
        }
        if (req.stopLoss() > 0) {
            String id = String.valueOf(nextOrderId++);
            working.put(id, new Working(id, parentId, ref + "-SL", req.symbol(), exit, req.quantity(), parentId));
            children.add(id);
        }
        Working parent = new Working(parentId, null, req.clientOrderId(), req.symbol(), req.side(), req.quantity(), null);
        if (req.type() == Order.Type.MARKET) {
            execute(parent, priceFor(req.symbol()));
            return IbkrOrderAck.accepted(parentId, children, "Filled");
        }
        working.put(parentId, parent);
        status(parent, "Submitted", 0, req.quantity(), 0);
        return IbkrOrderAck.accepted(parentId, children, "Submitted");
    }

    /** Test hook: fill a working LIMIT/STOP order or bracket child at {@code price}. */
    public synchronized void fillWorkingOrder(String brokerOrderId, double price) {
        Working w = working.remove(brokerOrderId);
        if (w == null) {
            throw new IllegalArgumentException("No working order " + brokerOrderId);
        }
        execute(w, price);
        if (w.ocaKey() != null) { // bracket child filled → cancel its OCA sibling
            working.values().stream()
                .filter(o -> w.ocaKey().equals(o.ocaKey()))
                .map(Working::brokerOrderId)
                .toList()
                .forEach(this::cancelOrder);
        }
    }

    @Override
    public synchronized IbkrOrderAck cancelOrder(String brokerOrderId) {
        Working w = working.remove(brokerOrderId);
        if (w == null) {
            return IbkrOrderAck.rejected(brokerOrderId, "No working order " + brokerOrderId);
        }
        emit(new IbkrOrderUpdate(IbkrOrderUpdate.Kind.CANCELLED, Instant.now(), w.brokerOrderId(), w.clientOrderId(),
            w.symbol(), w.side(), w.quantity(), 0, 0, w.quantity(), 0, "Cancelled", null, "Cancelled"));
        if (w.parentId() == null) { // cancelling a parent cancels its children
            working.values().stream()
                .filter(o -> brokerOrderId.equals(o.parentId()))
                .map(Working::brokerOrderId)
                .toList()
                .forEach(this::cancelOrder);
        }
        return IbkrOrderAck.accepted(brokerOrderId, List.of(), "PendingCancel");
    }

    @Override
    public IbkrAccountSnapshot fetchAccountSummary() {
        return account;
    }

    @Override
    public List<IbkrPositionSnapshot> fetchOpenPositions() {
        return List.copyOf(positions.values());
    }

    @Override
    public List<IbkrOpenOrder> fetchOpenOrders() {
        return working.values().stream()
            .map(w -> new IbkrOpenOrder(w.brokerOrderId(), w.clientOrderId(), w.symbol(), w.side(), "", w.quantity(),
                0, 0, w.parentId(), "Submitted"))
            .toList();
    }

    private void execute(Working w, double price) {
        String execId = "E-" + nextExecId++;
        emit(new IbkrOrderUpdate(IbkrOrderUpdate.Kind.EXECUTION, Instant.now(), w.brokerOrderId(), w.clientOrderId(),
            w.symbol(), w.side(), w.quantity(), price, w.quantity(), 0, price, "Filled", execId, null));
        status(w, "Filled", w.quantity(), 0, price);
        applyPosition(w.symbol(), w.side(), w.quantity(), price);
    }

    private void status(Working w, String status, double filled, double remaining, double avg) {
        emit(new IbkrOrderUpdate(IbkrOrderUpdate.Kind.STATUS, Instant.now(), w.brokerOrderId(), w.clientOrderId(),
            w.symbol(), w.side(), 0, avg, filled, remaining, avg, status, null, null));
    }

    private void applyPosition(String symbol, Order.Side side, double qty, double price) {
        IbkrPositionSnapshot existing = positions.get(symbol);
        double signedExisting = existing == null ? 0
            : (existing.side() == Order.Side.BUY ? existing.quantity() : -existing.quantity());
        double signedNew = signedExisting + (side == Order.Side.BUY ? qty : -qty);
        if (Math.abs(signedNew) < 1e-9) {
            positions.remove(symbol);
        } else {
            double avg = existing != null && Math.signum(signedNew) == Math.signum(signedExisting)
                && Math.abs(signedNew) > Math.abs(signedExisting)
                ? (existing.averagePrice() * Math.abs(signedExisting) + price * qty) / Math.abs(signedNew)
                : (existing != null && Math.signum(signedNew) == Math.signum(signedExisting) ? existing.averagePrice() : price);
            positions.put(symbol, new IbkrPositionSnapshot(symbol,
                signedNew > 0 ? Order.Side.BUY : Order.Side.SELL, Math.abs(signedNew), avg));
        }
    }

    private double priceFor(String symbol) {
        Double p = prices.get(symbol);
        if (p != null) {
            return p;
        }
        return symbol != null && symbol.contains("JPY") ? 150.0 : 1.10;
    }

    private void emit(IbkrOrderUpdate update) {
        for (Consumer<IbkrOrderUpdate> listener : listeners) {
            listener.accept(update);
        }
    }
}
