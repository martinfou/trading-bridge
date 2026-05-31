package com.martinfou.trading.data.ibkr;

import com.martinfou.trading.core.Order;

import java.util.List;

/** IBKR TWS / IB Gateway session contract (Story 16.10). */
public interface IbkrGatewayClient {

    void connect();

    void disconnect();

    boolean isConnected();

    IbkrMarketOrderResult placeMarketOrder(String symbol, double quantity, Order.Side side, String clientTag);

    IbkrAccountSnapshot fetchAccountSummary();

    List<IbkrPositionSnapshot> fetchOpenPositions();
}
