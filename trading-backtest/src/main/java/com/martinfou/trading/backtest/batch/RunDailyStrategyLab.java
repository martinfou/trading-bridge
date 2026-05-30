package com.martinfou.trading.backtest.batch;

import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.report.BacktestReportGenerator;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Daily Strategy Lab — 3 New Strategies × 9 Assets = 27 Backtests
 *
 * Generates PDF reports for each strategy-asset pair and ranks by
 * Robustness Factor.
 */
public class RunDailyStrategyLab {

    private static final int BAR_SIZE = 44;
    private static final double CAPITAL = 50_000;
    private static final double COMMISSION = 0.07;

    static final LinkedHashMap<String, String> ASSETS = new LinkedHashMap<>();
    static {
        ASSETS.put("GBP/JPY", "GBPJPY_H1_H1.bars");
        ASSETS.put("XAU/USD", "XAU_USD_H1_H1.bars");
        ASSETS.put("EUR/USD", "EUR_USD_H1_H1.bars");
        ASSETS.put("GBP/USD", "GBP_USD_H1_H1.bars");
        ASSETS.put("USD/CAD", "USD_CAD_H1_H1.bars");
        ASSETS.put("USD/JPY", "USD_JPY_H1_H1.bars");
        ASSETS.put("AUD/USD", "AUD_USD_H1_H1.bars");
        ASSETS.put("NZD/USD", "NZD_USD_H1_H1.bars");
        ASSETS.put("USD/CHF", "USD_CHF_H1_H1.bars");
    }

    static Path DATA_DIR = Path.of("/home/martinfou/projects/trading-bridge/data/historical/bars");

