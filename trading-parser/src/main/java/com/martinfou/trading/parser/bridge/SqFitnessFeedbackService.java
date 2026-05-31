package com.martinfou.trading.parser.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Exports TB fitness CSV and imports into StrategyQuant via sqcli (story 21-8).
 */
public final class SqFitnessFeedbackService {

    private static final Logger log = LoggerFactory.getLogger(SqFitnessFeedbackService.class);

    public static final String JOB_SETUP = "setup-tb-fitness";

    private final SqJobRunner jobRunner;

    public SqFitnessFeedbackService() {
        this(new SqJobRunner());
    }

    SqFitnessFeedbackService(SqJobRunner jobRunner) {
        this.jobRunner = jobRunner;
    }

    public SqFitnessFeedbackResult run(SqJobOptions jobOptions) throws IOException, InterruptedException {
        Path repoRoot = jobOptions.repoRoot();
        List<TbFitnessRecord> records = TbFitnessCollector.collectFromPassed(repoRoot);
        if (records.isEmpty()) {
            log.info("No passed inbox results with fitness metrics — skip SQ feedback");
            return SqFitnessFeedbackResult.skipped("No fitness records in passed/");
        }

        Path csvPath = TbFitnessCsvExporter.write(records, TbFitnessPaths.exportCsv(repoRoot));
        Path keysPath = TbFitnessCsvExporter.writeKeysManifest(records, TbFitnessPaths.keysManifest(repoRoot));
        log.info("Wrote {} fitness row(s) to {} (keys {})", records.size(), csvPath, keysPath);

        if (jobOptions.dryRun()) {
            try {
                jobRunner.runScript(jobOptions, JOB_SETUP);
            } catch (IOException e) {
                log.warn("SQ setup-tb-fitness dry-run skipped: {}", e.getMessage());
            }
            SqCliRunResult importPreview = jobRunner.runWithMutex(jobOptions, importArgs(csvPath));
            return new SqFitnessFeedbackResult(
                records.size(), csvPath, keysPath, true, 0, 0,
                "DRY-RUN: " + String.join(" ", importPreview.command())
            );
        }

        int setupExitCode = 0;
        SqJobRunner runner = jobRunner;
        try {
            SqCliRunResult setup = runner.runScript(jobOptions, JOB_SETUP);
            setupExitCode = setup.exitCode();
            log.info("SQ setup-tb-fitness exit={}", setupExitCode);
        } catch (IOException e) {
            log.warn("SQ setup-tb-fitness skipped: {}", e.getMessage());
        }

        SqCliRunResult importResult = runner.runWithMutex(jobOptions, importArgs(csvPath));
        log.info("SQ import-tb-fitness exit={}", importResult.exitCode());
        return new SqFitnessFeedbackResult(
            records.size(),
            csvPath,
            keysPath,
            false,
            setupExitCode,
            importResult.exitCode(),
            importResult.exitCode() == 0 ? "import ok" : "import failed"
        );
    }

    static List<String> importArgs(Path csvPath) throws IOException {
        TbFitnessCsvExporter.requireNoSpaces(csvPath);
        return List.of(
            "-extindicators",
            "action=import",
            "name=" + TbFitnessPaths.INDICATOR_NAME,
            "file=" + csvPath.toAbsolutePath().normalize()
        );
    }

    public record SqFitnessFeedbackResult(
        int rowCount,
        Path csvPath,
        Path keysManifestPath,
        boolean dryRun,
        int setupExitCode,
        int importExitCode,
        String message
    ) {
        static SqFitnessFeedbackResult skipped(String message) {
            return new SqFitnessFeedbackResult(0, null, null, false, 0, 0, message);
        }

        public int feedbackExitCode() {
            if (rowCount == 0) {
                return 0;
            }
            return importExitCode == 0 ? 0 : 1;
        }
    }
}
