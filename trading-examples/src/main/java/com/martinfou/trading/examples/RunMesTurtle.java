package com.martinfou.trading.examples;

import com.martinfou.trading.core.*;
import com.martinfou.trading.backtest.*;
import com.martinfou.trading.strategies.creative.MesTurtleStrategy;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/**
 * Runs the MesTurtleStrategy (Donchian breakout) on CME micro futures data.
 *
 * Usage: java ... RunMesTurtle <symbol> <tf> <entry> <exit> <contracts> <capital>
 *   e.g. RunMesTurtle MES D1 55 20 1 50000
 *
 * Data: data/historical/futures/<SYMBOL>_<TF>.csv  (D1 = yyyy-MM-dd, H1 = yyyy-MM-dd HH:mm:ss±HH:mm)
 * Costs: $0.62/contract commission + 1-tick slippage (TRADE_THROUGH fill), realistic MES/IBKR preset.
 */
public class RunMesTurtle {

    public static void main(String[] args) throws Exception {
        String symbol = args.length > 0 ? args[0] : "MES";
        String tf = args.length > 1 ? args[1].toUpperCase() : "D1";
        int entry = args.length > 2 ? Integer.parseInt(args[2]) : 55;
        int exit = args.length > 3 ? Integer.parseInt(args[3]) : 20;
        int contracts = args.length > 4 ? Integer.parseInt(args[4]) : 1;
        double capital = args.length > 5 ? Double.parseDouble(args[5]) : 50_000.0;
        boolean longOnly = args.length > 6 && args[6].equalsIgnoreCase("L");
        int startYear = args.length > 7 ? Integer.parseInt(args[7]) : 0;
        int endYear = args.length > 8 ? Integer.parseInt(args[8]) : 9999;
        int regimeMa = args.length > 9 ? Integer.parseInt(args[9]) : 0;

        Path csv = Path.of("data/historical/futures/" + symbol + "_" + tf + ".csv");
        if (!Files.exists(csv)) {
            System.err.println("Missing data file: " + csv);
            System.exit(1);
        }
        List<Bar> bars = loadBars(csv, symbol, tf);
        if (startYear > 0 || endYear < 9999) {
            final int sy = startYear, ey = endYear;
            bars = bars.stream()
                .filter(b -> b.timestamp().atZone(ZoneId.of("America/New_York")).getYear() >= sy
                          && b.timestamp().atZone(ZoneId.of("America/New_York")).getYear() <= ey)
                .toList();
        }

        String stratName = String.format("MesTurtle_%s_%s_%d_%d%s%s", symbol, tf, entry, exit,
            longOnly ? "_L" : "", regimeMa > 0 ? "_r" + regimeMa : "");
        MesTurtleStrategy strat = new MesTurtleStrategy(stratName, symbol, entry, exit, contracts, longOnly, regimeMa);

        MarginTracker margin = new MarginTracker();
        BacktestEngine engine = new BacktestEngine(strat, bars, capital)
            .withFillMode(FillMode.TRADE_THROUGH)
            .withCommissionFixed(0.62)         // $0.62 / contract (IBKR CME micro)
            .withSlippagePct(0.00005)          // ~0.25 pt = 1 tick on MES
            .withStopSlippagePct(0.0001)
            .withRollover(true)
            .withMarginTracker(margin);

        BacktestResult r = engine.run();
        print(r, symbol, tf, entry, exit, contracts, bars.size());
    }

    static List<Bar> loadBars(Path csv, String symbol, String tf) throws IOException {
        List<Bar> bars = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(csv)) {
            reader.readLine(); // header
            String line;
            int skipped = 0;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] t = line.split(",");
                if (t.length < 6) continue;
                try {
                    Instant ts = parseTime(t[0].trim(), tf);
                    double o = Double.parseDouble(t[1].trim());
                    double h = Double.parseDouble(t[2].trim());
                    double l = Double.parseDouble(t[3].trim());
                    double c = Double.parseDouble(t[4].trim());
                    long v = t.length >= 7 ? parseLong(t[6].trim()) : 0L;
                    bars.add(new Bar(symbol, ts, o, h, l, c, v));
                } catch (Exception e) {
                    skipped++;
                }
            }
            if (skipped > 0) System.err.println("Skipped " + skipped + " malformed rows");
        }
        return bars;
    }

    static Instant parseTime(String s, String tf) {
        if (tf.equals("D1")) {
            LocalDate d = LocalDate.parse(s); // yyyy-MM-dd
            return d.atTime(16, 0).atZone(ZoneId.of("America/New_York")).toInstant();
        }
        // H1: "yyyy-MM-dd HH:mm:ss-04:00" -> ISO with offset
        String iso = s.replace(' ', 'T');
        return Instant.parse(iso);
    }

    static long parseLong(String s) {
        try { return Long.parseLong(s); }
        catch (NumberFormatException e) { return 0L; }
    }

    static void print(BacktestResult r, String symbol, String tf, int entry, int exit, int contracts, int bars) {
        System.out.println("═══════════════════════════════════════════════════");
        System.out.printf("%s | %s %s | entry=%d exit=%d | contracts=%d | bars=%d%n",
            r.strategyName(), symbol, tf, entry, exit, contracts, bars);
        System.out.println("───────────────────────────────────────────────────");
        System.out.printf("Net PnL:        $%,.2f  (%+.2f%%)%n", r.totalPnl(), r.totalReturnPct());
        System.out.printf("Profit Factor:  %.3f%n", r.profitFactor());
        System.out.printf("Win Rate:       %.1f%%  (%dW / %dL)%n", r.winRatePct(), r.winningTrades(), r.losingTrades());
        System.out.printf("Trades:         %d%n", r.totalTrades());
        System.out.printf("Max Drawdown:   %.2f%%%n", r.maxDrawdownPct());
        System.out.printf("Avg Trade PnL:  $%,.2f%n", r.avgTradePnl());
        System.out.printf("Sharpe:         %.3f%n", r.sharpeRatio());
        System.out.println("─── Costs ──────────────────────────────────────────");
        System.out.printf("Commission:     $%,.2f%n", r.totalCommission());
        System.out.printf("Slippage:       $%,.2f%n", r.totalSlippage());
        System.out.printf("Swap:           $%,.2f%n", r.totalSwap());
        System.out.println("═══════════════════════════════════════════════════");
    }
}
