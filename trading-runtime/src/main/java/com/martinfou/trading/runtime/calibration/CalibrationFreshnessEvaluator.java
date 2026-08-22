package com.martinfou.trading.runtime.calibration;

import com.martinfou.trading.core.strategy.CalibrationPolicy;
import com.martinfou.trading.strategies.StrategyCatalog;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Evaluates calibration freshness and parameter drift against the strategy's @CalibrationPolicy.
 */
public final class CalibrationFreshnessEvaluator {

    public enum Status {
        FRESH,
        WARNING,
        EXPIRED
    }

    public record FreshnessEvaluation(
        String strategyId,
        Status status,
        long ageDays,
        int maxAgeDays,
        int barsCount,
        int maxBarsCount,
        int tradesCount,
        int maxTradesCount,
        Instant lastCalibratedAt,
        String message
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("strategyId", strategyId);
            map.put("status", status.name());
            map.put("ageDays", ageDays);
            map.put("maxAgeDays", maxAgeDays);
            map.put("barsCount", barsCount);
            map.put("maxBarsCount", maxBarsCount);
            map.put("tradesCount", tradesCount);
            map.put("maxTradesCount", maxTradesCount);
            map.put("lastCalibratedAt", lastCalibratedAt != null ? lastCalibratedAt.toString() : null);
            map.put("message", message);
            return map;
        }
    }

    private CalibrationFreshnessEvaluator() {}

    public static FreshnessEvaluation evaluate(
        String strategyId,
        Instant lastCalibratedAt,
        int barsCount,
        int tradesCount,
        Instant now
    ) {
        int maxAgeDays = 30;
        int maxBarsCount = 5000;
        int maxTradesCount = 100;

        try {
            var sample = StrategyCatalog.create(strategyId, "EUR_USD");
            Class<?> clazz = sample.getClass();
            CalibrationPolicy policy = clazz.getAnnotation(CalibrationPolicy.class);
            if (policy != null) {
                maxAgeDays = policy.maxAgeDays();
                maxBarsCount = policy.maxBarsCount();
                maxTradesCount = policy.maxTradesCount();
            }
        } catch (Exception ignored) {}

        if (lastCalibratedAt == null) {
            return new FreshnessEvaluation(
                strategyId,
                Status.EXPIRED,
                -1,
                maxAgeDays,
                barsCount,
                maxBarsCount,
                tradesCount,
                maxTradesCount,
                null,
                "Strategy has never been calibrated via Walk-Forward Analysis"
            );
        }

        long ageDays = Math.max(0, Duration.between(lastCalibratedAt, now).toDays());

        boolean ageExpired = ageDays > maxAgeDays;
        boolean barsExpired = maxBarsCount > 0 && barsCount > maxBarsCount;
        boolean tradesExpired = maxTradesCount > 0 && tradesCount > maxTradesCount;

        if (ageExpired || barsExpired || tradesExpired) {
            String reason = ageExpired ? "Age limit exceeded (" + ageDays + "/" + maxAgeDays + "d)"
                : barsExpired ? "Bar limit exceeded (" + barsCount + "/" + maxBarsCount + " bars)"
                : "Trade limit exceeded (" + tradesCount + "/" + maxTradesCount + " trades)";
            return new FreshnessEvaluation(
                strategyId,
                Status.EXPIRED,
                ageDays,
                maxAgeDays,
                barsCount,
                maxBarsCount,
                tradesCount,
                maxTradesCount,
                lastCalibratedAt,
                "Calibration expired: " + reason
            );
        }

        boolean ageWarning = ageDays >= Math.floor(maxAgeDays * 0.8);
        boolean barsWarning = maxBarsCount > 0 && barsCount >= Math.floor(maxBarsCount * 0.8);
        boolean tradesWarning = maxTradesCount > 0 && tradesCount >= Math.floor(maxTradesCount * 0.8);

        if (ageWarning || barsWarning || tradesWarning) {
            return new FreshnessEvaluation(
                strategyId,
                Status.WARNING,
                ageDays,
                maxAgeDays,
                barsCount,
                maxBarsCount,
                tradesCount,
                maxTradesCount,
                lastCalibratedAt,
                "Calibration approaching expiry (>= 80% lifecycle consumed)"
            );
        }

        return new FreshnessEvaluation(
            strategyId,
            Status.FRESH,
            ageDays,
            maxAgeDays,
            barsCount,
            maxBarsCount,
            tradesCount,
            maxTradesCount,
            lastCalibratedAt,
            "Calibration fresh and valid"
        );
    }
}
