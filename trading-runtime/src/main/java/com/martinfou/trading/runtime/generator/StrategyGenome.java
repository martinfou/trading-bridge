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
        MOMENTUM_ROC,
        MACD_HISTOGRAM,
        STOCHASTIC_OSC,
        KELTNER_CHANNEL,
        BB_KC_SQUEEZE,
        CCI_OSCILLATOR,
        WILLIAMS_R,
        VWAP_ANCHOR,
        VOLUME_SURGE,
        ENGULFING_PATTERN,
        INSIDE_BAR_COIL,
        LINEAR_REG_SLOPE,
        ZSCORE_EXTREME,
        CHOPPINESS_FILTER,
        HIGHER_HIGHS_SWING
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
                    double std = Math.abs(threshold) > 0.1 ? Math.abs(threshold) : 2.0;
                    if (threshold >= 0) {
                        yield isLong
                            ? String.format(Locale.US, "Close[0] > BBUpper(%d, %.1f)", period1, std)
                            : String.format(Locale.US, "Close[0] < BBLower(%d, %.1f)", period1, std);
                    } else {
                        yield isLong
                            ? String.format(Locale.US, "Close[0] < BBLower(%d, %.1f) (Oversold Dip)", period1, std)
                            : String.format(Locale.US, "Close[0] > BBUpper(%d, %.1f) (Overbought Spike)", period1, std);
                    }
                }
                case RSI_FILTER -> {
                    if (threshold >= 50.0) {
                        double lvl = isLong ? threshold : (100.0 - threshold);
                        yield isLong
                            ? String.format(Locale.US, "RSI(%d)[%d] > %.0f", period1, shift1, lvl)
                            : String.format(Locale.US, "RSI(%d)[%d] < %.0f", period1, shift1, lvl);
                    } else {
                        double lvl = isLong ? threshold : (100.0 - threshold);
                        yield isLong
                            ? String.format(Locale.US, "RSI(%d)[%d] < %.0f (Oversold)", period1, shift1, lvl)
                            : String.format(Locale.US, "RSI(%d)[%d] > %.0f (Overbought)", period1, shift1, lvl);
                    }
                }
                case ADX_REGIME -> String.format(Locale.US, "ADX(%d)[%d] > %.0f", period1, shift1, threshold);
                case ATR_EXPANSION -> String.format(Locale.US, "ATR(%d)[0] > %.2f * ATR(%d)[1]", period1, threshold, period2);
                case DAILY_HIGH_LOW -> isLong
                    ? String.format(Locale.US, "DailyHigh[%d] > DailyHigh[%d]", Math.max(1, shift1), Math.max(2, shift2) + 8)
                    : String.format(Locale.US, "DailyLow[%d] < DailyLow[%d]", Math.max(1, shift1), Math.max(2, shift2) + 8);
                case MOMENTUM_ROC -> isLong
                    ? String.format(Locale.US, "Close[0] > Close[%d] + %.2f * ATR(14)", period1, threshold)
                    : String.format(Locale.US, "Close[0] < Close[%d] - %.2f * ATR(14)", period1, threshold);
                case MACD_HISTOGRAM -> isLong
                    ? String.format(Locale.US, "MACD(%d, %d)[%d] > 0 & Rising", period1, period2, shift1)
                    : String.format(Locale.US, "MACD(%d, %d)[%d] < 0 & Falling", period1, period2, shift1);
                case STOCHASTIC_OSC -> {
                    if (threshold >= 50.0) {
                        double lvl = isLong ? threshold : (100.0 - threshold);
                        yield isLong
                            ? String.format(Locale.US, "Stoch%%K(%d)[%d] > %.0f", period1, shift1, lvl)
                            : String.format(Locale.US, "Stoch%%K(%d)[%d] < %.0f", period1, shift1, lvl);
                    } else {
                        double lvl = isLong ? threshold : (100.0 - threshold);
                        yield isLong
                            ? String.format(Locale.US, "Stoch%%K(%d)[%d] < %.0f (Oversold)", period1, shift1, lvl)
                            : String.format(Locale.US, "Stoch%%K(%d)[%d] > %.0f (Overbought)", period1, shift1, lvl);
                    }
                }
                case KELTNER_CHANNEL -> isLong
                    ? String.format(Locale.US, "Close[0] > KeltnerUpper(%d, %.1f)", period1, Math.abs(threshold))
                    : String.format(Locale.US, "Close[0] < KeltnerLower(%d, %.1f)", period1, Math.abs(threshold));
                case BB_KC_SQUEEZE -> isLong
                    ? String.format(Locale.US, "TTM Squeeze(%d, %.1f) + Bullish Breakout", period1, Math.abs(threshold))
                    : String.format(Locale.US, "TTM Squeeze(%d, %.1f) + Bearish Breakout", period1, Math.abs(threshold));
                case CCI_OSCILLATOR -> {
                    if (threshold >= 0) {
                        yield isLong
                            ? String.format(Locale.US, "CCI(%d)[%d] > +%.0f", period1, shift1, threshold)
                            : String.format(Locale.US, "CCI(%d)[%d] < -%.0f", period1, shift1, threshold);
                    } else {
                        double lvl = Math.abs(threshold);
                        yield isLong
                            ? String.format(Locale.US, "CCI(%d)[%d] < -%.0f (Oversold)", period1, shift1, lvl)
                            : String.format(Locale.US, "CCI(%d)[%d] > +%.0f (Overbought)", period1, shift1, lvl);
                    }
                }
                case WILLIAMS_R -> {
                    if (threshold >= 50.0) {
                        yield isLong
                            ? String.format(Locale.US, "Williams%%R(%d)[%d] > -%.0f", period1, shift1, 100.0 - threshold)
                            : String.format(Locale.US, "Williams%%R(%d)[%d] < -%.0f", period1, shift1, threshold);
                    } else {
                        yield isLong
                            ? String.format(Locale.US, "Williams%%R(%d)[%d] < -%.0f (Oversold)", period1, shift1, 100.0 - threshold)
                            : String.format(Locale.US, "Williams%%R(%d)[%d] > -%.0f (Overbought)", period1, shift1, threshold);
                    }
                }
                case VWAP_ANCHOR -> isLong
                    ? String.format(Locale.US, "Close[0] > RollingVWAP(%d)[%d]", period1, shift1)
                    : String.format(Locale.US, "Close[0] < RollingVWAP(%d)[%d]", period1, shift1);
                case VOLUME_SURGE -> String.format(Locale.US, "Volume[0] > %.2f * AvgVolume(%d)[1]", Math.max(1.05, threshold), period1);
                case ENGULFING_PATTERN -> isLong
                    ? String.format(Locale.US, "Bullish Engulfing Bar[0..%d] (Body > %.2f * ATR)", Math.max(1, shift1), Math.max(0.15, threshold))
                    : String.format(Locale.US, "Bearish Engulfing Bar[0..%d] (Body > %.2f * ATR)", Math.max(1, shift1), Math.max(0.15, threshold));
                case INSIDE_BAR_COIL -> {
                    int cs = Math.max(1, shift1);
                    yield isLong
                        ? String.format(Locale.US, "InsideBar Coil[%d] & Close[0] > High[%d]", cs, cs)
                        : String.format(Locale.US, "InsideBar Coil[%d] & Close[0] < Low[%d]", cs, cs);
                }
                case LINEAR_REG_SLOPE -> {
                    double sMult = Math.max(0.02, Math.abs(threshold) * 0.12);
                    yield isLong
                        ? String.format(Locale.US, "LinRegSlope(%d)[%d] > +%.3f * ATR", period1, shift1, sMult)
                        : String.format(Locale.US, "LinRegSlope(%d)[%d] < -%.3f * ATR", period1, shift1, sMult);
                }
                case ZSCORE_EXTREME -> {
                    double z = Math.max(0.8, Math.abs(threshold));
                    if (threshold < 0) {
                        yield isLong
                            ? String.format(Locale.US, "ZScore(%d)[%d] < -%.2f (Mean-Rev Dip)", period1, shift1, z)
                            : String.format(Locale.US, "ZScore(%d)[%d] > +%.2f (Mean-Rev Spike)", period1, shift1, z);
                    } else {
                        yield isLong
                            ? String.format(Locale.US, "ZScore(%d)[%d] > +%.2f (Momentum Thrust)", period1, shift1, z)
                            : String.format(Locale.US, "ZScore(%d)[%d] < -%.2f (Momentum Thrust)", period1, shift1, z);
                    }
                }
                case CHOPPINESS_FILTER -> String.format(Locale.US, "EfficiencyRatio(%d)[%d] > %.2f (Trending Regime)", period1, shift1, Math.clamp(threshold, 0.20, 0.70));
                case HIGHER_HIGHS_SWING -> {
                    int sw = Math.max(4, Math.min(20, Math.max(2, period1) / 2));
                    yield isLong
                        ? String.format(Locale.US, "SwingHigh/Low(%d) Higher-High & Higher-Low", sw)
                        : String.format(Locale.US, "SwingHigh/Low(%d) Lower-Low & Lower-High", sw);
                }
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
                case ATR_EXPANSION -> "ATR_EXP(" + period1 + ")";
                case DAILY_HIGH_LOW -> "DailyHighLow";
                case MOMENTUM_ROC -> "ROC(" + period1 + ")";
                case MACD_HISTOGRAM -> "MACD(" + period1 + "," + period2 + ")";
                case STOCHASTIC_OSC -> "Stoch(" + period1 + ")";
                case KELTNER_CHANNEL -> "Keltner(" + period1 + ")";
                case BB_KC_SQUEEZE -> "TTMSqueeze(" + period1 + ")";
                case CCI_OSCILLATOR -> "CCI(" + period1 + ")";
                case WILLIAMS_R -> "Will%R(" + period1 + ")";
                case VWAP_ANCHOR -> "VWAP(" + period1 + ")";
                case VOLUME_SURGE -> "VolSurge(" + period1 + ")";
                case ENGULFING_PATTERN -> "EngulfingPA";
                case INSIDE_BAR_COIL -> "InsideBarNR";
                case LINEAR_REG_SLOPE -> "LinReg(" + period1 + ")";
                case ZSCORE_EXTREME -> "ZScore(" + period1 + ")";
                case CHOPPINESS_FILTER -> "KaufmanER(" + period1 + ")";
                case HIGHER_HIGHS_SWING -> "SwingHHLL(" + Math.max(4, Math.min(20, Math.max(2, period1) / 2)) + ")";
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
                if (blockType == BlockType.MA_CROSSOVER || blockType == BlockType.ATR_EXPANSION || blockType == BlockType.MACD_HISTOGRAM) {
                    p2 = Math.max(p1 + 10, periods[rng.nextInt(periods.length)]);
                }
            } else if (choice == 1) {
                s1 = rng.nextInt(0, 4);
                s2 = Math.max(s1 + 1, rng.nextInt(1, 5));
            } else {
                if (blockType == BlockType.RSI_FILTER || blockType == BlockType.STOCHASTIC_OSC || blockType == BlockType.WILLIAMS_R) {
                    th = th >= 50.0 ? rng.nextInt(50, 68) : rng.nextInt(20, 42);
                } else if (blockType == BlockType.ADX_REGIME) {
                    th = rng.nextInt(18, 32);
                } else if (blockType == BlockType.ATR_EXPANSION || blockType == BlockType.VOLUME_SURGE) {
                    th = Math.round(rng.nextDouble(1.05, 1.65) * 100.0) / 100.0;
                } else if (blockType == BlockType.SUPERTREND || blockType == BlockType.BOLLINGER_BANDS
                    || blockType == BlockType.KELTNER_CHANNEL || blockType == BlockType.BB_KC_SQUEEZE
                    || blockType == BlockType.ZSCORE_EXTREME) {
                    double mag = Math.round(rng.nextDouble(1.3, 2.6) * 10.0) / 10.0;
                    th = th < 0 ? -mag : mag;
                } else if (blockType == BlockType.CCI_OSCILLATOR) {
                    double mag = rng.nextInt(60, 140);
                    th = th < 0 ? -mag : mag;
                } else if (blockType == BlockType.CHOPPINESS_FILTER) {
                    th = Math.round(rng.nextDouble(0.25, 0.58) * 100.0) / 100.0;
                } else if (blockType == BlockType.MOMENTUM_ROC || blockType == BlockType.LINEAR_REG_SLOPE || blockType == BlockType.ENGULFING_PATTERN) {
                    th = Math.round(rng.nextDouble(0.15, 0.95) * 100.0) / 100.0;
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
        sb.append("    private boolean inTrade = false;\n");
        sb.append("    private Order.Side positionSide = null;\n");
        sb.append("    private double stopLoss = 0.0;\n");
        sb.append("    private double takeProfit = 0.0;\n");
        sb.append("    private int barsInTrade = 0;\n\n");
        sb.append("    private Order.Side armedSide = null;\n");
        sb.append("    private double armedStopPrice = 0.0;\n");
        sb.append("    private double armedSlDist = 0.0;\n");
        sb.append("    private double armedPtDist = 0.0;\n");
        sb.append("    private int armedBarsRemaining = 0;\n\n");
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
        sb.append("        if (history.size() < 61) return;\n");
        sb.append("        double atr = calcAtr(").append(atrPeriod).append(", 0);\n");
        sb.append("        if (atr <= 0) return;\n\n");
        sb.append("        if (inTrade) {\n");
        sb.append("            barsInTrade++;\n");
        sb.append("            if (positionSide == Order.Side.BUY && (bar.low() <= stopLoss || bar.high() >= takeProfit)) {\n");
        sb.append("                closePosition(bar);\n");
        sb.append("                return;\n");
        sb.append("            } else if (positionSide == Order.Side.SELL && (bar.high() >= stopLoss || bar.low() <= takeProfit)) {\n");
        sb.append("                closePosition(bar);\n");
        sb.append("                return;\n");
        sb.append("            }\n");
        if (maxBarsInTrade > 0) {
            sb.append("            if (barsInTrade >= ").append(maxBarsInTrade).append(") {\n");
            sb.append("                closePosition(bar);\n");
            sb.append("                return;\n");
            sb.append("            }\n");
        }
        if (trailingAtrMult > 0) {
            sb.append("            double trailDist = ").append(String.format(Locale.US, "%.2f", trailingAtrMult)).append(" * atr;\n");
            sb.append("            if (positionSide == Order.Side.BUY) {\n");
            sb.append("                stopLoss = Math.max(stopLoss, quantize(bar.close() - trailDist));\n");
            sb.append("            } else if (positionSide == Order.Side.SELL) {\n");
            sb.append("                stopLoss = Math.min(stopLoss, quantize(bar.close() + trailDist));\n");
            sb.append("            }\n");
        }
        sb.append("            return;\n");
        sb.append("        }\n\n");
        sb.append("        if (armedSide != null && armedBarsRemaining > 0) {\n");
        sb.append("            if (armedSide == Order.Side.BUY && bar.high() >= armedStopPrice) {\n");
        sb.append("                executeEntry(Order.Side.BUY, bar.close(), armedSlDist, armedPtDist);\n");
        sb.append("                clearArmed();\n");
        sb.append("                return;\n");
        sb.append("            } else if (armedSide == Order.Side.SELL && bar.low() <= armedStopPrice) {\n");
        sb.append("                executeEntry(Order.Side.SELL, bar.close(), armedSlDist, armedPtDist);\n");
        sb.append("                clearArmed();\n");
        sb.append("                return;\n");
        sb.append("            }\n");
        sb.append("            armedBarsRemaining--;\n");
        sb.append("            if (armedBarsRemaining <= 0) clearArmed();\n");
        sb.append("        }\n\n");
        sb.append("        double slDist = quantize(").append(String.format(Locale.US, "%.2f", slAtrMult)).append(" * atr);\n");
        sb.append("        double ptDist = quantize(").append(String.format(Locale.US, "%.2f", tpAtrMult)).append(" * atr);\n\n");
        sb.append("        if (evaluateLong()) {\n");
        if ("STOP".equalsIgnoreCase(entryOrderType)) {
            sb.append("            double hh = calcHighestHigh(").append(stopEntryLookback).append(", 1);\n");
            sb.append("            double stopPrice = quantize(hh + ").append(String.format(Locale.US, "%.2f", stopEntryAtrMult)).append(" * atr);\n");
            sb.append("            if (bar.close() >= stopPrice && stopPrice > 0) {\n");
            sb.append("                executeEntry(Order.Side.BUY, bar.close(), slDist, ptDist);\n");
            sb.append("                clearArmed();\n");
            sb.append("                return;\n");
            sb.append("            } else if (stopPrice > 0) {\n");
            sb.append("                armOrder(Order.Side.BUY, stopPrice, slDist, ptDist, ").append(stopOrderValidBars).append(");\n");
                sb.append("                return;\n");
            sb.append("            }\n");
        } else {
            sb.append("            executeEntry(Order.Side.BUY, bar.close(), slDist, ptDist);\n");
            sb.append("            return;\n");
        }
        sb.append("        } else if (evaluateShort()) {\n");
        if ("STOP".equalsIgnoreCase(entryOrderType)) {
            sb.append("            double ll = calcLowestLow(").append(stopEntryLookback).append(", 1);\n");
            sb.append("            double stopPrice = quantize(ll - ").append(String.format(Locale.US, "%.2f", stopEntryAtrMult)).append(" * atr);\n");
            sb.append("            if (bar.close() <= stopPrice && stopPrice > 0) {\n");
            sb.append("                executeEntry(Order.Side.SELL, bar.close(), slDist, ptDist);\n");
            sb.append("                clearArmed();\n");
            sb.append("            } else if (stopPrice > 0) {\n");
            sb.append("                armOrder(Order.Side.SELL, stopPrice, slDist, ptDist, ").append(stopOrderValidBars).append(");\n");
            sb.append("            }\n");
        } else {
            sb.append("            executeEntry(Order.Side.SELL, bar.close(), slDist, ptDist);\n");
        }
        sb.append("        }\n");
        sb.append("    }\n\n");
        sb.append("    private void executeEntry(Order.Side side, double price, double slDist, double ptDist) {\n");
        sb.append("        double minOffset = Math.max(price * 0.0003, 0.0001);\n");
        sb.append("        double safeSl = Math.max(slDist, minOffset);\n");
        sb.append("        double safePt = Math.max(ptDist, minOffset);\n");
        sb.append("        double sl = side == Order.Side.BUY ? quantize(price - safeSl) : quantize(price + safeSl);\n");
        sb.append("        double tp = side == Order.Side.BUY ? quantize(price + safePt) : quantize(price - safePt);\n");
        sb.append("        pendingOrders.add(new Order(symbol, side, Order.Type.MARKET, 1.0, price)\n");
        sb.append("            .withStrategyId(\"").append(id).append("\")\n");
        sb.append("            .withStopLoss(sl)\n");
        sb.append("            .withTakeProfit(tp));\n");
        sb.append("        this.inTrade = true;\n");
        sb.append("        this.positionSide = side;\n");
        sb.append("        this.stopLoss = sl;\n");
        sb.append("        this.takeProfit = tp;\n");
        sb.append("        this.barsInTrade = 0;\n");
        sb.append("    }\n\n");
        sb.append("    private void closePosition(Bar bar) {\n");
        sb.append("        if (!inTrade || positionSide == null) return;\n");
        sb.append("        Order.Side exitSide = positionSide == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY;\n");
        sb.append("        pendingOrders.add(new Order(symbol, exitSide, Order.Type.MARKET, 1.0, bar.close())\n");
        sb.append("            .withStrategyId(\"").append(id).append("\")\n");
        sb.append("            .asCloseOnly());\n");
        sb.append("        this.inTrade = false;\n");
        sb.append("        this.positionSide = null;\n");
        sb.append("        this.barsInTrade = 0;\n");
        sb.append("    }\n\n");
        sb.append("    private void armOrder(Order.Side side, double stopPrice, double slDist, double ptDist, int validBars) {\n");
        sb.append("        this.armedSide = side;\n");
        sb.append("        this.armedStopPrice = stopPrice;\n");
        sb.append("        this.armedSlDist = slDist;\n");
        sb.append("        this.armedPtDist = ptDist;\n");
        sb.append("        this.armedBarsRemaining = Math.max(1, validBars);\n");
        sb.append("    }\n\n");
        sb.append("    private void clearArmed() {\n");
        sb.append("        this.armedSide = null;\n");
        sb.append("        this.armedStopPrice = 0.0;\n");
        sb.append("        this.armedSlDist = 0.0;\n");
        sb.append("        this.armedPtDist = 0.0;\n");
        sb.append("        this.armedBarsRemaining = 0;\n");
        sb.append("    }\n\n");
        sb.append("    private boolean evaluateLong() {\n");
        if ("SHORT_ONLY".equalsIgnoreCase(direction) || longRules == null || longRules.isEmpty()) {
            sb.append("        return false;\n");
        } else {
            sb.append("        return ").append(renderRulesExpr(longRules, true)).append(";\n");
        }
        sb.append("    }\n\n");
        sb.append("    private boolean evaluateShort() {\n");
        if ("LONG_ONLY".equalsIgnoreCase(direction) || shortRules == null || shortRules.isEmpty()) {
            sb.append("        return false;\n");
        } else {
            sb.append("        return ").append(renderRulesExpr(shortRules, false)).append(";\n");
        }
        sb.append("    }\n\n");
        sb.append("    private double getClose(int shift) {\n");
        sb.append("        int idx = Math.max(0, history.size() - 1 - shift);\n");
        sb.append("        return history.get(idx).close();\n");
        sb.append("    }\n\n");
        sb.append("    private double getOpen(int shift) {\n");
        sb.append("        int idx = Math.max(0, history.size() - 1 - shift);\n");
        sb.append("        return history.get(idx).open();\n");
        sb.append("    }\n\n");
        sb.append("    private double getHigh(int shift) {\n");
        sb.append("        int idx = Math.max(0, history.size() - 1 - shift);\n");
        sb.append("        return history.get(idx).high();\n");
        sb.append("    }\n\n");
        sb.append("    private double getLow(int shift) {\n");
        sb.append("        int idx = Math.max(0, history.size() - 1 - shift);\n");
        sb.append("        return history.get(idx).low();\n");
        sb.append("    }\n\n");
        sb.append("    private double getVolume(int shift) {\n");
        sb.append("        int idx = Math.max(0, history.size() - 1 - shift);\n");
        sb.append("        return Math.max(1.0, history.get(idx).volume());\n");
        sb.append("    }\n\n");
        sb.append("    private double calcSma(int period, int shift) {\n");
        sb.append("        int endIdx = history.size() - 1 - shift;\n");
        sb.append("        int startIdx = Math.max(0, endIdx - period + 1);\n");
        sb.append("        if (endIdx < startIdx) return 0.0;\n");
        sb.append("        double sum = 0.0;\n");
        sb.append("        for (int i = startIdx; i <= endIdx; i++) sum += history.get(i).close();\n");
        sb.append("        return sum / (endIdx - startIdx + 1);\n");
        sb.append("    }\n\n");
        sb.append("    private double calcEma(int period, int shift) {\n");
        sb.append("        int endIdx = history.size() - 1 - shift;\n");
        sb.append("        int startIdx = Math.max(0, endIdx - period * 2);\n");
        sb.append("        if (endIdx < startIdx) return 0.0;\n");
        sb.append("        double k = 2.0 / (period + 1.0);\n");
        sb.append("        double ema = history.get(startIdx).close();\n");
        sb.append("        for (int i = startIdx + 1; i <= endIdx; i++) ema = (history.get(i).close() - ema) * k + ema;\n");
        sb.append("        return ema;\n");
        sb.append("    }\n\n");
        sb.append("    private double calcStdDev(int period, int shift, double mean) {\n");
        sb.append("        int endIdx = history.size() - 1 - shift;\n");
        sb.append("        int startIdx = Math.max(0, endIdx - period + 1);\n");
        sb.append("        if (endIdx <= startIdx) return 0.0;\n");
        sb.append("        double sumSq = 0.0;\n");
        sb.append("        for (int i = startIdx; i <= endIdx; i++) { double d = history.get(i).close() - mean; sumSq += d * d; }\n");
        sb.append("        return Math.sqrt(sumSq / (endIdx - startIdx + 1));\n");
        sb.append("    }\n\n");
        sb.append("    private double calcRsi(int period, int shift) {\n");
        sb.append("        int endIdx = history.size() - 1 - shift;\n");
        sb.append("        int startIdx = Math.max(1, endIdx - period + 1);\n");
        sb.append("        if (endIdx < startIdx) return 50.0;\n");
        sb.append("        double gains = 0.0, losses = 0.0;\n");
        sb.append("        for (int i = startIdx; i <= endIdx; i++) {\n");
        sb.append("            double diff = history.get(i).close() - history.get(i - 1).close();\n");
        sb.append("            if (diff > 0) gains += diff; else losses -= diff;\n");
        sb.append("        }\n");
        sb.append("        if (losses == 0.0) return 100.0;\n");
        sb.append("        return 100.0 - (100.0 / (1.0 + gains / losses));\n");
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
        sb.append("    private double calcStochK(int period, int shift) {\n");
        sb.append("        double hh = calcHighestHigh(period, shift);\n");
        sb.append("        double ll = calcLowestLow(period, shift);\n");
        sb.append("        if (hh <= ll) return 50.0;\n");
        sb.append("        return ((getClose(shift) - ll) / (hh - ll)) * 100.0;\n");
        sb.append("    }\n\n");
        sb.append("    private double calcWilliamsR(int period, int shift) {\n");
        sb.append("        return calcStochK(period, shift) - 100.0;\n");
        sb.append("    }\n\n");
        sb.append("    private double calcCci(int period, int shift) {\n");
        sb.append("        double mean = calcSma(period, shift);\n");
        sb.append("        double sd = calcStdDev(period, shift, mean);\n");
        sb.append("        if (sd <= 0.0) return 0.0;\n");
        sb.append("        return (getClose(shift) - mean) / (0.015 * sd);\n");
        sb.append("    }\n\n");
        sb.append("    private double calcVwap(int period, int shift) {\n");
        sb.append("        int endIdx = history.size() - 1 - shift;\n");
        sb.append("        int startIdx = Math.max(0, endIdx - period + 1);\n");
        sb.append("        if (endIdx < startIdx) return getClose(shift);\n");
        sb.append("        double pv = 0.0, vol = 0.0;\n");
        sb.append("        for (int i = startIdx; i <= endIdx; i++) {\n");
        sb.append("            Bar b = history.get(i);\n");
        sb.append("            double v = Math.max(1.0, b.volume());\n");
        sb.append("            double typ = (b.high() + b.low() + b.close()) / 3.0;\n");
        sb.append("            pv += typ * v;\n");
        sb.append("            vol += v;\n");
        sb.append("        }\n");
        sb.append("        return vol > 0 ? pv / vol : getClose(shift);\n");
        sb.append("    }\n\n");
        sb.append("    private double calcAvgVolume(int period, int shift) {\n");
        sb.append("        int endIdx = history.size() - 1 - shift;\n");
        sb.append("        int startIdx = Math.max(0, endIdx - period + 1);\n");
        sb.append("        if (endIdx < startIdx) return 1.0;\n");
        sb.append("        double sum = 0.0;\n");
        sb.append("        for (int i = startIdx; i <= endIdx; i++) sum += Math.max(1.0, history.get(i).volume());\n");
        sb.append("        return sum / (endIdx - startIdx + 1);\n");
        sb.append("    }\n\n");
        sb.append("    private double calcLinRegSlope(int period, int shift) {\n");
        sb.append("        int n = Math.max(3, period);\n");
        sb.append("        int endIdx = history.size() - 1 - shift;\n");
        sb.append("        int startIdx = Math.max(0, endIdx - n + 1);\n");
        sb.append("        int count = endIdx - startIdx + 1;\n");
        sb.append("        if (count < 3) return 0.0;\n");
        sb.append("        double sumX = 0.0, sumY = 0.0, sumXY = 0.0, sumX2 = 0.0;\n");
        sb.append("        for (int i = 0; i < count; i++) {\n");
        sb.append("            double y = history.get(startIdx + i).close();\n");
        sb.append("            sumX += i; sumY += y; sumXY += i * y; sumX2 += (double) i * i;\n");
        sb.append("        }\n");
        sb.append("        double denom = count * sumX2 - sumX * sumX;\n");
        sb.append("        return Math.abs(denom) > 1e-9 ? (count * sumXY - sumX * sumY) / denom : 0.0;\n");
        sb.append("    }\n\n");
        sb.append("    private double calcEfficiencyRatio(int period, int shift) {\n");
        sb.append("        int endIdx = history.size() - 1 - shift;\n");
        sb.append("        int startIdx = Math.max(1, endIdx - period + 1);\n");
        sb.append("        if (endIdx <= startIdx) return 0.5;\n");
        sb.append("        double net = Math.abs(history.get(endIdx).close() - history.get(startIdx - 1).close());\n");
        sb.append("        double path = 0.0;\n");
        sb.append("        for (int i = startIdx; i <= endIdx; i++) {\n");
        sb.append("            path += Math.abs(history.get(i).close() - history.get(i - 1).close());\n");
        sb.append("        }\n");
        sb.append("        return path > 0.0 ? net / path : 0.0;\n");
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
        sb.append("        inTrade = false;\n");
        sb.append("        positionSide = null;\n");
        sb.append("        stopLoss = 0.0;\n");
        sb.append("        takeProfit = 0.0;\n");
        sb.append("        barsInTrade = 0;\n");
        sb.append("        clearArmed();\n");
        sb.append("    }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String renderRulesExpr(List<RuleGene> rules, boolean isLong) {
        List<String> parts = new ArrayList<>(rules.size());
        for (RuleGene r : rules) {
            int p1 = Math.max(2, r.period1());
            int p2 = Math.max(2, r.period2());
            int s1 = Math.max(0, r.shift1());
            int s2 = Math.max(0, r.shift2());
            double th = r.threshold();
            String expr = switch (r.blockType()) {
                case DONCHIAN_BREAKOUT -> {
                    int ls = Math.max(1, s1);
                    yield isLong ? "(getClose(0) > calcHighestHigh(" + p1 + ", " + ls + "))" : "(getClose(0) < calcLowestLow(" + p1 + ", " + ls + "))";
                }
                case MA_CROSSOVER -> isLong
                    ? "(calcEma(" + p1 + ", " + s1 + ") > calcSma(" + p2 + ", " + s2 + "))"
                    : "(calcEma(" + p1 + ", " + s1 + ") < calcSma(" + p2 + ", " + s2 + "))";
                case PRICE_VS_MA -> isLong
                    ? "(getClose(" + s1 + ") > calcEma(" + p1 + ", " + s1 + "))"
                    : "(getClose(" + s1 + ") < calcEma(" + p1 + ", " + s1 + "))";
                case SUPERTREND -> {
                    double mult = Math.abs(th) > 0.1 ? Math.abs(th) : 2.0;
                    String mStr = String.format(Locale.US, "%.2f", mult * 0.5);
                    yield isLong
                        ? "(getClose(0) > ((calcHighestHigh(" + p1 + ", 1) + calcLowestLow(" + p1 + ", 1)) * 0.5 + " + mStr + " * calcAtr(" + p1 + ", 1)))"
                        : "(getClose(0) < ((calcHighestHigh(" + p1 + ", 1) + calcLowestLow(" + p1 + ", 1)) * 0.5 - " + mStr + " * calcAtr(" + p1 + ", 1)))";
                }
                case BOLLINGER_BANDS -> {
                    double stdMult = Math.abs(th) > 0.1 ? Math.abs(th) : 2.0;
                    String sm = String.format(Locale.US, "%.2f", stdMult);
                    if (th >= 0) {
                        yield isLong
                            ? "(getClose(0) > (calcSma(" + p1 + ", 1) + " + sm + " * calcStdDev(" + p1 + ", 1, calcSma(" + p1 + ", 1))))"
                            : "(getClose(0) < (calcSma(" + p1 + ", 1) - " + sm + " * calcStdDev(" + p1 + ", 1, calcSma(" + p1 + ", 1))))";
                    } else {
                        yield isLong
                            ? "(getClose(0) < (calcSma(" + p1 + ", 1) - " + sm + " * calcStdDev(" + p1 + ", 1, calcSma(" + p1 + ", 1))))"
                            : "(getClose(0) > (calcSma(" + p1 + ", 1) + " + sm + " * calcStdDev(" + p1 + ", 1, calcSma(" + p1 + ", 1))))";
                    }
                }
                case RSI_FILTER -> {
                    String thHigh = String.format(Locale.US, "%.1f", th);
                    String thLow = String.format(Locale.US, "%.1f", 100.0 - th);
                    if (th >= 50.0) {
                        yield isLong ? "(calcRsi(" + p1 + ", " + s1 + ") > " + thHigh + ")" : "(calcRsi(" + p1 + ", " + s1 + ") < " + thLow + ")";
                    } else {
                        yield isLong ? "(calcRsi(" + p1 + ", " + s1 + ") < " + thHigh + ")" : "(calcRsi(" + p1 + ", " + s1 + ") > " + thLow + ")";
                    }
                }
                case ADX_REGIME -> {
                    String thAdx = String.format(Locale.US, "%.2f", th * 0.55);
                    yield "(calcAtr(" + p1 + ", " + s1 + ") > 0 && (((calcHighestHigh(" + p1 + ", " + s1 + ") - calcLowestLow(" + p1 + ", " + s1 + ")) / (calcAtr(" + p1 + ", " + s1 + ") * Math.sqrt(" + p1 + "))) * 15.0) >= " + thAdx + ")";
                }
                case ATR_EXPANSION -> {
                    String thAtr = String.format(Locale.US, "%.2f", th);
                    yield "(calcAtr(" + p2 + ", 1) > 0 && calcAtr(" + p1 + ", 0) > " + thAtr + " * calcAtr(" + p2 + ", 1))";
                }
                case DAILY_HIGH_LOW -> {
                    int ds1 = Math.max(1, s1);
                    int ds2 = Math.max(2, s2) + 8;
                    yield isLong
                        ? "(calcHighestHigh(24, " + ds1 + ") > calcHighestHigh(24, " + ds2 + "))"
                        : "(calcLowestLow(24, " + ds1 + ") < calcLowestLow(24, " + ds2 + "))";
                }
                case MOMENTUM_ROC -> {
                    String thRoc = String.format(Locale.US, "%.2f", th);
                    yield isLong
                        ? "(getClose(0) > getClose(Math.min(" + p1 + ", history.size() - 1)) + " + thRoc + " * calcAtr(14, 0))"
                        : "(getClose(0) < getClose(Math.min(" + p1 + ", history.size() - 1)) - " + thRoc + " * calcAtr(14, 0))";
                }
                case MACD_HISTOGRAM -> {
                    int fast = Math.min(p1, p2);
                    int slow = Math.max(fast + 6, Math.max(p1, p2));
                    yield isLong
                        ? "((calcEma(" + fast + ", " + s1 + ") - calcEma(" + slow + ", " + s1 + ")) > 0 && (calcEma(" + fast + ", " + s1 + ") - calcEma(" + slow + ", " + s1 + ")) > (calcEma(" + fast + ", " + (s1 + 1) + ") - calcEma(" + slow + ", " + (s1 + 1) + ")))"
                        : "((calcEma(" + fast + ", " + s1 + ") - calcEma(" + slow + ", " + s1 + ")) < 0 && (calcEma(" + fast + ", " + s1 + ") - calcEma(" + slow + ", " + s1 + ")) < (calcEma(" + fast + ", " + (s1 + 1) + ") - calcEma(" + slow + ", " + (s1 + 1) + ")))";
                }
                case STOCHASTIC_OSC -> {
                    String thHigh = String.format(Locale.US, "%.1f", th);
                    String thLow = String.format(Locale.US, "%.1f", 100.0 - th);
                    if (th >= 50.0) {
                        yield isLong ? "(calcStochK(" + p1 + ", " + s1 + ") > " + thHigh + ")" : "(calcStochK(" + p1 + ", " + s1 + ") < " + thLow + ")";
                    } else {
                        yield isLong ? "(calcStochK(" + p1 + ", " + s1 + ") < " + thHigh + ")" : "(calcStochK(" + p1 + ", " + s1 + ") > " + thLow + ")";
                    }
                }
                case KELTNER_CHANNEL -> {
                    double mult = Math.abs(th) > 0.2 ? Math.abs(th) : 1.8;
                    String mStr = String.format(Locale.US, "%.2f", mult);
                    yield isLong
                        ? "(getClose(0) > (calcEma(" + p1 + ", 1) + " + mStr + " * calcAtr(" + p1 + ", 1)))"
                        : "(getClose(0) < (calcEma(" + p1 + ", 1) - " + mStr + " * calcAtr(" + p1 + ", 1)))";
                }
                case BB_KC_SQUEEZE -> {
                    double mult = Math.abs(th) > 0.2 ? Math.abs(th) : 1.8;
                    String mStr = String.format(Locale.US, "%.2f", mult);
                    yield isLong
                        ? "((2.0 * calcStdDev(" + p1 + ", 1, calcSma(" + p1 + ", 1))) < (" + mStr + " * 1.35 * calcAtr(" + p1 + ", 1)) && getClose(0) > calcEma(" + p1 + ", 0))"
                        : "((2.0 * calcStdDev(" + p1 + ", 1, calcSma(" + p1 + ", 1))) < (" + mStr + " * 1.35 * calcAtr(" + p1 + ", 1)) && getClose(0) < calcEma(" + p1 + ", 0))";
                }
                case CCI_OSCILLATOR -> {
                    double lvl = Math.max(40.0, Math.abs(th));
                    String lvlStr = String.format(Locale.US, "%.1f", lvl);
                    if (th >= 0) {
                        yield isLong ? "(calcCci(" + p1 + ", " + s1 + ") > " + lvlStr + ")" : "(calcCci(" + p1 + ", " + s1 + ") < -" + lvlStr + ")";
                    } else {
                        yield isLong ? "(calcCci(" + p1 + ", " + s1 + ") < -" + lvlStr + ")" : "(calcCci(" + p1 + ", " + s1 + ") > " + lvlStr + ")";
                    }
                }
                case WILLIAMS_R -> {
                    String wrLongLvl = String.format(Locale.US, "-%.1f", 100.0 - th);
                    String wrShortLvl = String.format(Locale.US, "-%.1f", th);
                    if (th >= 50.0) {
                        yield isLong ? "(calcWilliamsR(" + p1 + ", " + s1 + ") > " + wrLongLvl + ")" : "(calcWilliamsR(" + p1 + ", " + s1 + ") < " + wrShortLvl + ")";
                    } else {
                        yield isLong ? "(calcWilliamsR(" + p1 + ", " + s1 + ") < " + wrLongLvl + ")" : "(calcWilliamsR(" + p1 + ", " + s1 + ") > " + wrShortLvl + ")";
                    }
                }
                case VWAP_ANCHOR -> isLong
                    ? "(getClose(0) > calcVwap(" + p1 + ", " + s1 + "))"
                    : "(getClose(0) < calcVwap(" + p1 + ", " + s1 + "))";
                case VOLUME_SURGE -> {
                    String vMult = String.format(Locale.US, "%.2f", Math.max(1.05, th));
                    yield "(getVolume(0) > " + vMult + " * calcAvgVolume(" + p1 + ", 1))";
                }
                case ENGULFING_PATTERN -> {
                    int ps = Math.max(1, s1);
                    String bodyMult = String.format(Locale.US, "%.2f", Math.max(0.15, Math.min(0.8, th)));
                    yield isLong
                        ? "(getClose(0) > getOpen(0) && getClose(0) > getHigh(" + ps + ") && (getClose(0) - getOpen(0)) >= " + bodyMult + " * calcAtr(14, 0))"
                        : "(getClose(0) < getOpen(0) && getClose(0) < getLow(" + ps + ") && (getOpen(0) - getClose(0)) >= " + bodyMult + " * calcAtr(14, 0))";
                }
                case INSIDE_BAR_COIL -> {
                    int cs = Math.max(1, s1);
                    yield isLong
                        ? "((getHigh(" + cs + ") - getLow(" + cs + ")) < 0.85 * calcAtr(14, " + cs + ") && getClose(0) > getHigh(" + cs + "))"
                        : "((getHigh(" + cs + ") - getLow(" + cs + ")) < 0.85 * calcAtr(14, " + cs + ") && getClose(0) < getLow(" + cs + "))";
                }
                case LINEAR_REG_SLOPE -> {
                    String sMult = String.format(Locale.US, "%.3f", Math.max(0.02, th * 0.12));
                    yield isLong
                        ? "(calcLinRegSlope(" + p1 + ", " + s1 + ") > " + sMult + " * calcAtr(14, 0))"
                        : "(calcLinRegSlope(" + p1 + ", " + s1 + ") < -" + sMult + " * calcAtr(14, 0))";
                }
                case ZSCORE_EXTREME -> {
                    double z = Math.max(0.8, Math.abs(th));
                    String zStr = String.format(Locale.US, "%.2f", z);
                    if (th < 0) {
                        yield isLong
                            ? "(calcStdDev(" + p1 + ", " + s1 + ", calcSma(" + p1 + ", " + s1 + ")) > 0 && ((getClose(" + s1 + ") - calcSma(" + p1 + ", " + s1 + ")) / calcStdDev(" + p1 + ", " + s1 + ", calcSma(" + p1 + ", " + s1 + "))) < -" + zStr + ")"
                            : "(calcStdDev(" + p1 + ", " + s1 + ", calcSma(" + p1 + ", " + s1 + ")) > 0 && ((getClose(" + s1 + ") - calcSma(" + p1 + ", " + s1 + ")) / calcStdDev(" + p1 + ", " + s1 + ", calcSma(" + p1 + ", " + s1 + "))) > " + zStr + ")";
                    } else {
                        yield isLong
                            ? "(calcStdDev(" + p1 + ", " + s1 + ", calcSma(" + p1 + ", " + s1 + ")) > 0 && ((getClose(" + s1 + ") - calcSma(" + p1 + ", " + s1 + ")) / calcStdDev(" + p1 + ", " + s1 + ", calcSma(" + p1 + ", " + s1 + "))) > " + zStr + ")"
                            : "(calcStdDev(" + p1 + ", " + s1 + ", calcSma(" + p1 + ", " + s1 + ")) > 0 && ((getClose(" + s1 + ") - calcSma(" + p1 + ", " + s1 + ")) / calcStdDev(" + p1 + ", " + s1 + ", calcSma(" + p1 + ", " + s1 + "))) < -" + zStr + ")";
                    }
                }
                case CHOPPINESS_FILTER -> {
                    String erStr = String.format(Locale.US, "%.2f", Math.clamp(th, 0.20, 0.70));
                    yield "(calcEfficiencyRatio(" + p1 + ", " + s1 + ") >= " + erStr + ")";
                }
                case HIGHER_HIGHS_SWING -> {
                    int sw = Math.max(4, Math.min(20, p1 / 2));
                    yield isLong
                        ? "(calcHighestHigh(" + sw + ", 1) > calcHighestHigh(" + sw + ", " + (sw + 1) + ") && calcLowestLow(" + sw + ", 1) > calcLowestLow(" + sw + ", " + (sw + 1) + "))"
                        : "(calcLowestLow(" + sw + ", 1) < calcLowestLow(" + sw + ", " + (sw + 1) + ") && calcHighestHigh(" + sw + ", 1) < calcHighestHigh(" + sw + ", " + (sw + 1) + "))";
                }
            };
            parts.add(expr);
        }
        return String.join(" && ", parts);
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
                if (positionSide == Order.Side.BUY) {
                    if (bar.low() <= stopLoss || bar.high() >= takeProfit
                        || (genome.maxBarsInTrade() > 0 && barsInTrade >= genome.maxBarsInTrade())) {
                        closePosition(bar);
                        return;
                    }
                    if (genome.trailingAtrMult() > 0) {
                        double candidateSl = quantize(bar.close() - genome.trailingAtrMult() * atr);
                        if (candidateSl > stopLoss) {
                            stopLoss = candidateSl;
                        }
                    }
                } else if (positionSide == Order.Side.SELL) {
                    if (bar.high() >= stopLoss || bar.low() <= takeProfit
                        || (genome.maxBarsInTrade() > 0 && barsInTrade >= genome.maxBarsInTrade())) {
                        closePosition(bar);
                        return;
                    }
                    if (genome.trailingAtrMult() > 0) {
                        double candidateSl = quantize(bar.close() + genome.trailingAtrMult() * atr);
                        if (candidateSl < stopLoss) {
                            stopLoss = candidateSl;
                        }
                    }
                }
                return;
            }

            if (armedSide != null && armedBarsRemaining > 0) {
                if (armedSide == Order.Side.BUY && bar.high() >= armedStopPrice) {
                    executeEntry(Order.Side.BUY, bar.close(), armedSlDist, armedPtDist);
                    clearArmed();
                    return;
                } else if (armedSide == Order.Side.SELL && bar.low() <= armedStopPrice) {
                    executeEntry(Order.Side.SELL, bar.close(), armedSlDist, armedPtDist);
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
                        return;
                    }
                } else {
                    executeEntry(Order.Side.BUY, bar.close(), slDist, ptDist);
                    return;
                }
            } else if (allowShort && evaluateRules(genome.shortRules(), false)) {
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
                    double netRange = calcHighestHigh(p1, s1) - calcLowestLow(p1, s1);
                    double ratio = atrFast > 0 ? (netRange / (atrFast * Math.sqrt(p1))) * 15.0 : 0.0;
                    yield ratio >= (th * 0.55);
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
                case MACD_HISTOGRAM -> {
                    int fast = Math.min(p1, p2);
                    int slow = Math.max(fast + 6, Math.max(p1, p2));
                    double macd0 = calcEma(fast, s1) - calcEma(slow, s1);
                    double macd1 = calcEma(fast, s1 + 1) - calcEma(slow, s1 + 1);
                    yield isLong ? (macd0 > 0 && macd0 > macd1) : (macd0 < 0 && macd0 < macd1);
                }
                case STOCHASTIC_OSC -> {
                    double k = calcStochK(p1, s1);
                    if (th >= 50.0) {
                        yield isLong ? k > th : k < (100.0 - th);
                    } else {
                        yield isLong ? k < th : k > (100.0 - th);
                    }
                }
                case KELTNER_CHANNEL -> {
                    double mult = Math.abs(th) > 0.2 ? Math.abs(th) : 1.8;
                    double mid = calcEma(p1, 1);
                    double atr = calcAtr(p1, 1);
                    yield isLong ? getClose(0) > (mid + mult * atr) : getClose(0) < (mid - mult * atr);
                }
                case BB_KC_SQUEEZE -> {
                    double mult = Math.abs(th) > 0.2 ? Math.abs(th) : 1.8;
                    double mean = calcSma(p1, 1);
                    double std = calcStdDev(p1, 1, mean);
                    double atr = calcAtr(p1, 1);
                    boolean squeezed = (2.0 * std) < (mult * 1.35 * atr);
                    double ema0 = calcEma(p1, 0);
                    yield squeezed && (isLong ? getClose(0) > ema0 : getClose(0) < ema0);
                }
                case CCI_OSCILLATOR -> {
                    double cci = calcCci(p1, s1);
                    double lvl = Math.max(40.0, Math.abs(th));
                    if (th >= 0) {
                        yield isLong ? cci > lvl : cci < -lvl;
                    } else {
                        yield isLong ? cci < -lvl : cci > lvl;
                    }
                }
                case WILLIAMS_R -> {
                    double wr = calcWilliamsR(p1, s1);
                    double wrLongLvl = -(100.0 - th);
                    double wrShortLvl = -th;
                    if (th >= 50.0) {
                        yield isLong ? wr > wrLongLvl : wr < wrShortLvl;
                    } else {
                        yield isLong ? wr < wrLongLvl : wr > wrShortLvl;
                    }
                }
                case VWAP_ANCHOR -> {
                    double vwap = calcVwap(p1, s1);
                    yield isLong ? getClose(0) > vwap : getClose(0) < vwap;
                }
                case VOLUME_SURGE -> {
                    double vMult = Math.max(1.05, th);
                    yield getVolume(0) > vMult * calcAvgVolume(p1, 1);
                }
                case ENGULFING_PATTERN -> {
                    int ps = Math.max(1, s1);
                    double bodyMult = Math.max(0.15, Math.min(0.8, th));
                    double atr = calcAtr(14, 0);
                    yield isLong
                        ? (getClose(0) > getOpen(0) && getClose(0) > getHigh(ps) && (getClose(0) - getOpen(0)) >= bodyMult * atr)
                        : (getClose(0) < getOpen(0) && getClose(0) < getLow(ps) && (getOpen(0) - getClose(0)) >= bodyMult * atr);
                }
                case INSIDE_BAR_COIL -> {
                    int cs = Math.max(1, s1);
                    boolean coiled = (getHigh(cs) - getLow(cs)) < 0.85 * calcAtr(14, cs);
                    yield coiled && (isLong ? getClose(0) > getHigh(cs) : getClose(0) < getLow(cs));
                }
                case LINEAR_REG_SLOPE -> {
                    double sMult = Math.max(0.02, th * 0.12);
                    double slope = calcLinRegSlope(p1, s1);
                    double atr = calcAtr(14, 0);
                    yield isLong ? slope > sMult * atr : slope < -sMult * atr;
                }
                case ZSCORE_EXTREME -> {
                    double z = Math.max(0.8, Math.abs(th));
                    double mean = calcSma(p1, s1);
                    double sd = calcStdDev(p1, s1, mean);
                    if (sd <= 0.0) yield false;
                    double zVal = (getClose(s1) - mean) / sd;
                    if (th < 0) {
                        yield isLong ? zVal < -z : zVal > z;
                    } else {
                        yield isLong ? zVal > z : zVal < -z;
                    }
                }
                case CHOPPINESS_FILTER -> {
                    double er = calcEfficiencyRatio(p1, s1);
                    yield er >= Math.clamp(th, 0.20, 0.70);
                }
                case HIGHER_HIGHS_SWING -> {
                    int sw = Math.max(4, Math.min(20, p1 / 2));
                    yield isLong
                        ? (calcHighestHigh(sw, 1) > calcHighestHigh(sw, sw + 1) && calcLowestLow(sw, 1) > calcLowestLow(sw, sw + 1))
                        : (calcLowestLow(sw, 1) < calcLowestLow(sw, sw + 1) && calcHighestHigh(sw, 1) < calcHighestHigh(sw, sw + 1));
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
                .withStrategyId(genome.id())
                .asCloseOnly();
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

        private double getOpen(int shift) {
            int idx = Math.max(0, history.size() - 1 - shift);
            return history.get(idx).open();
        }

        private double getHigh(int shift) {
            int idx = Math.max(0, history.size() - 1 - shift);
            return history.get(idx).high();
        }

        private double getLow(int shift) {
            int idx = Math.max(0, history.size() - 1 - shift);
            return history.get(idx).low();
        }

        private double getVolume(int shift) {
            int idx = Math.max(0, history.size() - 1 - shift);
            return Math.max(1.0, history.get(idx).volume());
        }

        private double calcStochK(int period, int shift) {
            double hh = calcHighestHigh(period, shift);
            double ll = calcLowestLow(period, shift);
            if (hh <= ll) return 50.0;
            return ((getClose(shift) - ll) / (hh - ll)) * 100.0;
        }

        private double calcWilliamsR(int period, int shift) {
            return calcStochK(period, shift) - 100.0;
        }

        private double calcCci(int period, int shift) {
            double mean = calcSma(period, shift);
            double sd = calcStdDev(period, shift, mean);
            if (sd <= 0.0) return 0.0;
            return (getClose(shift) - mean) / (0.015 * sd);
        }

        private double calcVwap(int period, int shift) {
            int endIdx = history.size() - 1 - shift;
            int startIdx = Math.max(0, endIdx - period + 1);
            if (endIdx < startIdx) return getClose(shift);
            double pv = 0.0;
            double vol = 0.0;
            for (int i = startIdx; i <= endIdx; i++) {
                Bar b = history.get(i);
                double v = Math.max(1.0, b.volume());
                double typ = (b.high() + b.low() + b.close()) / 3.0;
                pv += typ * v;
                vol += v;
            }
            return vol > 0 ? pv / vol : getClose(shift);
        }

        private double calcAvgVolume(int period, int shift) {
            int endIdx = history.size() - 1 - shift;
            int startIdx = Math.max(0, endIdx - period + 1);
            if (endIdx < startIdx) return 1.0;
            double sum = 0.0;
            for (int i = startIdx; i <= endIdx; i++) {
                sum += Math.max(1.0, history.get(i).volume());
            }
            return sum / (endIdx - startIdx + 1);
        }

        private double calcLinRegSlope(int period, int shift) {
            int n = Math.max(3, period);
            int endIdx = history.size() - 1 - shift;
            int startIdx = Math.max(0, endIdx - n + 1);
            int count = endIdx - startIdx + 1;
            if (count < 3) return 0.0;
            double sumX = 0.0;
            double sumY = 0.0;
            double sumXY = 0.0;
            double sumX2 = 0.0;
            for (int i = 0; i < count; i++) {
                double y = history.get(startIdx + i).close();
                sumX += i;
                sumY += y;
                sumXY += i * y;
                sumX2 += (double) i * i;
            }
            double denom = count * sumX2 - sumX * sumX;
            return Math.abs(denom) > 1e-9 ? (count * sumXY - sumX * sumY) / denom : 0.0;
        }

        private double calcEfficiencyRatio(int period, int shift) {
            int endIdx = history.size() - 1 - shift;
            int startIdx = Math.max(1, endIdx - period + 1);
            if (endIdx <= startIdx) return 0.5;
            double net = Math.abs(history.get(endIdx).close() - history.get(startIdx - 1).close());
            double path = 0.0;
            for (int i = startIdx; i <= endIdx; i++) {
                path += Math.abs(history.get(i).close() - history.get(i - 1).close());
            }
            return path > 0.0 ? net / path : 0.0;
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
