package com.martinfou.trading.parser.conditions;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.parser.config.StrategyConfig;
import com.martinfou.trading.parser.sq.SqStrategyDocument;
import com.martinfou.trading.parser.sq.SqXmlEvent;
import com.martinfou.trading.parser.sq.SqXmlRule;
import com.martinfou.trading.parser.sq.SqXmlSignal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Evaluates OnBarUpdate Signal rule trees into a UUID → boolean map (story 2-6). */
public final class SqSignalEvaluator {

    private SqSignalEvaluator() {}

    public static Map<String, Boolean> evaluate(
        SqStrategyDocument document,
        List<Bar> bars,
        StrategyConfig config
    ) {
        Map<String, Boolean> results = new LinkedHashMap<>();
        if (document == null || bars == null || bars.isEmpty()) {
            return Map.copyOf(results);
        }
        for (SqXmlEvent event : document.events()) {
            if (!"OnBarUpdate".equals(event.key())) {
                continue;
            }
            for (SqXmlRule rule : event.rules()) {
                if (!"Signal".equals(rule.type())) {
                    continue;
                }
                for (SqXmlSignal signal : rule.signals()) {
                    SqEvaluationContext context = SqEvaluationContext.of(bars, config, results);
                    Optional<Boolean> value = SqConditionEvaluator.evaluate(signal.rootItem(), context);
                    value.ifPresent(v -> results.put(signal.variableId(), v));
                }
            }
        }
        return Map.copyOf(results);
    }
}
