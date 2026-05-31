package com.martinfou.trading.runtime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Result of drift evaluation for one strategy (Stories 17.5 / 17.12). */
public record DriftEvaluation(
    String strategyId,
    DriftRecommendation recommendation,
    String dataSource,
    String reason,
    List<DriftMetricSignal> signals,
    Instant evaluatedAt,
    Optional<ExecutionLabel> deploymentLabel
) {

    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("strategyId", strategyId);
        map.put("recommendation", recommendation.name());
        map.put("dataSource", dataSource);
        map.put("reason", reason);
        map.put("signals", signals.stream().map(DriftMetricSignal::toMap).toList());
        map.put("evaluatedAt", evaluatedAt.toString());
        deploymentLabel.ifPresent(label -> map.put("executionLabel", label.name()));
        return Map.copyOf(map);
    }
}
