package com.martinfou.trading.runtime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Result of drift evaluation for one strategy (Stories 17.5 / 17.12).
 * Updated to contain side-by-side comparison metrics (Story 37-1 / 37-3).
 */
public record DriftEvaluation(
    String strategyId,
    DriftRecommendation recommendation,
    String dataSource,
    String reason,
    List<DriftMetricSignal> signals,
    Instant evaluatedAt,
    Optional<ExecutionLabel> deploymentLabel,
    Map<String, Object> comparisonMetrics
) {

    public DriftEvaluation(
        String strategyId,
        DriftRecommendation recommendation,
        String dataSource,
        String reason,
        List<DriftMetricSignal> signals,
        Instant evaluatedAt,
        Optional<ExecutionLabel> deploymentLabel
    ) {
        this(strategyId, recommendation, dataSource, reason, signals, evaluatedAt, deploymentLabel, Map.of());
    }

    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("strategyId", strategyId);
        map.put("recommendation", recommendation.name());
        map.put("dataSource", dataSource);
        map.put("reason", reason);
        map.put("signals", signals.stream().map(DriftMetricSignal::toMap).toList());
        map.put("evaluatedAt", evaluatedAt.toString());
        deploymentLabel.ifPresent(label -> map.put("executionLabel", label.name()));
        if (comparisonMetrics != null && !comparisonMetrics.isEmpty()) {
            map.put("comparisonMetrics", comparisonMetrics);
        }
        return Map.copyOf(map);
    }
}
