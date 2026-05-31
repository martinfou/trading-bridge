package com.martinfou.trading.data.oanda;

import com.martinfou.trading.core.Order;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** In-memory OANDA REST stub for unit tests (Story 16.3). */
public final class StubOandaRestClient implements OandaRestClient {

    private final List<OandaMarketOrderResult> scriptedResults = new CopyOnWriteArrayList<>();
    private final List<OandaPositionSnapshot> positions = new CopyOnWriteArrayList<>();
    private OandaAccountSnapshot account = new OandaAccountSnapshot(100_000, 100_000, 0, "USD");
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
            price));
        return OandaMarketOrderResult.success(orderId, tradeId, price);
    }

    @Override
    public OandaAccountSnapshot fetchAccountSummary() {
        return account;
    }

    @Override
    public List<OandaPositionSnapshot> fetchOpenPositions() {
        return List.copyOf(positions);
    }
}
