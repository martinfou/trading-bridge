package com.martinfou.trading.intelligence.plan;

import com.martinfou.trading.intelligence.template.RiskBudgetEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WeeklyPlanIOTest {

    @Test
    void writeAndRead_roundTrips(@TempDir Path tempDir) throws Exception {
        WeeklyPlan.Pick pick = new WeeklyPlan.Pick(
            "T4", "EUR_USD", "LONG", Map.of(), List.of("src-1"), "rationale"
        );
        WeeklyPlan original = new WeeklyPlan(
            "2026-W24",
            List.of(pick),
            ReviewerStatus.APPROVED,
            "brief-2026-06-06.json",
            RiskBudgetEnvelope.defaults(List.of("EUR_USD"))
        );

        Path path = tempDir.resolve("weekly-plan-2026-W24.json");
        WeeklyPlanIO.write(original, path);
        
        WeeklyPlan loaded = WeeklyPlanIO.read(path);
        assertEquals(original.weekId(), loaded.weekId());
        assertEquals(original.reviewerStatus(), loaded.reviewerStatus());
        assertEquals(original.briefRef(), loaded.briefRef());
        assertEquals(original.riskEnvelopeSnapshot(), loaded.riskEnvelopeSnapshot());
        assertEquals(1, loaded.picks().size());
        assertEquals("T4", loaded.picks().getFirst().templateId());
    }

    @Test
    void write_deletesTempFileOnFailure(@TempDir Path tempDir) throws IOException {
        WeeklyPlan.Pick pick = new WeeklyPlan.Pick(
            "T4", "EUR_USD", "LONG", Map.of(), List.of("src-1"), "rationale"
        );
        WeeklyPlan original = new WeeklyPlan(
            "2026-W24",
            List.of(pick),
            ReviewerStatus.APPROVED,
            "brief-2026-06-06.json",
            RiskBudgetEnvelope.defaults(List.of("EUR_USD"))
        );

        // Make the directory non-empty so Files.move fails
        Files.writeString(tempDir.resolve("dummy.txt"), "hello");

        Path tempFile = tempDir.resolveSibling(tempDir.getFileName() + ".tmp");

        assertThrows(IOException.class, () -> WeeklyPlanIO.write(original, tempDir));
        assertFalse(Files.exists(tempFile), "Temporary file should be cleaned up on failure");
    }
}
