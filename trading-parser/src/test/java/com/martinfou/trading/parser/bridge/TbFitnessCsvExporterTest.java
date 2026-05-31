package com.martinfou.trading.parser.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TbFitnessCsvExporterTest {

    @Test
    void write_producesSqCompatibleCsv(@TempDir Path repo) throws Exception {
        Instant processedAt = Instant.parse("2024-03-15T14:30:00Z");
        TbFitnessRecord record = new TbFitnessRecord(
            "strategy-1.6.221B",
            "EUR_USD",
            processedAt,
            1.25,
            2.5,
            12.34,
            0.987654
        );

        Path csv = TbFitnessPaths.exportCsv(repo);
        TbFitnessCsvExporter.write(List.of(record), csv);

        List<String> lines = Files.readAllLines(csv);
        assertEquals(1, lines.size());
        assertEquals("15/03/2024,14:30:00,1,1,1,1,0,1.250000,2.500000,12.340000,0.987654", lines.getFirst());
    }

    @Test
    void writeKeysManifest_mapsManifestIds(@TempDir Path repo) throws Exception {
        Instant processedAt = Instant.parse("2024-01-01T00:00:00Z");
        TbFitnessRecord record = new TbFitnessRecord(
            "alpha", "EUR_USD", processedAt, 0.5, 1.1, 5.0, 0.42
        );

        Path manifest = TbFitnessPaths.keysManifest(repo);
        TbFitnessCsvExporter.writeKeysManifest(List.of(record), manifest);

        assertEquals("alpha\tEUR_USD\t" + processedAt, Files.readString(manifest).trim());
    }

    @Test
    void requireNoSpaces_rejectsPathsWithSpaces(@TempDir Path repo) {
        Path bad = repo.resolve("bad path/tb-fitness.csv");
        assertThrows(Exception.class, () -> TbFitnessCsvExporter.requireNoSpaces(bad));
    }
}
