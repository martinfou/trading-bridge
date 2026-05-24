package com.martinfou.trading.strategies;

import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.strategies.StrategyCatalog.Family;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrategyCatalogTest {

    @AfterEach
    void resetCatalog() {
        StrategyCatalog.resetForTesting();
    }

    @Test
    void oneResolvableIdPerFamily() {
        Strategy prop = StrategyCatalog.create("LondonOpenRangeBreakout", "EUR_USD");
        assertNotNull(prop);
        assertEquals(Family.PROP, StrategyCatalog.family("LondonOpenRangeBreakout"));
        assertEquals("EUR_USD", StrategyCatalog.defaultSymbol("LondonOpenRangeBreakout"));

        Strategy sq = StrategyCatalog.create("Strategy_2_14_147_Adapted", "GBP_JPY");
        assertNotNull(sq);
        assertEquals(Family.SQ_IMPORTED, StrategyCatalog.family("Strategy_2_14_147_Adapted"));
        assertEquals("GBP_JPY", StrategyCatalog.defaultSymbol("Strategy_2_14_147_Adapted"));

        Strategy generated = StrategyCatalog.create("MyStrategy", "EUR_USD");
        assertNotNull(generated);
        assertEquals(Family.GENERATED, StrategyCatalog.family("MyStrategy"));
        assertEquals("EUR_USD", StrategyCatalog.defaultSymbol("MyStrategy"));

        StrategyCatalog.register("SmaCrossover", Family.EXAMPLE,
            sym -> StrategyCatalog.create("MyStrategy", sym), "EUR_USD");
        Strategy example = StrategyCatalog.create("SmaCrossover", "EUR_USD");
        assertNotNull(example);
        assertEquals(Family.EXAMPLE, StrategyCatalog.family("SmaCrossover"));
        assertEquals("EUR_USD", StrategyCatalog.defaultSymbol("SmaCrossover"));
    }

    @Test
    void entriesIncludeAllBuiltInFamilies() {
        assertTrue(StrategyCatalog.entries().size() >= 20);
        assertTrue(StrategyCatalog.ids().contains("LondonOpenRangeBreakout"));
        assertTrue(StrategyCatalog.ids().contains("Strategy_2_14_147_Adapted"));
        assertTrue(StrategyCatalog.ids().contains("MyStrategy"));
    }

    @Test
    void registerDuplicateIdThrows() {
        StrategyCatalog.register("DupExample", Family.EXAMPLE,
            sym -> StrategyCatalog.create("MyStrategy", sym), "EUR_USD");
        assertThrows(IllegalStateException.class, () ->
            StrategyCatalog.register("DupExample", Family.EXAMPLE,
                sym -> StrategyCatalog.create("MyStrategy", sym), "EUR_USD"));
    }

    @Test
    void unknownIdThrows() {
        assertThrows(IllegalArgumentException.class,
            () -> StrategyCatalog.create("NoSuchStrategy", "EUR_USD"));
    }
}
