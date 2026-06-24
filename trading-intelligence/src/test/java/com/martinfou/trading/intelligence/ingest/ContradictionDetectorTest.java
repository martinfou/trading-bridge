package com.martinfou.trading.intelligence.ingest;

import com.martinfou.trading.intelligence.brief.WeeklyIntelBrief;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContradictionDetectorTest {

    private final ContradictionDetector detector = new ContradictionDetector();

    @Test
    void detect_directPair_crowdedLong() {
        // EUR_USD: EUR is base. No inversion.
        // Speculators long EUR (70%), Retail long EUR_USD (75%)
        List<WeeklyIntelBrief.CotSnapshotEntry> cot = List.of(
            new WeeklyIntelBrief.CotSnapshotEntry("EUR", 70.0, 30.0, null, LocalDate.now())
        );
        List<WeeklyIntelBrief.OandaRetailEntry> oanda = List.of(
            new WeeklyIntelBrief.OandaRetailEntry("EUR_USD", 75.0, 25.0, "Bullish")
        );

        List<WeeklyIntelBrief.ContradictionEntry> result = detector.detect(cot, oanda);
        assertEquals(1, result.size());
        assertEquals("COT_RETAIL_CROWDED_LONG", result.get(0).type());
    }

    @Test
    void detect_directPair_crowdedShort() {
        // EUR_USD: EUR is base. No inversion.
        // Speculators short EUR (70%), Retail short EUR_USD (75%)
        List<WeeklyIntelBrief.CotSnapshotEntry> cot = List.of(
            new WeeklyIntelBrief.CotSnapshotEntry("EUR", 30.0, 70.0, null, LocalDate.now())
        );
        List<WeeklyIntelBrief.OandaRetailEntry> oanda = List.of(
            new WeeklyIntelBrief.OandaRetailEntry("EUR_USD", 25.0, 75.0, "Bearish")
        );

        List<WeeklyIntelBrief.ContradictionEntry> result = detector.detect(cot, oanda);
        assertEquals(1, result.size());
        assertEquals("COT_RETAIL_CROWDED_SHORT", result.get(0).type());
    }

    @Test
    void detect_indirectPair_invertsCorrectly_crowdedShort() {
        // USD_JPY: JPY is counter. Inversion applied.
        // Speculators long JPY (70%) -> represents Short USD_JPY (70% adjusted cotShort)
        // Retail short USD_JPY (75%) -> both heavily short on USD_JPY (net long JPY)
        List<WeeklyIntelBrief.CotSnapshotEntry> cot = List.of(
            new WeeklyIntelBrief.CotSnapshotEntry("JPY", 70.0, 30.0, null, LocalDate.now())
        );
        List<WeeklyIntelBrief.OandaRetailEntry> oanda = List.of(
            new WeeklyIntelBrief.OandaRetailEntry("USD_JPY", 25.0, 75.0, "Bearish")
        );

        List<WeeklyIntelBrief.ContradictionEntry> result = detector.detect(cot, oanda);
        assertEquals(1, result.size());
        assertEquals("COT_RETAIL_CROWDED_SHORT", result.get(0).type());
    }

    @Test
    void detect_indirectPair_preventsFalseContradiction() {
        // USD_JPY: JPY is counter. Inversion applied.
        // Speculators long JPY (70%) -> represents Short USD_JPY (70% adjusted cotShort, 30% adjusted cotLong)
        // Retail long USD_JPY (75% oanda long)
        // Without inversion, direct matching would raise a COT_RETAIL_CROWDED_LONG.
        // With inversion: cotLong (30%) < 65%, so no contradiction.
        List<WeeklyIntelBrief.CotSnapshotEntry> cot = List.of(
            new WeeklyIntelBrief.CotSnapshotEntry("JPY", 70.0, 30.0, null, LocalDate.now())
        );
        List<WeeklyIntelBrief.OandaRetailEntry> oanda = List.of(
            new WeeklyIntelBrief.OandaRetailEntry("USD_JPY", 75.0, 25.0, "Bullish")
        );

        List<WeeklyIntelBrief.ContradictionEntry> result = detector.detect(cot, oanda);
        assertTrue(result.isEmpty());
    }

    @Test
    void detect_nullSafety() {
        // Null inputs
        assertTrue(detector.detect(null, Collections.emptyList()).isEmpty());
        assertTrue(detector.detect(Collections.emptyList(), null).isEmpty());

        // Null entries inside lists
        List<WeeklyIntelBrief.CotSnapshotEntry> cot = new ArrayList<>();
        cot.add(null);
        cot.add(new WeeklyIntelBrief.CotSnapshotEntry(null, 70.0, 30.0, null, LocalDate.now()));

        List<WeeklyIntelBrief.OandaRetailEntry> oanda = new ArrayList<>();
        oanda.add(null);

        assertTrue(detector.detect(cot, oanda).isEmpty());
    }
}
