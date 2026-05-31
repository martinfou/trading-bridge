package com.martinfou.trading.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DueDiligenceHtmlExporterTest {

    @Test
    void exportHtml_includesDisclaimerMetricsAndConfigHash() throws Exception {
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
                null));
            waitForCompletion(manager, runId);

            RunRecord record = manager.getRun(runId).orElseThrow();
            String html = DueDiligenceHtmlExporter.exportHtml(
                record,
                stores.eventStore(),
                stores.deploymentStore().get(record.strategyId()));

            assertTrue(html.startsWith("<!DOCTYPE html>"));
            assertTrue(html.contains("DISCLAIMER"));
            assertTrue(html.contains("Historical backtest simulation"));
            assertTrue(html.contains("label-badge"));
            assertTrue(html.contains("Backtest"));
            assertTrue(html.contains("#64748b"));
            assertTrue(html.contains(record.configHash()));
            assertTrue(html.contains("Sharpe ratio"));
            assertTrue(html.contains("Profit factor"));
            assertTrue(html.contains("Max drawdown"));
            assertFalse(html.contains("cdn.jsdelivr.net"));
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
