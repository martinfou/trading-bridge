package com.martinfou.trading.data;

import com.martinfou.trading.core.Bar;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FuturesContinuousSeriesBuilderTest {

    @Test
    void testPanamaCanalStitching() {
        Instant t0 = Instant.parse("2024-03-01T10:00:00Z");
        Instant t1 = t0.plus(1, ChronoUnit.DAYS);
        Instant t2 = t0.plus(2, ChronoUnit.DAYS);
        Instant t3 = t0.plus(3, ChronoUnit.DAYS);

        // March contract (MESH24): Closes at 5000.0 on t1
        List<Bar> mesh24 = List.of(
            new Bar("MESH24", t0, 4980.0, 5010.0, 4970.0, 4990.0, 1000),
            new Bar("MESH24", t1, 4990.0, 5020.0, 4980.0, 5000.0, 1200)
        );

        // June contract (MESM24): Opens at 5020.0 on t2 (gap of +20.0 pts)
        List<Bar> mesm24 = List.of(
            new Bar("MESM24", t2, 5020.0, 5050.0, 5010.0, 5040.0, 1500),
            new Bar("MESM24", t3, 5040.0, 5060.0, 5030.0, 5055.0, 1400)
        );

        Map<String, List<Bar>> contracts = new LinkedHashMap<>();
        contracts.put("MESH24", mesh24);
        contracts.put("MESM24", mesm24);

        List<Bar> continuous = FuturesContinuousSeriesBuilder.buildContinuous(
            "MES",
            contracts,
            FuturesContinuousSeriesBuilder.AdjustmentMode.PANAMA_CANAL
        );

        assertEquals(4, continuous.size());
        assertEquals("MES", continuous.get(0).symbol());

        // Panama Canal shifts historical bars by gap (+20.0 pts):
        // Old MESH24 bar 0 close was 4990.0 -> becomes 5010.0
        assertEquals(5010.0, continuous.get(0).close(), 1e-6);
        // Old MESH24 bar 1 close was 5000.0 -> becomes 5020.0 (smooth join with next contract open 5020.0)
        assertEquals(5020.0, continuous.get(1).close(), 1e-6);
        // Modern MESM24 bars remain unchanged:
        assertEquals(5040.0, continuous.get(2).close(), 1e-6);
        assertEquals(5055.0, continuous.get(3).close(), 1e-6);
    }
}
