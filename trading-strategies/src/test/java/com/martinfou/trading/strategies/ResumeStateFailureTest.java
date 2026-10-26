package com.martinfou.trading.strategies;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Story 50.1 L1.5 — a corrupt/unreadable state file must NOT read as a clean start. It sets
 * {@code stateSource = resume-failed} (surfaced in the monitor) and is logged at ERROR in the code,
 * instead of silently dropping the book and leaving a live broker position unmanaged.
 */
class ResumeStateFailureTest {

    private static class NoopStrategy implements Strategy {
        @Override public String name() { return "Noop"; }
        @Override public void onBar(Bar bar) { }
        @Override public void onTick(double bid, double ask, long volume) { }
        @Override public List<Order> getPendingOrders() { return List.of(); }
        @Override public void reset() { }
    }

    @Test
    void truncatedJsonSetsResumeFailedAndRestoresNothing(@TempDir Path tmp) throws Exception {
        Path stateDir = tmp.resolve("state");
        Files.createDirectories(stateDir);
        // Deliberately truncated JSON: parse throws, the catch must set resume-failed, not a silent fresh.
        Files.writeString(stateDir.resolve("live-strategy-state-failtest.json"),
            "{ \"strategy\" : \"failtest\", \"instrument\" : \"GBP_JPY\", \"totalEntries\" : ");

        LiveStrategyRunner r = new LiveStrategyRunner(
            "k", "acc", new NoopStrategy(), "failtest", "H1", 60, stateDir);
        r.resumeState();

        assertEquals("resume-failed", r.getStateSource(),
            "a corrupt state file must surface resume-failed, never a silent fresh start");
        assertEquals(0, r.getTotalEntries(), "a corrupt file must not partially restore counters");
        assertTrue(r.getActiveTrades().isEmpty(), "nothing may be restored from an unparseable file");
    }
}
