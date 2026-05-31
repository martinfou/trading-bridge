package com.martinfou.trading.parser.actions;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.parser.conditions.SqExitEvaluator;
import com.martinfou.trading.parser.config.StrategyConfig;
import com.martinfou.trading.parser.sq.SqGlobalSlPt;
import com.martinfou.trading.parser.sq.SqMoneyManagement;
import com.martinfou.trading.parser.sq.SqStrategyDocument;
import com.martinfou.trading.parser.sq.SqXmlEvent;
import com.martinfou.trading.parser.sq.SqXmlItem;
import com.martinfou.trading.parser.sq.SqXmlParam;
import com.martinfou.trading.parser.sq.SqXmlRule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SqStrategyActionsEvaluatorTest {

    @Test
    void whenEntryRuleConditionTrue_emitsParsedEnterAtStop() {
        SqStrategyDocument document = documentWithRule(
            "Short entry",
            booleanConstant(true),
            enterAtStopAction()
        );
        StrategyConfig config = StrategyConfigTestSupport.sampleShortExitConfig();

        SqBarActions actions = SqStrategyActionsEvaluator.evaluateOnBar(
            document,
            bars(1, 2, 3, 4, 5),
            config,
            SqExitEvaluator.PositionState.flat()
        );

        assertTrue(actions.hasEntries());
        SqOrderIntent first = actions.entryOrders().getFirst();
        assertEquals(Order.Side.SELL, first.side());
        assertEquals(0.1, first.quantity(), 1e-9);
        assertEquals(4.0, first.stopPrice().orElseThrow(), 1e-9);
    }

    @Test
    void whenEntryRuleConditionFalse_emitsNoOrders() {
        SqStrategyDocument document = documentWithRule(
            "Short entry",
            booleanConstant(false),
            enterAtStopAction()
        );
        StrategyConfig config = StrategyConfigTestSupport.sampleShortExitConfig();

        SqBarActions actions = SqStrategyActionsEvaluator.evaluateOnBar(
            document,
            bars(1, 2, 3, 4, 5),
            config
        );

        assertFalse(actions.hasEntries());
    }

    @Test
    void whenExitRuleConditionTrue_emitsCloseIntent() {
        SqStrategyDocument document = documentWithRule(
            "Long exit",
            booleanConstant(true),
            closeAllPositionsAction()
        );
        StrategyConfig config = StrategyConfigTestSupport.sampleShortExitConfig();

        SqBarActions actions = SqStrategyActionsEvaluator.evaluateOnBar(
            document,
            bars(1, 2, 3),
            config,
            new SqExitEvaluator.PositionState(true, false)
        );

        assertTrue(actions.hasCloses());
        SqCloseIntent close = actions.closeActions().getFirst();
        assertEquals(SqCloseDirection.LONG, close.direction());
    }

    private static SqStrategyDocument documentWithRule(
        String ruleName,
        SqXmlItem condition,
        SqXmlItem action
    ) {
        SqXmlRule rule = new SqXmlRule(
            ruleName,
            "IfThen",
            List.of(),
            Optional.of(condition),
            List.of(action)
        );
        return new SqStrategyDocument(
            "1.6",
            "test",
            "MetaTrader",
            new SqMoneyManagement("FixedSize", Map.of()),
            new SqGlobalSlPt(true, 0, 0),
            List.of(),
            List.of(new SqXmlEvent("OnBarUpdate", List.of(rule)))
        );
    }

    private static SqXmlItem booleanConstant(boolean value) {
        return new SqXmlItem(
            "Boolean", "Boolean", "Boolean", "other", "boolean",
            List.of(new SqXmlParam("#Value#", "boolean", String.valueOf(value), false, null)),
            List.of()
        );
    }

    private static SqXmlItem enterAtStopAction() {
        return new SqXmlItem(
            "EnterAtStop", "EnterAtStop", "EnterAtStop", "other", "order",
            List.of(
                new SqXmlParam("#Price#", "double", "", false, smaItem(3)),
                new SqXmlParam("#Direction#", "int", "-1", false, null),
                new SqXmlParam("#Size#", "double", "", false, null),
                new SqXmlParam("#StopLoss.StopLoss#", "double", "ShortStopLoss", false, null),
                new SqXmlParam("#BarsValid#", "int", "ShortBarsValid", true, null)
            ),
            List.of()
        );
    }

    private static SqXmlItem closeAllPositionsAction() {
        return new SqXmlItem(
            "CloseAllPositions", "Close All Positions", "", "other", "none",
            List.of(new SqXmlParam("#Direction#", "int", "1", false, null)),
            List.of()
        );
    }

    private static SqXmlItem smaItem(int period) {
        return new SqXmlItem(
            "SMA", "SMA", "SMA", "indicator", "number",
            List.of(new SqXmlParam("#Period#", "int", String.valueOf(period), false, null)),
            List.of()
        );
    }

    private static List<Bar> bars(double... closes) {
        Instant t = Instant.parse("2020-01-01T00:00:00Z");
        Bar[] out = new Bar[closes.length];
        for (int i = 0; i < closes.length; i++) {
            double c = closes[i];
            out[i] = new Bar("EUR_USD", t, c, c, c, c, 0);
        }
        return List.of(out);
    }
}
