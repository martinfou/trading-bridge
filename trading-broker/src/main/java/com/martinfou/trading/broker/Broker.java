package com.martinfou.trading.broker;

import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Position;

import java.util.List;
import java.util.function.Consumer;

/**
 * Shared broker contract for paper and live execution (Story 16.2).
 * OANDA/IBKR implementations live in this module; HTTP clients stay in {@code trading-data}.
 */
public interface Broker extends AutoCloseable {

    boolean isConnected();

    void connect();

    void disconnect();

    /** Re-establish session after network drop or credential refresh. */
    void reconnect();

    OrderSubmitResult submitOrder(Order order);
    OrderSubmitResult cancelOrder(String brokerOrderId);
    default boolean closeTrade(String tradeId, double quantity) {
        return false;
    }

    List<Position> getPositions();

    AccountState getAccountState();

    void addEventListener(Consumer<BrokerEvent> listener);

    @Override
    default void close() {
        disconnect();
    }
}