    public static void main(String[] args) throws Exception {
        if (!DATA_DIR.toFile().exists()) {
            DATA_DIR = Path.of(System.getProperty("user.dir"), "data/historical/bars");
        }

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  DAILY STRATEGY LAB — 3 Strategies × 9 Assets                   ║");
        System.out.println("║  27 backtests | PDF reports + Joplin upload                      ║");
        System.out.println("║  Date: 2026-05-30                                               ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        // ── 1. Load all assets ──
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

        // ── 2. Create 3 new strategies ──
        List<Strategy> strategies = new ArrayList<>();
        strategies.add(new com.martinfou.trading.strategies.creative.IBSMeanReversionStrategy());
        strategies.add(new com.martinfou.trading.strategies.creative.MomentumDivergenceStrategy());
        strategies.add(new com.martinfou.trading.strategies.creative.FalseBreakoutReversalStrategy());

        System.out.println("\n📋 3 new strategies loaded for backtest\n");

        // ── 3. Run all backtests ──
        Map<String, Map<String, MResult>> allResults = new LinkedHashMap<>();
        Path reportDir = Path.of(System.getProperty("user.dir"), "creative-lab");
        if (!reportDir.toFile().exists()) {
            reportDir = Path.of("/home/martinfou/projects/trading-bridge/creative-lab");
        }
        Files.createDirectories(reportDir);

        List<String> generatedPdfs = new ArrayList<>();

        for (Strategy s : strategies) {
            System.out.println("─── " + s.name() + " ──────────────────────────────────────");
            Map<String, MResult> assetResults = new LinkedHashMap<>();

            for (var entry : allBars.entrySet()) {
                String asset = entry.getKey();
                List<Bar> bars = entry.getValue();

                try {
                    // Fresh instance per asset
                    Strategy fresh = s.getClass()
                        .getDeclaredConstructor(String.class, String.class)
                        .newInstance(s.name(), asset);

                    BacktestEngine engine = new BacktestEngine(fresh, bars, CAPITAL)
                        .withCommissionFixed(COMMISSION);

                    long t0 = System.nanoTime();
                    BacktestResult bt = engine.run();
                    long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

                    assetResults.put(asset, new MResult(
                        bt.totalReturnPct(), bt.sharpeRatio(), bt.sortinoRatio(),
                        bt.profitFactor(), bt.winRatePct(), bt.maxDrawdownPct(),
                        bt.totalTrades(), bt.totalPnl(), bt.calmarRatio(), elapsedMs
                    ));

                    System.out.printf("  %-8s → Ret:%+7.2f%% SR:%5.2f PF:%4.2f WR:%5.1f%% DD:%5.2f%% Tr:%4d (%dms)%n",
                        asset, bt.totalReturnPct(), bt.sharpeRatio(), bt.profitFactor(),
                        bt.winRatePct(), bt.maxDrawdownPct(), bt.totalTrades(), elapsedMs);

                    // Generate PDF report for this strategy-asset pair if enough trades
                    if (bt.totalTrades() >= 10) {
                        try {
                            Path pdfPath = new BacktestReportGenerator(bt, asset, s.name(), reportDir).generate();
                            generatedPdfs.add(pdfPath.toString());
                            System.out.println("    📄 PDF: " + pdfPath.getFileName());
                        } catch (Exception e) {
                            System.out.println("    ⚠ PDF generation failed: " + e.getMessage());
                        }
                    } else {
                        System.out.println("    ⚠ Insufficient trades (" + bt.totalTrades() + "), skipping PDF");
                    }

                } catch (Exception e) {
                    System.out.printf("  %-8s → FAILED: %s%n", asset, e.getMessage());
                    assetResults.put(asset, null);
                }
            }
            allResults.put(s.name(), assetResults);
        }

        // ── 4. Calculate Robustness Factor ──
        System.out.println("\n\n╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  Robustness Factor Rankings                                    ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        List<RankedStrategy> rankings = new ArrayList<>();
        for (var stratEntry : allResults.entrySet()) {
            String name = stratEntry.getKey();
            Map<String, MResult> results = stratEntry.getValue();

            double sharpeSum = 0;
            int countAssets = 0;
            int penaltyCount = 0;
            double maxDdWorst = 0;
            int totalTradesAll = 0;
            double pfSum = 0;

            for (var resEntry : results.entrySet()) {
                MResult r = resEntry.getValue();
                if (r == null) continue;
                countAssets++;
                sharpeSum += r.sharpeRatio;
                pfSum += r.profitFactor;
                totalTradesAll += r.totalTrades;
                if (r.maxDrawdown > 15) penaltyCount++;
                if (r.maxDrawdown > maxDdWorst) maxDdWorst = r.maxDrawdown;
            }

            if (countAssets == 0) continue;

            double avgSharpe = sharpeSum / countAssets;
            double avgPf = pfSum / countAssets;

            double penalty = 1.0 - (penaltyCount * 0.25);
            if (penalty < 0.1) penalty = 0.1;
            if (totalTradesAll < 100) penalty *= 0.5;

            double robustness = avgSharpe * penalty;

            rankings.add(new RankedStrategy(name, robustness, avgSharpe, avgPf,
                countAssets, penaltyCount, totalTradesAll, maxDdWorst, results));
        }

        rankings.sort((a, b) -> Double.compare(b.robustness, a.robustness));

        System.out.println(String.format("%-3s %-30s %10s %10s %8s %8s %8s %10s",
            "#", "Strategy", "Robustness", "Avg Sharpe", "Avg PF", "Assets", "Penalty", "Total Trades"));
        System.out.println("-".repeat(95));
        int rank = 0;
        for (RankedStrategy rs : rankings) {
            rank++;
            System.out.println(String.format("%-3d %-30s %10.4f %10.2f %8.2f %7d %8d %,10d",
                rank, truncate(rs.name, 28), rs.robustness, rs.avgSharpe,
                rs.avgPf, rs.assetCount, rs.penaltyCount, rs.totalTrades));

            String bestAsset = "";
            double bestSharp = -999;
            for (var e : rs.results.entrySet()) {
                MResult r = e.getValue();
                if (r != null && r.sharpeRatio > bestSharp) {
                    bestSharp = r.sharpeRatio;
                    bestAsset = e.getKey();
                }
            }
            if (!bestAsset.isEmpty()) {
                MResult br = rs.results.get(bestAsset);
                System.out.printf("     Best: %s (SR=%.2f R=%.2f%% DD=%.2f%% Trades=%d)%n",
                    bestAsset,
                    br != null ? br.sharpeRatio : 0,
                    br != null ? br.totalReturnPct : 0,
                    br != null ? br.maxDrawdown : 0,
                    br != null ? br.totalTrades : 0);
            }
        }

        // Generate report file
        generateReport(rankings, allBars, generatedPdfs);

        System.out.println("\n✅ Strategy Lab complete!");
        System.out.println("📊 PDF files generated: " + generatedPdfs.size());
        for (String pdf : generatedPdfs) {
            System.out.println("   " + pdf);
        }
    }

    static void generateReport(List<RankedStrategy> rankings, Map<String, List<Bar>> allBars, List<String> pdfs) throws Exception {
        Path reportDir = Path.of(System.getProperty("user.dir"), "creative-lab");
        if (!reportDir.toFile().exists()) {
            reportDir = Path.of("/home/martinfou/projects/trading-bridge/creative-lab");
        }
        Files.createDirectories(reportDir);

        StringBuilder md = new StringBuilder();
        md.append("# 📊 Daily Strategy Lab — 30/05/2026\n\n");
        md.append("## 3 New Strategies × 9 Assets = 27 Backtests\n\n");
        md.append("| Aspect | Value |\n");
        md.append("|--------|-------|\n");
        md.append("| **Assets** | ").append(String.join(", ", ASSETS.keySet())).append(" |\n");
        md.append("| **Stratégies** | IBSMeanReversion, MomentumDivergence, FalseBreakoutReversal |\n");
        md.append("| **Capital** | $50,000 |\n");
        md.append("| **Commission** | $0.07/trade |\n");
        md.append("| **Period** | ~20 ans H1 |\n");
        md.append("| **PDF Reports** | ").append(pdfs.size()).append(" generated |\n\n");

        // Ranking
        md.append("## Classement (Robustness Factor)\n\n");
        md.append("| # | Stratégie | RF | Avg Sharpe | Avg PF | Assets OK | Pénalités | Trades |\n");
        md.append("|---|----------|----|-----------|--------|----------|-----------|--------|\n");
        for (int i = 0; i < rankings.size(); i++) {
            RankedStrategy rs = rankings.get(i);
            md.append(String.format("| %d | %s | %.4f | %.2f | %.2f | %d | %d | %,d |\n",
                i + 1, rs.name, rs.robustness, rs.avgSharpe, rs.avgPf,
                rs.assetCount, rs.penaltyCount, rs.totalTrades));
        }
        md.append("\n");

        // Results by strategy
        md.append("## Résultats détaillés\n\n");
        for (int r = 0; r < rankings.size(); r++) {
            RankedStrategy rs = rankings.get(r);
            md.append("### ").append(r + 1).append(". ").append(rs.name).append("\n\n");
            md.append(String.format("**Robustness**: %.4f | **Avg Sharpe**: %.2f | **Avg PF**: %.2f | **Trades**: %d\n\n",
                rs.robustness, rs.avgSharpe, rs.avgPf, rs.totalTrades));

            md.append("| Asset | Return%% | Sharpe | PF | WinRate%% | MaxDD%% | Trades |\n");
            md.append("|-------|---------|--------|----|----------|--------|--------|\n");
            for (var e : rs.results.entrySet()) {
                MResult r2 = e.getValue();
                if (r2 == null) {
                    md.append("| ").append(e.getKey()).append(" | ERROR | - | - | - | - | - |\n");
                } else {
                    md.append(String.format("| %s | %.2f | %.2f | %.2f | %.1f | %.2f | %d |\n",
                        e.getKey(), r2.totalReturnPct, r2.sharpeRatio, r2.profitFactor,
                        r2.winRate, r2.maxDrawdown, r2.totalTrades));
                }
            }
            md.append("\n");
        }

        md.append("## PDFs générés\n\n");
        for (String pdf : pdfs) {
            md.append("- ").append(pdf).append("\n");
        }
        md.append("\n\n*Rapport généré le ").append(LocalDateTime.now()).append("*\n");

        Path reportFile = reportDir.resolve("2026-05-30-strategy-lab.md");
        Files.writeString(reportFile, md.toString());
        System.out.println("📄 Report: " + reportFile);
    }

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

    record MResult(double totalReturnPct, double sharpeRatio, double sortinoRatio,
                   double profitFactor, double winRate, double maxDrawdown,
                   int totalTrades, double totalPnl, double calmarRatio, long elapsedMs) {}

    record RankedStrategy(String name, double robustness, double avgSharpe, double avgPf,
                          int assetCount, int penaltyCount, int totalTrades, double worstMaxDd,
                          Map<String, MResult> results) {}
}
