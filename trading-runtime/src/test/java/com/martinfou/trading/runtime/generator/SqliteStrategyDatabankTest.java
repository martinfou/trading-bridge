package com.martinfou.trading.runtime.generator;

import com.martinfou.trading.runtime.generator.StrategyGeneratorService.DatabankCandidate;
import com.martinfou.trading.runtime.generator.StrategyGenome.BlockType;
import com.martinfou.trading.runtime.generator.StrategyGenome.RuleGene;
import com.martinfou.trading.strategies.StrategyCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqliteStrategyDatabankTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanupCatalog() {
        StrategyCatalog.resetForTesting();
    }

    private static DatabankCandidate sampleCandidate(String id, double score, boolean promoted) {
        StrategyGenome genome = new StrategyGenome(
            id,
            "MGC",
            "M30",
            "BREAKOUT",
            "BOTH",
            "STOP",
            List.of(new RuleGene(BlockType.DONCHIAN_BREAKOUT, 20, 0, 0, 0, 0.0)),
            List.of(new RuleGene(BlockType.DONCHIAN_BREAKOUT, 20, 0, 0, 0, 0.0)),
            15,
            0.25,
            3,
            14,
            1.8,
            3.6,
            0.0,
            24,
            0,
            24,
            31,
            "CONTINUOUS",
            16
        );
        String code = genome.toJavaSourceCode(1500.0, 2.4, 2.1, 3.5, 4.2, 42, score);
        return new DatabankCandidate(
            id,
            "MGC",
            "M30",
            "2024-2026",
            "BREAKOUT",
            "BOTH",
            "STOP",
            "ELITE",
            score,
            42,
            28,
            14,
            1500.0,
            1000.0,
            500.0,
            15.0,
            2.4,
            2.5,
            2.1,
            3.5,
            3.6,
            3.2,
            58.0,
            4.2,
            3.57,
            0.94,
            250.0,
            2.0,
            1,
            Instant.parse("2026-01-01T00:00:00Z"),
            promoted,
            "sqlite://strategies.db#" + id,
            "Donchian(20) Breakout",
            "Donchian(20) Breakdown",
            "STOP @ 15-bar High/Low",
            "SL 1.8x ATR | TP 3.6x ATR",
            List.of("DONCHIAN_BREAKOUT"),
            List.of(10000.0, 10500.0, 11500.0),
            2,
            genome,
            code,
            10000.0,
            1.0,
            0.62,
            0.01,
            1,
            8,
            300.0,
            2.3,
            84.0,
            3,
            List.of(),
            "Mon–Fri | 24h Session | Overnight Hold Allowed",
            0,
            24,
            31,
            "CONTINUOUS",
            16
        );
    }

    @Test
    void upsertLoadConflictUpdateAndDeleteRoundTrip() {
        SqliteStrategyDatabank db = new SqliteStrategyDatabank(tempDir.resolve("strategies.db"));
        DatabankCandidate c1 = sampleCandidate("MgcM30Breakout10001", 82.5, false);
        DatabankCandidate c2 = sampleCandidate("MgcM30Breakout10002", 91.0, true);

        assertTrue(db.upsertAll(List.of(c1, c2)));

        List<DatabankCandidate> loaded = db.loadAll();
        assertEquals(2, loaded.size());
        // Ordered by composite_score DESC
        assertEquals("MgcM30Breakout10002", loaded.get(0).id());
        assertTrue(loaded.get(0).promoted());
        // Rehydrated javaSourceCode on load even though stripped in SQLite
        assertNotNull(loaded.get(0).javaSourceCode());
        assertTrue(loaded.get(0).javaSourceCode().contains("class MgcM30Breakout10002"));

        // Conflict update + NaN score guard
        DatabankCandidate c1PromotedNan = sampleCandidate("MgcM30Breakout10001", Double.NaN, true);
        assertTrue(db.upsert(c1PromotedNan));

        List<DatabankCandidate> afterUpdate = db.loadAll();
        assertEquals(2, afterUpdate.size());
        DatabankCandidate updatedC1 = afterUpdate.stream()
            .filter(c -> c.id().equals("MgcM30Breakout10001"))
            .findFirst()
            .orElseThrow();
        assertTrue(updatedC1.promoted());

        db.delete("MgcM30Breakout10001");
        assertEquals(1, db.loadAll().size());

        db.deleteAll();
        assertTrue(db.loadAll().isEmpty());
    }

    @Test
    void serviceDeleteAndClearUnregisterFromStrategyCatalog() {
        SqliteStrategyDatabank db = new SqliteStrategyDatabank(tempDir.resolve("strategies.db"));
        db.upsert(sampleCandidate("MgcM30Breakout77777", 88.0, true));

        try (StrategyGeneratorService svc = new StrategyGeneratorService(tempDir)) {
            assertTrue(StrategyCatalog.contains("MgcM30Breakout77777"));
            svc.deleteCandidate("MgcM30Breakout77777");
            assertFalse(StrategyCatalog.contains("MgcM30Breakout77777"));
            assertTrue(svc.listDatabank().isEmpty());
        }
    }
}
