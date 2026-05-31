package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.RunMode;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Current deployment stage for a strategy in the promote pipeline. */
public record DeploymentRecord(
    String strategyId,
    RunMode mode,
    Instant promotedAt,
    String sourceRunId,
    List<GateCheckResult> checks,
    ExecutionLabel executionLabel,
    String brokerAccountId
) {

    public DeploymentRecord {
        if (executionLabel == null) {
            executionLabel = ExecutionLabel.forPromotedMode(mode);
        }
    }

    public DeploymentRecord(
        String strategyId,
        RunMode mode,
        Instant promotedAt,
        String sourceRunId,
        List<GateCheckResult> checks
    ) {
        this(strategyId, mode, promotedAt, sourceRunId, checks, ExecutionLabel.forPromotedMode(mode), null);
    }

    public DeploymentRecord(
        String strategyId,
        RunMode mode,
        Instant promotedAt,
        String sourceRunId,
        List<GateCheckResult> checks,
        ExecutionLabel executionLabel
    ) {
        this(strategyId, mode, promotedAt, sourceRunId, checks, executionLabel, null);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("strategyId", strategyId);
        map.put("mode", mode.name());
        map.put("executionLabel", executionLabel.name());
        map.put("executionLabelMeta", ExecutionLabelCatalog.of(executionLabel).toMap());
        map.put("promotedAt", promotedAt.toString());
        if (sourceRunId != null) {
            map.put("sourceRunId", sourceRunId);
        }
        if (brokerAccountId != null && !brokerAccountId.isBlank()) {
            map.put("brokerAccountId", brokerAccountId);
        }
        map.put("checks", checks);
        return Map.copyOf(map);
    }
}
