package com.martinfou.trading.parser.indicators;

import com.martinfou.trading.parser.config.StrategyConfig;
import com.martinfou.trading.parser.sq.SqXmlItem;
import com.martinfou.trading.parser.sq.SqXmlParam;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqIndicatorParamsTest {

    @Test
    void from_readsPeriodShiftAndAppliedPrice() {
        SqXmlItem item = new SqXmlItem(
            "SMA", "SMA", "SMA(#Period#)", "indicator", "number",
            List.of(
                new SqXmlParam("#Period#", "int", "20", false, null),
                new SqXmlParam("#Shift#", "int", "2", false, null),
                new SqXmlParam("#ComputedFrom#", "int", "1", false, null)
            ),
            List.of()
        );
        SqIndicatorParams params = SqIndicatorParams.from(item, SqIndicatorParams.SqParameterResolver.NONE);
        assertEquals(20, params.period());
        assertEquals(2, params.shift());
        assertEquals(SqAppliedPrice.OPEN, params.appliedPrice());
        assertEquals(7, params.endIndex(10));
    }

    @Test
    void resolver_usesStrategyConfigForVariableReference() {
        SqXmlItem item = new SqXmlItem(
            "RSI", "RSI", "RSI", "indicator", "number",
            List.of(new SqXmlParam("#Period#", "int", "ShortHullMvnAvrPrd", true, null)),
            List.of()
        );
        StrategyConfig config = new StrategyConfig(
            "1", "", "MetaTrader",
            new com.martinfou.trading.parser.config.PositionSizingConfig("FixedSize", Map.of()),
            new com.martinfou.trading.parser.config.GlobalExitConfig(true, 0, 0),
            Map.of("ShortHullMvnAvrPrd", new com.martinfou.trading.parser.config.StrategyParameter(
                "ShortHullMvnAvrPrd", "ShortHullMvnAvrPrd", "int", "14", "")),
            List.of(), List.of(), List.of(), List.of()
        );
        SqIndicatorParams params = SqIndicatorParams.from(
            item, SqIndicatorParams.SqParameterResolver.from(config));
        assertEquals(14, params.period());
    }
}
