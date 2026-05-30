package com.martinfou.trading.backtest.batch;

import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.CorrelationMatrix;
import com.martinfou.trading.backtest.MonteCarloSimulation;
import com.martinfou.trading.backtest.PortfolioBuilder;
import com.martinfou.trading.backtest.report.BacktestReportGenerator;
import com.martinfou.trading.backtest.report.PortfolioReportGenerator;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;

import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Portfolio backtest runner — tests all active strategies on each pair,
 * computes correlations, portfolio allocations, and generates combined PDF reports.
 *
 * <p>Usage:
 * <pre>mvn compile exec:java -pl trading-backtest
 *   -Dexec.mainClass=com.martinfou.trading.backtest.batch.RunPortfolioBacktest
 *   -Dexec.classpathScope=compile</pre>
 */
public class RunPortfolioBacktest {

    private static final int BAR_SIZE = 44;
    private static final double CAPITAL = 50_000;
    private static final double COMMISSION = 0.07;
    private static final int MC_RUNS = 1000;
    private static final String DISPLAY_LABEL = "2026-05-30";

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
        System.out.println("║  PORTFOLIO BACKTEST — Interaction Analysis                      ║");
        System.out.println("║  Toutes les stratégies × 9 assets = 9 rapports portfolio         ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        // Load all assets
        Map<String, List<Bar>> allBars = new LinkedHashMap<>();
        for (var entry : ASSETS.entrySet()) {
            String symbol = entry.getKey();
            Path file = DATA_DIR.resolve(entry.getValue());
            if (!file.toFile().exists()) {
                System.out.println("  ⚠ " + symbol + " not found: " + file);
                continue;
            }
            List<Bar> bars = loadBars(file, symbol);
            allBars.put(symbol, bars);
            if (!bars.isEmpty()) {
                System.out.printf("  ✓ %-8s — %,d bars (%s → %s)%n", symbol, bars.size(),
                    bars.getFirst().timestamp(), bars.getLast().timestamp());
            }
        }

        // Define strategies to test together
        List<StrategyPrototype> strategyPrototypes = List.of(
            new StrategyPrototype(
                "com.martinfou.trading.strategies.creative.MayExhaustionFadeStrategy",
                "MayExhaustionFade"),
            new StrategyPrototype(
                "com.martinfou.trading.strategies.creative.JPYSpecMomentumStrategy",
                "⚡ JPY Spec Momentum")
        );

        System.out.println("\n📋 " + strategyPrototypes.size() + " strategies loaded for portfolio analysis\n");

        Path creativeLab = Path.of(System.getProperty("user.dir"), "creative-lab");
        if (!creativeLab.toFile().exists()) {
            creativeLab = Path.of("/home/martinfou/projects/trading-bridge/creative-lab");
        }
        Files.createDirectories(creativeLab);

        List<Path> generatedPdfs = new ArrayList<>();

        // Process each asset independently
        for (var assetEntry : allBars.entrySet()) {
            String asset = assetEntry.getKey();
            List<Bar> bars = assetEntry.getValue();
            System.out.println("\n─── Portfolio: " + asset + " ───────────────────────────────");

            // Backtest each strategy on this asset
            Map<String, BacktestResult> results = new LinkedHashMap<>();
            Map<String, MonteCarloSimulation.Result> mcResults = new LinkedHashMap<>();
            List<String> failed = new ArrayList<>();

            for (StrategyPrototype proto : strategyPrototypes) {
                try {
                    Strategy fresh = (Strategy) Class.forName(proto.className)
                        .getDeclaredConstructor(String.class, String.class)
                        .newInstance(proto.displayName, asset);

                    BacktestEngine engine = new BacktestEngine(fresh, bars, CAPITAL)
                        .withCommissionFixed(COMMISSION);
                    long t0 = System.nanoTime();
                    BacktestResult bt = engine.run();
                    long ms = (System.nanoTime() - t0) / 1_000_000;

                    results.put(proto.displayName, bt);
                    System.out.printf("  %-22s Ret:%+10.2f%% SR:%6.2f PF:%5.2f WR:%5.1f%% DD:%6.2f%% Tr:%5d (%dms)%n",
                        truncate(proto.displayName, 22), bt.totalReturnPct(), bt.sharpeRatio(),
                        bt.profitFactor(), bt.winRatePct(), bt.maxDrawdownPct(), bt.totalTrades(), ms);

                    // Monte Carlo
                    MonteCarloSimulation mcSim = new MonteCarloSimulation(bt, MC_RUNS);
                    MonteCarloSimulation.Result mcResult = mcSim.run();
                    mcResults.put(proto.displayName, mcResult);

                } catch (Exception e) {
                    System.out.printf("  %-22s FAILED: %s%n", truncate(proto.displayName, 22), e.getMessage());
                    failed.add(proto.displayName);
                }
            }

            if (results.size() < 1) {
                System.out.println("  ❌ No strategies succeeded for " + asset + ", skipping.");
                continue;
            }

            // Correlation Matrix
            System.out.println("\n  ── Correlation Analysis ──");
            if (results.size() >= 2) {
                CorrelationMatrix cm = new CorrelationMatrix(results);
                cm.printPnLMatrix();
                cm.printDrawdownMatrix();

                // Portfolio allocations
                PortfolioBuilder pb = new PortfolioBuilder(results);
                var equalWeight = pb.equalWeightPortfolio();
                var maxSharpe = pb.maxSharpePortfolio();

                System.out.println("  ── Portfolio Allocations ──");
                equalWeight.printSummary();
                maxSharpe.printSummary();

                // Generate combined PDF
                try {
                    PortfolioReportGenerator gen = new PortfolioReportGenerator(
                        results, mcResults, asset, DISPLAY_LABEL, creativeLab)
                        .withCorrelationMatrix(cm)
                        .withPortfolioAllocation(equalWeight, maxSharpe);
                    Path pdf = gen.generate();
                    generatedPdfs.add(pdf);
                    System.out.println("  ✓ Portfolio PDF: " + pdf.getFileName());
                } catch (Exception e) {
                    System.out.println("  ❌ Portfolio PDF failed: " + e.getMessage());
                    e.printStackTrace(System.out);
                }
            } else {
                // Single strategy — generate individual PDF with MC
                var entry = results.entrySet().iterator().next();
                try {
                    Path pdf = new BacktestReportGenerator(entry.getValue(), asset,
                        entry.getKey() + "_" + DISPLAY_LABEL, creativeLab)
                        .withMonteCarlo(mcResults.get(entry.getKey()))
                        .generate();
                    generatedPdfs.add(pdf);
                } catch (Exception e) {
                    System.out.println("  ❌ PDF failed: " + e.getMessage());
                }
            }
        }

        // Summary
        System.out.println("\n\n✅ Portfolio backtest complete! " + generatedPdfs.size() + " PDFs generated.");
        System.out.println("\n📋 Upload to Joplin:");
        for (Path pdf : generatedPdfs) {
            String filename = pdf.getFileName().toString();
            String title = filename.replaceAll("_", " ").replace(".pdf", "");
            System.out.println("bash scripts/upload-to-joplin.sh \"" + pdf.toAbsolutePath()
                + "\" \"" + title + "\" \"02 - Projects / Trading robot management system / 04 - Assets\"");
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Data loading
    // ═══════════════════════════════════════════════════════════════════

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
                Instant ts = raw > 100_000_000_000L
                    ? Instant.ofEpochMilli(raw) : Instant.ofEpochSecond(raw);
                bars.add(new Bar(symbol, ts,
                    buf.getDouble(pos + 8), buf.getDouble(pos + 16),
                    buf.getDouble(pos + 24), buf.getDouble(pos + 32),
                    buf.getInt(pos + 40)));
            }
            return bars;
        }
    }

    static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max - 2) + ".." : s;
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Strategy prototype
    // ═══════════════════════════════════════════════════════════════════

    record StrategyPrototype(String className, String displayName) {}
}
