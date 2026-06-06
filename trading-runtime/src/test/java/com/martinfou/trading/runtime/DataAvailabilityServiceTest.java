package com.martinfou.trading.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DataAvailabilityServiceTest {

    @Test
    void listSymbols_whenRepoPresent() throws Exception {
        assumeTrue(EventStoreConfig.findRepoRoot() != null, "repo root required");
        Map<String, Object> result = new DataAvailabilityService().listSymbols();
        assertNotNull(result.get("symbols"));
    }

    @Test
    void availability_eurUsd_whenRepoPresent() throws Exception {
        assumeTrue(EventStoreConfig.findRepoRoot() != null, "repo root required");
        Map<String, Object> result = new DataAvailabilityService().availability("EUR_USD");
        assertFalse(result.isEmpty());
    }
}
