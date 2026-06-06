package com.martinfou.trading.strategies.harness;

import com.martinfou.trading.core.Strategy;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessStrategyCatalogTest {

    private static final Set<String> EXPECTED_IDS = Set.of(
        "Harness_NeverTrade",
        "Harness_BuyOnceHold",
        "Harness_BuyThenCloseNextBar",
        "Harness_LimitNeverFills",
        "Harness_OpenCloseSameBar",
        "Harness_WeekendProbe",
        "Harness_WeekendOnlyTrade",
        "Harness_DailyOpenClose",
        "Harness_DailyRoundTrip",
        "Harness_WeeklyRoundTrip"
    );

    @Test
    void allHarnessIdsRegistered() {
        assertEquals(EXPECTED_IDS, HarnessStrategyCatalog.ids());
    }

    @Test
    void createReturnsNamedStrategy() {
        Strategy s = HarnessStrategyCatalog.create("Harness_BuyOnceHold", "EUR_USD");
        assertNotNull(s);
        assertEquals("Harness_BuyOnceHold", s.name());
    }

    @Test
    void unknownIdThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> HarnessStrategyCatalog.create("Harness_NoSuch", "EUR_USD"));
    }

    @Test
    void defaultSymbolIsEurUsd() {
        assertEquals("EUR_USD", HarnessStrategyCatalog.defaultSymbol("Harness_DailyRoundTrip"));
    }
}
