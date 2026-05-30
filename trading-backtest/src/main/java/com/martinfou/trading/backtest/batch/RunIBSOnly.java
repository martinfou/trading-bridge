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
 * Quick runner — just IBS Mean Rev on remaining 5 assets.
 */
public class RunIBSOnly {

    private static final int BAR_SIZE = 44;
    private static final double CAPITAL = 50_000;
    private static final double COMMISSION = 0.07;

    static final LinkedHashMap<String, String> ASSETS = new LinkedHashMap<>();
    static {
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

        Map<String, List<Bar>> allBars = new LinkedHashMap<>();
        for (var entry : ASSETS.entrySet()) {
            String symbol = entry.getKey();
            Path file = DATA_DIR.resolve(entry.getValue());
            if (!file.toFile().exists()) { System.out.println("  ⚠ " + symbol + " — not found"); continue; }
            List<Bar> bars = loadBars(file, symbol);
            allBars.put(symbol, bars);
            System.out.printf("  ✓ %-8s — %,d bars%n", symbol, bars.size());
        }

        Path reportDir = Path.of(System.getProperty("user.dir"), "creative-lab");
        if (!reportDir.toFile().exists()) reportDir = Path.of("/home/martinfou/projects/trading-bridge/creative-lab");

        Strategy s = new com.martinfou.trading.strategies.creative.IBSMeanReversionStrategy();

        for (var entry : allBars.entrySet()) {
            String asset = entry.getKey();
            List<Bar> bars = entry.getValue();
            try {
                Strategy fresh = s.getClass().getDeclaredConstructor(String.class, String.class).newInstance(s.name(), asset);
                BacktestEngine engine = new BacktestEngine(fresh, bars, CAPITAL).withCommissionFixed(COMMISSION);
                long t0 = System.nanoTime();
                BacktestResult bt = engine.run();
                long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
                System.out.printf("  %-8s → Ret:%+7.2f%% SR:%5.2f PF:%4.2f WR:%5.1f%% DD:%5.2f%% Tr:%4d (%dms)%n",
                    asset, bt.totalReturnPct(), bt.sharpeRatio(), bt.profitFactor(),
                    bt.winRatePct(), bt.maxDrawdownPct(), bt.totalTrades(), elapsedMs);
                if (bt.totalTrades() >= 10) {
                    Path pdfPath = new BacktestReportGenerator(bt, asset, s.name(), reportDir).generate();
                    System.out.println("    📄 PDF: " + pdfPath.getFileName());
                }
            } catch (Exception e) {
                System.out.printf("  %-8s → FAILED: %s%n", asset, e.getMessage());
            }
        }
        System.out.println("✅ IBS Mean Rev complete!");
    }

    static List<Bar> loadBars(Path path, String symbol) throws Exception {
        try (var file = new RandomAccessFile(path.toFile(), "r"); var channel = file.getChannel()) {
            long size = channel.size();
            int count = (int)(size / BAR_SIZE);
            var buf = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
            List<Bar> bars = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                int pos = i * BAR_SIZE;
                long raw = buf.getLong(pos);
                Instant ts = raw > 100_000_000_000L ? Instant.ofEpochMilli(raw) : Instant.ofEpochSecond(raw);
                bars.add(new Bar(symbol, ts, buf.getDouble(pos+8), buf.getDouble(pos+16),
                    buf.getDouble(pos+24), buf.getDouble(pos+32), buf.getInt(pos+40)));
            }
            return bars;
        }
    }
}
