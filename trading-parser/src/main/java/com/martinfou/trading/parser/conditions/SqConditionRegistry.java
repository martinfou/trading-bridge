package com.martinfou.trading.parser.conditions;

import com.martinfou.trading.parser.sq.SqXmlItem;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Dispatches SQ boolean operator Item keys (story 2-6). */
public final class SqConditionRegistry {

    @FunctionalInterface
    interface Operator {
        Optional<Boolean> evaluate(SqXmlItem item, SqEvaluationContext context);
    }

    private static final Map<String, Operator> OPERATORS = buildOperators();

    /** Operators deferred to later stories (bar-history, etc.). */
    private static final Set<String> DEFERRED = Set.of(
        "IsFalling",
        "IsRising",
        "IsLowerCount",
        "IsHigherCount",
        "CloseAllPositions"
    );

    private SqConditionRegistry() {}

    static Optional<Boolean> evaluate(SqXmlItem item, SqEvaluationContext context) {
        if (item == null || item.key() == null) {
            return Optional.empty();
        }
        if (DEFERRED.contains(item.key())) {
            return Optional.empty();
        }
        Operator operator = OPERATORS.get(item.key());
        if (operator == null) {
            return Optional.empty();
        }
        return operator.evaluate(item, context);
    }

    public static boolean isRegistered(String key) {
        return key != null && (OPERATORS.containsKey(key) || DEFERRED.contains(key));
    }

    public static boolean isDeferred(String key) {
        return key != null && DEFERRED.contains(key);
    }

    public static boolean isSupported(String key) {
        return key != null && OPERATORS.containsKey(key);
    }

    private static Map<String, Operator> buildOperators() {
        Map<String, Operator> map = new HashMap<>();
        map.put("AND", SqConditionOperators::and);
        map.put("Not", SqConditionOperators::not);
        map.put("Boolean", SqConditionOperators::booleanConstant);
        map.put("BooleanVariable", SqConditionOperators::booleanVariable);
        map.put("MarketPositionIsLong", SqConditionOperators::marketPositionIsLong);
        map.put("MarketPositionIsShort", SqConditionOperators::marketPositionIsShort);
        map.put("GreaterThan", (item, ctx) ->
            SqConditionOperators.compare(item, ctx, (a, b) -> a > b));
        map.put("IsGreaterThan", (item, ctx) ->
            SqConditionOperators.compare(item, ctx, (a, b) -> a > b));
        map.put("IsGreater", (item, ctx) ->
            SqConditionOperators.compare(item, ctx, (a, b) -> a > b));
        map.put("LowerThan", (item, ctx) ->
            SqConditionOperators.compare(item, ctx, (a, b) -> a < b));
        map.put("IsLowerThan", (item, ctx) ->
            SqConditionOperators.compare(item, ctx, (a, b) -> a < b));
        map.put("IsLower", (item, ctx) ->
            SqConditionOperators.compare(item, ctx, (a, b) -> a < b));
        map.put("IsGreaterOrEqual", (item, ctx) ->
            SqConditionOperators.compare(item, ctx, (a, b) -> a >= b));
        map.put("IsLowerOrEqual", (item, ctx) ->
            SqConditionOperators.compare(item, ctx, (a, b) -> a <= b));
        return Map.copyOf(map);
    }
}
