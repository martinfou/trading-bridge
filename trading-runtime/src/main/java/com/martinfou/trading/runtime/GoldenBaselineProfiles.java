package com.martinfou.trading.runtime;

import java.util.Map;
import java.util.Optional;

/** Known golden / mini-golden baselines for promote gate comparison (Story 13.8). */
final class GoldenBaselineProfiles {

    record Profile(
        int expectedTrades,
        double expectedReturnPct,
        double returnTolerancePct,
        double expectedMaxDrawdownPct,
        double maxDrawdownTolerancePct
    ) {}

    private GoldenBaselineProfiles() {}

    static Optional<Profile> match(String strategyId, Map<String, Object> configSnapshot) {
        if (!"LondonOpenRangeBreakout".equals(strategyId) || configSnapshot == null) {
            return Optional.empty();
        }
        String type = stringOrNull(configSnapshot.get("barsSourceType"));
        if ("ci".equalsIgnoreCase(type)) {
            return Optional.of(new Profile(
                3,
                1.8153285714287921,
                1.0,
                0.03,
                0.01));
        }
        if ("year".equalsIgnoreCase(type)) {
            Object year = configSnapshot.get("barsSourceYear");
            if (year != null && "2012".equals(String.valueOf(year))) {
                return Optional.of(new Profile(
                    63,
                    16.439514464285008,
                    1.0,
                    0.12,
                    0.01));
            }
        }
        return Optional.empty();
    }

    private static String stringOrNull(Object value) {
        return value != null ? String.valueOf(value) : null;
    }
}
