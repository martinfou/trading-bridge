package com.martinfou.trading.tui;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TuiCommandHandlerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void tokenize_splitsQuotedArgs() {
        List<String> tokens = TuiCommandHandler.tokenize("promote LondonOpenRangeBreakout PAPER run-1");
        assertEquals(List.of("promote", "LondonOpenRangeBreakout", "PAPER", "run-1"), tokens);
    }

    @Test
    void help_listsCoreCommands() {
        List<String> lines = TuiCommandHandler.help();
        assertTrue(lines.stream().anyMatch(l -> l.contains("/backtest")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("/promote")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("/sq")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("/weekly-status")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("/weekly-build")));
    }

    @Test
    void handle_unknownCommand() {
        var handler = new TuiCommandHandler(new ControlPlaneClient("http://127.0.0.1:1"));
        List<String> out = handler.handle("/nope");
        assertTrue(out.getFirst().contains("Unknown command"));
    }

    @Test
    void backtestReport_matchesCliLayout() throws Exception {
        var run = MAPPER.readTree("""
            {
              "runId": "run-1",
              "strategyId": "LondonOpenRangeBreakout",
              "configSnapshot": { "barsSourceType": "sample", "barsSourceCount": 500 },
              "result": {
                "totalTrades": 4,
                "totalReturnPct": 0.03,
                "maxDrawdownPct": 0.02,
                "initialCapital": 100000,
                "finalEquity": 100030,
                "totalPnl": 30,
                "winningTrades": 3,
                "losingTrades": 1,
                "winRatePct": 75,
                "avgTradePnl": 7.5,
                "sharpeRatio": 1.2,
                "sortinoRatio": 1.5,
                "profitFactor": 2.1,
                "calmarRatio": 0.8
              }
            }
            """);
        List<String> lines = TuiBacktestReport.format(run);
        assertTrue(lines.stream().anyMatch(l -> l.contains("BACKTEST RESULT: LondonOpenRangeBreakout")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("Total Trades:  4")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("Sharpe:")));
    }

    @Test
    void appendMetrics_readsNestedResult() throws Exception {
        var run = MAPPER.readTree("""
            {
              "status": "COMPLETED",
              "configSnapshot": { "barsSourceType": "year", "barsSourceYear": "2012" },
              "result": { "totalTrades": 61, "totalReturnPct": 0.14, "maxDrawdownPct": 0.05 }
            }
            """);
        List<String> lines = new ArrayList<>();
        TuiCommandHandler.appendMetrics(lines, run);
        assertTrue(lines.stream().anyMatch(l -> l.contains("trades=61")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("data=year 2012")));
    }

    @Test
    void handle_nonSlashInput() {
        var handler = new TuiCommandHandler(new ControlPlaneClient("http://127.0.0.1:1"));
        List<String> out = handler.handle("hello");
        assertTrue(out.getFirst().contains("/help"));
    }

    @Test
    void weeklyStatus_formatsHotFolderCounts() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/weekly-builder/status", exchange -> {
            byte[] body = """
                {
                  "pendingCount": 1,
                  "compilingCount": 0,
                  "compiledCount": 2,
                  "deployedBundleCount": 3,
                  "failedBundleCount": 0,
                  "lastWeekId": "2026-W23",
                  "templates": ["T1","T2"],
                  "validUntil": "2026-06-13T21:00:00Z",
                  "planProcessing": false,
                  "compileProcessing": false,
                  "deployProcessing": false,
                  "lastPlanRun": {
                    "status": "completed",
                    "message": "Plan approved for 2026-W23"
                  }
                }
                """.strip().getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            var handler = new TuiCommandHandler(new ControlPlaneClient("http://127.0.0.1:" + server.getAddress().getPort()));
            List<String> lines = handler.handle("/weekly-status");
            assertTrue(lines.stream().anyMatch(l -> l.contains("pending=1")));
            assertTrue(lines.stream().anyMatch(l -> l.contains("lastWeekId=2026-W23")));
            assertTrue(lines.stream().anyMatch(l -> l.contains("validUntil=")));
            assertTrue(lines.stream().anyMatch(l -> l.contains("lastPlan=completed")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void weeklyBuild_triggersDeployByDefault() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/weekly-builder/deploy", exchange -> {
            byte[] body = "{\"accepted\":true,\"message\":\"Deploy job started\"}".getBytes();
            exchange.sendResponseHeaders(202, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            var handler = new TuiCommandHandler(new ControlPlaneClient("http://127.0.0.1:" + server.getAddress().getPort()));
            List<String> lines = handler.handle("/weekly-build");
            assertTrue(lines.getFirst().contains("Deploy job started"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void weeklyBuild_planFlag() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/weekly-builder/plan", exchange -> {
            byte[] body = "{\"accepted\":true,\"message\":\"Plan job started\"}".getBytes();
            exchange.sendResponseHeaders(202, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            var handler = new TuiCommandHandler(new ControlPlaneClient("http://127.0.0.1:" + server.getAddress().getPort()));
            List<String> lines = handler.handle("/weekly-build --plan");
            assertTrue(lines.getFirst().contains("Plan job started"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void dataCommand_help() {
        var handler = new TuiCommandHandler(new ControlPlaneClient("http://127.0.0.1:1"));
        List<String> out = handler.handle("/data help");
        assertTrue(out.stream().anyMatch(l -> l.contains("Usage: /data")));
        assertTrue(out.stream().anyMatch(l -> l.contains("status [tf]")));
    }

    @Test
    void dataCommand_status() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/historical-data/status", exchange -> {
            byte[] body = """
                {
                  "status": [
                    {
                      "symbol": "EUR_USD",
                      "pair": "eurusd",
                      "year": 2012,
                      "timeframe": "h1",
                      "csvExists": true,
                      "csvSize": 1024,
                      "barsExists": true,
                      "barsSize": 2048
                    },
                    {
                      "symbol": "GBP_USD",
                      "pair": "gbpusd",
                      "year": 2013,
                      "timeframe": "h1",
                      "csvExists": true,
                      "csvSize": 1024,
                      "barsExists": false,
                      "barsSize": 0
                    }
                  ],
                  "activeDownloads": [],
                  "activeTasks": [
                    {
                      "key": "eurusd-2012-h1",
                      "progress": 50,
                      "currentAction": "Processing"
                    }
                  ]
                }
                """.strip().getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            var handler = new TuiCommandHandler(new ControlPlaneClient("http://127.0.0.1:" + server.getAddress().getPort()));
            List<String> lines = handler.handle("/data status h1");
            assertTrue(lines.stream().anyMatch(l -> l.contains("Active tasks:")));
            assertTrue(lines.stream().anyMatch(l -> l.contains("eurusd-2012-h1: 50%")));
            assertTrue(lines.stream().anyMatch(l -> l.contains("EUR_USD") && l.contains("2012 (CSV+BARS)")));
            assertTrue(lines.stream().anyMatch(l -> l.contains("GBP_USD") && l.contains("2013 (CSV)")));
            assertTrue(lines.stream().anyMatch(l -> l.contains("GBP_JPY") && l.contains("[no data]")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void dataCommand_downloadSingleYear() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/historical-data/download", exchange -> {
            byte[] body = "{\"accepted\":true}".getBytes();
            exchange.sendResponseHeaders(202, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            var handler = new TuiCommandHandler(new ControlPlaneClient("http://127.0.0.1:" + server.getAddress().getPort()));
            List<String> lines = handler.handle("/data download eurusd 2012 h1");
            assertTrue(lines.getFirst().contains("Download started"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void dataCommand_downloadRange() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/historical-data/download", exchange -> {
            byte[] body = "{\"accepted\":true}".getBytes();
            exchange.sendResponseHeaders(202, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            var handler = new TuiCommandHandler(new ControlPlaneClient("http://127.0.0.1:" + server.getAddress().getPort()));
            List<String> lines = handler.handle("/data download eurusd 2012-2015 h1");
            assertTrue(lines.getFirst().contains("Download started"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void dataCommand_delete() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/historical-data/delete", exchange -> {
            byte[] body = "{\"success\":true}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            var handler = new TuiCommandHandler(new ControlPlaneClient("http://127.0.0.1:" + server.getAddress().getPort()));
            List<String> lines = handler.handle("/data delete EUR_USD 2012-2013 h1");
            assertTrue(lines.get(0).contains("Deleted EUR_USD 2012 (H1)"));
            assertTrue(lines.get(1).contains("Deleted EUR_USD 2013 (H1)"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void dataCommand_downloadSpaceSeparatedRange() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/historical-data/download", exchange -> {
            byte[] body = "{\"accepted\":true}".getBytes();
            exchange.sendResponseHeaders(202, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            var handler = new TuiCommandHandler(new ControlPlaneClient("http://127.0.0.1:" + server.getAddress().getPort()));
            List<String> lines = handler.handle("/data download eurusd 2010 2026 m1");
            assertTrue(lines.getFirst().contains("Download started for EUR_USD 2010-2026 (M1)"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void dataCommand_invalidTimeframeRejected() throws Exception {
        var handler = new TuiCommandHandler(new ControlPlaneClient("http://127.0.0.1:1"));
        List<String> lines = handler.handle("/data download eurusd 2010 2026 invalid_tf");
        assertTrue(lines.getFirst().contains("Invalid timeframe"));
        
        List<String> lines2 = handler.handle("/data download --sync 123");
        assertTrue(lines2.getFirst().contains("Invalid timeframe"));
    }

    @Test
    void compareCommand_outputsSideBySideMetrics() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/drift/comparison", exchange -> {
            byte[] body = """
                {
                  "strategyId": "LondonOpenRangeBreakout",
                  "recommendation": "PAUSE",
                  "dataSource": "BROKER",
                  "reason": "PAUSE — 2 dimension(s) breached",
                  "evaluatedAt": "2026-06-28T21:30:00Z",
                  "signals": [
                    { "dimension": "SHARPE_RATIO", "status": "BREACHED" },
                    { "dimension": "PROFIT_FACTOR", "status": "BREACHED" }
                  ],
                  "comparisonMetrics": {
                    "baselineWinRate": 55.0,
                    "liveWinRate": 60.0,
                    "baselineSharpe": 1.5,
                    "liveSharpe": 0.2,
                    "baselinePF": 1.5,
                    "livePF": 0.8,
                    "baselineSortino": 1.8,
                    "liveSortino": 1.9,
                    "baselineCalmar": 1.2,
                    "liveCalmar": 1.3,
                    "baselineAvgPnl": 50.0,
                    "liveAvgPnl": 60.0,
                    "pearsonCorrelation": 0.95,
                    "ksStatistic": 0.05,
                    "costDriftExceeded": false
                  }
                }
                """.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            var handler = new TuiCommandHandler(new ControlPlaneClient("http://127.0.0.1:" + server.getAddress().getPort()));
            List<String> lines = handler.handle("/compare LondonOpenRangeBreakout");
            assertTrue(lines.stream().anyMatch(l -> l.contains("Drift Comparison Report for Strategy: LondonOpenRangeBreakout")));
            assertTrue(lines.stream().anyMatch(l -> l.contains("Sharpe Ratio")));
            assertTrue(lines.stream().anyMatch(l -> l.contains("Profit Factor")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void dataCommand_supportsMultiTimeframesAndFuturesSymbols() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/historical-data/status", exchange -> {
            byte[] body = """
                {
                  "status": [
                    {
                      "symbol": "MES",
                      "pair": "mes",
                      "year": 2024,
                      "timeframe": "m15",
                      "csvExists": false,
                      "csvSize": 0,
                      "barsExists": true,
                      "barsSize": 4096
                    }
                  ],
                  "activeDownloads": [],
                  "activeTasks": []
                }
                """.strip().getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/api/historical-data/download", exchange -> {
            byte[] body = "{\"accepted\":true}".getBytes();
            exchange.sendResponseHeaders(202, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            var handler = new TuiCommandHandler(new ControlPlaneClient("http://127.0.0.1:" + server.getAddress().getPort()));
            List<String> statusLines = handler.handleCliArgs(List.of("data", "status", "m15", "--symbol", "MES"), null);
            assertTrue(statusLines.stream().anyMatch(l -> l.contains("MES") && l.contains("2024 (BARS)")));

            List<String> dlLines = handler.handleCliArgs(List.of("data", "download", "MES", "2024", "m15"), null);
            assertTrue(dlLines.getFirst().contains("Download started for MES 2024 (M15)"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void generatorAndInspectCommands_workInCliAndJsonMode() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/generator/start", exchange -> {
            String reqBody = new String(exchange.getRequestBody().readAllBytes());
            // Verify Smart Preset for MES ($10,000 capital, $0.62 comm) was applied
            assertTrue(reqBody.contains("\"symbol\":\"MES\""));
            assertTrue(reqBody.contains("\"commissionPerTrade\":0.62"));
            byte[] body = "{\"jobId\":\"gen-42\",\"status\":\"RUNNING\"}".getBytes();
            exchange.sendResponseHeaders(202, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/api/generator/databank", exchange -> {
            byte[] body = """
                {
                  "candidates": [
                    {
                      "id": "MES_H1_Trend_001",
                      "symbol": "MES",
                      "timeframe": "H1",
                      "yearSpec": "2020-2025",
                      "archetype": "TREND",
                      "direction": "BOTH",
                      "entryOrderType": "MARKET",
                      "tier": "GOLD",
                      "compositeScore": 88.5,
                      "totalTrades": 210,
                      "isTrades": 150,
                      "oosTrades": 60,
                      "netProfit": 4500.0,
                      "isNetProfit": 3100.0,
                      "oosNetProfit": 1400.0,
                      "totalReturnPct": 45.0,
                      "profitFactor": 1.85,
                      "isProfitFactor": 1.90,
                      "oosProfitFactor": 1.72,
                      "sharpeRatio": 1.65,
                      "isSharpeRatio": 1.70,
                      "oosSharpeRatio": 1.55,
                      "maxDrawdownPct": 6.4,
                      "retDdRatio": 7.03,
                      "rSquared": 0.94,
                      "mcVar95": 820.0,
                      "mcLossProbPct": 1.2,
                      "promoted": false,
                      "longSignalSummary": "EMA(20) > EMA(50) AND RSI(14) > 55",
                      "shortSignalSummary": "EMA(20) < EMA(50) AND RSI(14) < 45",
                      "entrySummary": "MARKET on bar open",
                      "exitSummary": "SL=2.0*ATR(14), TP=3.5*ATR(14)",
                      "javaSourceCode": "public class MES_H1_Trend_001 implements Strategy {}"
                    }
                  ],
                  "status": {"running": false}
                }
                """.strip().getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            var handler = new TuiCommandHandler(new ControlPlaneClient("http://127.0.0.1:" + server.getAddress().getPort()));
            List<String> startOut = handler.handleCliArgs(List.of("generator", "start", "--symbol", "MES", "--tf", "H1"), null);
            assertTrue(startOut.getFirst().contains("gen-42"));
            assertTrue(startOut.getFirst().contains("comm=$0.62"));

            List<String> dbOut = handler.handleCliArgs(List.of("generator", "databank"), null);
            assertTrue(dbOut.stream().anyMatch(l -> l.contains("MES_H1_Trend_001") && l.contains("GOLD")));

            List<String> inspectOut = handler.handleCliArgs(List.of("inspect", "MES_H1_Trend_001", "--code"), null);
            assertTrue(inspectOut.stream().anyMatch(l -> l.contains("EMA(20) > EMA(50)")));
            assertTrue(inspectOut.stream().anyMatch(l -> l.contains("public class MES_H1_Trend_001")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void wfaMonteCarloAndAnalyticsCommands_formatResearchMetrics() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/runs/run-99/monte-carlo", exchange -> {
            byte[] body = """
                {
                  "runId": "run-99",
                  "runs": 1000,
                  "blockSize": 3,
                  "pnlPercentiles": [500.0, 1200.0, 1800.0, 2400.0, 3100.0],
                  "drawdownPercentiles": [12.5, 9.0, 6.5, 4.8, 3.2],
                  "sharpePercentiles": [0.8, 1.1, 1.45, 1.8, 2.2],
                  "worstPnl": 120.0,
                  "bestPnl": 3900.0,
                  "var95": 640.0,
                  "probabilityOfLoss": 0.015
                }
                """.strip().getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/api/runs/walk-forward/wfa-1/report", exchange -> {
            byte[] body = """
                {
                  "strategyName": "LondonOpenRangeBreakout",
                  "symbol": "MES",
                  "timeframe": "H1",
                  "anchored": false,
                  "aggregatedOosNetProfit": 2340.5,
                  "aggregatedOosSharpe": 1.52,
                  "aggregatedOosMaxDrawdownPct": 5.8,
                  "wfaEfficiency": 0.78,
                  "folds": [
                    {
                      "foldIndex": 1,
                      "isNetProfit": 1200.0,
                      "isSharpe": 1.7,
                      "oosNetProfit": 480.0,
                      "oosSharpe": 1.5,
                      "bestParameters": {"period": 20}
                    }
                  ]
                }
                """.strip().getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            var handler = new TuiCommandHandler(new ControlPlaneClient("http://127.0.0.1:" + server.getAddress().getPort()));
            List<String> mcOut = handler.handleCliArgs(List.of("mc", "run-99"), null);
            assertTrue(mcOut.stream().anyMatch(l -> l.contains("MONTE CARLO BOOTSTRAP REPORT")));
            assertTrue(mcOut.stream().anyMatch(l -> l.contains("50th (Median)")));

            List<String> wfaOut = handler.handleCliArgs(List.of("wfa", "report", "wfa-1"), null);
            assertTrue(wfaOut.stream().anyMatch(l -> l.contains("WALK-FORWARD ANALYSIS REPORT: wfa-1")));
            assertTrue(wfaOut.stream().anyMatch(l -> l.contains("Fold #1")));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void edgeCaseGuardsAndBackendDtoSchemas_handleMalformedFlagsAndCanonicalFields() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/universe", exchange -> {
            byte[] body = """
                [
                  {
                    "symbol": "MES",
                    "name": "Micro E-mini S&P 500",
                    "assetClass": "FUTURES",
                    "pointValue": 5.0,
                    "tickSize": 0.25,
                    "currency": "USD",
                    "isCustom": false,
                    "providerTicker": "MES=F"
                  }
                ]
                """.strip().getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/api/runs/walk-forward/wfa-canonical/report", exchange -> {
            byte[] body = """
                {
                  "strategyName": "LondonOpenRangeBreakout",
                  "instrument": "MES",
                  "timeframe": "H1",
                  "wfe": 0.84,
                  "oosSharpe": 1.65,
                  "oosMaxDrawdownPct": 4.2,
                  "oosProfitFactor": 1.95,
                  "oosReturnPct": 18.4,
                  "oosTradesCount": 74,
                  "folds": [
                    {
                      "index": 1,
                      "isReturnPct": 11.2,
                      "isSharpe": 1.80,
                      "oosReturnPct": 6.4,
                      "oosSharpe": 1.60,
                      "chosenParameters": {"period": 20.0}
                    }
                  ]
                }
                """.strip().getBytes();
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try {
            var handler = new TuiCommandHandler(new ControlPlaneClient("http://127.0.0.1:" + server.getAddress().getPort()));

            // 1. Canonical InstrumentDefinition schema
            List<String> uniOut = handler.handleCliArgs(List.of("universe", "list"), null);
            assertTrue(uniOut.stream().anyMatch(l -> l.contains("MES") && l.contains("FUTURES") && l.contains("5.00") && l.contains("$0.62")));

            // 2. Canonical WfaReport & WfaFoldResult schema
            List<String> wfaOut = handler.handleCliArgs(List.of("wfa", "report", "wfa-canonical"), null);
            assertTrue(wfaOut.stream().anyMatch(l -> l.contains("WFE: 0.84") && l.contains("18.40%")));
            assertTrue(wfaOut.stream().anyMatch(l -> l.contains("Fold #1") && l.contains("IS=11.20") && l.contains("OOS=6.40")));

            // 3. Edge-case guards on stripped flags & malformed arguments
            List<String> btMissing = handler.handleCliArgs(List.of("backtest", "--weekly"), null);
            assertTrue(btMissing.getFirst().startsWith("Usage: /backtest"));

            List<String> promoteMissing = handler.handleCliArgs(List.of("promote", "--account", "acc-1"), null);
            assertTrue(promoteMissing.getFirst().startsWith("Usage: /promote"));

            List<String> wfaBadParam = handler.handleCliArgs(List.of("wfa", "run", "Strat", "MES", "--param", "badformat"), null);
            assertTrue(wfaBadParam.getFirst().contains("Invalid --param format"));

            List<String> invertedRange = handler.handleCliArgs(List.of("data", "delete", "MES", "2025-2023"), null);
            assertTrue(invertedRange.getFirst().contains("Invalid year range"));

            List<String> trailingFlag = handler.handleCliArgs(List.of("backtest", "Strat", "MES", "2024", "--comm"), null);
            assertTrue(trailingFlag.getFirst().contains("Missing value for flag --comm"));
        } finally {
            server.stop(0);
        }
    }
}
