package com.martinfou.trading.examples;

import com.martinfou.trading.backtest.BacktestExecutionCost;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.strategies.creative.GoldTurtleTrendStrategy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RunGoldCrossQuote — Decomposition intermarket de l'edge or (jeudi cross-asset).
 *
 * Question : l'edge GoldTurtle (PF 1.17 sur XAU/USD) est-il un edge du MÉTAL
 * (l'or monte contre TOUTES les devises) ou un edge du DOLLAR (l'or ne monte
 * que contre l'USD) ?
 *
 * Méthode : synthétiser les crosses XAU/XXX = XAU_USD × (ou ÷) la paire FX
 * (OHLC alignés par timestamp), puis rejouer la MÊME mécanique Turtle
 * (GoldTurtleTrendStrategy) sur chaque cross, avec coûts.
 *
 *  - XAU/EUR = XAU_USD / EUR_USD
 *  - XAU/JPY = XAU_USD × USD_JPY
 *  - XAU/GBP = XAU_USD / GBP_USD
 *  - XAU/CHF = XAU_USD × USD_CHF
 *  - XAU/CAD = XAU_USD × USD_CAD
 *  - XAU/AUD = XAU_USD / AUD_USD
 *  - XAU/NZD = XAU_USD / NZD_USD
 *
 * Lecture : si PF reste > 1.1-1.2 sur la majorité des crosses → edge MÉTAL.
 * Si PF s'effondre (~0.9) hors XAU_USD → edge DOLLAR (le « bull or » serait
 * un « bear USD » déguisé).
 *
 * Usage:
 *   java -cp "$CP" com.martinfou.trading.examples.RunGoldCrossQuote
 */
public class RunGoldCrossQuote {

    static final String YEAR_SPEC = "2006-2025";
    static final double CAPITAL = 50_000;

    public static void main(String[] args) throws Exception {
        var cost = BacktestExecutionCost.ofCommissionAndSlippage(0.07, 0.0001);

        System.out.println("==================================================");
        System.out.println("GOLD CROSS-QUOTE DECOMPOSITION — Turtle 55/20 H1");
        System.out.println("Coûts: commission $0.07 + slippage 0.01%");
        System.out.println("Capital: $" + CAPITAL);
        System.out.println("==================================================");

        // 1. Charger XAU_USD et toutes les paires FX
        var xau = HistoricalDataLoader.loadFromArgs("XAU_USD", "XAU_USD", YEAR_SPEC).bars();
        System.out.printf("XAU_USD: %d bars%n", xau.size());

        Map<String, List<Bar>> fx = new HashMap<>();
        String[] pairs = {"EUR_USD", "USD_JPY", "GBP_USD", "USD_CHF", "USD_CAD", "AUD_USD", "NZD_USD"};
        for (String p : pairs) {
            fx.put(p, HistoricalDataLoader.loadFromArgs(p, p, YEAR_SPEC).bars());
        }

        // 2. Pour chaque cross : synthétiser + backtester
        String[] crosses = {"XAU_EUR", "XAU_JPY", "XAU_GBP", "XAU_CHF", "XAU_CAD", "XAU_AUD", "XAU_NZD"};
        System.out.printf("%n%-10s %-7s %-6s %-6s %-7s %-12s %-10s%n",
            "CROSS", "BARS", "PF", "WR%", "DD%", "NET$", "RET%");

        // Baseline XAU_USD
        var base = RunContext.forStrategy(null, "GoldTurtleTrend", new GoldTurtleTrendStrategy("GoldTurtleTrend", "XAU_USD"),
            "XAU_USD", RunMode.BACKTEST, xau, CAPITAL, null, cost).run();
        System.out.printf("%-10s %-7d %-6.2f %-6.1f %-6.2f %12.2f %-10.2f%n",
            "XAU_USD", xau.size(), base.profitFactor(), base.winRatePct(), base.maxDrawdownPct(),
            base.totalPnl(), base.totalReturnPct());

        for (String cross : crosses) {
            String[] legs = crossLeg(cross);
            List<Bar> syn = synthesize(xau, fx.get(legs[1]), cross, legs[0]);
            if (syn.size() < 1000) {
                System.out.printf("%-10s PAS ASSEZ DE BARS COMMUNS (%d)%n", cross, syn.size());
                continue;
            }
            var strategy = new GoldTurtleTrendStrategy("GoldTurtleTrend", cross);
            BacktestResult r = RunContext.forStrategy(null, "GoldTurtleTrend", strategy, cross,
                RunMode.BACKTEST, syn, CAPITAL, null, cost).run();
            System.out.printf("%-10s %-7d %-6.2f %-6.1f %-6.2f %12.2f %-10.2f%n",
                cross, syn.size(), r.profitFactor(), r.winRatePct(), r.maxDrawdownPct(),
                r.totalPnl(), r.totalReturnPct());
        }
        System.out.println("\nDONE");
    }

    /** Returns {operation, fxLeg}: "DIV" → XAU_USD / fx ; "MUL" → XAU_USD × fx. */
    private static String[] crossLeg(String cross) {
        return switch (cross) {
            case "XAU_EUR" -> new String[]{"DIV", "EUR_USD"};
            case "XAU_GBP" -> new String[]{"DIV", "GBP_USD"};
            case "XAU_AUD" -> new String[]{"DIV", "AUD_USD"};
            case "XAU_NZD" -> new String[]{"DIV", "NZD_USD"};
            case "XAU_JPY" -> new String[]{"MUL", "USD_JPY"};
            case "XAU_CHF" -> new String[]{"MUL", "USD_CHF"};
            case "XAU_CAD" -> new String[]{"MUL", "USD_CAD"};
            default -> throw new IllegalArgumentException(cross);
        };
    }

    /** Synthesize XAU/XXX bars = XAU_USD (÷ or ×) FX pair, aligned by timestamp. */
    private static List<Bar> synthesize(List<Bar> xau, List<Bar> fx, String crossSymbol, String op) {
        Map<Long, Bar> fxByTs = new HashMap<>();
        for (Bar b : fx) fxByTs.put(b.timestamp().toEpochMilli(), b);

        List<Bar> out = new ArrayList<>();
        for (Bar g : xau) {
            Bar f = fxByTs.get(g.timestamp().toEpochMilli());
            if (f == null) continue;
            double o, h, l, c;
            if (op.equals("DIV")) {
                o = g.open() / f.open();
                h = Math.max(g.high() / f.low(), Math.max(g.open() / f.open(), g.close() / f.close()));
                l = Math.min(g.low() / f.high(), Math.min(g.open() / f.open(), g.close() / f.close()));
                c = g.close() / f.close();
            } else { // MUL
                o = g.open() * f.open();
                h = Math.max(g.high() * f.high(), Math.max(g.open() * f.open(), g.close() * f.close()));
                l = Math.min(g.low() * f.low(), Math.min(g.open() * f.open(), g.close() * f.close()));
                c = g.close() * f.close();
            }
            if (o <= 0 || h <= 0 || l <= 0 || c <= 0 || !(h >= l)) continue;
            out.add(new Bar(crossSymbol, g.timestamp(), o, h, l, c, g.volume()));
        }
        return out;
    }
}
