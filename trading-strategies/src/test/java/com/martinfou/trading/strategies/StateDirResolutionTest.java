package com.martinfou.trading.strategies;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Story 50.1 L1.1 — the state directory resolves env &gt; config &gt; /tmp and is created at
 * startup, with the state file landing INSIDE the resolved directory (not /tmp).
 */
class StateDirResolutionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static class NoopStrategy implements Strategy {
        @Override public String name() { return "Noop"; }
        @Override public void onBar(Bar bar) { }
        @Override public void onTick(double bid, double ask, long volume) { }
        @Override public List<Order> getPendingOrders() { return List.of(); }
        @Override public void reset() { }
    }

    @Test
    void resolutionPriorityIsEnvThenConfigThenTmp() throws Exception {
        JsonNode config = MAPPER.readTree("{\"stateDir\":\"/from/config\"}");

        assertEquals(Paths.get("/from/env"), LiveStrategyRunner.resolveStateDir("/from/env", config),
            "TB_STATE_DIR must override the config value");
        assertEquals(Paths.get("/from/config"), LiveStrategyRunner.resolveStateDir(null, config),
            "the config stateDir field must be consumed (it was dead before)");
        assertEquals(Paths.get("/from/config"), LiveStrategyRunner.resolveStateDir("   ", config),
            "a blank env value must fall through to the config");
        assertEquals(Paths.get("/tmp"), LiveStrategyRunner.resolveStateDir(null, null),
            "the default is /tmp when nothing overrides it");
    }

    @Test
    void stateDirectoryIsCreatedAndStateFileLandsInside(@TempDir Path tmp) throws Exception {
        Path stateDir = tmp.resolve("nested").resolve("state");
        assertFalse(Files.exists(stateDir), "precondition: the directory does not exist yet");

        LiveStrategyRunner r = new LiveStrategyRunner(
            "k", "acc", new NoopStrategy(), "sdrtest", "H1", 60, stateDir);
        r.saveStateNow();

        assertTrue(Files.isDirectory(stateDir), "the state directory must be created before the first write");
        assertTrue(Files.exists(stateDir.resolve("live-strategy-state-sdrtest.json")),
            "the state file must land INSIDE the resolved directory, not /tmp");
    }
}
