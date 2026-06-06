package com.martinfou.trading.tui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TuiSelectionTest {

    @Test
    void resolveByIndex() {
        assertEquals("LondonOpenRangeBreakout",
            TuiSelection.resolve("2", List.of("SmaCrossover", "LondonOpenRangeBreakout")));
    }

    @Test
    void resolveById() {
        assertEquals("LondonOpenRangeBreakout",
            TuiSelection.resolve("LondonOpenRangeBreakout", List.of("SmaCrossover", "LondonOpenRangeBreakout")));
    }

    @Test
    void resolveById_mustMatchList() {
        assertThrows(IllegalArgumentException.class, () -> TuiSelection.resolve(
            "NotARealStrategy", List.of("SmaCrossover", "LondonOpenRangeBreakout"), "strategy"));
    }

    @Test
    void rejectsOutOfRange() {
        assertThrows(IllegalArgumentException.class,
            () -> TuiSelection.resolve("9", List.of("A", "B")));
    }
}
