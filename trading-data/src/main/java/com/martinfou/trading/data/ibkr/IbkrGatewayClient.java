package com.martinfou.trading.data.ibkr;

import com.martinfou.trading.core.Order;

import java.util.List;
import java.util.function.Consumer;

/** IBKR TWS / IB Gateway session contract (Story 16.10). */
public interface IbkrGatewayClient {

    void connect();

    void disconnect();

    boolean isConnected();

    /**
     * Places a market order. IBKR fills are asynchronous: the broker must NOT assume the
     * order filled on return. A fill (if any) is delivered later through execution
     * listeners registered via {@link #addExecutionListener(Consumer)}.
     */
    IbkrMarketOrderResult placeMarketOrder(String symbol, double quantity, Order.Side side, String clientTag);

    /**
     * Registers a listener for asynchronous execution notifications (fills / rejects).
     * Called on the IBKR callback thread; implementations must be thread-safe.
     */
    void addExecutionListener(Consumer<IbkrExecution> listener);

    IbkrAccountSnapshot fetchAccountSummary();

    List<IbkrPositionSnapshot> fetchOpenPositions();
}
