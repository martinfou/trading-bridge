package com.martinfou.trading.parser.indicators;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.parser.config.StrategyConfig;
import com.martinfou.trading.parser.sq.SqXmlItem;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Dispatches SQ indicator Item keys to evaluators (stories 2-4, 2-5). */
public final class SqIndicatorRegistry {

    @FunctionalInterface
    public interface Evaluator {
        Optional<Double> evaluate(SqXmlItem item, List<Bar> bars, StrategyConfig config);
    }

    private static final Map<String, Evaluator> EVALUATORS = buildEvaluators();

    private SqIndicatorRegistry() {}

    public static boolean supports(String itemKey) {
        return itemKey != null && EVALUATORS.containsKey(itemKey);
    }

    public static Optional<Double> evaluate(
        SqXmlItem item,
        List<Bar> bars,
        StrategyConfig config
    ) {
        if (item == null) {
            return Optional.empty();
        }
        Evaluator evaluator = EVALUATORS.get(item.key());
        if (evaluator == null) {
            return Optional.empty();
        }
        return evaluator.evaluate(item, bars, config);
    }

    public static Optional<Double> evaluate(SqXmlItem item, List<Bar> bars) {
        return evaluate(item, bars, null);
    }

    private static Optional<Double> toOptional(double value) {
        return Double.isNaN(value) ? Optional.empty() : Optional.of(value);
    }

    private static Map<String, Evaluator> buildEvaluators() {
        Map<String, Evaluator> map = new HashMap<>();
        map.put("SMA", (item, bars, config) -> toOptional(
            SqCoreIndicators.sma(bars, SqIndicatorParams.from(item, SqIndicatorParams.SqParameterResolver.from(config)))));
        map.put("EMA", (item, bars, config) -> toOptional(
            SqCoreIndicators.ema(bars, SqIndicatorParams.from(item, SqIndicatorParams.SqParameterResolver.from(config)))));
        map.put("RSI", (item, bars, config) -> toOptional(
            SqCoreIndicators.rsi(bars, SqIndicatorParams.from(item, SqIndicatorParams.SqParameterResolver.from(config)))));
        map.put("ATR", (item, bars, config) -> toOptional(
            SqExtendedIndicators.atr(bars, SqIndicatorParams.from(item, SqIndicatorParams.SqParameterResolver.from(config)))));
        map.put("BBRange", (item, bars, config) -> toOptional(
            SqExtendedIndicators.bbRange(bars, SqBollingerParams.from(item, config))));
        map.put("BollingerBands", (item, bars, config) -> toOptional(
            SqExtendedIndicators.bollingerMiddle(bars, SqBollingerParams.from(item, config))));
        map.put("MACD", (item, bars, config) -> toOptional(
            SqExtendedIndicators.macdLine(bars, SqMacdParams.from(item, config))));
        return Map.copyOf(map);
    }
}
