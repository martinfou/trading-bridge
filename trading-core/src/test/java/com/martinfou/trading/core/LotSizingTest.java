package com.martinfou.trading.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LotSizingTest {

    @Test
    void resolveCapitalUsesDefaultWhenNull() {
        assertEquals(1_000.0, LotSizing.resolveCapital(null), 1e-9);
    }

    @Test
    void resolveCapitalUsesExplicitValue() {
        assertEquals(2_000.0, LotSizing.resolveCapital(2_000.0), 1e-9);
    }
}
