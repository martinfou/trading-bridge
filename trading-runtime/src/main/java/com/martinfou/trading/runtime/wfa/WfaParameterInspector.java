package com.martinfou.trading.runtime.wfa;

import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.runtime.generator.StrategyGeneratorService;
import com.martinfou.trading.runtime.generator.StrategyGenome;
import com.martinfou.trading.strategies.StrategyCatalog;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Introspects any strategy in {@link StrategyCatalog} to discover its real optimizable
 * parameters, default values, human-readable labels, and sensible grid search ranges.
 */
public final class WfaParameterInspector {

    private static final Set<String> IGNORED_RUNTIME_FIELDS = Set.of(
        "inTrade", "inPosition", "barsInTrade", "barsHeld", "entryPrice",
        "stopLoss", "takeProfit", "armedStopPrice", "armedSlDist", "armedPtDist",
        "armedBarsRemaining", "positionSize", "quantity", "quantityUnits", "units",
        "referenceCapital", "riskPct", "activeSl", "activeTp", "lastTradeDay",
        "currentDay", "dailyTrades", "tradesToday", "cooldownBars", "highSinceEntry",
        "lowSinceEntry", "peakProfit", "lotSize", "minTradeBarIndex", "barIndex",
        "lastClose", "prevClose", "rollingTrSum", "trCount"
    );

    public record StrategyParamMetadata(
        String name,
        String label,
        String category,
        double defaultValue,
        double min,
        double max,
        double step,
        boolean activeByDefault
    ) {}

    private WfaParameterInspector() {}

    public static List<StrategyParamMetadata> inspect(String strategyId) {
        if (strategyId == null || strategyId.isBlank()) {
            return defaultFallbackMetadata();
        }
        if (!StrategyCatalog.contains(strategyId)) {
            StrategyGeneratorService.ensureCatalogLoaded();
        }
        if (!StrategyCatalog.contains(strategyId)) {
            return defaultFallbackMetadata();
        }

        try {
            String sym = StrategyCatalog.defaultSymbol(strategyId);
            Strategy instance = StrategyCatalog.create(strategyId, sym != null ? sym : "EUR_USD");
            Strategy target = unwrapDelegate(instance);

            if (target instanceof StrategyGenome.GenomeExecutableStrategy genomeStrat) {
                return inspectGenomeStrategy(genomeStrat.genome());
            }

            List<StrategyParamMetadata> reflected = inspectReflectedFields(target);
            if (!reflected.isEmpty()) {
                return reflected;
            }
        } catch (Exception ignored) {
            // Fall through to universal envelope metadata
        }

        return defaultFallbackMetadata();
    }

    private static Strategy unwrapDelegate(Strategy strategy) {
        Strategy current = strategy;
        while (current != null) {
            try {
                Field f = findField(current.getClass(), "delegate");
                if (f != null) {
                    f.setAccessible(true);
                    Object val = f.get(current);
                    if (val instanceof Strategy next) {
                        current = next;
                        continue;
                    }
                }
            } catch (Exception ignored) {}
            break;
        }
        return current;
    }

