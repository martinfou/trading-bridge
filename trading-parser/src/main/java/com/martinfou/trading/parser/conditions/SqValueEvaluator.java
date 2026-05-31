package com.martinfou.trading.parser.conditions;

import com.martinfou.trading.parser.indicators.SqIndicatorRegistry;
import com.martinfou.trading.parser.sq.SqXmlItem;

import java.util.Optional;

/** Resolves numeric / price values from SQ indicator Items (story 2-6). */
public final class SqValueEvaluator {

    private SqValueEvaluator() {}

    public static Optional<Double> evaluate(SqXmlItem item, SqEvaluationContext context) {
        if (item == null || context == null) {
            return Optional.empty();
        }
        if (item.isIndicator()) {
            return SqIndicatorRegistry.evaluate(item, context.bars(), context.config());
        }
        return Optional.empty();
    }
}
