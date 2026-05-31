package com.martinfou.trading.runtime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Single drift metric observation (FR-15). */
public record DriftMetricSignal(
    String metric,
    double value,
    double threshold,
    String dimension,
    boolean breached,
    Instant timestamp
) {

    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("metric", metric);
        map.put("value", value);
        map.put("threshold", threshold);
        map.put("dimension", dimension);
        map.put("breached", breached);
        map.put("timestamp", timestamp.toString());
        return Map.copyOf(map);
    }
}