    private static List<StrategyParamMetadata> inspectGenomeStrategy(StrategyGenome genome) {
        List<StrategyParamMetadata> list = new ArrayList<>();
        List<StrategyGenome.RuleGene> rules = (genome.longRules() != null && !genome.longRules().isEmpty())
            ? genome.longRules()
            : genome.shortRules();

        if (rules != null && !rules.isEmpty()) {
            StrategyGenome.RuleGene r0 = rules.getFirst();
            int p1 = Math.max(4, r0.period1());
            int step1 = Math.max(2, roundToNiceStep(p1 * 0.25));
            int min1 = Math.max(4, p1 - step1 * 2);
            int max1 = p1 + step1 * 2;
            list.add(new StrategyParamMetadata(
                "period1",
                formatBlockLabel(r0.blockType()) + " Period",
                "SIGNAL",
                p1,
                min1,
                max1,
                step1,
                true
            ));

            boolean r0UsesP2 = (r0.blockType() == StrategyGenome.BlockType.MA_CROSSOVER
                || r0.blockType() == StrategyGenome.BlockType.ATR_EXPANSION
                || r0.blockType() == StrategyGenome.BlockType.MACD_HISTOGRAM);

            if (r0UsesP2 || rules.size() > 1) {
                int p2 = r0UsesP2 ? Math.max(10, r0.period2()) : Math.max(6, rules.get(1).period1());
                String label2 = r0UsesP2
                    ? formatBlockLabel(r0.blockType()) + " Slow Period"
                    : formatBlockLabel(rules.get(1).blockType()) + " Period";
                int step2 = Math.max(3, roundToNiceStep(p2 * 0.25));
                int min2 = Math.max(6, p2 - step2 * 2);
                int max2 = p2 + step2 * 2;
                list.add(new StrategyParamMetadata(
                    "period2",
                    label2,
                    "SIGNAL",
                    p2,
                    min2,
                    max2,
                    step2,
                    true
                ));
            }
        }

        double sl = round1(Math.max(0.8, genome.slAtrMult()));
        double slStep = 0.4;
        double slMin = round1(Math.max(0.8, sl - slStep));
        double slMax = round1(sl + slStep);
        list.add(new StrategyParamMetadata(
            "slAtrMult",
            "Stop Loss (× ATR)",
            "RISK",
            sl,
            slMin,
            slMax,
            slStep,
            true
        ));

        double tp = round1(Math.max(1.2, genome.tpAtrMult()));
        double tpStep = 0.8;
        double tpMin = round1(Math.max(1.2, tp - tpStep));
        double tpMax = round1(tp + tpStep);
        list.add(new StrategyParamMetadata(
            "tpAtrMult",
            "Take Profit (× ATR)",
            "RISK",
            tp,
            tpMin,
            tpMax,
            tpStep,
            list.size() < 3
        ));

        int atrP = Math.max(5, genome.atrPeriod());
        list.add(new StrategyParamMetadata(
            "atrPeriod",
            "ATR Volatility Period",
            "RISK",
            atrP,
            Math.max(6, atrP - 4),
            atrP + 6,
            5,
            false
        ));

        if (genome.maxBarsInTrade() > 0) {
            int mb = genome.maxBarsInTrade();
            int mbStep = Math.max(5, roundToNiceStep(mb * 0.25));
            list.add(new StrategyParamMetadata(
                "maxBarsInTrade",
                "Max Bars in Trade",
                "EXIT",
                mb,
                Math.max(10, mb - mbStep),
                mb + mbStep,
                mbStep,
                false
            ));
        }

        return list;
    }

