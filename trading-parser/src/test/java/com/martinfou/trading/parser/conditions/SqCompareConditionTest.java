package com.martinfou.trading.parser.conditions;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.parser.sq.SqXmlBlock;
import com.martinfou.trading.parser.sq.SqXmlItem;
import com.martinfou.trading.parser.sq.SqXmlParam;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqCompareConditionTest {

    @Test
    void isGreater_comparesRegistryIndicators() {
        List<Bar> bars = bars(1, 2, 3, 4, 5);
        SqXmlItem left = smaItem(3);
        SqXmlItem right = smaItem(5);
        SqXmlItem compare = new SqXmlItem(
            "IsGreaterThan", "IsGreaterThan", ">", "operators", "boolean",
            List.of(),
            List.of(
                new SqXmlBlock("#IndicatorLeft#", left),
                new SqXmlBlock("#IndicatorRight#", right)
            )
        );
        var ctx = SqEvaluationContext.of(bars, null, Map.of());
        assertTrue(SqConditionEvaluator.evaluate(compare, ctx).orElseThrow());
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
