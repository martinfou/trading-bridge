package com.martinfou.trading.parser.conditions;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.parser.config.StrategyConfig;
import com.martinfou.trading.parser.sq.SqStrategyDocument;
import com.martinfou.trading.parser.sq.SqXmlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqSignalEntryIntegrationTest {

    @Test
    void sampleStrategy_evaluatesSignalsAndEntryWiring() {
        InputStream in = getClass().getResourceAsStream("/sq/strategy-1.6.221B.xml");
        assertNotNull(in);
        SqStrategyDocument document = SqXmlParser.parse(in);
        StrategyConfig config = StrategyConfig.from(document);
        List<Bar> bars = sampleBars(30);

        SqEntryEvaluator.EntryResult result = SqEntryEvaluator.evaluate(document, bars, config);

        assertTrue(result.signalResults().size() >= 2,
            "at least Boolean signals should resolve; GAP operators may be absent");
        assertFalse(result.longEntryActive(), "LongBooleanValue is false in fixture");
        assertFalse(result.shortEntryActive(), "Short entry AND includes deferred IsFalling");
    }

    @Test
    void signalEvaluator_fillsBooleanSignalsFromFixture() {
        InputStream in = getClass().getResourceAsStream("/sq/strategy-1.6.221B.xml");
        SqStrategyDocument document = SqXmlParser.parse(in);
        StrategyConfig config = StrategyConfig.from(document);
        var signals = SqSignalEvaluator.evaluate(document, sampleBars(30), config);

        assertTrue(signals.containsKey("33333333-1111-1111-3333-333333333333"));
        assertEquals(false, signals.get("33333333-1111-1111-3333-333333333333"));
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
