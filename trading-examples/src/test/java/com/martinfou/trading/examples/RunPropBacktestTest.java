package com.martinfou.trading.examples;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RunPropBacktestTest {

    @Test
    void noArgsLoadsFullHistoryOnDefaultPairs() {
        var spec = RunPropBacktest.parseSuiteDataArgs(new String[] {"--all"});
        assertNull(spec.yearSpec());
        assertNull(spec.unifiedSymbol());
        assertEquals(true, spec.useFullHistory());
    }

    @Test
    void yearOnlyUsesDefaultSymbolsPerStrategy() {
        var spec = RunPropBacktest.parseSuiteDataArgs(new String[] {"--all", "2025"});
        assertEquals("2025", spec.yearSpec());
        assertNull(spec.unifiedSymbol());
        assertEquals(false, spec.useFullHistory());
    }

    @Test
    void symbolYearRunsAllOnThatPair() {
        var spec = RunPropBacktest.parseSuiteDataArgs(new String[] {"--all", "GBP_JPY", "2025"});
        assertEquals("2025", spec.yearSpec());
        assertEquals("GBP_JPY", spec.unifiedSymbol());
        assertEquals(false, spec.useFullHistory());
    }

    @Test
    void rejectsSymbolMismatchArgsOrder() {
        assertThrows(IllegalArgumentException.class,
            () -> RunPropBacktest.parseSuiteDataArgs(new String[] {"--all", "2025", "GBP_JPY"}));
    }
}
