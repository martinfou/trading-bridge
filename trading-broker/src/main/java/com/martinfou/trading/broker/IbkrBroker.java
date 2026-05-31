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
    private volatile boolean connected;

    public IbkrBroker(IbkrGatewayClient client) {
        if (client == null) {
            throw new IllegalArgumentException("client is required");
        }
        this.client = client;
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
        IbkrMarketOrderResult result = client.placeMarketOrder(
            symbol, order.quantity(), order.side(), order.id());

        if (!result.success()) {
            String reason = result.errorMessage() != null ? result.errorMessage() : "IBKR order rejected";
            log.warn("IBKR reject {} {}: {}", symbol, order.quantity(), reason);
            emit(BrokerEvent.reject(order, reason));
            return OrderSubmitResult.rejected(reason);
        }

        order.fill();
        double fillPrice = result.fillPrice() != null ? result.fillPrice() : order.price();
        emit(BrokerEvent.fill(order, fillPrice));
        return OrderSubmitResult.filled(result.orderId());
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
