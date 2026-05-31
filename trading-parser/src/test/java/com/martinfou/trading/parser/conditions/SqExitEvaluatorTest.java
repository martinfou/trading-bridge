package com.martinfou.trading.parser.conditions;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.parser.config.StrategyConfig;
import com.martinfou.trading.parser.sq.SqStrategyDocument;
import com.martinfou.trading.parser.sq.SqXmlBlock;
import com.martinfou.trading.parser.sq.SqXmlItem;
import com.martinfou.trading.parser.sq.SqXmlParam;
import com.martinfou.trading.parser.sq.SqXmlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqExitEvaluatorTest {

    @Test
    void marketPositionIsLong_reflectsContext() {
        SqXmlItem item = new SqXmlItem(
            "MarketPositionIsLong", "Market Position Is Long", "", "simpleRules", "boolean",
            List.of(), List.of()
        );
        var open = SqEvaluationContext.of(List.of(), null, Map.of(), true, false);
        var flat = SqEvaluationContext.of(List.of(), null, Map.of(), false, false);
        assertTrue(SqConditionEvaluator.evaluate(item, open).orElseThrow());
        assertFalse(SqConditionEvaluator.evaluate(item, flat).orElseThrow());
    }

    @Test
    void longExitCondition_matchesFixtureShapeWhenSignalsAndPositionSet() {
        String exitSignalId = "33333333-1111-2222-3333-333333333333";
        String entrySignalId = "33333333-1111-1111-3333-333333333333";

        SqXmlItem condition = new SqXmlItem(
            "AND", "AND", "AND", "operators", "boolean",
            List.of(),
            List.of(
                new SqXmlBlock("", new SqXmlItem(
                    "AND", "AND", "AND", "operators", "boolean",
                    List.of(),
                    List.of(
                        new SqXmlBlock("", booleanVariable(exitSignalId)),
                        new SqXmlBlock("", not(booleanVariable(entrySignalId)))
                    )
                )),
                new SqXmlBlock("", marketPositionLong())
            )
        );

        var ctx = SqEvaluationContext.of(
            List.of(), null,
            Map.of(exitSignalId, true, entrySignalId, false),
            true, false
        );
        assertTrue(SqConditionEvaluator.evaluate(condition, ctx).orElseThrow());
    }

    @Test
    void sampleStrategy_longExitFalseWithoutPosition() {
        InputStream in = getClass().getResourceAsStream("/sq/strategy-1.6.221B.xml");
        SqStrategyDocument document = SqXmlParser.parse(in);
        StrategyConfig config = StrategyConfig.from(document);
        List<Bar> bars = sampleBars(30);

        SqExitEvaluator.ExitResult result = SqExitEvaluator.evaluate(document, bars, config);

        assertFalse(result.longExitActive());
        assertFalse(result.shortExitActive());
    }

    @Test
    void evaluate_longExitInactiveWhenExitSignalFalseInFixture() {
        InputStream in = getClass().getResourceAsStream("/sq/strategy-1.6.221B.xml");
        SqStrategyDocument document = SqXmlParser.parse(in);
        StrategyConfig config = StrategyConfig.from(document);
        List<Bar> bars = sampleBars(30);

        SqExitEvaluator.ExitResult flat = SqExitEvaluator.evaluate(document, bars, config);
        assertFalse(flat.longExitActive());

        SqExitEvaluator.ExitResult withLong = SqExitEvaluator.evaluate(
            document, bars, config, new SqExitEvaluator.PositionState(true, false));
        assertFalse(withLong.longExitActive(), "LongExitSignal uses LongBooleanValue=false in fixture");
    }

    private static SqXmlItem booleanVariable(String id) {
        return new SqXmlItem(
            "BooleanVariable", "BooleanVariable", "", "other", "boolean",
            List.of(new SqXmlParam("#Variable#", "boolean", id, true, null)),
            List.of()
        );
    }

    private static SqXmlItem not(SqXmlItem value) {
        return new SqXmlItem(
            "Not", "Not", "Not", "operators", "boolean",
            List.of(),
            List.of(new SqXmlBlock("#Value#", value))
        );
    }

    private static SqXmlItem marketPositionLong() {
        return new SqXmlItem(
            "MarketPositionIsLong", "Market Position Is Long", "", "simpleRules", "boolean",
            List.of(), List.of()
        );
    }

    private static List<Bar> sampleBars(int count) {
        Instant t = Instant.parse("2020-01-01T00:00:00Z");
        Bar[] bars = new Bar[count];
        for (int i = 0; i < count; i++) {
            double c = 1.0 + i * 0.01;
            bars[i] = new Bar("EUR_USD", t, c, c, c, c, 0);
        }
        return List.of(bars);
    }
}
