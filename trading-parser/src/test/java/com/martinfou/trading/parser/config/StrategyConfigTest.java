package com.martinfou.trading.parser.config;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class StrategyConfigTest {

    private static StrategyConfig parseSample() {
        InputStream in = StrategyConfigTest.class.getResourceAsStream("/sq/strategy-1.6.221B.xml");
        assertNotNull(in);
        return StrategyConfig.parse(in);
    }

    @Test
    void parse_sample_hasEngineAndVersion() {
        StrategyConfig config = parseSample();
        assertEquals("MetaTrader", config.engine());
        assertFalse(config.strategyFileVersion().isBlank());
    }

    @Test
    void parse_sample_positionSizing() {
        StrategyConfig config = parseSample();
        assertEquals("FixedSize", config.positionSizing().type());
        assertEquals(0.1, config.positionSizing().fixedSizeOr(0), 0.0001);
    }

    @Test
    void parse_sample_standardSignalSlots() {
        StrategyConfig config = parseSample();
        assertEquals(4, config.signalSlots().size());
        assertTrue(config.signalSlots().stream().anyMatch(s -> "LongEntrySignal".equals(s.name())));
    }

    @Test
    void parse_sample_shortStopLossPips() {
        StrategyConfig config = parseSample();
        assertEquals(185, config.shortStopLossPips().orElse(-1));
        assertTrue(config.exitParameters().containsKey("ShortStopLoss"));
    }

    @Test
    void parse_sample_rulesIncludeSignalAndIfThen() {
        StrategyConfig config = parseSample();
        assertTrue(config.rules().stream().anyMatch(r -> "OnBarUpdate".equals(r.eventKey()) && "Signal".equals(r.type())));
        assertTrue(config.rules().stream().anyMatch(r -> "Short entry".equals(r.name()) && r.actionKeys().contains("EnterAtStop")));
    }

    @Test
    void parse_sample_indicatorKeysDeduped() {
        StrategyConfig config = parseSample();
        assertTrue(config.indicatorKeys().contains("LowestInRange"));
        assertTrue(config.indicatorKeys().contains("HullMovingAverage"));
        assertEquals(config.indicatorKeys().size(), config.indicatorKeys().stream().distinct().count());
    }

    @Test
    void parse_sample_entryActions() {
        StrategyConfig config = parseSample();
        assertTrue(config.entryActionKeys().contains("EnterAtStop"));
    }

    @Test
    void intParameter_missingReturnsDefault() {
        StrategyConfig config = parseSample();
        assertEquals(99, config.intParameter("NoSuchParam", 99));
    }
}
