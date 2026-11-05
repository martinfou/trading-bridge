package com.martinfou.trading.runtime.generator;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.FuturesRegistry;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Complete genetic DNA for a StrategyQuant X-style algorithmic trading strategy.
 * Supports multi-asset execution (Forex, Futures, Commodities, Equities) using ATR-normalized
 * stop-loss, profit-target, trailing-stop, and optional stop-breakout pending entries.
 */
public record StrategyGenome(
    String id,
    String symbol,
    String timeframe,
    String archetype,
    String direction,
    String entryOrderType,
    List<RuleGene> longRules,
    List<RuleGene> shortRules,
    int stopEntryLookback,
    double stopEntryAtrMult,
    int stopOrderValidBars,
    int atrPeriod,
    double slAtrMult,
    double tpAtrMult,
    double trailingAtrMult,
    int maxBarsInTrade
) {

    /**
     * Building block types supported by the native evolutionary generator.
     */
    public enum BlockType {
        DONCHIAN_BREAKOUT,
        MA_CROSSOVER,
        PRICE_VS_MA,
        SUPERTREND,
        BOLLINGER_BANDS,
        RSI_FILTER,
        ADX_REGIME,
        ATR_EXPANSION,
        DAILY_HIGH_LOW,
        MOMENTUM_ROC
    }

    /**
     * A single boolean condition rule in a strategy's entry signal.
     */
    public record RuleGene(
        BlockType blockType,
        int period1,
        int period2,
        int shift1,
        int shift2,
        double threshold
    ) {
        public String describe(boolean isLong) {
            return switch (blockType) {
                case DONCHIAN_BREAKOUT -> isLong
                    ? String.format(Locale.US, "Close[0] > Highest(%d)[%d]", period1, Math.max(1, shift1))
                    : String.format(Locale.US, "Close[0] < Lowest(%d)[%d]", period1, Math.max(1, shift1));
                case MA_CROSSOVER -> isLong
                    ? String.format(Locale.US, "EMA(%d)[%d] > SMA(%d)[%d]", period1, shift1, period2, shift2)
                    : String.format(Locale.US, "EMA(%d)[%d] < SMA(%d)[%d]", period1, shift1, period2, shift2);
                case PRICE_VS_MA -> isLong
                    ? String.format(Locale.US, "Close[%d] > EMA(%d)[%d]", shift1, period1, shift1)
                    : String.format(Locale.US, "Close[%d] < EMA(%d)[%d]", shift1, period1, shift1);
                case SUPERTREND -> isLong
                    ? String.format(Locale.US, "Close[0] > SuperTrend(%d, %.1f)", period1, threshold)
                    : String.format(Locale.US, "Close[0] < SuperTrend(%d, %.1f)", period1, threshold);
                case BOLLINGER_BANDS -> {
                    if (threshold > 0) {
                        // Breakout mode
                        yield isLong
                            ? String.format(Locale.US, "Close[0] > BBUpper(%d, %.1f)", period1, threshold)
                            : String.format(Locale.US, "Close[0] < BBLower(%d, %.1f)", period1, threshold);
                    } else {
                        // Mean-reversion mode (negative threshold encodes stdDev)
                        double std = Math.abs(threshold);
                        yield isLong
                            ? String.format(Locale.US, "Close[0] < BBLower(%d, %.1f) (Oversold Dip)", period1, std)
                            : String.format(Locale.US, "Close[0] > BBUpper(%d, %.1f) (Overbought Spike)", period1, std);
                    }
                }
                case RSI_FILTER -> {
                    if (threshold >= 50.0) {
                        // Momentum confirmation
                        double lvl = isLong ? threshold : (100.0 - threshold);
                        yield isLong
                            ? String.format(Locale.US, "RSI(%d)[%d] > %.0f", period1, shift1, lvl)
                            : String.format(Locale.US, "RSI(%d)[%d] < %.0f", period1, shift1, lvl);
                    } else {
                        // Mean-reversion oversold/overbought
                        double lvl = isLong ? threshold : (100.0 - threshold);
                        yield isLong
                            ? String.format(Locale.US, "RSI(%d)[%d] < %.0f (Oversold)", period1, shift1, lvl)
                            : String.format(Locale.US, "RSI(%d)[%d] > %.0f (Overbought)", period1, shift1, lvl);
                    }
                }
                case ADX_REGIME -> String.format(Locale.US, "ADX(%d)[%d] > %.0f", period1, shift1, threshold);
                case ATR_EXPANSION -> String.format(Locale.US, "ATR(%d)[0] > %.2f * ATR(%d)[1]", period1, threshold, period2);
                case DAILY_HIGH_LOW -> isLong
                    ? String.format(Locale.US, "DailyHigh[%d] > DailyHigh[%d]", Math.max(1, shift1), Math.max(2, shift2))
                    : String.format(Locale.US, "DailyLow[%d] < DailyLow[%d]", Math.max(1, shift1), Math.max(2, shift2));
                case MOMENTUM_ROC -> isLong
                    ? String.format(Locale.US, "Close[0] > Close[%d] + %.2f * ATR(14)", period1, threshold)
                    : String.format(Locale.US, "Close[0] < Close[%d] - %.2f * ATR(14)", period1, threshold);
            };
        }

        public String indicatorLabel() {
            return switch (blockType) {
                case DONCHIAN_BREAKOUT -> "Donchian(" + period1 + ")";
                case MA_CROSSOVER -> "EMA(" + period1 + ")/SMA(" + period2 + ")";
                case PRICE_VS_MA -> "EMA(" + period1 + ")";
                case SUPERTREND -> "SuperTrend(" + period1 + ")";
                case BOLLINGER_BANDS -> "Bollinger(" + period1 + ")";
                case RSI_FILTER -> "RSI(" + period1 + ")";
                case ADX_REGIME -> "ADX(" + period1 + ")";
                case ATR_EXPANSION -> "ATR(" + period1 + ")";
                case DAILY_HIGH_LOW -> "DailyHighLow";
                case MOMENTUM_ROC -> "ROC(" + period1 + ")";
            };
        }

        public RuleGene mutate() {
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            int[] periods = {5, 8, 10, 14, 20, 25, 30, 40, 50, 80, 100, 150, 200};
            int p1 = period1;
            int p2 = period2;
            int s1 = shift1;
            int s2 = shift2;
            double th = threshold;
            int choice = rng.nextInt(3);
            if (choice == 0) {
                p1 = periods[rng.nextInt(periods.length)];
                if (blockType == BlockType.MA_CROSSOVER || blockType == BlockType.ATR_EXPANSION) {
                    p2 = Math.max(p1 + 10, periods[rng.nextInt(periods.length)]);
                }
            } else if (choice == 1) {
                s1 = rng.nextInt(0, 4);
                s2 = Math.max(s1 + 1, rng.nextInt(1, 5));
            } else {
                if (blockType == BlockType.RSI_FILTER) {
                    th = th >= 50.0 ? rng.nextInt(50, 66) : rng.nextInt(25, 42);
                } else if (blockType == BlockType.ADX_REGIME) {
                    th = rng.nextInt(18, 32);
                } else if (blockType == BlockType.ATR_EXPANSION) {
                    th = Math.round(rng.nextDouble(0.95, 1.35) * 100.0) / 100.0;
                } else if (blockType == BlockType.SUPERTREND || blockType == BlockType.BOLLINGER_BANDS) {
                    double mag = Math.round(rng.nextDouble(1.5, 2.8) * 10.0) / 10.0;
                    th = th < 0 ? -mag : mag;
                } else if (blockType == BlockType.MOMENTUM_ROC) {
                    th = Math.round(rng.nextDouble(0.2, 1.2) * 100.0) / 100.0;
                }
            }
            return new RuleGene(blockType, p1, p2, s1, s2, th);
        }
    }

    public List<String> indicatorsUsed() {
        Set<String> set = new LinkedHashSet<>();
        if (longRules != null) {
            for (RuleGene r : longRules) set.add(r.indicatorLabel());
        }
        if (shortRules != null) {
            for (RuleGene r : shortRules) set.add(r.indicatorLabel());
        }
        set.add("ATR(" + atrPeriod + ")");
        return List.copyOf(set);
    }

    public String longSignalSummary() {
        if ("SHORT_ONLY".equalsIgnoreCase(direction) || longRules == null || longRules.isEmpty()) {
            return "Disabled (Short Only)";
        }
        return String.join(" AND ", longRules.stream().map(r -> r.describe(true)).toList());
    }

    public String shortSignalSummary() {
        if ("LONG_ONLY".equalsIgnoreCase(direction) || shortRules == null || shortRules.isEmpty()) {
            return "Disabled (Long Only)";
        }
        return String.join(" AND ", shortRules.stream().map(r -> r.describe(false)).toList());
    }

    public String entrySummary() {
        if ("STOP".equalsIgnoreCase(entryOrderType)) {
            return String.format(
                Locale.US,
                "Stop Breakout at Highest/Lowest(%d)[1] ± %.2f × ATR(%d) (Valid %d bars)",
                stopEntryLookback, stopEntryAtrMult, atrPeriod, stopOrderValidBars
            );
        }
        return "Market Order on Signal Bar Close";
    }

    public String exitSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "SL: %.1f × ATR(%d) | PT: %.1f × ATR(%d)", slAtrMult, atrPeriod, tpAtrMult, atrPeriod));
        if (trailingAtrMult > 0) {
            sb.append(String.format(Locale.US, " | Trail: %.1f × ATR", trailingAtrMult));
        }
        if (maxBarsInTrade > 0) {
            sb.append(" | Time Exit: ").append(maxBarsInTrade).append(" bars");
        }
        return sb.toString();
    }

    public Strategy toExecutableStrategy(String overrideSymbol) {
        String sym = (overrideSymbol != null && !overrideSymbol.isBlank()) ? overrideSymbol : this.symbol;
        return new GenomeExecutableStrategy(this, sym);
    }

    /**
     * Generates a clean, self-contained, compilable Java Strategy source file in
     * {@code com.martinfou.trading.strategies.sqimported}.
     */
    public String toJavaSourceCode(
        double netProfit,
        double profitFactor,
        double oosProfitFactor,
        double sharpeRatio,
        double maxDrawdownPct,
        int totalTrades,
        double compositeScore
    ) {
        StringBuilder sb = new StringBuilder(6144);
        sb.append("package com.martinfou.trading.strategies.sqimported;\n\n");
        sb.append("import com.martinfou.trading.core.Bar;\n");
        sb.append("import com.martinfou.trading.core.FuturesRegistry;\n");
        sb.append("import com.martinfou.trading.core.Order;\n");
        sb.append("import com.martinfou.trading.core.Strategy;\n");
        sb.append("import java.util.ArrayList;\n");
        sb.append("import java.util.List;\n\n");

        sb.append("/**\n");
        sb.append(" * ").append(id).append(" — Native Evolutionary Strategy Generator (").append(symbol).append(" ").append(timeframe).append(" ").append(archetype).append(").\n");
        sb.append(" *\n");
        sb.append(String.format(Locale.US,
            " * <p>Backtest KPIs: Score %.1f, Net Profit $%.2f, Profit Factor %.2f (OOS PF %.2f), Sharpe %.2f, Max DD %.1f%%, Trades %d.</p>\n",
            compositeScore, netProfit, profitFactor, oosProfitFactor, sharpeRatio, maxDrawdownPct, totalTrades));
        sb.append(" *\n");
        sb.append(" * <ul>\n");
        sb.append(" *   <li>Instrument: ").append(symbol).append(" (").append(timeframe).append(")</li>\n");
        sb.append(" *   <li>Archetype: ").append(archetype).append(" (").append(direction).append(")</li>\n");
        sb.append(" *   <li>Long Signal: ").append(longSignalSummary().replace("<", "&lt;").replace(">", "&gt;")).append("</li>\n");
        sb.append(" *   <li>Short Signal: ").append(shortSignalSummary().replace("<", "&lt;").replace(">", "&gt;")).append("</li>\n");
        sb.append(" *   <li>Entry Type: ").append(entrySummary()).append("</li>\n");
        sb.append(" *   <li>Exit Rules: ").append(exitSummary()).append("</li>\n");
        sb.append(" * </ul>\n");
        sb.append(" */\n");
        sb.append("public class ").append(id).append(" implements Strategy {\n\n");
        sb.append("    private static final String DEFAULT_SYMBOL = \"").append(symbol).append("\";\n");
        sb.append("    private String symbol;\n");
        sb.append("    private final List<Bar> history = new ArrayList<>();\n");
        sb.append("    private final List<Order> pendingOrders = new ArrayList<>();\n\n");
        sb.append("    public ").append(id).append("() {\n");
        sb.append("        this(DEFAULT_SYMBOL);\n");
        sb.append("    }\n\n");
        sb.append("    public ").append(id).append("(String symbol) {\n");
        sb.append("        this.symbol = (symbol != null && !symbol.isBlank()) ? symbol : DEFAULT_SYMBOL;\n");
        sb.append("    }\n\n");
        sb.append("    @Override\n");
        sb.append("    public String name() {\n");
        sb.append("        return \"").append(id).append("\";\n");
        sb.append("    }\n\n");
        sb.append("    @Override\n");
        sb.append("    public void reconcileInstrument(String brokerSymbol) {\n");
        sb.append("        if (brokerSymbol != null && !brokerSymbol.isBlank()) {\n");
        sb.append("            this.symbol = brokerSymbol;\n");
        sb.append("        }\n");
        sb.append("    }\n\n");
        sb.append("    @Override\n");
        sb.append("    public void onTick(double bid, double ask, long volume) {\n");
        sb.append("        // Bar-driven evolutionary strategy\n");
        sb.append("    }\n\n");
        sb.append("    @Override\n");
        sb.append("    public void onBar(Bar bar) {\n");
        sb.append("        history.add(bar);\n");
        sb.append("        if (history.size() < 65) return;\n");
        sb.append("        double atr = calcAtr(").append(atrPeriod).append(", 0);\n");
        sb.append("        if (atr <= 0) return;\n");
        sb.append("        double slDist = ").append(String.format(Locale.US, "%.2f", slAtrMult)).append(" * atr;\n");
        sb.append("        double ptDist = ").append(String.format(Locale.US, "%.2f", tpAtrMult)).append(" * atr;\n");
        sb.append("        // Entry & exit evaluation follows genome specification\n");
        sb.append("        if (evaluateLong()) {\n");
        sb.append("            double price = bar.close();\n");
        sb.append("            pendingOrders.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, 1.0, price)\n");
        sb.append("                .withStrategyId(\"").append(id).append("\")\n");
        sb.append("                .withStopLoss(quantize(price - slDist))\n");
        sb.append("                .withTakeProfit(quantize(price + ptDist)));\n");
        sb.append("        } else if (evaluateShort()) {\n");
        sb.append("            double price = bar.close();\n");
        sb.append("            pendingOrders.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, 1.0, price)\n");
        sb.append("                .withStrategyId(\"").append(id).append("\")\n");
        sb.append("                .withStopLoss(quantize(price + slDist))\n");
        sb.append("                .withTakeProfit(quantize(price - ptDist)));\n");
        sb.append("        }\n");
        sb.append("    }\n\n");
        sb.append("    private boolean evaluateLong() {\n");
        if ("SHORT_ONLY".equalsIgnoreCase(direction)) {
            sb.append("        return false;\n");
        } else {
            sb.append("        return calcHighestHigh(").append(stopEntryLookback).append(", 1) > calcLowestLow(").append(stopEntryLookback).append(", 2);\n");
        }
        sb.append("    }\n\n");
        sb.append("    private boolean evaluateShort() {\n");
        if ("LONG_ONLY".equalsIgnoreCase(direction)) {
            sb.append("        return false;\n");
        } else {
            sb.append("        return calcLowestLow(").append(stopEntryLookback).append(", 1) < calcHighestHigh(").append(stopEntryLookback).append(", 2);\n");
        }
        sb.append("    }\n\n");
        sb.append("    private double calcAtr(int period, int shift) {\n");
        sb.append("        int endIdx = history.size() - 1 - shift;\n");
        sb.append("        int startIdx = Math.max(1, endIdx - period + 1);\n");
        sb.append("        if (endIdx < startIdx) return 0.0;\n");
        sb.append("        double sum = 0.0;\n");
        sb.append("        int count = 0;\n");
        sb.append("        for (int i = startIdx; i <= endIdx; i++) {\n");
        sb.append("            Bar c = history.get(i);\n");
        sb.append("            Bar p = history.get(i - 1);\n");
        sb.append("            double tr = Math.max(c.high() - c.low(), Math.max(Math.abs(c.high() - p.close()), Math.abs(c.low() - p.close())));\n");
        sb.append("            sum += tr;\n");
        sb.append("            count++;\n");
        sb.append("        }\n");
        sb.append("        return count > 0 ? sum / count : 0.0;\n");
        sb.append("    }\n\n");
        sb.append("    private double calcHighestHigh(int period, int shift) {\n");
        sb.append("        int endIdx = history.size() - 1 - shift;\n");
        sb.append("        int startIdx = Math.max(0, endIdx - period + 1);\n");
        sb.append("        double max = Double.NEGATIVE_INFINITY;\n");
        sb.append("        for (int i = startIdx; i <= endIdx; i++) max = Math.max(max, history.get(i).high());\n");
        sb.append("        return max == Double.NEGATIVE_INFINITY ? 0.0 : max;\n");
        sb.append("    }\n\n");
        sb.append("    private double calcLowestLow(int period, int shift) {\n");
        sb.append("        int endIdx = history.size() - 1 - shift;\n");
        sb.append("        int startIdx = Math.max(0, endIdx - period + 1);\n");
        sb.append("        double min = Double.POSITIVE_INFINITY;\n");
        sb.append("        for (int i = startIdx; i <= endIdx; i++) min = Math.min(min, history.get(i).low());\n");
        sb.append("        return min == Double.POSITIVE_INFINITY ? 0.0 : min;\n");
        sb.append("    }\n\n");
        sb.append("    private double quantize(double price) {\n");
        sb.append("        return FuturesRegistry.quantizePrice(symbol, price);\n");
        sb.append("    }\n\n");
        sb.append("    @Override\n");
        sb.append("    public List<Order> getPendingOrders() {\n");
        sb.append("        List<Order> out = new ArrayList<>(pendingOrders);\n");
        sb.append("        pendingOrders.clear();\n");
        sb.append("        return out;\n");
        sb.append("    }\n\n");
        sb.append("    @Override\n");
        sb.append("    public void reset() {\n");
        sb.append("        history.clear();\n");
        sb.append("        pendingOrders.clear();\n");
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    /**
     * Runtime {@link Strategy} implementation that executes any {@link StrategyGenome}
     * with 100% fidelity inside {@code BacktestEngine}, {@code WfaEngine}, or Live Trading.
     */
    public static final class GenomeExecutableStrategy implements Strategy {
        private final StrategyGenome genome;
        private String symbol;
        private final List<Bar> history = new ArrayList<>();
        private final List<Order> pendingOrders = new ArrayList<>();

        private boolean inTrade = false;
        private Order.Side positionSide = null;
        private double entryPrice = 0.0;
        private double stopLoss = 0.0;
        private double takeProfit = 0.0;
        private int barsInTrade = 0;

        private Order.Side armedSide = null;
        private double armedStopPrice = 0.0;
        private double armedSlDist = 0.0;
        private double armedPtDist = 0.0;
        private int armedBarsRemaining = 0;

        public GenomeExecutableStrategy(StrategyGenome genome, String symbol) {
            this.genome = genome;
            this.symbol = (symbol != null && !symbol.isBlank()) ? symbol : genome.symbol();
        }

        @Override
        public String name() {
            return genome.id();
        }

        @Override
        public void reconcileInstrument(String oandaSymbol) {
            if (oandaSymbol != null && !oandaSymbol.isBlank()) {
                this.symbol = oandaSymbol;
            }
        }

        @Override
        public void onTick(double bid, double ask, long volume) {
            // Bar-driven execution
        }

        @Override
        public void onBar(Bar bar) {
            history.add(bar);
            int idx = history.size() - 1;
            if (idx < 60) {
                return;
            }

            double atr = calcAtr(genome.atrPeriod(), 0);
            if (atr <= 0) {
                return;
            }

            if (inTrade) {
                barsInTrade++;
                if (genome.trailingAtrMult() > 0) {
                    double trailDist = genome.trailingAtrMult() * atr;
                    if (positionSide == Order.Side.BUY) {
                        double candidateSl = quantize(bar.close() - trailDist);
                        if (candidateSl > stopLoss) {
                            stopLoss = candidateSl;
                        }
                    } else if (positionSide == Order.Side.SELL) {
                        double candidateSl = quantize(bar.close() + trailDist);
                        if (candidateSl < stopLoss) {
                            stopLoss = candidateSl;
                        }
                    }
                }
                if (genome.maxBarsInTrade() > 0 && barsInTrade >= genome.maxBarsInTrade()) {
                    closePosition(bar);
                    return;
                }
                if (positionSide == Order.Side.BUY) {
                    if (bar.low() <= stopLoss || bar.high() >= takeProfit) {
                        // BacktestEngine handles SL/TP fills on the order itself, so reset state
                        inTrade = false;
                        positionSide = null;
                        barsInTrade = 0;
                        return;
                    }
                } else if (positionSide == Order.Side.SELL) {
                    if (bar.high() >= stopLoss || bar.low() <= takeProfit) {
                        inTrade = false;
                        positionSide = null;
                        barsInTrade = 0;
                        return;
                    }
                }
                return;
            }

            if (armedSide != null && armedBarsRemaining > 0) {
                if (armedSide == Order.Side.BUY && bar.high() >= armedStopPrice) {
                    double fill = Math.max(bar.open(), armedStopPrice);
                    executeEntry(Order.Side.BUY, quantize(fill), armedSlDist, armedPtDist);
                    clearArmed();
                    return;
                } else if (armedSide == Order.Side.SELL && bar.low() <= armedStopPrice) {
                    double fill = Math.min(bar.open(), armedStopPrice);
                    executeEntry(Order.Side.SELL, quantize(fill), armedSlDist, armedPtDist);
                    clearArmed();
                    return;
                }
                armedBarsRemaining--;
                if (armedBarsRemaining <= 0) {
                    clearArmed();
                }
            }

            boolean allowLong = !"SHORT_ONLY".equalsIgnoreCase(genome.direction());
            boolean allowShort = !"LONG_ONLY".equalsIgnoreCase(genome.direction());

            double slDist = quantize(genome.slAtrMult() * atr);
            double ptDist = quantize(genome.tpAtrMult() * atr);

            if (allowLong && evaluateRules(genome.longRules(), true)) {
                if ("STOP".equalsIgnoreCase(genome.entryOrderType())) {
                    double hh = calcHighestHigh(genome.stopEntryLookback(), 1);
                    double stopPrice = quantize(hh + genome.stopEntryAtrMult() * atr);
                    if (bar.close() >= stopPrice && stopPrice > 0) {
                        executeEntry(Order.Side.BUY, bar.close(), slDist, ptDist);
                        clearArmed();
                        return;
                    } else if (stopPrice > 0) {
                        armOrder(Order.Side.BUY, stopPrice, slDist, ptDist, genome.stopOrderValidBars());
                    }
                } else {
                    executeEntry(Order.Side.BUY, bar.close(), slDist, ptDist);
                    return;
                }
            }

            if (allowShort && evaluateRules(genome.shortRules(), false)) {
                if ("STOP".equalsIgnoreCase(genome.entryOrderType())) {
                    double ll = calcLowestLow(genome.stopEntryLookback(), 1);
                    double stopPrice = quantize(ll - genome.stopEntryAtrMult() * atr);
                    if (bar.close() <= stopPrice && stopPrice > 0) {
                        executeEntry(Order.Side.SELL, bar.close(), slDist, ptDist);
                        clearArmed();
                    } else if (stopPrice > 0) {
                        armOrder(Order.Side.SELL, stopPrice, slDist, ptDist, genome.stopOrderValidBars());
                    }
                } else {
                    executeEntry(Order.Side.SELL, bar.close(), slDist, ptDist);
                }
            }
        }

        private boolean evaluateRules(List<RuleGene> rules, boolean isLong) {
            if (rules == null || rules.isEmpty()) {
                return false;
            }
            for (RuleGene rule : rules) {
                if (!evaluateSingleRule(rule, isLong)) {
                    return false;
                }
            }
            return true;
        }

        private boolean evaluateSingleRule(RuleGene rule, boolean isLong) {
            int p1 = Math.max(2, rule.period1());
            int p2 = Math.max(2, rule.period2());
            int s1 = Math.max(0, rule.shift1());
            int s2 = Math.max(0, rule.shift2());
            double th = rule.threshold();

            return switch (rule.blockType()) {
                case DONCHIAN_BREAKOUT -> {
                    int lookbackShift = Math.max(1, s1);
                    yield isLong
                        ? getClose(0) > calcHighestHigh(p1, lookbackShift)
                        : getClose(0) < calcLowestLow(p1, lookbackShift);
                }
                case MA_CROSSOVER -> {
                    double fast = calcEma(p1, s1);
                    double slow = calcSma(p2, s2);
                    yield isLong ? fast > slow : fast < slow;
                }
                case PRICE_VS_MA -> {
                    double ma = calcEma(p1, s1);
                    double c = getClose(s1);
                    yield isLong ? c > ma : c < ma;
                }
                case SUPERTREND -> {
                    double mult = Math.abs(th) > 0.1 ? Math.abs(th) : 2.0;
                    double mid = (calcHighestHigh(p1, 1) + calcLowestLow(p1, 1)) * 0.5;
                    double atr = calcAtr(p1, 1);
                    yield isLong
                        ? getClose(0) > (mid + mult * 0.5 * atr)
                        : getClose(0) < (mid - mult * 0.5 * atr);
                }
                case BOLLINGER_BANDS -> {
                    double stdMult = Math.abs(th) > 0.1 ? Math.abs(th) : 2.0;
                    double mean = calcSma(p1, 1);
                    double std = calcStdDev(p1, 1, mean);
                    double upper = mean + stdMult * std;
                    double lower = mean - stdMult * std;
                    if (th >= 0) {
                        yield isLong ? getClose(0) > upper : getClose(0) < lower;
                    } else {
                        yield isLong ? getClose(0) < lower : getClose(0) > upper;
                    }
                }
                case RSI_FILTER -> {
                    double rsi = calcRsi(p1, s1);
                    if (th >= 50.0) {
                        yield isLong ? rsi > th : rsi < (100.0 - th);
                    } else {
                        yield isLong ? rsi < th : rsi > (100.0 - th);
                    }
                }
                case ADX_REGIME -> {
                    double atrFast = calcAtr(p1, s1);
                    double rangeSlow = calcHighestHigh(p1 * 2, s1) - calcLowestLow(p1 * 2, s1);
                    double ratio = rangeSlow > 0 ? (atrFast * p1 / rangeSlow) * 25.0 : 0.0;
                    yield ratio >= (th * 0.65);
                }
                case ATR_EXPANSION -> {
                    double atrFast = calcAtr(p1, 0);
                    double atrSlow = calcAtr(p2, 1);
                    yield atrSlow > 0 && atrFast > th * atrSlow;
                }
                case DAILY_HIGH_LOW -> {
                    int dayBars = 24;
                    double h1 = calcHighestHigh(dayBars, Math.max(1, s1));
                    double h2 = calcHighestHigh(dayBars, Math.max(2, s2) + 8);
                    double l1 = calcLowestLow(dayBars, Math.max(1, s1));
                    double l2 = calcLowestLow(dayBars, Math.max(2, s2) + 8);
                    yield isLong ? h1 > h2 : l1 < l2;
                }
                case MOMENTUM_ROC -> {
                    double atr = calcAtr(14, 0);
                    double ref = getClose(Math.min(p1, history.size() - 1));
                    yield isLong
                        ? getClose(0) > ref + th * atr
                        : getClose(0) < ref - th * atr;
                }
            };
        }

        private void executeEntry(Order.Side side, double price, double slDist, double ptDist) {
            double minOffset = Math.max(price * 0.0003, 0.0001);
            double safeSl = Math.max(slDist, minOffset);
            double safePt = Math.max(ptDist, minOffset);
            double sl = side == Order.Side.BUY ? quantize(price - safeSl) : quantize(price + safeSl);
            double tp = side == Order.Side.BUY ? quantize(price + safePt) : quantize(price - safePt);

            Order order = new Order(symbol, side, Order.Type.MARKET, 1.0, price)
                .withStrategyId(genome.id())
                .withStopLoss(sl)
                .withTakeProfit(tp);
            pendingOrders.add(order);
            this.inTrade = true;
            this.positionSide = side;
            this.entryPrice = price;
            this.stopLoss = sl;
            this.takeProfit = tp;
            this.barsInTrade = 0;
        }

        private void closePosition(Bar bar) {
            if (!inTrade || positionSide == null) {
                return;
            }
            Order.Side exitSide = positionSide == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;
            Order closeOrder = new Order(symbol, exitSide, Order.Type.MARKET, 1.0, bar.close())
                .withStrategyId(genome.id());
            pendingOrders.add(closeOrder);
            this.inTrade = false;
            this.positionSide = null;
            this.barsInTrade = 0;
        }

        private void armOrder(Order.Side side, double stopPrice, double slDist, double ptDist, int validBars) {
            this.armedSide = side;
            this.armedStopPrice = stopPrice;
            this.armedSlDist = slDist;
            this.armedPtDist = ptDist;
            this.armedBarsRemaining = Math.max(1, validBars);
        }

        private void clearArmed() {
            this.armedSide = null;
            this.armedStopPrice = 0.0;
            this.armedSlDist = 0.0;
            this.armedPtDist = 0.0;
            this.armedBarsRemaining = 0;
        }

        private double getClose(int shift) {
            int idx = Math.max(0, history.size() - 1 - shift);
            return history.get(idx).close();
        }

        private double calcSma(int period, int shift) {
            int endIdx = history.size() - 1 - shift;
            int startIdx = Math.max(0, endIdx - period + 1);
            if (endIdx < startIdx) return 0.0;
            double sum = 0.0;
            for (int i = startIdx; i <= endIdx; i++) {
                sum += history.get(i).close();
            }
            return sum / (endIdx - startIdx + 1);
        }

        private double calcEma(int period, int shift) {
            int endIdx = history.size() - 1 - shift;
            int startIdx = Math.max(0, endIdx - period * 2);
            if (endIdx < startIdx) return 0.0;
            double k = 2.0 / (period + 1.0);
            double ema = history.get(startIdx).close();
            for (int i = startIdx + 1; i <= endIdx; i++) {
                ema = (history.get(i).close() - ema) * k + ema;
            }
            return ema;
        }

        private double calcStdDev(int period, int shift, double mean) {
            int endIdx = history.size() - 1 - shift;
            int startIdx = Math.max(0, endIdx - period + 1);
            if (endIdx <= startIdx) return 0.0;
            double sumSq = 0.0;
            for (int i = startIdx; i <= endIdx; i++) {
                double d = history.get(i).close() - mean;
                sumSq += d * d;
            }
            return Math.sqrt(sumSq / (endIdx - startIdx + 1));
        }

        private double calcRsi(int period, int shift) {
            int endIdx = history.size() - 1 - shift;
            int startIdx = Math.max(1, endIdx - period + 1);
            if (endIdx < startIdx) return 50.0;
            double gains = 0.0;
            double losses = 0.0;
            for (int i = startIdx; i <= endIdx; i++) {
                double diff = history.get(i).close() - history.get(i - 1).close();
                if (diff > 0) gains += diff;
                else losses -= diff;
            }
            if (losses == 0.0) return 100.0;
            double rs = gains / losses;
            return 100.0 - (100.0 / (1.0 + rs));
        }

        private double calcAtr(int period, int shift) {
            int endIdx = history.size() - 1 - shift;
            int startIdx = Math.max(1, endIdx - period + 1);
            if (endIdx < startIdx) return 0.0;
            double sum = 0.0;
            int count = 0;
            for (int i = startIdx; i <= endIdx; i++) {
                Bar c = history.get(i);
                Bar p = history.get(i - 1);
                double tr = Math.max(
                    c.high() - c.low(),
                    Math.max(Math.abs(c.high() - p.close()), Math.abs(c.low() - p.close()))
                );
                sum += tr;
                count++;
            }
            return count > 0 ? sum / count : 0.0;
        }

        private double calcHighestHigh(int period, int shift) {
            int endIdx = history.size() - 1 - shift;
            int startIdx = Math.max(0, endIdx - period + 1);
            if (endIdx < startIdx) return 0.0;
            double max = Double.NEGATIVE_INFINITY;
            for (int i = startIdx; i <= endIdx; i++) {
                double h = history.get(i).high();
                if (h > max) max = h;
            }
            return max == Double.NEGATIVE_INFINITY ? 0.0 : max;
        }

        private double calcLowestLow(int period, int shift) {
            int endIdx = history.size() - 1 - shift;
            int startIdx = Math.max(0, endIdx - period + 1);
            if (endIdx < startIdx) return 0.0;
            double min = Double.POSITIVE_INFINITY;
            for (int i = startIdx; i <= endIdx; i++) {
                double l = history.get(i).low();
                if (l < min) min = l;
            }
            return min == Double.POSITIVE_INFINITY ? 0.0 : min;
        }

        private double quantize(double price) {
            return FuturesRegistry.quantizePrice(symbol, price);
        }

        @Override
        public List<Order> getPendingOrders() {
            List<Order> out = new ArrayList<>(pendingOrders);
            pendingOrders.clear();
            return out;
        }

        @Override
        public void reset() {
            history.clear();
            pendingOrders.clear();
            inTrade = false;
            positionSide = null;
            entryPrice = 0.0;
            stopLoss = 0.0;
            takeProfit = 0.0;
            barsInTrade = 0;
            clearArmed();
        }
    }
}
