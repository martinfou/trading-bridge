package com.martinfou.trading.parser.conditions;

import com.martinfou.trading.parser.sq.SqXmlItem;

import java.util.Optional;

/** Evaluates boolean SQ Item trees (story 2-6). */
public final class SqConditionEvaluator {

    private SqConditionEvaluator() {}

    public static Optional<Boolean> evaluate(SqXmlItem item, SqEvaluationContext context) {
        if (item == null || context == null) {
            return Optional.empty();
        }
        if (!"boolean".equals(item.returnType())) {
            return Optional.empty();
        }
        return SqConditionRegistry.evaluate(item, context);
    }
}
