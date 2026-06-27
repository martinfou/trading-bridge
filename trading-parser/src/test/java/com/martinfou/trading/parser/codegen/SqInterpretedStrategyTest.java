package com.martinfou.trading.parser.codegen;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.parser.config.GlobalExitConfig;
import com.martinfou.trading.parser.config.PositionSizingConfig;
import com.martinfou.trading.parser.config.StrategyConfig;
import com.martinfou.trading.parser.config.StrategyParameter;
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

class SqInterpretedStrategyTest {

    @Test
    void onBar_emitsStopOrderWithSlPipsWhenEntryRuleActive() {
        SqStrategyDocument document = documentWithEntryRule();
        StrategyConfig config = sampleShortExitConfig();
        SqInterpretedStrategy strategy = new SqInterpretedStrategy(
            document, config, "TestStrategy", "EUR_USD");

        strategy.onBar(bar(1.0));
        strategy.onBar(bar(2.0));
        strategy.onBar(bar(3.0));
        strategy.onBar(bar(4.0));
        strategy.onBar(bar(5.0));
        List<Order> orders = strategy.getPendingOrders();

        assertEquals(1, orders.size());
        Order order = orders.getFirst();
        assertEquals(Order.Type.STOP, order.type());
        assertEquals(Order.Side.SELL, order.side());
        assertEquals(0.1, order.quantity(), 1e-9);
        assertEquals(2.0, order.price(), 1e-9);
        assertTrue(order.stopLoss() > order.price(), "short SL above entry stop price");
    }

    @Test
    void onBar_emitsMarketCloseWhenExitRuleActive() {
        SqStrategyDocument document = documentWithRule(
            "Short exit",
            booleanConstant(true),
            closeAllPositionsAction()
        );
        StrategyConfig config = sampleShortExitConfig();
        SqInterpretedStrategy strategy = new SqInterpretedStrategy(
            document, config, "ExitTest", "EUR_USD");
        strategy.seedShortPosition(0.1);

        strategy.onBar(bar(1.0));
        List<Order> close = strategy.getPendingOrders();

        assertEquals(1, close.size());
        assertEquals(Order.Type.MARKET, close.getFirst().type());
        assertEquals(Order.Side.BUY, close.getFirst().side());
    }

    @Test
    void syncPosition_restoresInternalState() throws Exception {
        SqStrategyDocument document = documentWithEntryRule();
        StrategyConfig config = sampleShortExitConfig();
        SqInterpretedStrategy strategy = new SqInterpretedStrategy(
            document, config, "TestStrategy", "EUR_USD");

        strategy.syncPosition(Order.Side.BUY, 0.5, 0.0, 0.0);
        
        java.lang.reflect.Field longOpenField = SqInterpretedStrategy.class.getDeclaredField("longOpen");
        longOpenField.setAccessible(true);
        assertTrue((Boolean) longOpenField.get(strategy));

        java.lang.reflect.Field longQtyField = SqInterpretedStrategy.class.getDeclaredField("longQuantity");
        longQtyField.setAccessible(true);
        assertEquals(0.5, (Double) longQtyField.get(strategy), 1e-9);

        // Sync to short
        strategy.syncPosition(Order.Side.SELL, 0.2, 0.0, 0.0);
        java.lang.reflect.Field shortOpenField = SqInterpretedStrategy.class.getDeclaredField("shortOpen");
        shortOpenField.setAccessible(true);
        assertTrue((Boolean) shortOpenField.get(strategy));
        assertFalse((Boolean) longOpenField.get(strategy));

        // Sync to flat
        strategy.syncPosition(null, 0.0, 0.0, 0.0);
        assertFalse((Boolean) shortOpenField.get(strategy));
        assertFalse((Boolean) longOpenField.get(strategy));
    }

    private static SqStrategyDocument documentWithEntryRule() {
        return documentWithRule("Short entry", booleanConstant(true), enterAtStopAction());
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
            List.of(new SqXmlParam("#Direction#", "int", "-1", false, null)),
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

    private static Bar bar(double close) {
        return new Bar("EUR_USD", Instant.parse("2020-01-01T00:00:00Z"), close, close, close, close, 0);
    }

    private static StrategyConfig sampleShortExitConfig() {
        return new StrategyConfig(
            "1", "", "MetaTrader",
            new PositionSizingConfig("FixedSize", Map.of("#Size#", "0.1")),
            new GlobalExitConfig(true, 0, 0),
            Map.of(
                "ShortStopLoss", new StrategyParameter("ShortStopLoss", "ShortStopLoss", "int", "185", ""),
                "ShortBarsValid", new StrategyParameter("ShortBarsValid", "ShortBarsValid", "int", "168", "")
            ),
            List.of(), List.of(), List.of(), List.of()
        );
    }
}
