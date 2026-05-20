package com.martinfou.trading.genetics;

import com.martinfou.trading.backtest.BacktestEngine;
import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.core.Bar;
import java.util.ArrayList;
import java.util.List;

/**
 * Strategies forex eprouvees, pre-parametrees.
 * SMA Golden Cross, EMA Momentum, RSI Extreme, Bollinger+RSI, ADX+EMA.
 */
public class ProvenStrategies {

    // === 1. SMA GOLDEN CROSS ===
    public static Chromosome smaGoldenCross() {
        var entry = new ArrayList<Gene>();
        entry.add(new Gene(Gene.IndicatorType.SMA, 50, Gene.Field.CLOSE));
        entry.add(new Gene(Gene.IndicatorType.SMA, 200, Gene.Field.CLOSE));
        var exit = new ArrayList<Gene>();
        exit.add(new Gene(Gene.IndicatorType.SMA, 50, Gene.Field.CLOSE));
        exit.add(new Gene(Gene.IndicatorType.SMA, 200, Gene.Field.CLOSE));
        return new Chromosome(entry, exit, 150, 300);
    }

    // === 2. EMA 9/21 MOMENTUM ===
    public static Chromosome emaMomentum() {
        var entry = new ArrayList<Gene>();
        entry.add(new Gene(Gene.IndicatorType.EMA, 9, Gene.Field.CLOSE));
        entry.add(new Gene(Gene.IndicatorType.EMA, 21, Gene.Field.CLOSE));
        var exit = new ArrayList<Gene>();
        exit.add(new Gene(Gene.IndicatorType.EMA, 9, Gene.Field.CLOSE));
        exit.add(new Gene(Gene.IndicatorType.EMA, 21, Gene.Field.CLOSE));
        return new Chromosome(entry, exit, 80, 160);
    }

    // === 3. RSI EXTREME ===
    public static Chromosome rsiExtreme() {
        var entry = new ArrayList<Gene>();
        entry.add(new Gene(Gene.IndicatorType.RSI, 14, Gene.Field.CLOSE));
        var exit = new ArrayList<Gene>();
        exit.add(new Gene(Gene.IndicatorType.RSI, 14, Gene.Field.CLOSE));
        return new Chromosome(entry, exit, 60, 180);
    }

    // === 4. BOLLINGER + RSI ===
    public static Chromosome bollingerRSI() {
        var entry = new ArrayList<Gene>();
        entry.add(new Gene(Gene.IndicatorType.RSI, 14, Gene.Field.CLOSE));
        entry.add(new Gene(Gene.IndicatorType.ATR, 14, Gene.Field.CLOSE));
        var exit = new ArrayList<Gene>();
        exit.add(new Gene(Gene.IndicatorType.ATR, 14, Gene.Field.CLOSE));
        return new Chromosome(entry, exit, 40, 120);
    }

    // === 5. RSI + SMA200 TREND FILTER ===
    public static Chromosome rsiWithTrendFilter() {
        var entry = new ArrayList<Gene>();
        entry.add(new Gene(Gene.IndicatorType.RSI, 14, Gene.Field.CLOSE));
        entry.add(new Gene(Gene.IndicatorType.SMA, 200, Gene.Field.CLOSE));
        var exit = new ArrayList<Gene>();
        exit.add(new Gene(Gene.IndicatorType.RSI, 14, Gene.Field.CLOSE));
        exit.add(new Gene(Gene.IndicatorType.SMA, 50, Gene.Field.CLOSE));
        return new Chromosome(entry, exit, 100, 250);
    }

    // === 6. ADX + EMA ===
    public static Chromosome adxTrendStrength() {
        var entry = new ArrayList<Gene>();
        entry.add(new Gene(Gene.IndicatorType.ADX, 14, Gene.Field.CLOSE));
        entry.add(new Gene(Gene.IndicatorType.EMA, 20, Gene.Field.CLOSE));
        var exit = new ArrayList<Gene>();
        exit.add(new Gene(Gene.IndicatorType.ADX, 14, Gene.Field.CLOSE));
        return new Chromosome(entry, exit, 120, 240);
    }

    // === 7. MACD MOMENTUM ===
    public static Chromosome macdMomentum() {
        var entry = new ArrayList<Gene>();
        entry.add(new Gene(Gene.IndicatorType.EMA, 12, Gene.Field.CLOSE));
        entry.add(new Gene(Gene.IndicatorType.EMA, 26, Gene.Field.CLOSE));
        var exit = new ArrayList<Gene>();
        exit.add(new Gene(Gene.IndicatorType.EMA, 12, Gene.Field.CLOSE));
        exit.add(new Gene(Gene.IndicatorType.EMA, 26, Gene.Field.CLOSE));
        return new Chromosome(entry, exit, 100, 200);
    }

    // === ALL ===
    public static List<Chromosome> all() {
        return List.of(
            smaGoldenCross(), emaMomentum(), rsiExtreme(),
            bollingerRSI(), rsiWithTrendFilter(), adxTrendStrength(),
            macdMomentum()
        );
    }

    public record Bundle(String name, String family, Chromosome chromosome) {}

    public static List<Bundle> allBundles() {
        return List.of(
            new Bundle("SMA Golden Cross", "TR", smaGoldenCross()),
            new Bundle("EMA 9/21 Momentum", "TR", emaMomentum()),
            new Bundle("RSI Extreme", "MR", rsiExtreme()),
            new Bundle("Bollinger + RSI", "MR", bollingerRSI()),
            new Bundle("RSI + SMA200", "MR", rsiWithTrendFilter()),
            new Bundle("ADX > 25 + EMA", "TR", adxTrendStrength()),
            new Bundle("MACD Momentum", "MM", macdMomentum())
        );
    }

    // === MAIN: backtester toutes les strategies ===
    public static void main(String[] args) throws Exception {
        System.out.println("\n=== PROVEN STRATEGIES - Backtest ===");
        var bars = generateBars(500, 1.0, 0.005);
        double capital = 100000;

        for (var b : allBundles()) {
            try {
                var strat = new StrategyTemplate(b.chromosome());
                var engine = new BacktestEngine(strat, bars, capital);
                var result = engine.run();
                System.out.printf("%-22s %-4s Sharpe: %7.2f  PF: %6.2f  Win: %5.1f%%  DD: %5.1f%%  Trades: %3d\n",
                    b.name(), b.family(),
                    result.sharpeRatio(), result.profitFactor(), result.winRatePct(),
                    result.maxDrawdownPct(), result.totalTrades());
            } catch (Exception e) {
                System.out.printf("%-22s ERROR: %s\n", b.name(), e.getMessage());
            }
        }
    }

    private static List<Bar> generateBars(int count, double basePrice, double volatility) {
        var bars = new ArrayList<Bar>(count);
        var now = java.time.Instant.now();
        double price = basePrice;
        for (int i = count; i > 0; i--) {
            double open = price;
            double close = open + (Math.random() - 0.48) * volatility * open;
            double high = Math.max(open, close) + Math.random() * volatility * open * 0.5;
            double low = Math.min(open, close) - Math.random() * volatility * open * 0.5;
            price = close;
            bars.add(new Bar("EUR_USD", now.minusSeconds(i * 3600L), open, high, low, close, 1000 + (int)(Math.random() * 500)));
        }
        return bars;
    }
}
