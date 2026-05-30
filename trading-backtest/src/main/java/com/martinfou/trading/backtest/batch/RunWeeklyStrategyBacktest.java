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
 * Weekly strategy backtest runner — tests 2 weekly strategies on 9 FX pairs.
 * Generates PDF reports for the best performing (strategy, asset) combos.
 *
 * Usage: mvn compile -q -pl trading-core,trading-data,trading-strategies,trading-backtest -am &&
 *   mvn exec:java -pl trading-backtest
 *   -Dexec.mainClass=com.martinfou.trading.backtest.batch.RunWeeklyStrategyBacktest
 *   -Dexec.classpathScope=compile
 */
public class RunWeeklyStrategyBacktest {

    private static final int BAR_SIZE = 44;
    private static final double CAPITAL = 50_000;
    private static final double COMMISSION = 0.07;
    private static final String DISPLAY_LABEL = "2026-05-29-weekly";

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

    static final Map<String, Double> QUOTE_RATES = new HashMap<>();
    static {
        QUOTE_RATES.put("GBP/JPY", 95.0);
        QUOTE_RATES.put("XAU/USD", 1.0);
        QUOTE_RATES.put("EUR/USD", 1.0);
        QUOTE_RATES.put("GBP/USD", 1.0);
        QUOTE_RATES.put("USD/CAD", null);
        QUOTE_RATES.put("USD/JPY", 95.0);
        QUOTE_RATES.put("AUD/USD", 1.0);
        QUOTE_RATES.put("NZD/USD", 1.0);
        QUOTE_RATES.put("USD/CHF", null);
    }

    static Path DATA_DIR = Path.of("/home/martinfou/projects/trading-bridge/data/historical/bars");

    public static void main(String[] args) throws Exception {
        if (!DATA_DIR.toFile().exists()) {
            DATA_DIR = Path.of(System.getProperty("user.dir"), "data/historical/bars");
        }

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  WEEKLY STRATEGY BACKTEST — Semaine du 01/06/2026               ║");
        System.out.println("║  2 nouvelles stratégies × 9 assets = 18 backtests               ║");
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

        // 2. Create strategies
        List<Strategy> strategies = new ArrayList<>();
        strategies.add(new com.martinfou.trading.strategies.creative.MayExhaustionFadeStrategy());
        strategies.add(new com.martinfou.trading.strategies.creative.JPYSpecMomentumStrategy());

        System.out.println("\n📋 2 weekly strategies loaded for backtest\n");

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

        // 4. Ranking and summary
        System.out.println("\n\n╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  WEEKLY RANKING — Best Strategy × Asset pairs                   ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        List<StratAssetResult> ranked = new ArrayList<>();

        for (var stratEntry : allResults.entrySet()) {
            String stratName = stratEntry.getKey();
            for (var resEntry : stratEntry.getValue().entrySet()) {
                String asset = resEntry.getKey();
                Result r = resEntry.getValue();
                if (r == null) continue;
                ranked.add(new StratAssetResult(stratName, asset, r.bt.sharpeRatio(),
                    r.bt.totalReturnPct(), r.bt.profitFactor(), r.bt.winRatePct(),
                    r.bt.maxDrawdownPct(), r.bt.totalTrades(), r.bt));
            }
        }

        ranked.sort((a, b) -> Double.compare(b.sharpe, a.sharpe));

        System.out.printf("%-3s %-25s %-8s %8s %8s %8s %8s %8s%n",
            "#", "Strategy", "Asset", "Sharpe", "Return%", "PF", "WR%", "DD%");
        System.out.println("-".repeat(80));

        int rank = 0;
        for (StratAssetResult r : ranked) {
            rank++;
            System.out.printf("%-3d %-25s %-8s %8.2f %8.2f %8.2f %8.1f %8.2f%n",
                rank, truncate(r.strategyName, 23), r.asset, r.sharpe,
                r.totalReturn, r.profitFactor, r.winRate, r.maxDrawdown);
        }

        // 5. Generate PDFs for top 4 best combinations
        System.out.println("\n\n📄 Generating PDF reports...");
        Path creativeLab = Path.of(System.getProperty("user.dir"), "creative-lab");
        if (!creativeLab.toFile().exists()) {
            creativeLab = Path.of("/home/martinfou/projects/trading-bridge/creative-lab");
        }
        Files.createDirectories(creativeLab);

        List<String> generatedPdfs = new ArrayList<>();
        int pdfCount = 0;
        for (StratAssetResult r : ranked) {
            if (pdfCount >= 4) break;
            if (r.sharpe <= 0.3 && r.totalReturn < 5) continue; // Skip weak results

            String label = r.strategyName + "_" + r.asset.replace("/", "");
            try {
                Path pdf = new BacktestReportGenerator(r.bt, r.asset, label, creativeLab).generate();
                generatedPdfs.add(pdf.toString());
                System.out.println("  ✓ " + pdf.getFileName());
                pdfCount++;
            } catch (Exception e) {
                System.out.println("  ❌ Failed to generate PDF for " + label + ": " + e.getMessage());
            }
        }

        // 6. Print upload commands
        System.out.println("\n\n📋 Upload to Joplin:");
        for (String pdfPath : generatedPdfs) {
            Path p = Path.of(pdfPath);
            String filename = p.getFileName().toString();
            String title = filename.replaceAll("_", " ").replace(".pdf", "");
            System.out.println("bash scripts/upload-to-joplin.sh \"" + pdfPath + "\" \"" + title + "\" \"03 - Resources / 🔗 Reference\"");
        }

        System.out.println("\n✅ Weekly strategy backtest complete!");
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

    // ══════════════════════════════════════════════════════════════════════
    //  Records
    // ══════════════════════════════════════════════════════════════════════

    record Result(BacktestResult bt, long elapsedMs) {}

    record StratAssetResult(String strategyName, String asset, double sharpe,
                            double totalReturn, double profitFactor, double winRate,
                            double maxDrawdown, int totalTrades, BacktestResult bt) {}
}
