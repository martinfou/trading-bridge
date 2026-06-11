package com.martinfou.trading.data.oanda;

import java.util.Map;

/**
 * Service to retrieve sentiment and order book data from OANDA (Phase 3).
 */
public class OandaSentimentService {

    private final OandaRestClient restClient;

    public OandaSentimentService(OandaRestClient restClient) {
        this.restClient = restClient;
    }

    public Map<String, Object> getOrderBook(String instrument) {
        return restClient.fetchOrderBook(instrument);
    }
}
