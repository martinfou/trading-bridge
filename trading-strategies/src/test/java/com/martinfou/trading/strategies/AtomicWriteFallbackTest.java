package com.martinfou.trading.strategies;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Story 50.1 L1.7 — when the filesystem rejects {@code ATOMIC_MOVE}, the state write degrades to a
 * plain replace-move (logged WARN) instead of failing the whole save. A filesystem without atomic
 * move must not make the state unwritable.
 */
class AtomicWriteFallbackTest {

    private static class NoopStrategy implements Strategy {
        @Override public String name() { return "Noop"; }
        @Override public void onBar(Bar bar) { }
        @Override public void onTick(double bid, double ask, long volume) { }
        @Override public List<Order> getPendingOrders() { return List.of(); }
        @Override public void reset() { }
    }

    /** A runner whose first-attempt atomic move always fails, to exercise the fallback path. */
    private static class NoAtomicMoveRunner extends LiveStrategyRunner {
        NoAtomicMoveRunner(Path stateDir) {
            super("k", "acc", new NoopStrategy(), "atomicfallback", "H1", 60, stateDir);
        }

        @Override
        void moveAtomically(Path tmp, Path file) throws IOException {
            throw new AtomicMoveNotSupportedException(tmp.toString(), file.toString(), "simulated");
        }
    }

    @Test
    void atomicMoveFailureFallsBackToPlainMoveAndWritesState(@TempDir Path tmp) throws Exception {
        Path stateDir = tmp.resolve("state");

        LiveStrategyRunner r = new NoAtomicMoveRunner(stateDir);
        r.saveStateNow();

        Path stateFile = stateDir.resolve("live-strategy-state-atomicfallback.json");
        assertTrue(Files.exists(stateFile),
            "the state file must be written via the plain-move fallback when ATOMIC_MOVE is refused");
        JsonNode root = new ObjectMapper().readTree(stateFile.toFile());
        assertEquals("atomicfallback", root.get("strategy").asText(),
            "the fallback write must be complete and parseable, not a truncated tmp");

        // The monitor file (in /tmp) went through the same fallback; clean it up.
        Files.deleteIfExists(Paths.get("/tmp/paper-status-atomicfallback.json"));
    }
}
