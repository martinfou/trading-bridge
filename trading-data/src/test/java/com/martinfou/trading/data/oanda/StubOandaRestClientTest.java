package com.martinfou.trading.data.oanda;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StubOandaRestClientTest {

    @Test
    void placeMarketOrder_successByDefault() {
        var client = new StubOandaRestClient();
        OandaMarketOrderResult result = client.placeMarketOrder("EUR_USD", 1000, "tag");
        assertTrue(result.success());
        assertEquals(1, client.fetchOpenPositions().size());
    }

    @Test
    void placeMarketOrder_scriptedFailure() {
        var client = new StubOandaRestClient().scriptFailure(400, "insufficient margin");
        OandaMarketOrderResult result = client.placeMarketOrder("EUR_USD", 1000, "tag");
        assertTrue(result.errorMessage().contains("insufficient margin"));
    }
}
