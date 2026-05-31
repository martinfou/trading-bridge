package com.martinfou.trading.data.oanda;

import java.util.List;

/**
 * OANDA v20 REST operations used by {@code OandaBroker} (Story 16.3).
 * HTTP implementation lives in this module; broker adapter in {@code trading-broker}.
 */
public interface OandaRestClient {

    OandaMarketOrderResult placeMarketOrder(String instrument, long units, String clientTag);

    OandaAccountSnapshot fetchAccountSummary();

    List<OandaPositionSnapshot> fetchOpenPositions();
}
