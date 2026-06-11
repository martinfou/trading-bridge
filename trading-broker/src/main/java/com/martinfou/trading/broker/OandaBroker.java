package com.martinfou.trading.broker;

import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Position;
import com.martinfou.trading.data.oanda.OandaAccountSnapshot;
import com.martinfou.trading.data.oanda.OandaMarketOrderResult;
import com.martinfou.trading.data.oanda.OandaPositionSnapshot;
import com.martinfou.trading.data.oanda.OandaRestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * {@link Broker} adapter for OANDA demo/live via {@link OandaRestClient} (Story 16.3).
 */
public final class OandaBroker implements Broker {

    private static final Logger log = LoggerFactory.getLogger(OandaBroker.class);

    private final OandaRestClient client;
    private final List<Consumer<BrokerEvent>> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean connected;

    public OandaBroker(OandaRestClient client) {
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
        client.fetchAccountSummary();
        connected = true;
    }

    @Override
    public void disconnect() {
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

        long units = Math.round(order.quantity());
        if (order.side() == Order.Side.SELL) {
            units = -units;
        }
        String instrument = toOandaInstrument(order.symbol());
        OandaMarketOrderResult result;

        if (order.type() == Order.Type.MARKET) {
            if (order.stopLoss() > 0 || order.takeProfit() > 0 || order.trailingStop() > 0) {
                result = client.placeOrder("MARKET", instrument, units, 0.0, order.stopLoss(), order.takeProfit(), order.trailingStop(), order.guaranteed(), order.id());
            } else {
                result = client.placeMarketOrder(instrument, units, order.id());
            }
        } else {
            result = client.placeOrder(order.type().name(), instrument, units, order.price(), order.stopLoss(), order.takeProfit(), order.trailingStop(), order.guaranteed(), order.id());
        }

        if (!result.success()) {
            String reason = result.errorMessage() != null ? result.errorMessage() : "OANDA order rejected";
            log.warn("OANDA reject {} {} type={}: {}", instrument, units, order.type(), reason);
            emit(BrokerEvent.reject(order, reason));
            return OrderSubmitResult.rejected(reason);
        }

        if (order.type() == Order.Type.MARKET) {
            order.fill();
            double fillPrice = result.fillPrice() != null ? result.fillPrice() : order.price();
            emit(BrokerEvent.fill(order, fillPrice));
        }
        return OrderSubmitResult.filled(result.orderId());
    }

    @Override
    public OrderSubmitResult cancelOrder(String brokerOrderId) {
        if (!connected) {
            return OrderSubmitResult.rejected("Broker not connected");
        }
        boolean ok = client.cancelOrder(brokerOrderId);
        if (ok) {
            return OrderSubmitResult.filled(brokerOrderId);
        }
        return OrderSubmitResult.rejected("Failed to cancel order on OANDA");
    }

    @Override
    public List<Position> getPositions() {
        List<Position> out = new ArrayList<>();
        for (OandaPositionSnapshot row : client.fetchOpenPositions()) {
            java.time.Instant entryTime = row.entryTime() != null ? row.entryTime() : java.time.Instant.EPOCH;
            out.add(new Position(row.instrument(), row.side(), row.units(), row.averagePrice(), entryTime, row.clientTag()));
        }
        return List.copyOf(out);
    }

    @Override
    public AccountState getAccountState() {
        OandaAccountSnapshot account = client.fetchAccountSummary();
        return new AccountState(account.balance(), account.nav(), account.currency());
    }

    @Override
    public void addEventListener(Consumer<BrokerEvent> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    static String toOandaInstrument(String symbol) {
        if (symbol == null) {
            throw new IllegalArgumentException("symbol is required");
        }
        return symbol.replace("/", "_").replace("-", "_").toUpperCase();
    }

    private void emit(BrokerEvent event) {
        for (Consumer<BrokerEvent> listener : listeners) {
            listener.accept(event);
        }
    }
}
