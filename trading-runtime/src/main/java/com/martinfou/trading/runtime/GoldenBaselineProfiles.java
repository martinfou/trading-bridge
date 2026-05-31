package com.martinfou.trading.runtime;

import com.martinfou.trading.core.GoldenBacktestBaseline;

import java.util.Map;
import java.util.Optional;

/** Known golden / mini-golden baselines for promote gate comparison. */
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
        if (!GoldenBacktestBaseline.STRATEGY_ID.equals(strategyId) || configSnapshot == null) {
            return Optional.empty();
        }
        String type = stringOrNull(configSnapshot.get("barsSourceType"));
        if ("ci".equalsIgnoreCase(type)) {
            return Optional.of(from(GoldenBacktestBaseline.CI_SUBSET));
        }
        if ("year".equalsIgnoreCase(type)) {
            Object year = configSnapshot.get("barsSourceYear");
            if (year != null && String.valueOf(GoldenBacktestBaseline.FULL_YEAR).equals(String.valueOf(year))) {
                return Optional.of(from(GoldenBacktestBaseline.EUR_USD_2012));
            }
        }
        return Optional.empty();
    }

    private static Profile from(GoldenBacktestBaseline.Profile golden) {
        return new Profile(
            golden.trades(),
            golden.returnPct(),
            GoldenBacktestBaseline.RETURN_TOLERANCE_PCT,
            golden.maxDrawdownPct(),
            GoldenBacktestBaseline.MAX_DRAWDOWN_TOLERANCE_PCT);
    }

    private static String stringOrNull(Object value) {
        return value != null ? String.valueOf(value) : null;
    }
}
