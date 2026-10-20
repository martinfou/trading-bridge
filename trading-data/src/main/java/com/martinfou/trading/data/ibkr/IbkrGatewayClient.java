package com.martinfou.trading.data.ibkr;

import com.martinfou.trading.core.Order;

import java.util.List;
import java.util.function.Consumer;

/**
 * IBKR TWS / IB Gateway session contract (Story 16.10).
 *
 * <p>Implementations: {@link EClientIbkrGatewayClient} (real socket API) and
 * {@link StubIbkrGatewayClient} (in-memory, tests).</p>
 */
public interface IbkrGatewayClient {

    void connect();

    void disconnect();

    boolean isConnected();

    /**
     * Places an order (MARKET / LIMIT / STOP, optional native bracket) and waits for IB's
     * acknowledgement. Fills are delivered asynchronously to {@link #addOrderListener}.
     */
    IbkrOrderAck placeOrder(IbkrOrderRequest request);

    /** Requests cancellation; the resulting {@link IbkrOrderUpdate.Kind#CANCELLED} arrives asynchronously. */
    IbkrOrderAck cancelOrder(String brokerOrderId);

    void addOrderListener(Consumer<IbkrOrderUpdate> listener);

    IbkrAccountSnapshot fetchAccountSummary();

    List<IbkrPositionSnapshot> fetchOpenPositions();

    default List<IbkrOpenOrder> fetchOpenOrders() {
        return List.of();
    }

    // ------------------------------------------------------------------ read-only reporting (hermes-web)

    /**
     * Working orders of the whole account, including those placed by other API clients or by hand
     * ({@code reqAllOpenOrders}). Defaults to this client's own orders.
     */
    default List<IbkrOpenOrder> fetchAllOpenOrders() {
        return fetchOpenOrders();
    }

    /** Account values in base currency plus FX rates to base. Defaults to the basic summary. */
    default IbkrAccountDetails fetchAccountDetails() {
        IbkrAccountSnapshot s = fetchAccountSummary();
        return new IbkrAccountDetails(null, s.currency(),
            java.util.Map.of(IbkrAccountDetails.NET_LIQUIDATION, s.equity(), IbkrAccountDetails.TOTAL_CASH, s.balance()),
            java.util.Map.of());
    }

    /** Executions of the current IB trading day joined with their commission reports. */
    default List<IbkrExecutionReport> fetchExecutions() {
        return List.of();
    }

    /**
     * Legacy synchronous MARKET helper: acknowledgement only (no fill price).
     * Prefer {@link #placeOrder(IbkrOrderRequest)} with an order listener.
     */
    default IbkrMarketOrderResult placeMarketOrder(String symbol, double quantity, Order.Side side, String clientTag) {
        IbkrOrderAck ack = placeOrder(IbkrOrderRequest.market(clientTag, symbol, side, quantity));
        return ack.accepted()
            ? new IbkrMarketOrderResult(true, ack.brokerOrderId(), null, null, null)
            : IbkrMarketOrderResult.failure(ack.errorMessage());
    }
}
