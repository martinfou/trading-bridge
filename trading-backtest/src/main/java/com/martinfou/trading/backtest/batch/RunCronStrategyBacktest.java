package com.martinfou.trading.backtest.batch;

import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Cron strategy generation & backtest runner — tests 3 new strategies × 9 assets.
 *
 * Usage:
 *   export JAVA_HOME=/home/martinfou/.local/share/mise/installs/java/26.0
 *   export PATH="$JAVA_HOME/bin:..."
 *   mvn compile -q -pl trading-core,trading-data,trading-strategies,trading-backtest -am
 *   mvn exec:java -pl trading-backtest
 *     -Dexec.mainClass=com.martinfou.trading.backtest.batch.RunCronStrategyBacktest
 *     -Dexec.classpathScope=compile
 */
public class RunCronStrategyBacktest {

    private static final int BAR_SIZE = 44;
    private static final double CAPITAL = 50_000;
    private static final double COMMISSION = 0.07;
    private static final String DISPLAY_LABEL = "2026-06-05-cron";

    static final LinkedHashMap<String, String> ASSETS = new LinkedHashMap<>();
    static {
        ASSETS.put("EUR/USD", "EUR_USD_H1_H1.bars");
        ASSETS.put("GBP/USD", "GBP_USD_H1_H1.bars");
        ASSETS.put("USD/JPY", "USD_JPY_H1_H1.bars");
        ASSETS.put("AUD/USD", "AUD_USD_H1_H1.bars");
        ASSETS.put("USD/CAD", "USD_CAD_H1_H1.bars");
        ASSETS.put("NZD/USD", "NZD_USD_H1_H1.bars");
        ASSETS.put("USD/CHF", "USD_CHF_H1_H1.bars");
        ASSETS.put("XAU/USD", "XAU_USD_H1_H1.bars");
        ASSETS.put("GBP/JPY", "GBPJPY_H1_H1.bars");
    }

    static Path DATA_DIR = Path.of("/home/martinfou/projects/trading-bridge/data/historical/bars");

