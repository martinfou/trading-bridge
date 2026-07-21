package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.data.HistoricalDataLoader;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

/**
 * Batch screen all creative/ strategies on EUR_USD H1 (all available data).
 * Keeps only strategies with PF >= 1.2 OR Sharpe >= 0.5, and >= 30 trades.
 *
 * Usage:
 *   mvn exec:java -pl trading-examples \
 *     -Dexec.mainClass="com.martinfou.trading.examples.CreativeStrategyScreen"
 */
public class CreativeStrategyScreen {

    static final double CAPITAL = 50_000;
    static final double COMMISSION = 0.07;
    static final double PF_MIN = 1.2;
    static final double SHARPE_MIN = 0.5;
    static final int MIN_TRADES = 30;

    record ScreenResult(String id, int trades, double pf, double sharpe,
                        double winRate, double maxDD, double totalReturn,
                        long elapsedMs, boolean pass, String error) {}

    public static void main(String[] args) throws Exception {
        String symbol = args.length > 0 ? args[0] : "EUR_USD";

        System.out.println("╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  Creative Strategy Screen — Batch Backtest                      ║");
        System.out.printf ("║  Asset: %-10s | PF≥%.1f | Sharpe≥%.1f | Trades≥%d        ║%n",
            symbol, PF_MIN, SHARPE_MIN, MIN_TRADES);
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");

        // Load bars
        System.out.println("\n📊 Loading bars for " + symbol + "...");
        var loadResult = HistoricalDataLoader.loadAllAvailable(symbol);
        List<Bar> bars = loadResult.bars();
        System.out.println("   ✓ " + bars.size() + " bars loaded (" + bars.get(0).timestamp() + " to " + bars.get(bars.size()-1).timestamp() + ")");

        // Discover creative strategy classes via filesystem
        String pkg = "com.martinfou.trading.strategies.creative";
        String pkgPath = pkg.replace('.', '/');
        Path srcDir = Path.of(System.getProperty("user.dir"),
            "trading-strategies/src/main/java", pkgPath);

        if (!srcDir.toFile().exists()) {
            System.err.println("❌ Source dir not found: " + srcDir);
            System.exit(1);
        }

        List<String> classNames;
        try (var stream = Files.list(srcDir)) {
            classNames = stream
                .filter(f -> f.toString().endsWith(".java"))
                .map(f -> f.getFileName().toString().replace(".java", ""))
                .filter(n -> !n.startsWith("_")) // skip _rejected, _disabled
                .sorted()
                .collect(Collectors.toList());
        }

        System.out.println("\n🔍 Found " + classNames.size() + " strategies in creative/");

        // Backtest each strategy
        List<ScreenResult> results = new ArrayList<>();
        int loaded = 0;

        for (String cn : classNames) {
            String fqcn = pkg + "." + cn;
            long t0 = System.nanoTime();

            try {
                Class<?> clazz = Class.forName(fqcn);
                if (!Strategy.class.isAssignableFrom(clazz)) {
                    results.add(new ScreenResult(cn, 0, 0, 0, 0, 0, 0, 0, false, "not a Strategy"));
                    continue;
                }

                Strategy strategy = (Strategy) clazz
                    .getDeclaredConstructor(String.class, String.class)
                    .newInstance(cn, symbol);

                BacktestEngine engine = new BacktestEngine(strategy, bars, CAPITAL)
                    .withCommissionFixed(COMMISSION);

                BacktestResult bt = engine.run();
                long ms = (System.nanoTime() - t0) / 1_000_000;

                int trades = bt.totalTrades();
                double pf = bt.profitFactor();
                double sharpe = bt.sharpeRatio();
                double wr = bt.winRatePct();
                double dd = bt.maxDrawdownPct();
                double ret = bt.totalReturnPct();

                boolean invalid = Double.isNaN(pf) || Double.isInfinite(pf)
                    || Double.isNaN(sharpe) || Double.isInfinite(sharpe);

                boolean pass = !invalid && trades >= MIN_TRADES
                    && (pf >= PF_MIN || sharpe >= SHARPE_MIN);

                results.add(new ScreenResult(cn, trades, pf, sharpe, wr, dd, ret, ms, pass, null));
                loaded++;

                String icon = pass ? "✅" : "❌";
                System.out.printf("%s %-35s Tr:%4d PF:%7.2f SR:%7.2f WR:%5.1f%% DD:%5.2f%% Ret:%+7.2f%% (%dms)%n",
                    icon, cn, trades, pf, sharpe, wr, dd, ret, ms);

            } catch (Exception e) {
                long ms = (System.nanoTime() - t0) / 1_000_000;
                System.out.printf("❌ %-35s FAILED: %s (%dms)%n", cn, e.getMessage(), ms);
                results.add(new ScreenResult(cn, 0, 0, 0, 0, 0, 0, ms, false, e.getMessage()));
            }
        }

        // === REPORT ===
        System.out.println("\n══════════════════════════════════════════════════════════════════");
        System.out.println("📊 SCREENING RESULTS — " + symbol);
        System.out.printf("   Tested: %d | Loaded: %d | Failed: %d%n",
            classNames.size(), loaded, results.size() - loaded);

        long passCount = results.stream().filter(ScreenResult::pass).count();
        long failCount = results.size() - passCount;
        System.out.printf("   ✅ PASS: %d | ❌ FAIL: %d%n", passCount, failCount);

        // PASSED — sorted by PF desc
        var passed = results.stream().filter(ScreenResult::pass)
            .sorted((a, b) -> Double.compare(b.pf, a.pf)).toList();

        System.out.println("\n✅ STRATEGIES TO KEEP (" + passCount + "):");
        System.out.printf("%-35s %6s %8s %8s %8s %8s %8s%n",
            "Name", "Trades", "PF", "Sharpe", "WR%", "DD%", "Ret%");
        System.out.println("-".repeat(90));
        for (var r : passed) {
            System.out.printf("%-35s %6d %8.2f %8.2f %8.1f %8.2f %+8.2f%n",
                r.id, r.trades, r.pf, r.sharpe, r.winRate, r.maxDD, r.totalReturn);
        }

        // FAILED (that at least ran) — sorted by trades desc
        var failed = results.stream().filter(r -> !r.pass && r.trades > 0)
            .sorted((a, b) -> Double.compare(b.trades, a.trades)).toList();

        System.out.println("\n❌ REJECTED — ran but failed gate (" + failed.size() + "):");
        System.out.printf("%-35s %6s %8s %8s %8s %8s%n",
            "Name", "Trades", "PF", "Sharpe", "WR%", "DD%");
        System.out.println("-".repeat(75));
        for (var r : failed) {
            System.out.printf("%-35s %6d %8.2f %8.2f %8.1f %8.2f%n",
                r.id, r.trades, r.pf, r.sharpe, r.winRate, r.maxDD);
        }

        // FAILED (didn't load) — sorted by name
        var crashed = results.stream().filter(r -> !r.pass && r.trades == 0 && r.error != null)
            .sorted((a, b) -> a.id.compareTo(b.id)).toList();

        if (!crashed.isEmpty()) {
            System.out.println("\n💥 FAILED TO LOAD (" + crashed.size() + "):");
            for (var r : crashed) {
                System.out.printf("   %-35s %s%n", r.id, r.error);
            }
        }

        // === GIT MV SCRIPT ===
        System.out.println("\n══════════════════════════════════════════════════════════════════");
        System.out.println("📋 REJECTION SCRIPT (run from trading-bridge root):");
        System.out.println();
        System.out.println("mkdir -p trading-strategies/src/main/java/com/martinfou/trading/strategies/creative/_rejected");

        var toReject = results.stream().filter(r -> !r.pass && r.trades > 0).toList();
        if (toReject.isEmpty()) {
            System.out.println("# No strategies to reject — all passed!");
        } else {
            for (var r : toReject) {
                System.out.printf("git mv trading-strategies/src/main/java/com/martinfou/trading/strategies/creative/%s.java \\%n", r.id);
                System.out.printf("      trading-strategies/src/main/java/com/martinfou/trading/strategies/creative/_rejected/%n");
            }
        }

        // Also move crashed ones
        var crashedMove = results.stream().filter(r -> !r.pass && r.trades == 0 && r.error != null).toList();
        if (!crashedMove.isEmpty()) {
            System.out.println("# --- Also crashed (move to _rejected too) ---");
            for (var r : crashedMove) {
                System.out.printf("git mv trading-strategies/src/main/java/com/martinfou/trading/strategies/creative/%s.java \\%n", r.id);
                System.out.printf("      trading-strategies/src/main/java/com/martinfou/trading/strategies/creative/_rejected/%n");
            }
        }

        System.out.println("\n✅ Screening complete. " + passCount + " keep, " + failCount + " rejected.");
    }
}
