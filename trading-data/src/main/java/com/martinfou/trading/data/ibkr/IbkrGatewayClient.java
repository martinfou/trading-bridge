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

    /**
     * Whether the LAST {@link #fetchOpenPositions()} call was actually answered by the venue.
     *
     * <p>An empty position list is ambiguous on its own: it means "flat" only if the venue answered.
     * A kill switch that reads an empty list produced by a timeout or a dead connection concludes the
     * account is flat while it is merely blind, so callers that act on emptiness must consult this.</p>
     */
    default boolean lastPositionQueryConfirmed() {
        // Fail-closed: an implementation that does not track this cannot prove it answered, so it must
        // not be trusted to certify a flat account.
        return false;
    }
}
