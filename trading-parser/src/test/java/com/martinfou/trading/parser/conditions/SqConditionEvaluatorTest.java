package com.martinfou.trading.parser.conditions;

import com.martinfou.trading.parser.config.StrategyConfig;
import com.martinfou.trading.parser.sq.SqXmlBlock;
import com.martinfou.trading.parser.sq.SqXmlItem;
import com.martinfou.trading.parser.sq.SqXmlParam;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqConditionEvaluatorTest {

    @Test
    void and_requiresAllChildrenTrue() {
        SqXmlItem and = new SqXmlItem(
            "AND", "AND", "AND", "operators", "boolean",
            List.of(),
            List.of(
                block(booleanVariable("sig-1")),
                block(booleanVariable("sig-2"))
            )
        );
        var ctx = SqEvaluationContext.of(List.of(), null, Map.of(
            "sig-1", true,
            "sig-2", true
        ));
        assertTrue(SqConditionEvaluator.evaluate(and, ctx).orElseThrow());
    }

    @Test
    void and_emptyIsFalse() {
        SqXmlItem and = new SqXmlItem("AND", "AND", "AND", "operators", "boolean", List.of(), List.of());
        assertFalse(SqConditionEvaluator.evaluate(and, SqEvaluationContext.of(List.of(), null, Map.of())).orElseThrow());
    }

    @Test
    void not_negatesChild() {
        SqXmlItem not = new SqXmlItem(
            "Not", "Not", "Not", "operators", "boolean",
            List.of(),
            List.of(new SqXmlBlock("#Value#", booleanVariable("sig-1")))
        );
        var ctx = SqEvaluationContext.of(List.of(), null, Map.of("sig-1", true));
        assertFalse(SqConditionEvaluator.evaluate(not, ctx).orElseThrow());
    }

    @Test
    void boolean_readsConfigParameter() {
        StrategyConfig config = StrategyConfigTestSupport.configWithBoolean("LongBooleanValue", "false");
        SqXmlItem item = new SqXmlItem(
            "Boolean", "Boolean", "Boolean", "other", "boolean",
            List.of(new SqXmlParam("#Value#", "boolean", "LongBooleanValue", true, null)),
            List.of()
        );
        var ctx = SqEvaluationContext.of(List.of(), config, Map.of());
        assertFalse(SqConditionEvaluator.evaluate(item, ctx).orElseThrow());
    }

    @Test
    void booleanVariable_looksUpSignalMap() {
        SqXmlItem item = booleanVariable("33333333-1111-1111-3333-333333333333");
        var ctx = SqEvaluationContext.of(List.of(), null, Map.of("33333333-1111-1111-3333-333333333333", true));
        assertTrue(SqConditionEvaluator.evaluate(item, ctx).orElseThrow());
    }

    private static SqXmlItem booleanVariable(String variableId) {
        return new SqXmlItem(
            "BooleanVariable", "BooleanVariable", "BooleanVariable", "other", "boolean",
            List.of(new SqXmlParam("#Variable#", "boolean", variableId, true, null)),
            List.of()
        );
    }

    private static SqXmlBlock block(SqXmlItem item) {
        return new SqXmlBlock("", item);
    }
}
