package com.martinfou.trading.parser.bridge;

import com.martinfou.trading.parser.sq.SqStrategyDocument;
import com.martinfou.trading.parser.sq.SqXmlParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class SqXmlCoverageValidatorTest {

    @Test
    void catalogFixture_listsDeferredAndUnknownWithoutGap() {
        SqStrategyDocument document = load("/sq/strategy-1.6.221B.xml");
        SqXmlCoverageReport report = SqXmlCoverageValidator.analyze(document);

        assertFalse(report.requiresDlq());
        assertTrue(report.deferredKeys().contains("IsFalling"));
        assertTrue(report.unknownKeys().contains("HullMovingAverage"));
        assertTrue(report.inlineKeys().contains("LowestInRange") || report.supportedKeys().contains("SMA"));
    }

    @Test
    void gapInventoryBlock_requiresDlq() {
        SqStrategyDocument document = load("/sq/strategy-gap-ichimoku.xml");
        SqXmlCoverageReport report = SqXmlCoverageValidator.analyze(document);

        assertTrue(report.requiresDlq());
        assertTrue(report.gapKeys().contains("Ichimoku"));
        assertNotNull(report.dlqReason());
    }

    private static SqStrategyDocument load(String resource) {
        InputStream in = SqXmlCoverageValidatorTest.class.getResourceAsStream(resource);
        assertNotNull(in, resource);
        return SqXmlParser.parse(in);
    }
}
