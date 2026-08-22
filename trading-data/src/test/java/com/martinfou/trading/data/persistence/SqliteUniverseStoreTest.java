package com.martinfou.trading.data.persistence;

import com.martinfou.trading.core.InstrumentDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SqliteUniverseStoreTest {

    @TempDir
    Path tempDir;

    private SqliteUniverseStore store;

    @BeforeEach
    void setUp() {
        Path dbPath = tempDir.resolve("test-universe.db");
        store = new SqliteUniverseStore(dbPath);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    void testSeedsDefaultInstruments() {
        List<InstrumentDefinition> all = store.listAll();
        assertNotNull(all);
        assertTrue(all.size() >= 17, "Expected at least 17 default instruments (8 forex, 4 futures, 5 equities)");

        Optional<InstrumentDefinition> mes = store.getBySymbol("MES");
        assertTrue(mes.isPresent());
        assertEquals("FUTURES", mes.get().assetClass());
        assertEquals(5.0, mes.get().pointValue());
        assertFalse(mes.get().isCustom());

        Optional<InstrumentDefinition> eurusd = store.getBySymbol("EUR_USD");
        assertTrue(eurusd.isPresent());
        assertEquals("FOREX", eurusd.get().assetClass());
        assertFalse(eurusd.get().isCustom());

        Optional<InstrumentDefinition> iwm = store.getBySymbol("IWM");
        assertTrue(iwm.isPresent());
        assertEquals("EQUITIES", iwm.get().assetClass());
        assertFalse(iwm.get().isCustom());
    }

    @Test
    void testListByAssetClass() {
        List<InstrumentDefinition> futures = store.listByAssetClass("FUTURES");
        assertEquals(4, futures.size());
        assertTrue(futures.stream().anyMatch(f -> f.symbol().equals("MES")));

        List<InstrumentDefinition> forex = store.listByAssetClass("FOREX");
        assertEquals(8, forex.size());
        assertTrue(forex.stream().anyMatch(f -> f.symbol().equals("EUR_USD")));

        List<InstrumentDefinition> equities = store.listByAssetClass("EQUITIES");
        assertEquals(5, equities.size());
        assertTrue(equities.stream().anyMatch(f -> f.symbol().equals("SPY")));
    }

    @Test
    void testAddAndRetrieveCustomMinicap() {
        InstrumentDefinition ijr = InstrumentDefinition.custom("IJR", "iShares Core S&P Small-Cap ETF", "EQUITIES", 1.0, 0.01);
        boolean saved = store.save(ijr);
        assertTrue(saved);

        Optional<InstrumentDefinition> retrieved = store.getBySymbol("IJR");
        assertTrue(retrieved.isPresent());
        assertEquals("IJR", retrieved.get().symbol());
        assertEquals("iShares Core S&P Small-Cap ETF", retrieved.get().name());
        assertEquals("EQUITIES", retrieved.get().assetClass());
        assertTrue(retrieved.get().isCustom());

        List<InstrumentDefinition> equities = store.listByAssetClass("EQUITIES");
        assertEquals(6, equities.size());
        assertTrue(equities.stream().anyMatch(e -> e.symbol().equals("IJR")));
    }

    @Test
    void testDeleteCustomInstrument() {
        InstrumentDefinition vb = InstrumentDefinition.custom("VB", "Vanguard Small-Cap ETF", "EQUITIES", 1.0, 0.01);
        store.save(vb);
        assertTrue(store.getBySymbol("VB").isPresent());

        boolean deleted = store.delete("VB");
        assertTrue(deleted);
        assertFalse(store.getBySymbol("VB").isPresent());
    }

    @Test
    void testCannotDeleteBuiltInSeedInstrument() {
        boolean deleted = store.delete("MES");
        assertFalse(deleted, "Should not be allowed to delete core seed instrument");
        assertTrue(store.getBySymbol("MES").isPresent());
    }
}
