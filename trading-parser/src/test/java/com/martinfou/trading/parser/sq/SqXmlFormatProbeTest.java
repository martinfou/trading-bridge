package com.martinfou.trading.parser.sq;

import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class SqXmlFormatProbeTest {

    private static SqXmlFormatReport analyzeSample() throws Exception {
        try (InputStream in = SqXmlFormatProbeTest.class.getResourceAsStream("/sq/strategy-1.6.221B.xml")) {
            assertNotNull(in, "fixture missing from classpath");
            return SqXmlFormatProbe.analyze(in);
        }
    }

    @Test
    void analyze_realSample_hasStrategyFileVersion() throws Exception {
        SqXmlFormatReport report = analyzeSample();
        assertFalse(report.strategyFileVersion().isBlank());
        assertEquals("MetaTrader", report.engine());
    }

    @Test
    void analyze_realSample_hasStandardSignalVariables() throws Exception {
        SqXmlFormatReport report = analyzeSample();
        assertTrue(report.hasStandardSignalVariables());
        assertTrue(report.variables().stream().anyMatch(v -> "LongEntrySignal".equals(v.name())));
        assertTrue(report.variables().stream().anyMatch(v -> "ShortStopLoss".equals(v.name())));
    }

    @Test
    void analyze_realSample_collectsBuildingBlocks() throws Exception {
        SqXmlFormatReport report = analyzeSample();
        assertTrue(report.buildingBlocks().stream().anyMatch(b -> "LowestInRange".equals(b.key())));
        assertTrue(report.buildingBlocks().stream().anyMatch(b -> "HullMovingAverage".equals(b.key())));
    }

    @Test
    void analyze_realSample_findsEnterAtStop() throws Exception {
        SqXmlFormatReport report = analyzeSample();
        assertTrue(report.entryActionKeys().contains("EnterAtStop"));
    }

    @Test
    void analyze_realSample_signalIdsMatchVariables() throws Exception {
        SqXmlFormatReport report = analyzeSample();
        assertFalse(report.signalVariableIds().isEmpty());
        for (String id : report.signalVariableIds()) {
            assertTrue(report.variables().stream().anyMatch(v -> id.equals(v.id())),
                "signal variable id not in Variables: " + id);
        }
    }

    @Test
    void exitParameters_includeStopLoss() throws Exception {
        SqXmlFormatReport report = analyzeSample();
        assertTrue(report.exitParameters().stream().anyMatch(v -> v.name().contains("StopLoss")));
    }
}
