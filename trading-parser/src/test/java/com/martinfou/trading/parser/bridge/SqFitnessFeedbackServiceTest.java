package com.martinfou.trading.parser.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqFitnessFeedbackServiceTest {

    @Test
    void run_skipsWhenNoPassedResults(@TempDir Path repo) throws Exception {
        Files.createDirectories(SqInboxPaths.passed(repo));

        SqFitnessFeedbackService.SqFitnessFeedbackResult result = new SqFitnessFeedbackService().run(
            new SqJobOptions(repo, repo, true, true, null)
        );

        assertEquals(0, result.rowCount());
        assertTrue(result.message().contains("No fitness records"));
        assertEquals(0, result.feedbackExitCode());
    }

    @Test
    void run_dryRun_writesCsvAndPreviewsImport(@TempDir Path repo) throws Exception {
        installRegistry(repo);
        writePassedResult(repo, "alpha-result.json", "alpha", 1.1, 2.0, 8.0, 0.75);

        SqFitnessFeedbackService.SqFitnessFeedbackResult result = new SqFitnessFeedbackService().run(
            new SqJobOptions(repo, repo, true, true, null)
        );

        assertEquals(1, result.rowCount());
        assertTrue(result.dryRun());
        assertTrue(Files.exists(TbFitnessPaths.exportCsv(repo)));
        assertTrue(Files.exists(TbFitnessPaths.keysManifest(repo)));
        assertEquals(0, result.feedbackExitCode());
    }

    @Test
    void importArgs_usesExtIndicatorsImport(@TempDir Path repo) throws Exception {
        Path csv = TbFitnessPaths.exportCsv(repo);
        Files.createDirectories(csv.getParent());
        Files.writeString(csv, "line");

        List<String> args = SqFitnessFeedbackService.importArgs(csv);

        assertEquals("-extindicators", args.get(0));
        assertEquals("action=import", args.get(1));
        assertEquals("name=tbFitness", args.get(2));
        assertTrue(args.get(3).startsWith("file="));
        assertFalse(args.get(3).contains(" "));
    }

    private static void installRegistry(Path repo) throws Exception {
        Path registry = SqJobPaths.registryFile(repo);
        Files.createDirectories(registry.getParent());
        Files.writeString(registry, """
            {
              "scripts": [
                {
                  "id": "setup-tb-fitness",
                  "description": "Setup TB fitness indicator",
                  "args": [
                    "-extindicators",
                    "action=add",
                    "name=tbFitness",
                    "values=sharpe,profitFactor,maxDrawdown,compositeScore"
                  ]
                }
              ]
            }
            """);
    }

    private static void writePassedResult(
        Path repo,
        String fileName,
        String manifestId,
        double sharpe,
        double pf,
        double dd,
        double composite
    ) throws Exception {
        Path passed = SqInboxPaths.passed(repo);
        Files.createDirectories(passed);
        SqInboxResult result = SqInboxResult.success(
            manifestId, "EUR_USD", 10, 5.0, 105_000.0,
            sharpe, pf, dd, composite, Instant.parse("2024-06-01T12:00:00Z")
        );
        new ObjectMapper().findAndRegisterModules()
            .writeValue(passed.resolve(fileName).toFile(), result);
    }
}
