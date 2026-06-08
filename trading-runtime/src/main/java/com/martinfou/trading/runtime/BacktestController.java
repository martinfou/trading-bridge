package com.martinfou.trading.runtime;

import io.javalin.http.Context;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Controller exposing REST API endpoints for backtest queries and analytics.
 */
public final class BacktestController {

    private final Path dbPath;

    public BacktestController() {
        this.dbPath = com.martinfou.trading.backtest.persistence.BacktestPersistenceService.resolveDefaultDbPath();
    }

    public BacktestController(Path dbPath) {
        this.dbPath = dbPath;
    }

    public void listBacktests(Context ctx) {
        String symbol = ctx.queryParam("symbol");
        String strategyId = ctx.queryParam("strategyId");
        if (strategyId == null) {
            strategyId = ctx.queryParam("strategy");
        }
        Double minSharpe = ctx.queryParamAsClass("minSharpe", Double.class).getOrDefault(null);
        if (minSharpe == null) {
            minSharpe = ctx.queryParamAsClass("min_sharpe", Double.class).getOrDefault(null);
        }
        Double minProfitFactor = ctx.queryParamAsClass("minProfitFactor", Double.class).getOrDefault(null);
        if (minProfitFactor == null) {
            minProfitFactor = ctx.queryParamAsClass("min_pf", Double.class).getOrDefault(null);
        }
        String sortBy = ctx.queryParam("sortBy");
        if (sortBy == null) {
            sortBy = ctx.queryParam("sort_by");
        }
        String sortOrder = ctx.queryParam("sortOrder");
        if (sortOrder == null) {
            sortOrder = ctx.queryParam("sort_order");
        }
        Integer limit = ctx.queryParamAsClass("limit", Integer.class).getOrDefault(20);
        Integer offset = ctx.queryParamAsClass("offset", Integer.class).getOrDefault(0);

        var filters = com.martinfou.trading.backtest.persistence.BacktestQueryFilters.builder()
            .symbol(symbol)
            .strategyId(strategyId)
            .minSharpe(minSharpe)
            .minProfitFactor(minProfitFactor)
            .sortBy(sortBy)
            .sortOrder(sortOrder)
            .limit(limit)
            .offset(offset)
            .build();

        try (var store = new com.martinfou.trading.backtest.persistence.SqliteBacktestRunStore(dbPath)) {
            var items = store.list(filters);
            int total = store.count(filters);
            ctx.json(Map.of(
                "total", total,
                "limit", limit,
                "offset", offset,
                "items", items
            ));
        } catch (Exception e) {
            ctx.status(500);
            ctx.json(Map.of("error", e.getMessage() != null ? e.getMessage() : "Failed to query backtest runs"));
        }
    }

    public void getBacktestDetails(Context ctx) {
        String runId = ctx.pathParam("runId");
        try (var store = new com.martinfou.trading.backtest.persistence.SqliteBacktestRunStore(dbPath)) {
            var detailsOpt = store.get(runId);
            if (detailsOpt.isEmpty()) {
                ctx.status(404);
                ctx.json(Map.of("error", "Backtest run not found: " + runId));
                return;
            }
            ctx.json(detailsOpt.get());
        } catch (Exception e) {
            ctx.status(500);
            ctx.json(Map.of("error", e.getMessage() != null ? e.getMessage() : "Failed to load backtest details"));
        }
    }

    public void getHeatmap(Context ctx) {
        String strategyId = ctx.queryParam("strategyId");
        if (strategyId == null) {
            strategyId = ctx.queryParam("strategy");
        }
        if (strategyId == null || strategyId.isBlank()) {
            ctx.status(400);
            ctx.json(Map.of("error", "Missing required query parameter: strategyId"));
            return;
        }

        String xParam = ctx.queryParam("xParam");
        if (xParam == null) {
            xParam = ctx.queryParam("x_param");
        }
        String yParam = ctx.queryParam("yParam");
        if (yParam == null) {
            yParam = ctx.queryParam("y_param");
        }
        if (xParam == null || xParam.isBlank() || yParam == null || yParam.isBlank()) {
            ctx.status(400);
            ctx.json(Map.of("error", "Missing required parameters: xParam, yParam"));
            return;
        }

        String metric = ctx.queryParam("metric");
        if (metric == null) {
            metric = "sharpeRatio";
        }

        String symbol = ctx.queryParam("symbol");

        var filters = com.martinfou.trading.backtest.persistence.BacktestQueryFilters.builder()
            .strategyId(strategyId)
            .symbol(symbol)
            .limit(1000)
            .build();

        try (var store = new com.martinfou.trading.backtest.persistence.SqliteBacktestRunStore(dbPath)) {
            var runs = store.list(filters);
            var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();
            List<Map<String, Object>> grid = new ArrayList<>();

            for (var run : runs) {
                try {
                    Map<?, ?> paramsMap = objectMapper.readValue(run.parameters(), Map.class);
                    Object xVal = paramsMap.get(xParam);
                    Object yVal = paramsMap.get(yParam);
                    if (xVal != null && yVal != null) {
                        double metricVal = getMetricValue(run, metric);
                        Map<String, Object> cell = new LinkedHashMap<>();
                        cell.put("x", xVal);
                        cell.put("y", yVal);
                        cell.put("value", metricVal);
                        cell.put("runId", run.runId());
                        grid.add(cell);
                    }
                } catch (Exception ignored) {
                }
            }

            ctx.json(Map.of(
                "strategyId", strategyId,
                "xParam", xParam,
                "yParam", yParam,
                "metric", metric,
                "data", grid
            ));
        } catch (Exception e) {
            ctx.status(500);
            ctx.json(Map.of("error", e.getMessage() != null ? e.getMessage() : "Failed to compute parameter heatmap"));
        }
    }

