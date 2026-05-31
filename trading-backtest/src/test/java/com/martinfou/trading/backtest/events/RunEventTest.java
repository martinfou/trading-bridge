package com.martinfou.trading.backtest.events;

import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.backtest.TestStrategies;
import com.martinfou.trading.core.Bar;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunEventTest {

    @Test
    void jsonRoundTrip_preservesFields() {
        RunEvent original = new RunEvent(
            RunEvent.SCHEMA_VERSION,
            RunEventType.RUN_STARTED,
            Instant.parse("2026-05-23T12:00:00Z"),
            "run-abc",
            "LondonOpenRangeBreakout",
            "EUR_USD",
            "BACKTEST",
            Map.of("barCount", 8760, "initialCapital", 100_000.0));

        RunEvent parsed = RunEventJson.fromJsonLine(RunEventJson.toJsonLine(original));

        assertEquals(original.schemaVersion(), parsed.schemaVersion());
        assertEquals(original.type(), parsed.type());
        assertEquals(original.timestamp(), parsed.timestamp());
        assertEquals(original.runId(), parsed.runId());
        assertEquals(original.strategyId(), parsed.strategyId());
        assertEquals(original.symbol(), parsed.symbol());
        assertEquals(original.mode(), parsed.mode());
        assertEquals(8760, ((Number) parsed.payload().get("barCount")).intValue());
    }

    @Test
    void jsonRoundTrip_preservesHeartbeatFields() {
        Instant barTime = Instant.parse("2024-06-01T12:00:00Z");
        RunEvent original = RunEvent.heartbeat(
            "run-hb",
            "LondonOpenRangeBreakout",
            "EUR_USD",
            RunMode.PAPER,
            Map.of("source", "BAR_LOOP", "barIndex", 3, "barTime", barTime.toString()),
            barTime);

        RunEvent parsed = RunEventJson.fromJsonLine(RunEventJson.toJsonLine(original));

        assertEquals(RunEventType.HEARTBEAT, parsed.type());
        assertEquals(barTime, parsed.timestamp());
        assertEquals("BAR_LOOP", parsed.payload().get("source"));
        assertEquals(3, ((Number) parsed.payload().get("barIndex")).intValue());
    }

    @Test
    void runContext_emitsStartedThenEnded_withMatchingRunId() {
        List<Bar> bars = sampleBars("EUR_USD", 200);
        List<RunEvent> events = new ArrayList<>();

        RunContext.forStrategy(
            "LondonOpenRangeBreakout",
            TestStrategies.smaCrossover(5, 20),
            "EUR_USD",
            RunMode.BACKTEST,
            bars,
            100_000.0,
            events::add).run();

        assertEquals(2, events.size());
        assertEquals(RunEventType.RUN_STARTED, events.get(0).type());
        assertEquals(RunEventType.RUN_ENDED, events.get(1).type());
        assertEquals(RunEvent.SCHEMA_VERSION, events.get(0).schemaVersion());
        assertEquals(events.get(0).runId(), events.get(1).runId());
        assertEquals("LondonOpenRangeBreakout", events.get(0).strategyId());
        assertEquals("BACKTEST", events.get(0).mode());
        assertTrue(events.get(1).payload().containsKey("totalTrades"));
    }

    @Test
    void factoryHelpers_buildExpectedTypes() {
        RunEvent started = RunEvent.started("id", "Strat", "EUR_USD", com.martinfou.trading.backtest.RunMode.BACKTEST,
            Map.of("barCount", 1));
        assertEquals(RunEventType.RUN_STARTED, started.type());
        assertEquals(RunEvent.SCHEMA_VERSION, started.schemaVersion());

        RunEvent error = RunEvent.error("id", "Strat", "EUR_USD", com.martinfou.trading.backtest.RunMode.BACKTEST,
            "boom");
        assertEquals(RunEventType.ERROR, error.type());
        assertEquals("boom", error.payload().get("message"));

        RunEvent heartbeat = RunEvent.heartbeat(
            "id",
            "Strat",
            "EUR_USD",
            com.martinfou.trading.backtest.RunMode.PAPER,
            Map.of("source", "BAR_LOOP", "barIndex", 0),
            Instant.parse("2024-01-01T00:00:00Z"));
        assertEquals(RunEventType.HEARTBEAT, heartbeat.type());
        assertEquals("BAR_LOOP", heartbeat.payload().get("source"));
        assertEquals(Instant.parse("2024-01-01T00:00:00Z"), heartbeat.timestamp());
    }

    private static List<Bar> sampleBars(String symbol, int count) {
        var bars = new ArrayList<Bar>(count);
        var time = Instant.parse("2012-01-01T00:00:00Z");
        double price = 1.30;
        for (int i = 0; i < count; i++) {
            bars.add(new Bar(symbol, time, price, price + 0.001, price - 0.001, price, 1000));
            time = time.plusSeconds(3600);
        }
        return bars;
    }
}
