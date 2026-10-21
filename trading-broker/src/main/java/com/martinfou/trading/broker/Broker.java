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

    /**
     * Cancel all working (unfilled) orders at the broker.
     * Used by the kill switch / emergency liquidation path so that pending stop-loss,
     * take-profit, and limit orders do not fire after the strategy is decommissioned.
     *
     * @return number of working orders cancelled. Default is {@code 0} (not supported);
     *         broker implementations MUST override this for live/paper safety.
     */
    default int cancelAllOrders() {
        return 0;
    }

    /**
     * Flatten (close) all open positions at the broker, submitting market orders in the
     * opposite direction. Used by the kill switch as the last-resort de-risking step.
     *
     * @return number of positions flattened. Default is {@code 0} (not supported);
     *         broker implementations MUST override this for live/paper safety.
     */
    default int flattenAllPositions() {
        return 0;
    }

    /**
     * Whether the LAST position read was actually answered by the broker.
     *
     * <p>An empty list is ambiguous: it means "flat" only when the broker answered. Callers that act
     * on emptiness (a kill switch, a reconciliation) must consult this, otherwise a dead connection
     * reads as a flat account. Defaults to {@code true} for brokers whose reads cannot fail silently;
     * implementations that can must override it.</p>
     */
    default boolean lastPositionQueryConfirmed() {
        return true;
    }

    List<Position> getPositions();

    AccountState getAccountState();

    void addEventListener(Consumer<BrokerEvent> listener);

    default java.time.Instant getUptimeStart() {
        return null;
    }

    default java.time.Instant getLastReplyTime() {
        return null;
    }

    default int getConnectionFailures() {
        return 0;
    }

    @Override
    default void close() {
        disconnect();
    }
}
