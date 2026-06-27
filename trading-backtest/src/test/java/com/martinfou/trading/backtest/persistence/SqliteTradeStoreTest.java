package com.martinfou.trading.backtest.persistence;

import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Trade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqliteTradeStoreTest {

    @TempDir
    Path tempDir;

    private Path dbFile;
    private SqliteTradeStore store;

    @BeforeEach
    void setUp() {
        dbFile = tempDir.resolve("test_trades.db");
        store = new SqliteTradeStore(dbFile);
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void testInsertAndRetrieveTrades() {
        Instant entryTime = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        Instant exitTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        Trade t1 = new Trade("EUR_USD", Order.Side.BUY, 1.1000, 1.1050, 1000.0, entryTime, exitTime, 1.0, 1.0950, 1.1100);
        Trade t2 = new Trade("EUR_USD", Order.Side.SELL, 1.1050, 1.1020, 1000.0, entryTime, exitTime, 1.0, 1.1100, 1.0950);

        store.insert("run_1", t1);
        store.insert("run_1", t2);

        List<Trade> run1Trades = store.getTrades("run_1");
        assertEquals(2, run1Trades.size());

        Trade retrieved1 = run1Trades.get(0);
        assertEquals(t1.id(), retrieved1.id());
        assertEquals("EUR_USD", retrieved1.symbol());
        assertEquals(Order.Side.BUY, retrieved1.side());
        assertEquals(1.1000, retrieved1.entryPrice(), 0.00001);
        assertEquals(1.1050, retrieved1.exitPrice(), 0.00001);
        assertEquals(1000.0, retrieved1.quantity(), 0.00001);
        assertEquals(entryTime, retrieved1.entryTime());
        assertEquals(exitTime, retrieved1.exitTime());
        assertEquals(t1.pnl(), retrieved1.pnl(), 0.00001);
        assertEquals(1.0950, retrieved1.stopLoss(), 0.00001);
        assertEquals(1.1100, retrieved1.takeProfit(), 0.00001);

        Trade retrieved2 = run1Trades.get(1);
        assertEquals(t2.id(), retrieved2.id());
        assertEquals(Order.Side.SELL, retrieved2.side());
        assertEquals(t2.pnl(), retrieved2.pnl(), 0.00001);

        // Delete for run
        store.deleteForRun("run_1");
        assertTrue(store.getTrades("run_1").isEmpty());
    }

    @Test
    void testInsertAllBatch() {
        Instant entryTime = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        Instant exitTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        Trade t1 = new Trade("EUR_USD", Order.Side.BUY, 1.1000, 1.1050, 1000.0, entryTime, exitTime, 1.0, 1.0950, 1.1100);
        Trade t2 = new Trade("EUR_USD", Order.Side.SELL, 1.1050, 1.1020, 1000.0, entryTime, exitTime, 1.0, 1.1100, 1.0950);

        store.insertAll("run_batch", List.of(t1, t2));

        List<Trade> retrieved = store.getTrades("run_batch");
        assertEquals(2, retrieved.size());
        assertEquals(t1.id(), retrieved.get(0).id());
        assertEquals(t2.id(), retrieved.get(1).id());
    }
}
