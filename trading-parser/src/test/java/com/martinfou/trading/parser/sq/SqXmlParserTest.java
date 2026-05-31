package com.martinfou.trading.parser.sq;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class SqXmlParserTest {

    private static SqStrategyDocument parseSample() {
        InputStream in = SqXmlParserTest.class.getResourceAsStream("/sq/strategy-1.6.221B.xml");
        assertNotNull(in);
        return SqXmlParser.parse(in);
    }

    @Test
    void parse_realSample_readsVersionAndEngine() {
        SqStrategyDocument doc = parseSample();
        assertFalse(doc.strategyFileVersion().isBlank());
        assertEquals("MetaTrader", doc.engine());
    }

    @Test
    void parse_realSample_readsMoneyManagement() {
        SqStrategyDocument doc = parseSample();
        assertEquals("FixedSize", doc.moneyManagement().type());
        assertEquals("0.1", doc.moneyManagement().params().get("#Size#"));
    }

    @Test
    void parse_realSample_readsGlobalSlPt() {
        SqStrategyDocument doc = parseSample();
        assertTrue(doc.globalSlPt().useSameForBothDirections());
        assertEquals(0, doc.globalSlPt().globalStopLoss());
    }

    @Test
    void parse_realSample_variableLookup() {
        SqStrategyDocument doc = parseSample();
        var longEntry = doc.variableByName("LongEntrySignal").orElseThrow();
        assertEquals("boolean", longEntry.type());
        assertTrue(doc.variableById(longEntry.id()).isPresent());
    }

    @Test
    void parse_realSample_onBarUpdateHasSignalAndIfThenRules() {
        SqStrategyDocument doc = parseSample();
        var onBar = doc.events().stream()
            .filter(e -> "OnBarUpdate".equals(e.key()))
            .findFirst()
            .orElseThrow();
        assertTrue(onBar.rules().stream().anyMatch(r -> "Signal".equals(r.type())));
        assertTrue(onBar.rules().stream().anyMatch(r -> "IfThen".equals(r.type()) && "Short entry".equals(r.name())));
    }

    @Test
    void parse_realSample_enterAtStopHasHmaFormula() {
        SqStrategyDocument doc = parseSample();
        var enterAtStop = doc.allItems().stream()
            .filter(SqXmlItem::isEntryAction)
            .findFirst()
            .orElseThrow();
        var priceParam = enterAtStop.params().stream()
            .filter(p -> "#Price#".equals(p.key()))
            .findFirst()
            .orElseThrow();
        var hma = priceParam.formulaItem().orElseThrow();
        assertEquals("HullMovingAverage", hma.key());
        assertTrue(hma.isIndicator());
    }

    @Test
    void parse_realSample_lowestInRangeNestedInSignal() {
        SqStrategyDocument doc = parseSample();
        assertTrue(doc.allItems().stream().anyMatch(i -> "LowestInRange".equals(i.key())));
    }

    @Test
    void parse_missingStrategy_throws() {
        String xml = "<StrategyFile Version=\"1\"><Other/></StrategyFile>";
        assertThrows(SqXmlParseException.class, () -> SqXmlParser.parse(new java.io.ByteArrayInputStream(xml.getBytes())));
    }
}
