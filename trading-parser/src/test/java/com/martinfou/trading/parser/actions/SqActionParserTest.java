package com.martinfou.trading.parser.actions;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.parser.conditions.SqEvaluationContext;
import com.martinfou.trading.parser.config.StrategyConfig;
import com.martinfou.trading.parser.sq.SqStrategyDocument;
import com.martinfou.trading.parser.sq.SqXmlItem;
import com.martinfou.trading.parser.sq.SqXmlParam;
import com.martinfou.trading.parser.sq.SqXmlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqActionParserTest {

    @Test
    void parseEnterAtStop_withSmaPriceFormula() {
        SqXmlItem item = new SqXmlItem(
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
        StrategyConfig config = StrategyConfigTestSupport.sampleShortExitConfig();
        List<Bar> bars = bars(1, 2, 3, 4, 5);
        var ctx = SqEvaluationContext.of(bars, config, Map.of());

        SqOrderIntent intent = SqActionParser.parseEnterAtStop(item, ctx).orElseThrow();

        assertEquals(Order.Side.SELL, intent.side());
        assertEquals(0.1, intent.quantity(), 1e-9);
        assertEquals(4.0, intent.stopPrice().orElseThrow(), 1e-9);
        assertEquals(185, intent.stopLossPips().orElseThrow());
        assertEquals(168, intent.barsValid().orElseThrow());
        assertTrue(intent.isComplete());
    }

    @Test
    void parseCloseAllPositions_readsDirectionAndMagic() {
        SqXmlItem item = new SqXmlItem(
            "CloseAllPositions", "Close All Positions", "", "other", "none",
            List.of(
                new SqXmlParam("#Direction#", "int", "1", false, null),
                new SqXmlParam("#MagicNumber#", "int", "MagicNumber", true, null)
            ),
            List.of()
        );
        SqCloseIntent close = SqActionParser.parseCloseAllPositions(item).orElseThrow();
        assertEquals(SqCloseDirection.LONG, close.direction());
        assertEquals("MagicNumber", close.magicNumberVariable().orElseThrow());
    }

    @Test
    void fixture_shortEntryEnterAtStop_hasSizingAndSlWithoutPrice() {
        InputStream in = getClass().getResourceAsStream("/sq/strategy-1.6.221B.xml");
        SqStrategyDocument document = SqXmlParser.parse(in);
        StrategyConfig config = StrategyConfig.from(document);
        SqXmlItem enterAtStop = document.events().stream()
            .flatMap(e -> e.rules().stream())
            .filter(r -> "Short entry".equals(r.name()))
            .flatMap(r -> r.actions().stream())
            .findFirst()
            .orElseThrow();

        var ctx = SqEvaluationContext.of(bars(1, 2, 3, 4, 5), config, Map.of());
        SqOrderIntent intent = SqActionParser.parseEnterAtStop(enterAtStop, ctx).orElseThrow();

        assertEquals(Order.Side.SELL, intent.side());
        assertEquals(0.1, intent.quantity(), 1e-9);
        assertEquals(185, intent.stopLossPips().orElseThrow());
        assertTrue(intent.stopPrice().isEmpty(), "HMA price is GAP indicator");
        assertFalse(intent.isComplete());
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
