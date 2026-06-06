package com.martinfou.trading.intelligence.research;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ResearchInspirationStoreTest {

    private Path tempDb;
    private ResearchInspirationStore store;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        tempDb = tempDir.resolve("test_research.db");
        store = new ResearchInspirationStore(tempDb);
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void crudOperations() {
        Instant now = Instant.parse("2026-06-06T12:00:00Z");
        ResearchInspiration item = new ResearchInspiration(
            "insp-1",
            "Turtle Trend Following",
            "Richard Dennis Turtles channel breakout",
            "PENDING",
            null,
            null,
            null,
            now,
            now
        );

        // Save
        store.save(item);

        // Get
        Optional<ResearchInspiration> fetched = store.get("insp-1");
        assertTrue(fetched.isPresent());
        assertEquals("Turtle Trend Following", fetched.get().title());
        assertEquals("PENDING", fetched.get().status());
        assertNull(fetched.get().resultStatus());

        // Update
        ResearchInspiration updated = new ResearchInspiration(
            "insp-1",
            "Turtle Trend Following",
            "Richard Dennis Turtles channel breakout",
            "COMPLETED",
            "PASS",
            "STRAT_TURTLE",
            "{\"netProfit\":120.5}",
            now,
            now.plusSeconds(3600)
        );
        store.save(updated);

        // Get again
        fetched = store.get("insp-1");
        assertTrue(fetched.isPresent());
        assertEquals("COMPLETED", fetched.get().status());
        assertEquals("PASS", fetched.get().resultStatus());
        assertEquals("STRAT_TURTLE", fetched.get().strategyId());
        assertEquals("{\"netProfit\":120.5}", fetched.get().metricsJson());
        assertEquals(now.plusSeconds(3600), fetched.get().updatedAt());

        // List
        List<ResearchInspiration> all = store.listAll();
        assertEquals(1, all.size());
        assertEquals("insp-1", all.get(0).id());

        // Delete
        store.delete("insp-1");
        fetched = store.get("insp-1");
        assertFalse(fetched.isPresent());
        assertTrue(store.listAll().isEmpty());
    }
}
