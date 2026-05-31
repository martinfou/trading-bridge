package com.martinfou.trading.parser.conditions;

import com.martinfou.trading.parser.config.StrategyParameter;
import com.martinfou.trading.parser.sq.SqXmlItem;

import java.util.Optional;

/** Built-in SQ boolean operator implementations (story 2-6). */
final class SqConditionOperators {

    private SqConditionOperators() {}

    static Optional<Boolean> and(SqXmlItem item, SqEvaluationContext context) {
        var children = SqBlockUtils.childItems(item);
        if (children.isEmpty()) {
            return Optional.of(false);
        }
        for (SqXmlItem child : children) {
            Optional<Boolean> value = SqConditionEvaluator.evaluate(child, context);
            if (value.isEmpty() || !value.get()) {
                return Optional.of(false);
            }
        }
        return Optional.of(true);
    }

    static Optional<Boolean> not(SqXmlItem item, SqEvaluationContext context) {
        Optional<SqXmlItem> valueItem = SqBlockUtils.blockItem(item, "#Value#");
        if (valueItem.isEmpty()) {
            return Optional.empty();
        }
        return SqConditionEvaluator.evaluate(valueItem.get(), context).map(v -> !v);
    }

    static Optional<Boolean> booleanConstant(SqXmlItem item, SqEvaluationContext context) {
        return SqBlockUtils.paramText(item, "#Value#").flatMap(name -> {
            if (context.config() == null) {
                return parseLiteral(name);
            }
            return context.config().parameter(name)
                .map(StrategyParameter::booleanValue)
                .or(() -> parseLiteral(name));
        });
    }

    static Optional<Boolean> booleanVariable(SqXmlItem item, SqEvaluationContext context) {
        return SqBlockUtils.paramText(item, "#Variable#").flatMap(context::signalResult);
    }

    static Optional<Boolean> marketPositionIsLong(SqXmlItem item, SqEvaluationContext context) {
        return Optional.of(context.longPositionOpen());
    }

    static Optional<Boolean> marketPositionIsShort(SqXmlItem item, SqEvaluationContext context) {
        return Optional.of(context.shortPositionOpen());
    }

    static Optional<Boolean> compare(
        SqXmlItem item,
        SqEvaluationContext context,
        java.util.function.BiPredicate<Double, Double> predicate
    ) {
        Optional<SqXmlItem> left = SqBlockUtils.blockItem(item, "#IndicatorLeft#", "#Left#");
        Optional<SqXmlItem> right = SqBlockUtils.blockItem(item, "#IndicatorRight#", "#Right#");
        if (left.isEmpty() || right.isEmpty()) {
            return Optional.empty();
        }
        Optional<Double> leftValue = SqValueEvaluator.evaluate(left.get(), context);
        Optional<Double> rightValue = SqValueEvaluator.evaluate(right.get(), context);
        if (leftValue.isEmpty() || rightValue.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(predicate.test(leftValue.get(), rightValue.get()));
    }

    private static Optional<Boolean> parseLiteral(String text) {
        if ("true".equalsIgnoreCase(text) || "1".equals(text)) {
            return Optional.of(true);
        }
        if ("false".equalsIgnoreCase(text) || "0".equals(text)) {
            return Optional.of(false);
        }
        return Optional.empty();
    }
}
