package com.martinfou.trading.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.martinfou.trading.backtest.events.RunEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Builds faithful JSONL evidence exports (Story 15.4 / 15.6). */
final class EvidencePackExporter {

    private EvidencePackExporter() {}

    static String exportJsonl(RunRecord record, EventStore eventStore, Optional<DeploymentRecord> deployment) {
        StringBuilder out = new StringBuilder();
        out.append(toJsonLine(buildMetadata(record, deployment))).append('\n');
        for (RunEvent event : eventStore.replayAll(record.runId())) {
            out.append(RunEventMessages.toJson(event)).append('\n');
        }
        return out.toString();
    }

    private static Map<String, Object> buildMetadata(RunRecord record, Optional<DeploymentRecord> deployment) {
        ExecutionLabel label = deployment
            .map(DeploymentRecord::executionLabel)
            .orElseGet(() -> ExecutionLabel.forRunMode(record.mode()));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("type", "EVIDENCE_METADATA");
        meta.put("runId", record.runId());
        meta.put("strategyId", record.strategyId());
        meta.put("symbol", record.symbol());
        meta.put("mode", record.mode().name());
        meta.put("executionLabel", label.name());
        meta.put("executionLabelMeta", ExecutionLabelCatalog.of(label).toMap());
        meta.put("status", record.status().name());
        meta.put("configSnapshot", record.configSnapshot());
        meta.put("configHash", record.configHash());
        record.completedAt().ifPresent(t -> meta.put("completedAt", t.toString()));
        record.endedPayload().ifPresent(p -> meta.put("result", p));
        deployment.ifPresent(d -> {
            meta.put("deploymentMode", d.mode().name());
            meta.put("deploymentExecutionLabel", d.executionLabel().name());
            meta.put("promotedAt", d.promotedAt().toString());
            if (d.brokerAccountId() != null && !d.brokerAccountId().isBlank()) {
                meta.put("brokerAccountId", d.brokerAccountId());
            }
        });
        return meta;
    }

    private static String toJsonLine(Map<String, Object> map) {
        try {
            return RunEventMessages.MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize evidence metadata", e);
        }
    }
}
