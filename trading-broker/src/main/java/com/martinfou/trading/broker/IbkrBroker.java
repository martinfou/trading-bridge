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

    /** How long the kill switch waits for the venue to confirm the account is flat. */
    static final long FLATTEN_CONFIRM_ATTEMPTS = 10;
    static final long FLATTEN_CONFIRM_INTERVAL_MS = 500;

    private final IbkrGatewayClient client;
    private final List<Consumer<BrokerEvent>> listeners = new CopyOnWriteArrayList<>();
    private final java.util.Map<String, Order> pendingOrdersByClientTag = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Order> pendingOrdersByBrokerId = new java.util.concurrent.ConcurrentHashMap<>();
    // Quantite deja remplie par commande : IBKR peut livrer plusieurs executions pour un seul ordre.
    private final java.util.Map<String, Double> filledQuantityByOrderId = new java.util.concurrent.ConcurrentHashMap<>();
    private final long flattenConfirmAttempts;
    private final long flattenConfirmIntervalMs;
    private volatile boolean connected;

    public IbkrBroker(IbkrGatewayClient client) {
        this(client, FLATTEN_CONFIRM_ATTEMPTS, FLATTEN_CONFIRM_INTERVAL_MS);
    }

    /** Test seam: shrink the flatten confirmation window so tests do not sleep 5s. */
    IbkrBroker(IbkrGatewayClient client, long flattenConfirmAttempts, long flattenConfirmIntervalMs) {
        if (client == null) {
            throw new IllegalArgumentException("client is required");
        }
        this.client = client;
        this.flattenConfirmAttempts = flattenConfirmAttempts;
        this.flattenConfirmIntervalMs = flattenConfirmIntervalMs;
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
        if (order != null && execution.isRejected()) {
            purge(order, execution.orderId());
        }
        if (execution.isFill()) {
            if (order != null) {
                // Un ordre peut etre rempli en plusieurs executions. La premiere ne doit pas purger la
                // commande : les partiels suivants tomberaient en « unknown order » et seraient perdus.
                double partial = execution.quantity();
                double cumulative = filledQuantityByOrderId.merge(order.id(), partial, Double::sum);
                log.info("IBKR fill {} {} qty={} (cumul {} / {}) @ {} (execId={})",
                    execution.symbol(), execution.side(), partial, cumulative, order.quantity(),
                    execution.fillPrice(), execution.execId());
                // L'evenement porte la quantite DU PARTIEL : additionner les fills donne la position reelle.
                emit(BrokerEvent.fill(order.id(), order.symbol(), order.side().name(), partial,
                    execution.fillPrice(), order.stopLoss(), order.takeProfit(), order.correlationId()));
                if (cumulative + 1e-9 >= order.quantity()) {
                    purge(order, execution.orderId());
                }
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

    /** Retire la commande des registres une fois reglee (remplie en totalite ou rejetee). */
    private void purge(Order order, String brokerOrderId) {
        pendingOrdersByClientTag.remove(order.id());
        filledQuantityByOrderId.remove(order.id());
        if (brokerOrderId != null) {
            pendingOrdersByBrokerId.remove(brokerOrderId);
        }
    }

    @Override
    public boolean isConnected() {
        return connected && client.isConnected();
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
        if (order.type() == null) {
            emit(BrokerEvent.reject(order, "Order type is required"));
            return OrderSubmitResult.rejected("Order type is required");
        }
        if (order.type() == Order.Type.STOP) {
            if (order.price() <= 0.0 || !Double.isFinite(order.price())) {
                emit(BrokerEvent.reject(order, "IBKR STOP order requires a positive stop price"));
                return OrderSubmitResult.rejected("IBKR STOP order requires a positive stop price");
            }
        } else if (order.type() == Order.Type.LIMIT) {
            if (order.price() <= 0.0 || !Double.isFinite(order.price())) {
                emit(BrokerEvent.reject(order, "IBKR LIMIT order requires a positive limit price"));
                return OrderSubmitResult.rejected("IBKR LIMIT order requires a positive limit price");
            }
        } else if (order.type() != Order.Type.MARKET) {
            emit(BrokerEvent.reject(order, "Unsupported IBKR order type: " + order.type()));
            return OrderSubmitResult.rejected("Unsupported IBKR order type: " + order.type());
        }

        String symbol = toIbkrSymbol(order.symbol());
        // Register the order BEFORE placing it: IBKR may deliver the execution callback
        // (via the stub or a fast venue) synchronously inside placeMarketOrder. Matching by
        // clientTag (order.id()) lets onExecution resolve it regardless of arrival order.
        if (order.strategyId() == null || order.strategyId().isBlank()) {
            // Pas de refus : un ordre manuel reste permis. Mais on le dit, parce que son fill sera un leak.
            log.error("IBKR order {} sans strategyId : son fill sera classe leak (aucune attribution possible)",
                order.id());
        }
        pendingOrdersByClientTag.put(order.id(), order);
        // clientTag = id d'ordre (appariement des executions) ; orderRef = nom de la strategie, SEULE chose
        // qui permet a la lane P&L de rattacher le fill a une strategie plutot qu'a un leak. Un UUID ici
        // creerait une fausse strategie par fill.
        IbkrMarketOrderResult result = switch (order.type()) {
            case MARKET -> client.placeMarketOrder(
                symbol, order.quantity(), order.side(), order.id(), order.strategyId());
            case STOP -> client.placeStopOrder(
                symbol, order.quantity(), order.side(), order.price(), order.id(),
                order.strategyId(), order.isCloseOnly(), order.ocaGroup(), order.ocaType());
            case LIMIT -> client.placeLimitOrder(
                symbol, order.quantity(), order.side(), order.price(), order.id(),
                order.strategyId(), order.isCloseOnly(), order.ocaGroup(), order.ocaType());
        };

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
        if (!connected) {
            String reason = "Broker not connected";
            log.warn("IBKR cancelOrder failed for {}: {}", brokerOrderId, reason);
            return OrderSubmitResult.rejected(reason);
        }
        if (brokerOrderId == null || brokerOrderId.isBlank()) {
            return OrderSubmitResult.rejected("brokerOrderId is required");
        }
        boolean ok = client.cancelOrder(brokerOrderId);
        if (ok) {
            Order order = pendingOrdersByBrokerId.get(brokerOrderId);
            if (order != null) {
                emit(BrokerEvent.reject(order.id(), order.symbol(), order.side().name(), order.quantity(),
                    order.price(), "CANCELLED", order.stopLoss(), order.takeProfit(), order.correlationId()));
                purge(order, brokerOrderId);
            } else {
                emit(BrokerEvent.reject(brokerOrderId, "", "", 0.0, 0.0, "CANCELLED", null, null));
            }
            log.info("IBKR cancelOrder dispatched to venue for brokerOrderId={}", brokerOrderId);
            return OrderSubmitResult.filled(brokerOrderId);
        }
        String reason = "Failed to cancel order on IBKR venue";
        log.warn("IBKR cancelOrder failed for {}: {}", brokerOrderId, reason);
        return OrderSubmitResult.rejected(reason);
    }

    @Override
    public int cancelAllOrders() {
        if (!connected) {
            log.warn("IBKR cancelAllOrders rejected: Broker not connected");
            return 0;
        }
        int venueCancelled = client.cancelAllOrders();
        int localCount = 0;
        for (java.util.Map.Entry<String, Order> entry : List.copyOf(pendingOrdersByBrokerId.entrySet())) {
            String brokerOrderId = entry.getKey();
            Order order = entry.getValue();
            emit(BrokerEvent.reject(order.id(), order.symbol(), order.side().name(), order.quantity(),
                order.price(), "CANCELLED", order.stopLoss(), order.takeProfit(), order.correlationId()));
            purge(order, brokerOrderId);
            localCount++;
        }
        int total = Math.max(venueCancelled, localCount);
        log.info("IBKR cancelAllOrders dispatched: {} order(s) cancelled", total);
        return total;
    }

    @Override
    public int flattenAllPositions() {
        // Last-resort de-risk: close every open position with an opposite MARKET order.
        // The kill switch MUST be able to flatten paper positions; a silent 0 here would let
        // the kill switch "succeed" while leaving the account exposed.
        List<Position> initial = getPositions();
        int submitted = 0;
        for (Position pos : initial) {
            if (pos.quantity() <= 0.0) {
                continue;
            }
            Order.Side closeSide = pos.side() == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
            // strategyId « flatten » : le fill qui ferme portera orderRef=flatten, donc il sera classe
            // ordre de controle dans la lane P&L, ni strategie ni leak.
            Order close = new Order(pos.symbol(), closeSide, Order.Type.MARKET, pos.quantity(), 0.0)
                .withStrategyId("flatten");
            OrderSubmitResult result = submitOrder(close);
            if (result.accepted()) {
                submitted++;
            } else {
                log.warn("IBKR flatten failed for {} {}: {}", pos.symbol(), closeSide, result.rejectReason());
            }
        }
        if (submitted == 0) {
            if (!connected || !client.lastPositionQueryConfirmed()) {
                log.error("IBKR flatten: position state UNCONFIRMED (connected={}, queryConfirmed={}); the account is not known to be flat",
                    connected, client.lastPositionQueryConfirmed());
            }
            return 0;
        }
        if (!connected) {
            log.error("IBKR flatten NOT confirmed: broker disconnected with {} closing order(s) accepted only", submitted);
            return 0;
        }
        // An ACCEPTED closing order is not a CLOSED position: IBKR fills asynchronously, so the
        // position stays open for a moment after submitOrder returns. Confirm against the venue
        // before claiming anything was flattened, otherwise a kill switch reports success while
        // the account is still exposed. Check once immediately: a paper fill can land in <1 ms.
        // An EMPTY list is only proof of flatness when the venue actually answered the query.
        for (long attempt = 0; attempt <= flattenConfirmAttempts; attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(flattenConfirmIntervalMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("IBKR flatten interrupted while waiting for confirmation; {} order(s) accepted only", submitted);
                    return 0;
                }
            }
            List<Position> open = getPositions();
            boolean confirmed = connected && client.lastPositionQueryConfirmed();
            if (confirmed && open.isEmpty()) {
                log.info("IBKR flatten confirmed flat after {} ms ({} closing order(s))",
                    attempt * flattenConfirmIntervalMs, submitted);
                return submitted;
            }
            if (!confirmed) {
                log.error("IBKR flatten attempt {}: position state UNCONFIRMED (connected={}), {} position(s) known",
                    attempt, connected, open.size());
            }
        }
        log.error("IBKR flatten NOT confirmed: position(s) still open after {} ms; {} closing order(s) were only accepted, not verified",
            flattenConfirmAttempts * flattenConfirmIntervalMs, submitted);
        return 0;
    }

    @Override
    public boolean lastPositionQueryConfirmed() {
        return connected && client.lastPositionQueryConfirmed();
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
        // Un listener qui leve sur le thread lecteur d'IBKR peut faire perdre des callbacks : on l'isole.
        for (Consumer<BrokerEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                log.error("IBKR listener failed on {}: {}", event.type(), e.getMessage(), e);
            }
        }
    }
}
