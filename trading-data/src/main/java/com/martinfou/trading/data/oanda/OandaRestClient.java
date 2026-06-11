package com.martinfou.trading.data.oanda;

import java.util.List;

/**
 * OANDA v20 REST operations used by {@code OandaBroker} (Story 16.3).
 * HTTP implementation lives in this module; broker adapter in {@code trading-broker}.
 */
public interface OandaRestClient {

    OandaMarketOrderResult placeMarketOrder(String instrument, long units, String clientTag);
    OandaMarketOrderResult placeOrder(String type, String instrument, long units, double price, double stopLoss, double takeProfit, double trailingStop, boolean guaranteed, String clientTag);
    boolean cancelOrder(String orderId);
    boolean closeTrade(String tradeId, String units);
    java.util.List<java.util.Map<String, Object>> fetchTransactions(int limit);

    OandaAccountSnapshot fetchAccountSummary();

    List<OandaPositionSnapshot> fetchOpenPositions();

    java.util.Map<String, Object> fetchOrderBook(String instrument);
}
