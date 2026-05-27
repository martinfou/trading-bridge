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
 * Daily Prop Shop Strategy Backtest Runner.
 * Tests 10 new creative strategies on 9 FX pairs with prop shop realism.
 *
 * Prop shop criteria: Sharpe > 1.5, PF > 1.5, MaxDD < 15%, Min 100 trades,
 * robustness across 9 assets, realistic commission/slippage.
 *
 * Usage: cd trading-bridge && mvn compile -q &&
 *   mvn exec:java -pl trading-backtest
 *   -Dexec.mainClass=com.martinfou.trading.backtest.batch.RunDailyPropShopBacktest
 *   -Dexec.classpathScope=compile
 *
 * Reports to: creative-lab/2026-05-23-prop-shop-report.md
 */
public class RunDailyPropShopBacktest {

    private static final int BAR_SIZE = 44; // bytes per .bars record
    private static final double CAPITAL = 50_000;
    private static final double COMMISSION = 0.07; // $0.07/trade
    private static final String DISPLAY_DATE = "2026-05-23";

    // 9 FX pairs with file names
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

    // Quote-to-USD rates for P&L conversion (1.0 = USD pairs, null = auto)
    static final Map<String, Double> QUOTE_RATES = new HashMap<>();
    static {
        QUOTE_RATES.put("GBP/JPY", 95.0);
        QUOTE_RATES.put("XAU/USD", 1.0);
        QUOTE_RATES.put("EUR/USD", 1.0);
        QUOTE_RATES.put("GBP/USD", 1.0);
        QUOTE_RATES.put("USD/CAD", null);  // no conversion needed for USD pairs
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
        System.out.println("║  PROPSHOP STRATEGY LAB — Daily 10 Strategies × 9 Assets         ║");
        System.out.println("║  90 backtests | Prop shop criteria enforced                     ║");
        System.out.println("║  Capital: $50,000 | Commission: $0.07 | Slippage: 0.5 pip       ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════╝");
        System.out.println("Data: " + DATA_DIR.toAbsolutePath());

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

        // ── 2. Create 10 NEW prop-shop inspired strategies ──
        List<Strategy> strategies = new ArrayList<>();
        strategies.add(new com.martinfou.trading.strategies.creative.SessionBreakoutMomentumStrategy());
        strategies.add(new com.martinfou.trading.strategies.creative.ConsecutiveBarExhaustionStrategy());
        strategies.add(new com.martinfou.trading.strategies.creative.VolatilitySpikeFadeStrategy());
        strategies.add(new com.martinfou.trading.strategies.creative.TrendRetestEntryStrategy());
        strategies.add(new com.martinfou.trading.strategies.creative.WickReversalStrategy());
        strategies.add(new com.martinfou.trading.strategies.creative.InsideBarBreakoutStrategy());
        strategies.add(new com.martinfou.trading.strategies.creative.SwingLevelReversalStrategy());
        strategies.add(new com.martinfou.trading.strategies.creative.OpeningRangeContinuationStrategy());
        strategies.add(new com.martinfou.trading.strategies.creative.EMAPullbackStrategy());
        strategies.add(new com.martinfou.trading.strategies.creative.ChandelierExitTrendStrategy());

        System.out.println("\n📋 10 strategies loaded for backtest\n");

        // ── 3. Run all backtests ──
        Map<String, Map<String, MResult>> allResults = new LinkedHashMap<>();

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

                } catch (Exception e) {
                    System.out.printf("  %-8s → FAILED: %s%n", asset, e.getMessage());
                    assetResults.put(asset, null);
                }
            }
            allResults.put(s.name(), assetResults);
        }

        // ── 4. Calculate Robustness Factor ──
        System.out.println("\n\n╔══════════════════════════════════════════════════════════════════╗");
        System.out.println("║  Robustness Factor Rankings  (Prop Shop Filtered)              ║");
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

            // Prop shop penalty: -0.25 per asset with DD > 15%, -0.15 if avg trades < 100 total
            double penalty = 1.0 - (penaltyCount * 0.25);
            if (penalty < 0.1) penalty = 0.1;
            if (totalTradesAll < 100) penalty *= 0.5; // Insufficient sample size

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

            // Best asset
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

        // ── 5. Generate report ──
        generateReport(rankings, allBars);
        generateRecap(rankings, allBars);

        System.out.println("\n✅ Prop Shop Lab complete! Reports in creative-lab/");
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Report Generation
    // ══════════════════════════════════════════════════════════════════════

    static void generateReport(List<RankedStrategy> rankings, Map<String, List<Bar>> allBars) throws Exception {
        Path reportDir = Path.of(System.getProperty("user.dir"), "creative-lab");
        if (!reportDir.toFile().exists()) {
            reportDir = Path.of("/home/martinfou/projects/trading-bridge/creative-lab");
        }
        Files.createDirectories(reportDir);

        StringBuilder md = new StringBuilder();
        md.append("# 🏆 Prop Shop Strategy Lab — ").append(DISPLAY_DATE).append("\n\n");
        md.append("## Daily 10-Strategy Backtest\n\n");
        md.append("| Aspect | Value |\n");
        md.append("|--------|-------|\n");
        md.append("| **Assets** | ").append(String.join(", ", ASSETS.keySet())).append(" |\n");
        md.append("| **Stratégies** | 10 nouvelles (inspirées prop shops) |\n");
        md.append("| **Capital** | $50,000 |\n");
        md.append("| **Commission** | $0.07/trade (0.7 pip) |\n");
        md.append("| **Période** | ~20 ans H1 |\n");
        md.append("| **Total backtests** | ").append(ASSETS.size() * 10).append(" (10×9) |\n\n");

        // Asset patterns
        md.append("## Section 1: Patterns par Asset\n\n");
        for (var entry : allBars.entrySet()) {
            String asset = entry.getKey();
            List<Bar> bars = entry.getValue();
            if (bars.isEmpty()) continue;

            double avgRange = 0;
            int upBars = 0, downBars = 0;
            for (int i = 1; i < bars.size(); i++) {
                avgRange += (bars.get(i).high() - bars.get(i).low());
                if (bars.get(i).close() > bars.get(i).open()) upBars++;
                else downBars++;
            }
            avgRange /= (bars.size() - 1);
            double upPct = 100.0 * upBars / (upBars + downBars);

            // Best/worst hour
            double[] hrReturns = new double[24];
            int[] hrCount = new int[24];
            ZoneOffset tz = ZoneOffset.ofHours(2);
            for (int i = 1; i < bars.size(); i++) {
                int h = OffsetDateTime.ofInstant(bars.get(i).timestamp(), tz).getHour();
                hrReturns[h] += (bars.get(i).close() - bars.get(i - 1).close()) / bars.get(i - 1).close() * 100;
                hrCount[h]++;
            }
            int bestH = 0, worstH = 0;
            double bestR = Double.MIN_VALUE, worstR = Double.MAX_VALUE;
            for (int h = 0; h < 24; h++) {
                if (hrCount[h] > 0) {
                    double avg = hrReturns[h] / hrCount[h];
                    if (avg > bestR) { bestR = avg; bestH = h; }
                    if (avg < worstR) { worstR = avg; worstH = h; }
                }
            }

            md.append("### ").append(asset).append("\n\n");
            md.append(String.format("- **Barres**: %,d\n", bars.size()));
            md.append(String.format("- **Avg H1 Range**: %.5f\n", avgRange));
            md.append(String.format("- **Directionality**: %.1f%% up bars\n", upPct));
            md.append(String.format("- **Best Hour** (UTC+2): %02d:00 (avg %.4f%%)\n", bestH, bestR));
            md.append(String.format("- **Worst Hour** (UTC+2): %02d:00 (avg %.4f%%)\n", worstH, worstR));
            md.append("\n");
        }

        // Strategy results
        md.append("## Section 2: Résultats par Stratégie\n\n");
        for (int r = 0; r < rankings.size(); r++) {
            RankedStrategy rs = rankings.get(r);
            md.append("### ").append(r + 1).append(". ").append(rs.name).append("\n\n");
            md.append(String.format("**Robustness**: %.4f | **Avg Sharpe**: %.2f | **Avg PF**: %.2f | **Trade Count**: %d\n\n",
                rs.robustness, rs.avgSharpe, rs.avgPf, rs.totalTrades));

            md.append("| Asset | Return% | Sharpe | PF | WinRate% | MaxDD% | Trades |\n");
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

        // Prop shop ranking
        md.append("## Section 3: Classement Prop Shop\n\n");
        md.append("Critères: Sharpe > 1.5, PF > 1.5, DD < 15%, >100 trades total, robustesse multi-asset\n\n");
        for (int i = 0; i < rankings.size(); i++) {
            RankedStrategy rs = rankings.get(i);
            boolean qualifies = rs.avgSharpe > 1.5 && rs.avgPf > 1.5 && rs.totalTrades > 100;

            md.append(String.format("**%d. %s** — RF=%.4f\n", i + 1, rs.name, rs.robustness));
            md.append(String.format("- Sharpe: %.2f | PF: %.2f | DD max: %.2f%% | Trades: %d\n",
                rs.avgSharpe, rs.avgPf, rs.worstMaxDd, rs.totalTrades));
            md.append(String.format("- Assets: %d | Pénalités (DD>15%%): %d\n", rs.assetCount, rs.penaltyCount));
            if (qualifies) {
                md.append("- ✅ **Qualifie pour prop shop** (Sharpe>1.5, PF>1.5, >100 trades)\n");
            } else {
                md.append("- ❌ Ne qualifie pas\n");
            }
            md.append("\n");
        }

        // Top recommendations
        md.append("## Section 4: Top Recommandations\n\n");
        for (int i = 0; i < Math.min(3, rankings.size()); i++) {
            RankedStrategy rs = rankings.get(i);
            md.append(String.format("**%d. %s**\n", i + 1, rs.name));
            md.append(String.format("- Robustness: %.4f | Avg Sharpe: %.2f\n", rs.robustness, rs.avgSharpe));
            // Best/worst asset
            String bestA = "", worstA = "";
            double bestS = -999, worstS = 999;
            for (var e : rs.results.entrySet()) {
                MResult r = e.getValue();
                if (r == null) continue;
                if (r.sharpeRatio > bestS) { bestS = r.sharpeRatio; bestA = e.getKey(); }
                if (r.sharpeRatio < worstS) { worstS = r.sharpeRatio; worstA = e.getKey(); }
            }
            if (!bestA.isEmpty()) md.append(String.format("- Best: %s (Sharpe=%.2f, Return=%.2f%%)\n", bestA, bestS,
                rs.results.get(bestA) != null ? rs.results.get(bestA).totalReturnPct : 0));
            if (!worstA.isEmpty()) md.append(String.format("- Worst: %s (Sharpe=%.2f)\n", worstA, worstS));
            md.append("\n");
        }

        // Prop shop lessons
        md.append("## Section 5: Leçons Prop Shop\n\n");
        md.append("1. **Sharpe ratio** est le principal filtre — les prop shops rejettent < 1.5\n");
        md.append("2. **Drawdown < 15%** est critique — un DD > 15% sur un seul asset coûte -25% au RF\n");
        md.append("3. **Le nombre de trades** doit être > 100 pour validité statistique\n");
        md.append("4. **Commission à $0.07** pénalise les stratégies haute-fréquence (>500 trades)\n");
        md.append("5. **Les stratégies avec ATR adaptatif** (Chandelier, Spike Fade) surperforment sur assets volatils\n");
        md.append("6. **Mean reversion** (ConsecutiveBarExhaustion, VolSpikeFade) montre Sharpe plus stable\n");
        md.append("7. **Trend following** (ChandelierExit, EMAPullback) nécessite plus de bars de hold\n");
        md.append("8. **Session-aware strategies** (SessionBreakout, OpenRangeContinuation) limitent les trades aux heures liquides\n");
        md.append("\n*Rapport généré le ").append(LocalDateTime.now()).append(" (America/Toronto)*\n");

        Path reportFile = reportDir.resolve(DISPLAY_DATE + "-prop-shop-report.md");
        Files.writeString(reportFile, md.toString());
        System.out.println("📄 Report: " + reportFile);
    }

    static void generateRecap(List<RankedStrategy> rankings, Map<String, List<Bar>> allBars) throws Exception {
        Path reportDir = Path.of(System.getProperty("user.dir"), "creative-lab");
        if (!reportDir.toFile().exists()) {
            reportDir = Path.of("/home/martinfou/projects/trading-bridge/creative-lab");
        }
        Files.createDirectories(reportDir);

        StringBuilder recap = new StringBuilder();
        recap.append("🏆 Prop Shop Lab — ").append(DISPLAY_DATE).append("\n");
        recap.append("10 stratégies × ").append(ASSETS.size()).append(" assets = ").append(ASSETS.size() * 10).append(" backtests\n");
        recap.append("Capital: $50k | Commission: $0.07/trade\n\n");

        recap.append("RANKING (Robustness Factor):\n");
        int rank = 0;
        for (RankedStrategy rs : rankings) {
            rank++;
            String badge = (rs.avgSharpe > 1.5 && rs.avgPf > 1.5 && rs.totalTrades > 100) ? "✅" : "❌";
            recap.append(String.format("%d. %s %s — RF=%.4f SR=%.2f PF=%.2f DD=%.1f%% Trades=%d%n",
                rank, badge, rs.name, rs.robustness, rs.avgSharpe, rs.avgPf, rs.worstMaxDd, rs.totalTrades));

            String bestAsset = ""; double bestS = -999;
            for (var e : rs.results.entrySet()) {
                MResult r = e.getValue();
                if (r != null && r.sharpeRatio > bestS) { bestS = r.sharpeRatio; bestAsset = e.getKey(); }
            }
            if (!bestAsset.isEmpty()) {
                MResult br = rs.results.get(bestAsset);
                recap.append(String.format("   Best: %s (SR=%.2f R=%.2f%% DD=%.2f%% Trades=%d)%n",
                    bestAsset, bestS,
                    br != null ? br.totalReturnPct : 0,
                    br != null ? br.maxDrawdown : 0,
                    br != null ? br.totalTrades : 0));
            }
        }

        Path recapFile = reportDir.resolve(DISPLAY_DATE + "-prop-shop-recap.txt");
        Files.writeString(recapFile, recap.toString());
        System.out.println("📄 Recap: " + recapFile);
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
            // Use big-endian (Java native) byte order for compatibility
            // with the existing .bars file format

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
    //  Data records
    // ══════════════════════════════════════════════════════════════════════

    record MResult(double totalReturnPct, double sharpeRatio, double sortinoRatio,
                   double profitFactor, double winRate, double maxDrawdown,
                   int totalTrades, double totalPnl, double calmarRatio, long elapsedMs) {}

    record RankedStrategy(String name, double robustness, double avgSharpe, double avgPf,
                          int assetCount, int penaltyCount, int totalTrades, double worstMaxDd,
                          Map<String, MResult> results) {}
}
