package com.martinfou.trading.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class LaneTelemetryCollectorTest {

    @Test
    void collectsAndDeduplicatesIbkrTradesFromPnlStateAndJsonlWithSentinelFiltering(@TempDir Path tempDir) throws Exception {
        Path fillsDir = Files.createDirectories(tempDir.resolve("fills"));
        Path pnlStateFile = tempDir.resolve("pnl_state.json");

        // Write JSONL fills with two round-trips (one at 2026-11-04T08:06:05Z that has a native IBKR twin,
        // and one at 2026-11-02T14:30:00Z that is JSONL-only)
        String jsonlContent = String.join("\n",
            "{\"strategy\":\"sq-mgc-m30-breakout-0-24542\",\"symbol\":\"MGC\",\"side\":\"SELL\",\"quantity\":1.0,\"requestedPrice\":3916.2,\"filledPrice\":3916.7,\"commission\":0.62,\"timestamp\":\"2026-10-30T14:30:05Z\",\"mode\":\"paper\"}",
            "{\"strategy\":\"sq-mgc-m30-breakout-0-24542\",\"symbol\":\"MGC\",\"side\":\"BUY\",\"quantity\":1.0,\"requestedPrice\":3942.0,\"filledPrice\":3958.4,\"commission\":0.62,\"timestamp\":\"2026-11-02T14:30:00Z\",\"mode\":\"paper\"}",
            "{\"strategy\":\"sq-mgc-m30-breakout-0-24542\",\"symbol\":\"MGC\",\"side\":\"BUY\",\"quantity\":1.0,\"requestedPrice\":4009.2,\"filledPrice\":4008.0,\"commission\":0.62,\"timestamp\":\"2026-11-04T06:00:11Z\",\"mode\":\"paper\"}",
            "{\"strategy\":\"sq-mgc-m30-breakout-0-24542\",\"symbol\":\"MGC\",\"side\":\"SELL\",\"quantity\":1.0,\"requestedPrice\":4041.1,\"filledPrice\":4041.1,\"commission\":0.62,\"timestamp\":\"2026-11-04T08:06:05Z\",\"mode\":\"paper\"}"
        );
        Files.writeString(fillsDir.resolve("sq-mgc-m30-breakout-0-24542-MGC-paper.jsonl"), jsonlContent, StandardCharsets.UTF_8);

        // Write pnl_state.json containing:
        // 1. An opening fill with IBKR Double.MAX_VALUE sentinel (1.7976931348623157e+308)
        // 2. A closing native fill at 20261104 08:06:05 (within ±20s of the second JSONL round-trip)
        // 3. A JSONL twin entry in pnl_state that should also be deduplicated
        String pnlStateJson = """
            {
              "last_success_utc": "2026-11-07T01:47:03Z",
              "executions": {
                "0000e1a7.6aeb0001.01.01": {
                  "exec_id": "0000e1a7.6aeb0001.01.01",
                  "time": "20261104  06:00:11",
                  "side": "BOT",
                  "shares": 1.0,
                  "price": 4008.0,
                  "order_id": 1015,
                  "order_ref": "sq-mgc-m30-breakout-0-24542_MGC",
                  "currency": "USD",
                  "symbol": "MGCZ6",
                  "report": {
                    "commission": 0.96,
                    "currency": "USD",
                    "realized_pnl": 1.7976931348623157e+308
                  }
                },
                "0000e1a7.6aeb3cc6.01.01": {
                  "exec_id": "0000e1a7.6aeb3cc6.01.01",
                  "time": "20261104  08:06:05",
                  "side": "SLD",
                  "shares": 1.0,
                  "price": 4041.1,
                  "order_id": 1017,
                  "order_ref": "sq-mgc-m30-breakout-0-24542_MGC",
                  "currency": "USD",
                  "symbol": "MGCZ6",
                  "report": {
                    "commission": 0.96,
                    "currency": "USD",
                    "realized_pnl": 329.08
                  }
                },
                "jsonl.MGC.20261104T080605Z.01": {
                  "exec_id": "jsonl.MGC.20261104T080605Z.01",
                  "time": "20261104 08:06:05 UTC",
                  "side": "SLD",
                  "shares": 1.0,
                  "price": 4041.1,
                  "entry_price": 4008.0,
                  "opened_at_utc": "2026-11-04T06:00:11Z",
                  "order_id": 90010,
                  "order_ref": "sq-mgc-m30-breakout-0-24542_MGC",
                  "currency": "USD",
                  "symbol": "MGC",
                  "report": {
                    "commission": 1.24,
                    "currency": "USD",
                    "realized_pnl": 329.76
                  }
                }
              }
            }
            """;
        Files.writeString(pnlStateFile, pnlStateJson, StandardCharsets.UTF_8);

        LaneTelemetryCollector collector = new LaneTelemetryCollector(
            List.of(fillsDir),
            List.of(pnlStateFile),
            List.of(),
            List.of(),
            List.of()
        );

        List<Map<String, Object>> trades = collector.collectIbkrTrades("ibkr-paper", "****7322", "USD", List.of());

        // Expect exactly 2 closed trades (the native 2026-11-04 trade deduplicated with its JSONL twin,
        // and the 2026-11-02 JSONL round-trip; the opening leg with 1.797e308 sentinel is consumed)
        assertEquals(2, trades.size());

        Map<String, Object> newest = trades.get(0);
        assertEquals("0000e1a7.6aeb3cc6.01.01", newest.get("id"));
        assertEquals("MGCZ6", newest.get("symbol"));
        assertEquals("CLOSED", newest.get("state"));
        assertEquals("BUY", newest.get("side"));
        assertEquals(4008.0, (Double) newest.get("entryPrice"), 1e-4);
        assertEquals(4041.1, (Double) newest.get("exitPrice"), 1e-4);
        assertEquals(329.08, (Double) newest.get("realizedPnL"), 1e-4);
        assertEquals("sq-mgc-m30-breakout-0-24542", newest.get("strategyTag"));

        Map<String, Object> older = trades.get(1);
        assertEquals("MGC", older.get("symbol"));
        assertEquals("CLOSED", older.get("state"));
        assertEquals("SELL", older.get("side"));
        assertEquals(3916.7, (Double) older.get("entryPrice"), 1e-4);
        assertEquals(3958.4, (Double) older.get("exitPrice"), 1e-4);
        assertEquals(-418.24, (Double) older.get("realizedPnL"), 1e-2);
        assertEquals("sq-mgc-m30-breakout-0-24542", older.get("strategyTag"));
    }

    @Test
    void collectsActiveStrategiesFromFuturesAndOandaStateDirsAndIgnoresCorruptFiles(@TempDir Path tempDir) throws Exception {
        Path futuresStateDir = Files.createDirectories(tempDir.resolve("futures-state"));
        Path oandaPaperDir = Files.createDirectories(tempDir.resolve("oanda-paper"));
        Path oandaDevDir = Files.createDirectories(tempDir.resolve("oanda-dev"));

        Files.writeString(
            futuresStateDir.resolve("sq-mgc-m30-breakout-0-24542-MGC-cursor.json"),
            """
            {
              "strategy": "sq-mgc-m30-breakout-0-24542",
              "symbol": "MGC",
              "lastProcessedBarTimestamp": "2026-11-06T21:00:00Z",
              "savedAt": "2026-11-06T21:30:05Z"
            }
            """,
            StandardCharsets.UTF_8
        );

        // Corrupt cursor file should be skipped gracefully
        Files.writeString(futuresStateDir.resolve("corrupt-cursor.json"), "{not-valid-json", StandardCharsets.UTF_8);

        Files.writeString(
            oandaPaperDir.resolve("live-strategy-state-sq-gbpjpy-h1-breakout-4-19-179.json"),
            """
            {
              "strategy": "sq-gbpjpy-h1-breakout-4-19-179",
              "instrument": "GBP_JPY",
              "granularity": "H1",
              "lane": "paper",
              "paused": false,
              "killed": false,
              "inTrade": false,
              "savedAt": "2026-11-07T01:50:41Z",
              "pnlLedger": {
                "530": 8.84,
                "657": -1.01
              },
              "activeTrades": []
            }
            """,
            StandardCharsets.UTF_8
        );

        Files.writeString(
            oandaDevDir.resolve("live-strategy-state-vwpreversion.json"),
            """
            {
              "strategy": "vwpreversion",
              "instrument": "USD_CHF",
              "granularity": "H1",
              "lane": "dev",
              "paused": false,
              "killed": false,
              "inTrade": true,
              "savedAt": "2026-11-07T01:52:00Z",
              "pnlLedger": {
                "2095": 360.29,
                "2120": 141.83
              },
              "activeTrades": [
                {
                  "tradeId": "2233",
                  "symbol": "USD_CHF",
                  "side": "SELL",
                  "entryPrice": 0.82072,
                  "quantity": 174200.0,
                  "stopLoss": 0.82263,
                  "takeProfit": 0.81806,
                  "entryTime": "2026-11-06T14:00:28Z"
                }
              ]
            }
            """,
            StandardCharsets.UTF_8
        );

        LaneTelemetryCollector collector = new LaneTelemetryCollector(
            List.of(),
            List.of(),
            List.of(futuresStateDir),
            List.of(oandaPaperDir),
            List.of(oandaDevDir)
        );

        List<Map<String, Object>> runs = collector.collectLaneStrategyRuns(
            Set.of(),
            Instant.parse("2026-11-07T02:00:00Z")
        );

        assertEquals(3, runs.size());

        Map<String, Object> gbpRun = runs.stream()
            .filter(r -> "sq-gbpjpy-h1-breakout-4-19-179".equals(r.get("strategyId")))
            .findFirst()
            .orElseThrow();
        assertEquals("GBP_JPY", gbpRun.get("symbol"));
        assertEquals(7.83, (Double) gbpRun.get("realizedPnL"), 1e-2);
        assertEquals(2, gbpRun.get("tradesCount"));

        Map<String, Object> vwapRun = runs.stream()
            .filter(r -> "vwpreversion".equals(r.get("strategyId")))
            .findFirst()
            .orElseThrow();
        assertEquals("USD_CHF", vwapRun.get("symbol"));
        assertEquals("SHORT", vwapRun.get("netSide"));
        assertEquals(174200.0, (Double) vwapRun.get("netQuantity"), 1e-2);
        assertEquals(1, vwapRun.get("openTrades"));
    }

    @Test
    void remoteCoreClientPersistsUrlAndApiKeyAndVerifiesAuthHeaders(@TempDir Path tempDir) throws Exception {
        Path configFile = tempDir.resolve("remote-core.json");
        RemoteCoreClient client = new RemoteCoreClient(configFile);

        RemoteCoreClient.RemoteCoreSettings saved = client.saveSettings(
            true,
            "10.0.0.50:8080/",
            "tb_secret_key_9876",
            false,
            "srv_inbound_key_1234",
            false
        );

        assertEquals("http://10.0.0.50:8080", saved.url());
        assertEquals("tb_secret_key_9876", saved.apiKey());
        assertEquals("srv_inbound_key_1234", saved.serverApiKey());

        Map<String, Object> masked = client.toMaskedStatusMap();
        assertEquals(true, masked.get("enabled"));
        assertEquals("http://10.0.0.50:8080", masked.get("url"));
        assertEquals(true, masked.get("apiKeyConfigured"));
        assertEquals("****9876", masked.get("apiKeyMasked"));
        assertEquals("****1234", masked.get("serverApiKeyMasked"));

        // Verify inbound API key validation
        assertTrue(client.verifyIncomingRequestApiKey("Bearer srv_inbound_key_1234", null));
        assertTrue(client.verifyIncomingRequestApiKey(null, "srv_inbound_key_1234"));
        assertFalse(client.verifyIncomingRequestApiKey("Bearer wrong_key", null));
        assertFalse(client.verifyIncomingRequestApiKey(null, null));

        // Saving with masked placeholder preserves existing secret
        RemoteCoreClient.RemoteCoreSettings preserved = client.saveSettings(
            true,
            "https://core.trading.local",
            "****9876",
            false,
            "****1234",
            false
        );
        assertEquals("https://core.trading.local", preserved.url());
        assertEquals("tb_secret_key_9876", preserved.apiKey());
        assertEquals("srv_inbound_key_1234", preserved.serverApiKey());
    }
}

