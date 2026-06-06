package com.martinfou.trading.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BarsSourceDeserializationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void startRunRequest_acceptsYearRange() throws Exception {
        RunManager.StartRunRequest request = MAPPER.readValue("""
            {
              "strategyId": "LondonOpenRangeBreakout",
              "symbol": "EUR_USD",
              "mode": "BACKTEST",
              "barsSource": { "type": "year", "year": "2006-2025" }
            }
            """, RunManager.StartRunRequest.class);

        assertEquals("year", request.barsSource().type());
        assertEquals("2006-2025", request.barsSource().yearSpec());
    }

    @Test
    void startRunRequest_acceptsSingleYear() throws Exception {
        RunManager.StartRunRequest request = MAPPER.readValue("""
            {
              "strategyId": "LondonOpenRangeBreakout",
              "symbol": "EUR_USD",
              "mode": "BACKTEST",
              "barsSource": { "type": "year", "year": 2012 }
            }
            """, RunManager.StartRunRequest.class);

        assertEquals("2012", request.barsSource().yearSpec());
    }
}
