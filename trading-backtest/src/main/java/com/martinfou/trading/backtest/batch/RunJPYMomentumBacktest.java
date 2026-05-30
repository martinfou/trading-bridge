package com.martinfou.trading.backtest.batch;

import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.MonteCarloSimulation;
import com.martinfou.trading.backtest.report.BacktestReportGenerator;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Focused backtest — tests the best weekly strategy on selected pairs.
 * Generates PDF reports for deployment consideration.
 *
 * Strategy: JPYSpecMomentum — follows COT bullish JPY spec positioning
 * Pairs: GBP/USD, EUR/USD, USD/CAD (PF > 3.2, WR > 67%) + GBP/JPY
 */
public class RunJPYMomentumBacktest {

    private static final int BAR_SIZE = 44;
    private static final double CAPITAL = 50_000;
    private static final double COMMISSION = 0.07;

    static final LinkedHashMap<String, String> ASSETS = new LinkedHashMap<>();
    static {
        ASSETS.put("EUR/USD", "EUR_USD_H1_H1.bars");
        ASSETS.put("GBP/USD", "GBP_USD_H1_H1.bars");
        ASSETS.put("USD/CAD", "USD_CAD_H1_H1.bars");
        ASSETS.put("GBP/JPY", "GBPJPY_H1_H1.bars");
        ASSETS.put("USD/JPY", "USD_JPY_H1_H1.bars");
        ASSETS.put("USD/CHF", "USD_CHF_H1_H1.bars");
    }

    static Path DATA_DIR = Path.of("/home/martinfou/projects/trading-bridge/data/historical/bars");

    public static void main(String[] args) throws Exception {
        if (!DATA_DIR.toFile().exists()) {
            DATA_DIR = Path.of(System.getProperty("user.dir"), "data/historical/bars");
        }

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  JPY SPEC MOMENTUM — Focused Backtest                          ║");
        System.out.println("║  Suivant COT bullish sur JPY pairs (USD/JPY 1.50:1 spec ratio)  ║");
        System.out.println("║  Capital: $50,000 | Comm: $0.07 | Pos: 1000                     ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        // Find data dir
        System.out.println("Data: " + DATA_DIR.toAbsolutePath());

        // Load bars for selected assets
        Map<String, List<Bar>> allBars = new LinkedHashMap<>();
        for (var entry : ASSETS.entrySet()) {
            String symbol = entry.getKey();
            Path file = DATA_DIR.resolve(entry.getValue());
            if (!file.toFile().exists()) { System.out.println("  ⚠ " + symbol + " not found"); continue; }
            List<Bar> bars = loadBars(file, symbol);
            allBars.put(symbol, bars);
            System.out.printf("  ✓ %-8s — %,d bars%n", symbol, bars.size());
        }

        // Strategy instance
        String stratClass = "com.martinfou.trading.strategies.creative.JPYSpecMomentumStrategy";
        Class<?> clazz = Class.forName(stratClass);

        List<Result> results = new ArrayList<>();

        for (var entry : allBars.entrySet()) {
            String asset = entry.getKey();
            List<Bar> bars = entry.getValue();

            try {
                Strategy fresh = (Strategy) clazz
                    .getDeclaredConstructor(String.class, String.class)
                    .newInstance("JPYSpecMomentum", asset);

                BacktestEngine engine = new BacktestEngine(fresh, bars, CAPITAL)
                    .withCommissionFixed(COMMISSION);

                long t0 = System.nanoTime();
                BacktestResult bt = engine.run();
                long ms = (System.nanoTime() - t0) / 1_000_000;

                results.add(new Result(asset, bt, ms));
                System.out.printf("  %-8s → Ret:%+10.2f%% SR:%6.2f PF:%5.2f WR:%5.1f%% DD:%6.2f%% Tr:%5d (%dms)%n",
                    asset, bt.totalReturnPct(), bt.sharpeRatio(), bt.profitFactor(),
                    bt.winRatePct(), bt.maxDrawdownPct(), bt.totalTrades(), ms);

            } catch (Exception e) {
                System.out.printf("  %-8s → FAILED: %s%n", asset, e.getMessage());
            }
        }

        // Rank by Sharpe (positive Sharpe preferred)
        results.sort((a, b) -> Double.compare(b.bt.sharpeRatio(), a.bt.sharpeRatio()));

        System.out.println("\n\n📊 RANKING:");
        System.out.printf("%-3s %-8s %8s %10s %8s %8s %8s%n", "#", "Asset", "Sharpe", "Return%", "PF", "WR%", "DD%");
        System.out.println("-".repeat(60));
        for (int i = 0; i < results.size(); i++) {
            Result r = results.get(i);
            System.out.printf("%-3d %-8s %8.2f %10.2f %8.2f %8.1f %8.2f%n",
                i + 1, r.asset, r.bt.sharpeRatio(), r.bt.totalReturnPct(),
                r.bt.profitFactor(), r.bt.winRatePct(), r.bt.maxDrawdownPct());
        }

        // Generate PDFs for top results (filter: PF > 1.0, DD < 50%)
        System.out.println("\n\n📄 Generating PDF reports...");
        Path creativeLab = Path.of(System.getProperty("user.dir"), "creative-lab");
        if (!creativeLab.toFile().exists()) {
            creativeLab = Path.of("/home/martinfou/projects/trading-bridge/creative-lab");
        }
        Files.createDirectories(creativeLab);

        List<Path> pdfs = new ArrayList<>();
        for (Result r : results) {
            if (r.bt.profitFactor() <= 1.0) continue; // skip unprofitable
            if (r.bt.maxDrawdownPct() > 50) continue; // skip catastrophic DD

            String label = "JPYSpecMomentum_" + r.asset.replace("/", "");
            try {
                // Run Monte Carlo for risk analysis
                MonteCarloSimulation mcSim = new MonteCarloSimulation(r.bt, 1000);
                var mcResult = mcSim.run();
                Path pdf = new BacktestReportGenerator(r.bt, r.asset, label, creativeLab)
                    .withMonteCarlo(mcResult)
                    .generate();
                pdfs.add(pdf);
                System.out.println("  ✓ " + pdf.getFileName());
            } catch (Exception e) {
                System.out.println("  ❌ " + label + ": " + e.getMessage());
            }
        }

        // Output upload commands
        System.out.println("\n\n📋 Upload commands:");
        for (Path p : pdfs) {
            String title = "JPY Spec Momentum — " + p.getFileName().toString()
                .replace("JPYSpecMomentum_", "").replace(".pdf", "").replace("_", "/");
            System.out.printf("bash scripts/upload-to-joplin.sh \"%s\" \"%s\" \"03 - Resources / 🔗 Reference\"%n",
                p.toAbsolutePath(), title);
        }

        System.out.println("\n✅ Complete! " + pdfs.size() + " PDFs generated.");
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
                Instant ts = raw > 100_000_000_000L ? Instant.ofEpochMilli(raw) : Instant.ofEpochSecond(raw);
                bars.add(new Bar(symbol, ts,
                    buf.getDouble(pos + 8), buf.getDouble(pos + 16),
                    buf.getDouble(pos + 24), buf.getDouble(pos + 32),
                    buf.getInt(pos + 40)));
            }
            return bars;
        }
    }

    record Result(String asset, BacktestResult bt, long elapsedMs) {}
}
