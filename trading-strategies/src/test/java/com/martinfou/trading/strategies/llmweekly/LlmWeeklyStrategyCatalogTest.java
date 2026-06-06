package com.martinfou.trading.strategies.llmweekly;

import com.martinfou.trading.core.Strategy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LlmWeeklyStrategyCatalogTest {

    @Test
    void registerAndCreateStrategy() {
        LlmWeeklyStrategyCatalog.register("LLM_WEEKLY_TEST_T8_NONE",
            sym -> new NoTradeWeekStrategy("LLM_WEEKLY_TEST_T8_NONE", "test"));
        Strategy strategy = LlmWeeklyStrategyCatalog.create("LLM_WEEKLY_TEST_T8_NONE", "EUR_USD");
        assertNotNull(strategy);

        LlmWeeklyStrategyCatalog.register("LLM_WEEKLY_2026-W24_T4_EUR_USD",
            sym -> new NoTradeWeekStrategy("LLM_WEEKLY_2026-W24_T4_EUR_USD", "test"));
        assertEquals("EUR_USD", LlmWeeklyStrategyCatalog.defaultSymbol("LLM_WEEKLY_2026-W24_T4_EUR_USD"));
        assertEquals("EUR_USD", LlmWeeklyStrategyCatalog.defaultSymbol("LLM_WEEKLY_TEST_T8_NONE"));
    }
}
