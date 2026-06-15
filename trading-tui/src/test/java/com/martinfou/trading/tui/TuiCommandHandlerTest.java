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
}
