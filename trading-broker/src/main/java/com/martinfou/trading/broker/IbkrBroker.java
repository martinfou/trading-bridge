package com.martinfou.trading.broker;

import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Position;
import com.martinfou.trading.data.ibkr.IbkrAccountSnapshot;
import com.martinfou.trading.data.ibkr.IbkrGatewayClient;
import com.martinfou.trading.data.ibkr.IbkrOrderAck;
import com.martinfou.trading.data.ibkr.IbkrOrderRequest;
import com.martinfou.trading.data.ibkr.IbkrOrderUpdate;
import com.martinfou.trading.data.ibkr.IbkrPositionSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * {@link Broker} adapter for IBKR via IB Gateway / TWS (Story 16.10).
 *
 * <p>Supports MARKET, LIMIT and STOP orders; when {@code stopLoss}/{@code takeProfit} are set the order is sent
 * as a native IB bracket (children OCA-linked server-side, so protection survives a trading-bridge crash).</p>
 *
 * <p>Fill semantics: {@link #submitOrder} returns {@code accepted} once IB acknowledges the order. A single
 * {@link BrokerEvent#fill} (VWAP over partial executions) is emitted when the order is completely filled;
 * a partially filled order that is then cancelled emits a FILL for the filled portion followed by a
 * {@code CANCELLED} reject. MARKET orders additionally block up to {@code marketFillWait} so callers that expect
 * near-synchronous market fills keep working.</p>
 */
public final class IbkrBroker implements Broker {

    private static final Logger log = LoggerFactory.getLogger(IbkrBroker.class);
    public static final Duration DEFAULT_MARKET_FILL_WAIT = Duration.ofSeconds(5);

    private final IbkrGatewayClient client;
    private final Duration marketFillWait;
    private final List<Consumer<BrokerEvent>> listeners = new CopyOnWriteArrayList<>();
    private final Map<String, Order> ordersByClientId = new ConcurrentHashMap<>();
    private final Map<String, FillAggregate> fillsByBrokerId = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Void>> marketWaiters = new ConcurrentHashMap<>();
    private final Set<String> terminalEmitted = ConcurrentHashMap.newKeySet();
    private final AtomicInteger connectionFailures = new AtomicInteger();
    private volatile boolean connected;
    private volatile Instant uptimeStart;
    private volatile Instant lastReplyTime;

    public IbkrBroker(IbkrGatewayClient client) {
        this(client, DEFAULT_MARKET_FILL_WAIT);
    }

    public IbkrBroker(IbkrGatewayClient client, Duration marketFillWait) {
        if (client == null) {
            throw new IllegalArgumentException("client is required");
        }
        this.client = client;
        this.marketFillWait = marketFillWait == null ? Duration.ZERO : marketFillWait;
        client.addOrderListener(this::onUpdate);
    }

    // ------------------------------------------------------------------ session

    @Override
    public boolean isConnected() {
        return connected && client.isConnected();
    }

    @Override
    public void connect() {
        try {
            client.connect();
            client.fetchAccountSummary();
        } catch (RuntimeException e) {
            connectionFailures.incrementAndGet();
            emit(BrokerEvent.connection("FAILED", e.getMessage()));
            throw e;
        }
        connected = true;
        uptimeStart = Instant.now();
        lastReplyTime = uptimeStart;
    }

    @Override
    public void disconnect() {
        connected = false;
        client.disconnect();
    }

    @Override
    public void reconnect() {
        disconnect();
        connect();
    }

    @Override
    public Instant getUptimeStart() {
        return uptimeStart;
    }

    @Override
    public Instant getLastReplyTime() {
        return lastReplyTime;
    }

    @Override
    public int getConnectionFailures() {
        return connectionFailures.get();
    }

    // ------------------------------------------------------------------ orders

    @Override
    public OrderSubmitResult submitOrder(Order order) {
        emit(BrokerEvent.submitted(order));

        if (!isConnected()) {
            return reject(order, "Broker not connected");
        }
        IbkrOrderRequest request;
        try {
            request = IbkrOrderRequest.from(order);
        } catch (IllegalArgumentException e) {
            return reject(order, e.getMessage());
        }

        ordersByClientId.put(order.id(), order);
        CompletableFuture<Void> waiter = null;
        if (order.type() == Order.Type.MARKET && !marketFillWait.isZero()) {
            waiter = new CompletableFuture<>();
            marketWaiters.put(order.id(), waiter); // register first: fills can arrive before placeOrder returns
        }

        IbkrOrderAck ack = client.placeOrder(request);
        if (!ack.accepted()) {
            marketWaiters.remove(order.id());
            String reason = ack.errorMessage() != null ? ack.errorMessage() : "IBKR order rejected";
            log.warn("IBKR reject {} {} {}: {}", order.side(), order.quantity(), order.symbol(), reason);
            if (terminalEmitted.add(order.id())) {
                emit(BrokerEvent.reject(order, reason));
            }
            ordersByClientId.remove(order.id());
            return OrderSubmitResult.rejected(reason);
        }

        if (waiter != null) {
            try {
                waiter.get(marketFillWait.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                log.warn("IBKR market order {} ({} {}) not filled within {}; fill will be reported asynchronously",
                    ack.brokerOrderId(), order.side(), order.symbol(), marketFillWait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.debug("market waiter completed exceptionally", e);
            } finally {
                marketWaiters.remove(order.id());
            }
        }
        return OrderSubmitResult.filled(ack.brokerOrderId());
    }

    @Override
    public OrderSubmitResult cancelOrder(String brokerOrderId) {
        IbkrOrderAck ack = client.cancelOrder(brokerOrderId);
        return ack.accepted()
            ? OrderSubmitResult.filled(brokerOrderId)
            : OrderSubmitResult.rejected(ack.errorMessage());
    }

    private OrderSubmitResult reject(Order order, String reason) {
        emit(BrokerEvent.reject(order, reason));
        return OrderSubmitResult.rejected(reason);
    }

    // ------------------------------------------------------------------ account

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

    // ------------------------------------------------------------------ async updates from IB

    private void onUpdate(IbkrOrderUpdate u) {
        lastReplyTime = Instant.now();
        switch (u.kind()) {
            case EXECUTION -> onExecution(u);
            case CANCELLED -> onTerminal(u, "CANCELLED");
            case REJECTED -> onTerminal(u, u.message() != null ? u.message() : "IBKR order rejected");
            case CONNECTION -> {
                if ("CLOSED".equals(u.status())) {
                    connected = false;
                }
                emit(BrokerEvent.connection(u.status(), u.message()));
            }
            case STATUS, COMMISSION -> { }
        }
    }

    private void onExecution(IbkrOrderUpdate u) {
        String key = u.brokerOrderId();
        FillAggregate agg = fillsByBrokerId.computeIfAbsent(key, k -> new FillAggregate());
        synchronized (agg) {
            agg.filled += u.quantity();
            agg.notional += u.quantity() * u.price();
            if (u.remaining() > 1e-9 || agg.emitted) {
                return;
            }
            agg.emitted = true;
        }
        fillsByBrokerId.remove(key);
        emitFill(u, agg);
        complete(u.clientOrderId());
    }

    private void onTerminal(IbkrOrderUpdate u, String reason) {
        String ref = u.clientOrderId() != null ? u.clientOrderId() : u.brokerOrderId();
        FillAggregate agg = fillsByBrokerId.remove(u.brokerOrderId());
        if (agg != null) {
            boolean emitPartial;
            synchronized (agg) {
                emitPartial = !agg.emitted && agg.filled > 0;
                agg.emitted = true;
            }
            if (emitPartial) {
                emitFill(u, agg);
            }
        }
        if (terminalEmitted.add(ref)) {
            Order parent = parentOf(u.clientOrderId());
            boolean isParent = parent != null && parent.id().equals(u.clientOrderId());
            emit(BrokerEvent.reject(ref, u.symbol(), u.side() != null ? u.side().name() : "",
                u.quantity(), 0.0, reason,
                isParent ? parent.stopLoss() : null, isParent ? parent.takeProfit() : null,
                parent != null ? parent.correlationId() : null));
        }
        complete(u.clientOrderId());
    }

    private void emitFill(IbkrOrderUpdate u, FillAggregate agg) {
        double qty = agg.filled;
        double vwap = qty > 0 ? agg.notional / qty : u.price();
        Order parent = parentOf(u.clientOrderId());
        boolean isParent = parent != null && parent.id().equals(u.clientOrderId());
        String orderId = u.clientOrderId() != null ? u.clientOrderId() : u.brokerOrderId();
        emit(BrokerEvent.fill(orderId, u.symbol(), u.side() != null ? u.side().name() : "", qty, vwap,
            isParent ? parent.stopLoss() : null, isParent ? parent.takeProfit() : null,
            parent != null ? parent.correlationId() : null));
    }

    /** Resolves the trading-bridge parent order for a ref, including bracket children ({@code <id>-TP/-SL}). */
    private Order parentOf(String clientOrderId) {
        if (clientOrderId == null) {
            return null;
        }
        Order direct = ordersByClientId.get(clientOrderId);
        if (direct != null) {
            return direct;
        }
        if (clientOrderId.endsWith("-TP") || clientOrderId.endsWith("-SL")) {
            return ordersByClientId.get(clientOrderId.substring(0, clientOrderId.length() - 3));
        }
        return null;
    }

    private void complete(String clientOrderId) {
        if (clientOrderId != null) {
            CompletableFuture<Void> waiter = marketWaiters.get(clientOrderId);
            if (waiter != null) {
                waiter.complete(null);
            }
        }
    }

    private void emit(BrokerEvent event) {
        for (Consumer<BrokerEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                log.warn("Broker event listener failed: {}", e.toString());
            }
        }
    }

    private static final class FillAggregate {
        double filled;
        double notional;
        boolean emitted;
    }
}
