package com.martinfou.trading.data.oanda;

import com.martinfou.trading.core.Order;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory OANDA REST stub for unit tests (Story 16.3). */
public final class StubOandaRestClient implements OandaRestClient {

    private final List<OandaMarketOrderResult> scriptedResults = new CopyOnWriteArrayList<>();
    private final List<OandaPositionSnapshot> positions = new CopyOnWriteArrayList<>();
    private OandaAccountSnapshot account = new OandaAccountSnapshot(100_000, 100_000, 0, "USD", 100_000, 0, 0);
    private long nextOrderId = 1;

    public StubOandaRestClient scriptFailure(int httpStatus, String message) {
        scriptedResults.add(OandaMarketOrderResult.failure(httpStatus, message));
        return this;
    }

    public StubOandaRestClient account(OandaAccountSnapshot snapshot) {
        this.account = snapshot;
        return this;
    }

    @Override
    public OandaMarketOrderResult placeMarketOrder(String instrument, long units, String clientTag) {
        if (!scriptedResults.isEmpty()) {
            return scriptedResults.removeFirst();
        }
        double price = instrument.contains("JPY") ? 150.0 : 1.10;
        String orderId = String.valueOf(nextOrderId++);
        String tradeId = "T-" + orderId;
        positions.add(new OandaPositionSnapshot(
            instrument,
            units >= 0 ? Order.Side.BUY : Order.Side.SELL,
            Math.abs(units),
            price,
            clientTag,
            null));
        return OandaMarketOrderResult.success(orderId, tradeId, price);
    }

    @Override
    public OandaMarketOrderResult placeOrder(String type, String instrument, long units, double price, double stopLoss, double takeProfit, double trailingStop, boolean guaranteed, String clientTag) {
        if (!scriptedResults.isEmpty()) {
            return scriptedResults.removeFirst();
        }
        double fillPrice = price > 0 ? price : (instrument.contains("JPY") ? 150.0 : 1.10);
        String orderId = String.valueOf(nextOrderId++);
        String tradeId = type.equalsIgnoreCase("MARKET") ? "T-" + orderId : null;
        if (type.equalsIgnoreCase("MARKET")) {
            positions.add(new OandaPositionSnapshot(
                instrument,
                units >= 0 ? Order.Side.BUY : Order.Side.SELL,
                Math.abs(units),
                fillPrice,
                clientTag,
                null));
        }
        return new OandaMarketOrderResult(201, orderId, tradeId, type.equalsIgnoreCase("MARKET") ? fillPrice : null, null);
    }

    @Override
    public boolean cancelOrder(String orderId) {
        return true;
    }

    @Override
    public boolean closeTrade(String tradeId, String units) {
        return true;
    }

    @Override
    public List<java.util.Map<String, Object>> fetchTransactions(int limit) {
        return List.of();
    }

    @Override
    public OandaAccountSnapshot fetchAccountSummary() {
        return new OandaAccountSnapshot(100_000, 100_000, 0, "USD", 100_000, 0, 0);
    }

    @Override
    public List<OandaPositionSnapshot> fetchOpenPositions() {
        return new ArrayList<>(positions);
    }

    @Override
    public java.util.Map<String, Object> fetchOrderBook(String instrument) {
        return java.util.Map.of("instrument", instrument, "orderBook", java.util.Map.of());
    }
}
