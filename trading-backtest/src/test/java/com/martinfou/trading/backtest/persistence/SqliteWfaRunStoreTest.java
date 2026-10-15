package com.martinfou.trading.backtest.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SqliteWfaRunStoreTest {

    @TempDir
    Path tempDir;

    private SqliteWfaRunStore store;

    @BeforeEach
    void setUp() {
        Path dbPath = tempDir.resolve("test_wfa.db");
        store = new SqliteWfaRunStore(dbPath);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void testSaveAndFindById() {
        Instant now = Instant.now();
        WfaRunRecord record = new WfaRunRecord(
            "wfa-123",
            "LtCrossMomentumStrategy",
            "M2K",
            "FUTURES",
            "H1",
            180,
            60,
            false,
            10000.0,
            0.85,
            1.75,
            12.5,
            1.9,
            24.5,
            42,
            "COMPLETED",
            now,
            now.plusSeconds(10),
            null
        );

        store.save(record);

        Optional<WfaRunRecord> loaded = store.findById("wfa-123");
        assertTrue(loaded.isPresent());
        assertEquals("wfa-123", loaded.get().wfaId());
        assertEquals("LtCrossMomentumStrategy", loaded.get().strategyName());
        assertEquals("M2K", loaded.get().symbol());
        assertEquals("FUTURES", loaded.get().assetClass());
        assertEquals("H1", loaded.get().timeframe());
        assertEquals(1.75, loaded.get().oosSharpe());
        assertEquals("COMPLETED", loaded.get().status());
    }

    @Test
    void testUpdateRecordOnConflict() {
        Instant now = Instant.now();
        WfaRunRecord pending = new WfaRunRecord(
            "wfa-456",
            "LtRsiMeanRevStrategy",
            "EUR_USD",
            "FOREX",
            "H1",
            180,
            60,
            false,
            10000.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0,
            "RUNNING",
            now,
            null,
            null
        );
        store.save(pending);

        WfaRunRecord completed = new WfaRunRecord(
            "wfa-456",
            "LtRsiMeanRevStrategy",
            "EUR_USD",
            "FOREX",
            "H1",
            180,
            60,
            false,
            10000.0,
            0.92,
            2.10,
            8.4,
            2.4,
            35.0,
            55,
            "COMPLETED",
            now,
            now.plusSeconds(30),
            null
        );
        store.save(completed);

        Optional<WfaRunRecord> loaded = store.findById("wfa-456");
        assertTrue(loaded.isPresent());
        assertEquals("COMPLETED", loaded.get().status());
        assertEquals(2.10, loaded.get().oosSharpe());
        assertNotNull(loaded.get().completedAt());
    }

    @Test
    void testFindLatestCompleted() {
        Instant t1 = Instant.now().minusSeconds(100);
        Instant t2 = Instant.now().minusSeconds(10);

        store.save(new WfaRunRecord("wfa-old", "TestStrat", "MES", "FUTURES", "H1", 90, 30, false, 10000, 0.7, 1.2, 10, 1.3, 15, 20, "COMPLETED", t1, t1.plusSeconds(5), null));
        store.save(new WfaRunRecord("wfa-new", "TestStrat", "MES", "FUTURES", "H1", 90, 30, false, 10000, 0.8, 1.6, 9, 1.5, 22, 25, "COMPLETED", t2, t2.plusSeconds(5), null));

        Optional<WfaRunRecord> latest = store.findLatestCompleted("TestStrat", "MES");
        assertTrue(latest.isPresent());
        assertEquals("wfa-new", latest.get().wfaId());
    }
}
