package com.martinfou.trading.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidencePackExporterTest {

    @Test
    void exportJsonl_includesExecutionLabelInMetadata() throws Exception {
        try (RuntimeStores.Bundle stores = RuntimeStores.inMemoryWithBroadcast();
             RunManager manager = new RunManager(stores.eventStore())) {

            String runId = manager.startRun(new RunManager.StartRunRequest(
                "LondonOpenRangeBreakout",
                "EUR_USD",
                "BACKTEST",
                new BarSourceResolver.BarsSource("sample", 100, null),
                100_000.0,
                null,
                null,
                null,
                null));
            waitForCompletion(manager, runId);

            RunRecord record = manager.getRun(runId).orElseThrow();
            String jsonl = EvidencePackExporter.exportJsonl(
                record,
                stores.eventStore(),
                stores.deploymentStore().get(record.strategyId()));

            assertTrue(jsonl.startsWith("{\"type\":\"EVIDENCE_METADATA\""));
            assertTrue(jsonl.contains("\"executionLabel\":\"BACKTEST\""));
            assertTrue(jsonl.contains("\"displayName\":\"Backtest\""));
            assertTrue(jsonl.contains("\"badgeBackgroundColor\":\"#64748b\""));
            assertTrue(jsonl.contains("RUN_STARTED"));
            assertTrue(jsonl.contains("RUN_ENDED"));
        }
    }

    private static void waitForCompletion(RunManager manager, String runId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            RunRecord record = manager.getRun(runId).orElseThrow();
            if (record.status() != RunRecord.Status.RUNNING) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("timeout");
    }
}
