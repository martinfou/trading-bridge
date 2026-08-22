package com.martinfou.trading.data;

import com.martinfou.trading.core.Bar;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HistoricalDataLoadersTest {

    @Test
    void testYahooFinanceCsvLoading() throws IOException {
        String csvContent = """
            Date,Open,High,Low,Close,Adj Close,Volume
            2024-01-02,4980.25,5020.50,4975.00,5010.00,5010.00,154200
            2024-01-03,5012.00,5035.75,5005.25,5025.50,5025.50,162100
            """;

        List<Bar> bars = YahooFinanceDataLoader.loadCsv(
            new ByteArrayInputStream(csvContent.getBytes(StandardCharsets.UTF_8)),
            "MES"
        );

        assertEquals(2, bars.size());
        Bar b0 = bars.get(0);
        assertEquals("MES", b0.symbol());
        assertEquals(Instant.parse("2024-01-02T00:00:00Z"), b0.timestamp());
        assertEquals(4980.25, b0.open(), 1e-6);
        assertEquals(5020.50, b0.high(), 1e-6);
        assertEquals(4975.00, b0.low(), 1e-6);
        assertEquals(5010.00, b0.close(), 1e-6);
        assertEquals(154200L, b0.volume());

        // Symbol mapping
        assertEquals("MES", YahooFinanceDataLoader.mapProxySymbol("ES=F"));
        assertEquals("M2K", YahooFinanceDataLoader.mapProxySymbol("RTY=F"));
        assertEquals("MNQ", YahooFinanceDataLoader.mapProxySymbol("NQ=F"));
        assertEquals("IWM", YahooFinanceDataLoader.mapProxySymbol("IWM"));
    }

    @Test
    void testIbkrCsvLoading() throws IOException {
        String ibkrCsv = """
            Date,Open,High,Low,Close,Volume,Average,BarCount
            20240102  09:30:00,4980.0,4995.0,4978.0,4990.0,1250,4986.5,350
            20240102  10:30:00,4990.0,5010.0,4985.0,5005.0,2100,4998.0,480
            """;

        List<Bar> bars = IbkrHistoricalDataLoader.loadCsv(
            new ByteArrayInputStream(ibkrCsv.getBytes(StandardCharsets.UTF_8)),
            "MES"
        );

        assertEquals(2, bars.size());
        Bar b0 = bars.get(0);
        assertEquals("MES", b0.symbol());
        assertEquals(Instant.parse("2024-01-02T09:30:00Z"), b0.timestamp());
        assertEquals(4980.0, b0.open(), 1e-6);
        assertEquals(5005.0, bars.get(1).close(), 1e-6);
    }
}
