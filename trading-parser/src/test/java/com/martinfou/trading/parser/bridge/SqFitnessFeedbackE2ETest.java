package com.martinfou.trading.parser.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manual E2E against a live StrategyQuant install (story 21-8 AC6).
 *
 * <p>Requires {@code SQ_HOME} pointing at a Mac SQ install with {@code sqcli} on PATH via SQ_HOME.
 * Run locally after inbox processing has populated {@code data/sq-inbox/passed/*-result.json}.</p>
 */
@EnabledIfEnvironmentVariable(named = "SQ_HOME", matches = ".+")
class SqFitnessFeedbackE2ETest {

    @Test
    void importTbFitnessIntoStrategyQuant(@TempDir Path repo) throws Exception {
        Path passed = SqInboxPaths.passed(repo);
        Files.createDirectories(passed);
        Files.writeString(
            passed.resolve("e2e-strategy-result.json"),
            """
                {"manifestId":"e2e-strategy","symbol":"EUR_USD","disposition":"passed","success":true,
                "totalTrades":5,"totalReturnPct":3.5,"finalEquity":103500.0,"errorMessage":null,
                "processedAt":"2024-06-01T12:00:00Z","sharpeRatio":0.8,"profitFactor":1.4,
                "maxDrawdownPct":6.0,"compositeScore":0.55}
                """
        );

        installRegistry(repo);
        SqFitnessFeedbackService.SqFitnessFeedbackResult result = new SqFitnessFeedbackService().run(
            new SqJobOptions(repo, null, false, false, java.time.Duration.ofMinutes(5))
        );

        org.junit.jupiter.api.Assertions.assertEquals(1, result.rowCount());
        org.junit.jupiter.api.Assertions.assertEquals(0, result.importExitCode(), result.message());
    }

    private static void installRegistry(Path repo) throws Exception {
        Path registry = SqJobPaths.registryFile(repo);
        Files.createDirectories(registry.getParent());
        Files.writeString(registry, """
            {
              "scripts": [
                {
                  "id": "setup-tb-fitness",
                  "description": "Register TB fitness external indicator",
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
}