    private static List<StrategyParamMetadata> inspectReflectedFields(Strategy target) {
        List<StrategyParamMetadata> list = new ArrayList<>();
        Class<?> curr = target.getClass();
        while (curr != null && curr != Object.class) {
            for (Field f : curr.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isFinal(mod)) {
                    continue;
                }
                String name = f.getName();
                if (IGNORED_RUNTIME_FIELDS.contains(name) || name.startsWith("this$")) {
                    continue;
                }
                Class<?> t = f.getType();
                if (t != int.class && t != Integer.class && t != double.class && t != Double.class
                    && t != long.class && t != Long.class && t != float.class && t != Float.class) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object rawVal = f.get(target);
                    if (!(rawVal instanceof Number num)) {
                        continue;
                    }
                    double val = num.doubleValue();
                    if (!Double.isFinite(val) || val <= 0) {
                        continue;
                    }
                    boolean isInt = (t == int.class || t == Integer.class || t == long.class || t == Long.class);
                    double min;
                    double max;
                    double step;
                    if (isInt) {
                        int iv = Math.max(2, (int) Math.round(val));
                        int st = Math.max(1, roundToNiceStep(iv * 0.25));
                        min = Math.max(2, iv - st * 2);
                        max = iv + st * 2;
                        step = st;
                    } else {
                        double dv = round2(val);
                        double st = round2(Math.max(0.1, dv * 0.25));
                        min = round2(Math.max(0.1, dv - st));
                        max = round2(dv + st);
                        step = st;
                    }
                    String category = (name.toLowerCase().contains("sl") || name.toLowerCase().contains("tp") || name.toLowerCase().contains("atr") || name.toLowerCase().contains("stop"))
                        ? "RISK"
                        : "SIGNAL";
                    list.add(new StrategyParamMetadata(
                        name,
                        humanizeFieldName(name),
                        category,
                        isInt ? Math.round(val) : round2(val),
                        min,
                        max,
                        step,
                        list.size() < 3
                    ));
                } catch (Exception ignored) {}
            }
            curr = curr.getSuperclass();
        }
        return list;
    }

    private static List<StrategyParamMetadata> defaultFallbackMetadata() {
        return List.of(
            new StrategyParamMetadata("slScale", "Stop-Loss Distance Scale (×)", "RISK", 1.0, 0.75, 1.25, 0.25, true),
            new StrategyParamMetadata("tpScale", "Take-Profit Distance Scale (×)", "RISK", 1.0, 0.75, 1.50, 0.25, true),
            new StrategyParamMetadata("warmupBars", "Signal Warmup Filter (Bars)", "FILTER", 20, 10, 40, 15, false)
        );
    }

    private static Field findField(Class<?> clazz, String name) {
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static String formatBlockLabel(StrategyGenome.BlockType bt) {
        if (bt == null) return "Signal";
        return switch (bt) {
            case DONCHIAN_BREAKOUT -> "Donchian Channel";
            case MA_CROSSOVER -> "EMA Fast";
            case PRICE_VS_MA -> "EMA Trend";
            case SUPERTREND -> "SuperTrend";
            case BOLLINGER_BANDS -> "Bollinger Bands";
            case RSI_FILTER -> "RSI";
            case ADX_REGIME -> "ADX Regime";
            case ATR_EXPANSION -> "ATR Fast";
            case DAILY_HIGH_LOW -> "Daily High/Low";
            case MOMENTUM_ROC -> "Momentum ROC";
            case MACD_HISTOGRAM -> "MACD Fast";
            case STOCHASTIC_OSC -> "Stochastic %K";
            case KELTNER_CHANNEL -> "Keltner Channel";
            case BB_KC_SQUEEZE -> "TTM Squeeze";
            case CCI_OSCILLATOR -> "CCI";
            case WILLIAMS_R -> "Williams %R";
            case VWAP_ANCHOR -> "Rolling VWAP";
            case VOLUME_SURGE -> "Volume MA";
            case ENGULFING_PATTERN -> "Engulfing Lookback";
            case INSIDE_BAR_COIL -> "Inside Bar";
            case LINEAR_REG_SLOPE -> "LinReg Slope";
            case ZSCORE_EXTREME -> "Z-Score";
            case CHOPPINESS_FILTER -> "Efficiency Ratio";
            case HIGHER_HIGHS_SWING -> "Swing Lookback";
        };
    }

    private static String humanizeFieldName(String camel) {
        if (camel == null || camel.isEmpty()) return "Parameter";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < camel.length(); i++) {
            char c = camel.charAt(i);
            if (i == 0) {
                sb.append(Character.toUpperCase(c));
            } else if (Character.isUpperCase(c)) {
                sb.append(' ').append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString()
            .replace("Atr ", "ATR ")
            .replace("Ema ", "EMA ")
            .replace("Sma ", "SMA ")
            .replace("Rsi ", "RSI ")
            .replace("Bb ", "Bollinger ")
            .replace("Kc ", "Keltner ")
            .replace("Sl ", "Stop-Loss ")
            .replace("Tp ", "Take-Profit ");
    }

    private static int roundToNiceStep(double raw) {
        if (raw <= 2) return 2;
        if (raw <= 4) return 3;
        if (raw <= 7) return 5;
        if (raw <= 12) return 10;
        if (raw <= 20) return 15;
        return 20;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
