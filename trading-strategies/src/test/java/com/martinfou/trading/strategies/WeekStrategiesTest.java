package com.martinfou.trading.strategies;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WeekStrategiesTest {

    @Test
    void getStrategies_eachNewsTimeResolvesCalendarEvent() {
        var strategies = WeekStrategies.getStrategies();
        assertFalse(strategies.isEmpty());
        for (var s : strategies) {
            assertNotNull(s.getNewsTime(), s.name());
        }
    }

    @Test
    void ukCpiAndGbpjpyShareNewsInstant() {
        var strategies = WeekStrategies.getStrategies();
        var ukCpi = strategies.stream().filter(s -> s.name().contains("IPC UK")).findFirst().orElseThrow();
        var gbpjpy = strategies.stream().filter(s -> s.name().contains("GBPJPY")).findFirst().orElseThrow();
        assertEquals(ukCpi.getNewsTime(), gbpjpy.getNewsTime());
    }
}
