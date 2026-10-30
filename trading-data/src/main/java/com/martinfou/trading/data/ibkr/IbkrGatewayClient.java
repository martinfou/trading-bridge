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
     * Meme ordre, avec le nom de la strategie transmis a IBKR dans `orderRef`. C'est ce champ qui permet aux
     * fills d'etre rattaches a une strategie dans la lane P&L ; sans lui ils sont des leaks.
     */
    default IbkrMarketOrderResult placeMarketOrder(String symbol, double quantity, Order.Side side,
                                                   String clientTag, String orderRef) {
        return placeMarketOrder(symbol, quantity, side, clientTag);
    }

    /**
     * Places a stop order (STP) on the IBKR venue with {@code tif = GTC}.
     * Used for protective stop-loss orders (Story 46.8).
     */
    default IbkrMarketOrderResult placeStopOrder(String symbol, double quantity, Order.Side side,
                                                 double stopPrice, String clientTag, String orderRef) {
        return placeStopOrder(symbol, quantity, side, stopPrice, clientTag, orderRef, true, null, 0);
    }

    /**
     * Places a stop order (STP) on the IBKR venue with {@code tif = GTC}, optional {@code closeOnly},
     * and optional OCA group attributes.
     */
    default IbkrMarketOrderResult placeStopOrder(String symbol, double quantity, Order.Side side,
                                                 double stopPrice, String clientTag, String orderRef,
                                                 boolean closeOnly, String ocaGroup, int ocaType) {
        return IbkrMarketOrderResult.failure("placeStopOrder not implemented by this client");
    }

    /**
     * Places a limit order (LMT) on the IBKR venue with {@code tif = GTC}.
     * Used for take-profit / limit orders (Story 46.8).
     */
    default IbkrMarketOrderResult placeLimitOrder(String symbol, double quantity, Order.Side side,
                                                  double limitPrice, String clientTag, String orderRef) {
        return placeLimitOrder(symbol, quantity, side, limitPrice, clientTag, orderRef, true, null, 0);
    }

    /**
     * Places a limit order (LMT) on the IBKR venue with {@code tif = GTC}, optional {@code closeOnly},
     * and optional OCA group attributes.
     */
    default IbkrMarketOrderResult placeLimitOrder(String symbol, double quantity, Order.Side side,
                                                  double limitPrice, String clientTag, String orderRef,
                                                  boolean closeOnly, String ocaGroup, int ocaType) {
        return IbkrMarketOrderResult.failure("placeLimitOrder not implemented by this client");
    }

    /**
     * Registers a listener for asynchronous execution notifications (fills / rejects).
     * Called on the IBKR callback thread; implementations must be thread-safe.
     */
    void addExecutionListener(Consumer<IbkrExecution> listener);

    IbkrAccountSnapshot fetchAccountSummary();

    List<IbkrPositionSnapshot> fetchOpenPositions();

    /**
     * Cancels a working order by its broker order ID.
     *
     * @param brokerOrderId the ID returned when the order was placed
     * @return true if the cancellation request was dispatched to the venue
     */
    default boolean cancelOrder(String brokerOrderId) {
        return false;
    }

    /**
     * Cancels all working orders across the account (global cancel).
     *
     * @return number of orders requested cancelled
     */
    default int cancelAllOrders() {
        return 0;
    }

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
