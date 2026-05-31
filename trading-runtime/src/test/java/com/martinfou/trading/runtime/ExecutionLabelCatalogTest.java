package com.martinfou.trading.runtime;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionLabelCatalogTest {

    @Test
    void catalog_coversAllLabelsWithDistinctColors() {
        Set<String> colors = new HashSet<>();
        for (ExecutionLabel label : ExecutionLabel.values()) {
            ExecutionLabelPresentation presentation = ExecutionLabelCatalog.of(label);
            assertEquals(label.name(), presentation.id());
            assertFalse(presentation.displayName().isBlank());
            assertFalse(presentation.badgeBackgroundColor().isBlank());
            colors.add(presentation.badgeBackgroundColor());
            assertEquals(label.isBrokerBacked(), presentation.brokerBacked());
            assertEquals(label.countsTowardPaperPeriod(), presentation.countsTowardPaperPeriod());
        }
        assertEquals(ExecutionLabel.values().length, colors.size());
    }

    @Test
    void paperStub_hasStubWarningAndDistinctCategory() {
        ExecutionLabelPresentation stub = ExecutionLabelCatalog.of(ExecutionLabel.PAPER_STUB);
        ExecutionLabelPresentation oanda = ExecutionLabelCatalog.of(ExecutionLabel.PAPER_OANDA);

        assertTrue(stub.stubWarning());
        assertFalse(oanda.stubWarning());
        assertEquals("PAPER_STUB", stub.category());
        assertEquals("BROKER_PAPER", oanda.category());
        assertNotEquals(stub.badgeBackgroundColor(), oanda.badgeBackgroundColor());
    }

    @Test
    void catalogMap_matchesPerLabelMaps() {
        Map<String, Object> catalog = ExecutionLabelCatalog.catalogMap();
        assertEquals(ExecutionLabel.values().length, catalog.size());
        for (ExecutionLabel label : ExecutionLabel.values()) {
            assertEquals(ExecutionLabelCatalog.of(label).toMap(), catalog.get(label.name()));
        }
    }
}
