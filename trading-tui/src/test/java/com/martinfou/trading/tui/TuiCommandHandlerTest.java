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
}