    public void getPareto(Context ctx) {
        String strategyId = ctx.queryParam("strategyId");
        if (strategyId == null) {
            strategyId = ctx.queryParam("strategy");
        }
        if (strategyId == null || strategyId.isBlank()) {
            ctx.status(400);
            ctx.json(Map.of("error", "Missing required query parameter: strategyId"));
            return;
        }

        String symbol = ctx.queryParam("symbol");
        String metricX = ctx.queryParam("metricX");
        if (metricX == null) {
            metricX = "maxDrawdownPct";
        }
        String metricY = ctx.queryParam("metricY");
        if (metricY == null) {
            metricY = "totalReturnPct";
        }

        var filters = com.martinfou.trading.backtest.persistence.BacktestQueryFilters.builder()
            .strategyId(strategyId)
            .symbol(symbol)
            .limit(1000)
            .build();

        try (var store = new com.martinfou.trading.backtest.persistence.SqliteBacktestRunStore(dbPath)) {
            var allRuns = store.list(filters);
            List<com.martinfou.trading.backtest.persistence.BacktestRunSummary> paretoFrontier = new ArrayList<>();

            for (var a : allRuns) {
                boolean dominated = false;
                double ax = getMetricValue(a, metricX);
                double ay = getMetricValue(a, metricY);

                for (var b : allRuns) {
                    if (a == b) {
                        continue;
                    }
                    double bx = getMetricValue(b, metricX);
                    double by = getMetricValue(b, metricY);

                    boolean bBetterX = isBetter(bx, ax, metricX);
                    boolean bBetterY = isBetter(by, ay, metricY);
                    boolean bEqualX = Math.abs(bx - ax) < 1e-9;
                    boolean bEqualY = Math.abs(by - ay) < 1e-9;

                    if ((bBetterX || bEqualX) && (bBetterY || bEqualY) && (bBetterX || bBetterY)) {
                        dominated = true;
                        break;
                    }
                }
                if (!dominated) {
                    paretoFrontier.add(a);
                }
            }

            ctx.json(Map.of(
                "strategyId", strategyId,
                "metricX", metricX,
                "metricY", metricY,
                "frontier", paretoFrontier,
                "runs", allRuns,
                "allRunsCount", allRuns.size()
            ));
        } catch (Exception e) {
            ctx.status(500);
            ctx.json(Map.of("error", e.getMessage() != null ? e.getMessage() : "Failed to compute Pareto frontier"));
        }
    }

    public void deleteBacktest(Context ctx) {
        String runId = ctx.pathParam("runId");
        try (var store = new com.martinfou.trading.backtest.persistence.SqliteBacktestRunStore(dbPath)) {
            store.delete(runId);
            ctx.status(200);
            ctx.json(Map.of("success", true, "message", "Deleted backtest run: " + runId));
        } catch (Exception e) {
            ctx.status(500);
            ctx.json(Map.of("error", e.getMessage() != null ? e.getMessage() : "Failed to delete backtest run"));
        }
    }

    public void deleteAllBacktests(Context ctx) {
        try (var store = new com.martinfou.trading.backtest.persistence.SqliteBacktestRunStore(dbPath)) {
            store.deleteAll();
            ctx.status(200);
            ctx.json(Map.of("success", true, "message", "Deleted all backtest runs"));
        } catch (Exception e) {
            ctx.status(500);
            ctx.json(Map.of("error", e.getMessage() != null ? e.getMessage() : "Failed to delete all backtest runs"));
        }
    }

    private double getMetricValue(com.martinfou.trading.backtest.persistence.BacktestRunSummary run, String metric) {
        return switch (metric.toLowerCase()) {
            case "sharpe", "sharperatio" -> run.sharpeRatio();
            case "profitfactor", "pf" -> run.profitFactor();
            case "totalpnl", "pnl" -> run.totalPnl();
            case "totalreturnpct", "return" -> run.totalReturnPct();
            case "maxdrawdownpct", "drawdown" -> run.maxDrawdownPct();
            case "winratepct", "winrate" -> run.winRatePct();
            case "totaltrades", "trades" -> run.totalTrades();
            case "sortinoratio", "sortino" -> run.sortinoRatio();
            case "calmarratio", "calmar" -> run.calmarRatio();
            default -> run.sharpeRatio();
        };
    }

    private static boolean isBetter(double v1, double v2, String metric) {
        if ("maxDrawdownPct".equalsIgnoreCase(metric) || "drawdown".equalsIgnoreCase(metric) || "totalCommission".equalsIgnoreCase(metric) || "totalSlippage".equalsIgnoreCase(metric)) {
            return v1 < v2;
        }
        return v1 > v2;
    }
}
