package com.martinfou.trading.runtime;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory kill flags per strategy (Story 16.6). */
public final class KillSwitchRegistry {

    private final Set<String> killed = ConcurrentHashMap.newKeySet();

    public boolean isKilled(String strategyId) {
        return strategyId != null && killed.contains(strategyId);
    }

    public void kill(String strategyId) {
        if (strategyId == null || strategyId.isBlank()) {
            throw new IllegalArgumentException("strategyId is required");
        }
        killed.add(strategyId);
    }

    void clear() {
        killed.clear();
    }
}