    public static void main(String[] args) throws Exception {
        if (!DATA_DIR.toFile().exists()) {
            DATA_DIR = Path.of(System.getProperty("user.dir"), "data/historical/bars");
        }

        // Check EUR/JPY file mapping
        // The EUR_JPY file might not exist under that name. Let's check and adjust.
        for (Map.Entry<String, String> e : new LinkedHashMap<>(ASSETS).entrySet()) {
            if (!DATA_DIR.resolve(e.getValue()).toFile().exists()) {
                // Try alternative name
                String alt = e.getValue().replace("EUR_JPY", "EURJPY");
                if (DATA_DIR.resolve(alt).toFile().exists()) {
                    ASSETS.put(e.getKey(), alt);
                    System.out.println("  ℹ Using alt file for " + e.getKey() + ": " + alt);
                }
            }
        }

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  CRON STRATEGY BACKTEST — " + DISPLAY_LABEL + "              ║");
        System.out.println("║  3 nouvelles stratégies × 9 assets = 27 backtests               ║");
        System.out.println("║  Capital: $50,000 | Commission: $0.07 | Position: 1000          ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.println("Data: " + DATA_DIR.toAbsolutePath());

        // 1. Load all assets
        Map<String, List<Bar>> allBars = new LinkedHashMap<>();
        for (var entry : ASSETS.entrySet()) {
            String symbol = entry.getKey();
            Path file = DATA_DIR.resolve(entry.getValue());
            if (!file.toFile().exists()) {
                System.out.println("  ⚠ " + symbol + " — not found: " + file);
                continue;
            }
            List<Bar> bars = loadBars(file, symbol);
            allBars.put(symbol, bars);
            if (!bars.isEmpty()) {
                System.out.printf("  ✓ %-8s — %,d bars (%s → %s)%n", symbol, bars.size(),
                    bars.getFirst().timestamp(), bars.getLast().timestamp());
            }
        }

        if (allBars.isEmpty()) {
            System.err.println("ERROR: No assets loaded!"); System.exit(1);
        }

        // 2. Create 3 strategies — new ideas for this run (2026-06-05)
        // Previous run (May 23 batch): all 3 strategies FAILED with 2-7 trades OOS (overfiltering).
        // NEW designs deliberately simple to generate 100+ trades:
        List<Strategy> strategies = new ArrayList<>();
        strategies.add(new com.martinfou.trading.strategies.creative.TrueRangeMomentumStrategy());
        strategies.add(new com.martinfou.trading.strategies.creative.WeekendContinuationStrategy());
        strategies.add(new com.martinfou.trading.strategies.creative.MidMonthExhaustionStrategy());

        String[] categories = {
            "TrueRangeMomentum          (Structure/Technical)",
            "WeekendContinuation        (News/Sentiment)",
            "MidMonthExhaustion         (Seasonality/Calendar)"
        };

        for (int si = 0; si < strategies.size(); si++) {
            System.out.printf("%n─── [%d/3] %s ──────────────────────────────────%n",
                si + 1, categories[si]);
        }

        System.out.println();

        // 3. Run all backtests
        Map<String, Map<String, Result>> allResults = new LinkedHashMap<>();

        for (Strategy s : strategies) {
            System.out.println("─── " + s.name() + " ──────────────────────────────────────");
            Map<String, Result> assetResults = new LinkedHashMap<>();

            for (var entry : allBars.entrySet()) {
                String asset = entry.getKey();
                List<Bar> bars = entry.getValue();

                try {
                    Strategy fresh = s.getClass()
                        .getDeclaredConstructor(String.class, String.class)
                        .newInstance(s.name(), asset);

                    BacktestEngine engine = new BacktestEngine(fresh, bars, CAPITAL)
                        .withCommissionFixed(COMMISSION);

                    long t0 = System.nanoTime();
                    BacktestResult bt = engine.run();
                    long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

                    assetResults.put(asset, new Result(bt, elapsedMs));

                    System.out.printf("  %-8s → Ret:%+7.2f%% SR:%5.2f PF:%4.2f WR:%5.1f%% DD:%5.2f%% Tr:%4d (%dms)%n",
                        asset, bt.totalReturnPct(), bt.sharpeRatio(), bt.profitFactor(),
                        bt.winRatePct(), bt.maxDrawdownPct(), bt.totalTrades(), elapsedMs);

                } catch (Exception e) {
                    System.out.printf("  %-8s → FAILED: %s%n", asset, e.getMessage());
                    assetResults.put(asset, null);
                }
            }
            allResults.put(s.name(), assetResults);
        }

        // 4. Ranking
        System.out.println("\n\n╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  CRON RANKING — Best Strategy × Asset pairs                     ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        List<StratAssetResult> ranked = new ArrayList<>();

        for (var stratEntry : allResults.entrySet()) {
            String stratName = stratEntry.getKey();
            for (var resEntry : stratEntry.getValue().entrySet()) {
                String asset = resEntry.getKey();
                Result r = resEntry.getValue();
                if (r == null) continue;

                // Compute adjusted Sharpe: penalize for <100 trades (apply Ali Casey's rule)
                double adjustedSharpe = r.bt.sharpeRatio();
                int trades = r.bt.totalTrades();
                if (trades < 100) {
                    adjustedSharpe *= (trades / 100.0); // Linear penalty
                }
                // Penalize if maxDD > 25%
                if (r.bt.maxDrawdownPct() > 25) {
                    adjustedSharpe *= (1.0 - (r.bt.maxDrawdownPct() - 25) / 100.0);
                }

                ranked.add(new StratAssetResult(stratName, asset, adjustedSharpe,
                    r.bt.sharpeRatio(), r.bt.totalReturnPct(), r.bt.profitFactor(),
                    r.bt.winRatePct(), r.bt.maxDrawdownPct(), trades, r.bt));
            }
        }

        ranked.sort((a, b) -> Double.compare(b.adjSharpe, a.adjSharpe));

        System.out.printf("%-3s %-28s %-8s %8s %8s %8s %8s %8s %6s%n",
            "#", "Strategy", "Asset", "AdjSR", "Sharpe", "Ret%", "PF", "DD%", "Tr");
        System.out.println("-".repeat(90));

        int rank = 0;
        for (StratAssetResult r : ranked) {
            rank++;
            System.out.printf("%-3d %-28s %-8s %8.2f %8.2f %8.2f %8.2f %8.2f %6d%n",
                rank, truncate(r.strategyName, 26), r.asset, r.adjSharpe,
                r.sharpe, r.totalReturn, r.profitFactor, r.maxDrawdown, r.trades);
        }

        // 5. Qualification assessment per strategy
        System.out.println("\n\n╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  QUALIFICATION ASSESSMENT — Ali Casey / Quantified Strategies  ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        for (Map.Entry<String, Map<String, Result>> stratEntry : allResults.entrySet()) {
            String stratName = stratEntry.getKey();
            int passed = 0;
            int total = 0;
            double bestAdjSharpe = -999;
            String bestAsset = "";

            for (Map.Entry<String, Result> resEntry : stratEntry.getValue().entrySet()) {
                String asset = resEntry.getKey();
                Result r = resEntry.getValue();
                if (r == null) continue;
                total++;

                boolean qualPF = r.bt.profitFactor() >= 1.6;
                boolean qualSharpe = r.bt.sharpeRatio() >= 1.5;
                boolean qualDD = r.bt.maxDrawdownPct() <= 25;
                boolean qualTrades = r.bt.totalTrades() >= 100;
                boolean qualRet = r.bt.totalReturnPct() > 5;

                if (qualPF && qualDD && qualTrades) {
                    passed++;
                }

                // Compute adjusted sharpe for best detection
                double adj = r.bt.sharpeRatio();
                if (r.bt.totalTrades() < 100) adj *= (r.bt.totalTrades() / 100.0);
                if (adj > bestAdjSharpe) {
                    bestAdjSharpe = adj;
                    bestAsset = asset;
                }
            }

            String category = switch (stratName) {
                case "TrueRangeMomentum" -> "Structure/Technical";
                case "WeekendContinuation" -> "News/Sentiment";
                case "MidMonthExhaustion" -> "Seasonality/Calendar";
                default -> "Other";
            };

            System.out.printf("%n  📊 %s [%s]%n", stratName, category);
            System.out.printf("     Assets tested: %d | Passed qualification: %d/%d%n", total, passed, total);
            System.out.printf("     Best asset: %s (Adj Sharpe: %.2f)%n", bestAsset, bestAdjSharpe);

            if (passed >= 3 && bestAdjSharpe >= 0.8) {
                System.out.println("     ✅ QUALIFIED — viable for further iteration");
            } else if (bestAdjSharpe >= 0.5) {
                System.out.println("     🟡 PROMISING — needs parameter tuning");
            } else {
                System.out.println("     ❌ REJECTED — not statistically significant");
            }
        }

        // 6. Generate PDFs for top qualified candidates
        System.out.println("\n\n📄 Generating PDF reports for top picks...");
        Path creativeLab = Path.of("/home/martinfou/projects/trading-bridge/creative-lab");
        Files.createDirectories(creativeLab);

        List<String> generatedPdfs = new ArrayList<>();
        int pdfCount = 0;
        for (StratAssetResult r : ranked) {
            if (pdfCount >= 3) break;
            if (r.adjSharpe < 0.5) continue;

            String label = r.strategyName.replaceAll("[^a-zA-Z0-9]", "") + "_" + r.asset.replace("/", "");
            try {
                // Check if MonteCarlo and BacktestReportGenerator exist
                // Use simple HTML report instead
                String html = generateHtmlReport(r, label);
                Path htmlPath = creativeLab.resolve(label + ".html");
                Files.writeString(htmlPath, html);
                generatedPdfs.add(htmlPath.toString());
                System.out.println("  ✓ " + htmlPath.getFileName());
                pdfCount++;
            } catch (Exception e) {
                System.out.println("  ❌ Failed to generate report for " + label + ": " + e.getMessage());
            }
        }

        // 7. Final recommendation
        System.out.println("\n\n╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  TOP RECOMMENDATION                                              ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        if (!ranked.isEmpty() && ranked.get(0).adjSharpe > 0.5) {
            StratAssetResult top = ranked.get(0);
            System.out.printf("  🏆 %s on %s (Adj Sharpe: %.2f)%n", top.strategyName, top.asset, top.adjSharpe);
            System.out.printf("     Sharpe: %.2f | Return: %.2f%% | PF: %.2f | MaxDD: %.2f%% | Trades: %d%n",
                top.sharpe, top.totalReturn, top.profitFactor, top.maxDrawdown, top.trades);
            System.out.println("  → Continue development on this strategy in next run.");
        } else {
            System.out.println("  ❌ No strategy met minimum qualification thresholds.");
            System.out.println("  → Generate 3 new ideas next run.");
        }

        // List generated reports
        if (!generatedPdfs.isEmpty()) {
            System.out.println("\n  📁 Reports generated in creative-lab/:");
            for (String p : generatedPdfs) {
                System.out.println("     " + p);
            }
        }

        System.out.println("\n✅ Cron strategy backtest complete!");
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Data loading
    // ══════════════════════════════════════════════════════════════════════

    static List<Bar> loadBars(Path path, String symbol) throws Exception {
        try (var file = new RandomAccessFile(path.toFile(), "r");
             var channel = file.getChannel()) {
            long size = channel.size();
            int count = (int) (size / BAR_SIZE);
            var buf = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);

            List<Bar> bars = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int pos = i * BAR_SIZE;
                long raw = buf.getLong(pos);
                Instant ts;
                if (raw > 100_000_000_000L) {
                    ts = Instant.ofEpochMilli(raw);
                } else {
                    ts = Instant.ofEpochSecond(raw);
                }
                double open  = buf.getDouble(pos + 8);
                double high  = buf.getDouble(pos + 16);
                double low   = buf.getDouble(pos + 24);
                double close = buf.getDouble(pos + 32);
                int vol      = buf.getInt(pos + 40);
                bars.add(new Bar(symbol, ts, open, high, low, close, vol));
            }
            return bars;
        }
    }

    static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 2) + ".." : s;
    }

    static String generateHtmlReport(StratAssetResult r, String label) {
        return """
<!DOCTYPE html>
<html><head><meta charset="UTF-8"><title>%s</title>
<style>
body{font-family:sans-serif;max-width:800px;margin:auto;padding:20px;background:#1a1a2e;color:#eee}
h1{color:#e94560;border-bottom:2px solid #e94560;padding-bottom:8px}
h2{color:#0f3460;margin-top:30px}
.metrics{display:grid;grid-template-columns:repeat(3,1fr);gap:12px}
.card{background:#16213e;padding:16px;border-radius:8px;text-align:center}
.card .value{font-size:24px;font-weight:bold;color:#e94560}
.card .label{font-size:12px;color:#888;margin-top:4px}
table{width:100%%;border-collapse:collapse;margin-top:16px}
th,td{padding:8px;text-align:right;border-bottom:1px solid #333}
th{background:#0f3460;color:#fff;text-align:right}
td{color:#bbb}
tr:hover{background:#1a1a3e}
</style></head><body>
<h1>📊 %s</h1>
<p><strong>Asset:</strong> %s | <strong>Strategy:</strong> %s</p>
<div class="metrics">
  <div class="card"><div class="value">%.2f</div><div class="label">Adj. Sharpe</div></div>
  <div class="card"><div class="value">%.2f</div><div class="label">Sharpe</div></div>
  <div class="card"><div class="value">%.2f%%</div><div class="label">Return</div></div>
  <div class="card"><div class="value">%.2f</div><div class="label">Profit Factor</div></div>
  <div class="card"><div class="value">%.2f%%</div><div class="label">Max DD</div></div>
  <div class="card"><div class="value">%d</div><div class="label">Trades</div></div>
</div>
<h2>Qualification</h2>
<table>
<tr><th>Criteria</th><th>Threshold</th><th>Value</th><th>Status</th></tr>
<tr><td>Sharpe</td><td>≥ 1.5</td><td>%.2f</td><td>%s</td></tr>
<tr><td>Profit Factor</td><td>≥ 1.6</td><td>%.2f</td><td>%s</td></tr>
<tr><td>Max DD</td><td>≤ 25%%</td><td>%.2f%%</td><td>%s</td></tr>
<tr><td>Min Trades</td><td>≥ 100</td><td>%d</td><td>%s</td></tr>
</table>
<p><small>Generated: %s</small></p>
</body></html>""".formatted(
            label, label, r.asset, r.strategyName,
            r.adjSharpe, r.sharpe, r.totalReturn, r.profitFactor, r.maxDrawdown, r.trades,
            r.sharpe, r.sharpe >= 1.5 ? "✅" : "❌",
            r.profitFactor, r.profitFactor >= 1.6 ? "✅" : "❌",
            r.maxDrawdown, r.maxDrawdown <= 25 ? "✅" : "❌",
            r.trades, r.trades >= 100 ? "✅" : "❌",
            Instant.now()
        ).replace("|", " "); // Remove pipe issue
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Records
    // ══════════════════════════════════════════════════════════════════════

    record Result(BacktestResult bt, long elapsedMs) {}

    record StratAssetResult(String strategyName, String asset, double adjSharpe,
                            double sharpe, double totalReturn, double profitFactor,
                            double winRate, double maxDrawdown, int trades, BacktestResult bt) {}
}
