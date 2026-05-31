package com.martinfou.trading.runtime;

import com.martinfou.trading.core.Bar;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Splits chronological bars into in-sample and locked OOS holdout (Story 19.4). */
public record BarHoldoutSplit(
    List<Bar> inSample,
    List<Bar> holdout,
    double holdoutPct,
    Instant holdoutStart,
    Instant holdoutEnd
) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("holdoutPct", holdoutPct);
        map.put("inSampleBarCount", inSample.size());
        map.put("holdoutBarCount", holdout.size());
        map.put("holdoutStart", holdoutStart.toString());
        map.put("holdoutEnd", holdoutEnd.toString());
        return Map.copyOf(map);
    }

    /** Immutable holdout window for validation audit (Story 19.4). */
    public Map<String, Object> validationConfigSnapshot(String sourceConfigHash, double holdoutPctConfigured) {
        Map<String, Object> map = new LinkedHashMap<>(toMap());
        map.put("sourceConfigHash", sourceConfigHash);
        map.put("holdoutPctConfigured", holdoutPctConfigured);
        return Map.copyOf(map);
    }

    public static BarHoldoutSplit split(List<Bar> bars, double holdoutPct, int minHoldoutBars) {
        if (bars == null || bars.isEmpty()) {
            throw new IllegalArgumentException("bars are required");
        }
        if (holdoutPct <= 0.0 || holdoutPct >= 1.0) {
            throw new IllegalArgumentException("holdoutPct must be between 0 and 1 exclusive");
        }
        if (minHoldoutBars < 1) {
            throw new IllegalArgumentException("minHoldoutBars must be positive");
        }
        int holdoutSize = Math.max(minHoldoutBars, (int) Math.round(bars.size() * holdoutPct));
        if (holdoutSize >= bars.size()) {
            holdoutSize = Math.max(minHoldoutBars, bars.size() / 5);
        }
        int splitIndex = bars.size() - holdoutSize;
        if (splitIndex < minHoldoutBars) {
            throw new IllegalArgumentException(
                "Insufficient in-sample bars after holdout split: " + splitIndex
                    + " (need at least " + minHoldoutBars + ")");
        }
        List<Bar> holdoutBars = List.copyOf(bars.subList(splitIndex, bars.size()));
        List<Bar> inSampleBars = List.copyOf(bars.subList(0, splitIndex));
        return new BarHoldoutSplit(
            inSampleBars,
            holdoutBars,
            holdoutPct,
            holdoutBars.getFirst().timestamp(),
            holdoutBars.getLast().timestamp());
    }
}
