package com.martinfou.trading.parser.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Nightly orchestrator: sqcli maintenance jobs, optional XML import, inbox drain (story 21-6).
 */
public final class SqNightlyPipeline {

    private static final Logger log = LoggerFactory.getLogger(SqNightlyPipeline.class);

    private static final List<String> DEFAULT_JOBS = List.of(
        SqNightlyOptions.JOB_UPDATE_DATA,
        SqNightlyOptions.JOB_LIST_DATABANKS
    );

    private final SqJobRunner jobRunner;
    private final SqInboxProcessor inboxProcessor;

    public SqNightlyPipeline() {
        this(new SqJobRunner(), new SqInboxProcessor());
    }

    SqNightlyPipeline(SqJobRunner jobRunner, SqInboxProcessor inboxProcessor) {
        this.jobRunner = jobRunner;
        this.inboxProcessor = inboxProcessor;
    }

    public SqNightlyResult run(SqNightlyOptions options) throws IOException, InterruptedException {
        Map<String, Integer> jobExitCodes = new LinkedHashMap<>();
        if (!options.skipJobs()) {
            SqJobOptions jobOptions = options.toJobOptions();
            for (String jobId : DEFAULT_JOBS) {
                SqCliRunResult result = jobRunner.runScript(jobOptions, jobId);
                jobExitCodes.put(jobId, result.exitCode());
                log.info("SQ job {} exit={}", jobId, result.exitCode());
            }
        }

        int exported = 0;
        if (!options.skipExport()) {
            Path exportDir = resolveExportDir(options);
            if (exportDir != null) {
                exported = importExports(exportDir, SqInboxPaths.pending(options.repoRoot()));
                log.info("Imported {} XML file(s) from {} into pending/", exported, exportDir);
            }
        }

        SqInboxBatchResult inbox = SqInboxBatchResult.empty();
        if (!options.skipInbox()) {
            inbox = inboxProcessor.runBatch(options.inboxOptions());
            if (options.inboxOptions().sqFeedback()) {
                runSqFeedback(options);
            }
        }

        return new SqNightlyResult(jobExitCodes, exported, inbox);
    }

    private void runSqFeedback(SqNightlyOptions options) throws IOException, InterruptedException {
        SqFitnessFeedbackService.SqFitnessFeedbackResult feedback = new SqFitnessFeedbackService().run(
            options.toJobOptions()
        );
        log.info("SQ fitness feedback: {}", feedback.message());
        if (feedback.feedbackExitCode() != 0) {
            throw new IOException("SQ fitness feedback failed: " + feedback.message());
        }
    }

    static Path resolveExportDir(SqNightlyOptions options) {
        if (options.exportDir() != null) {
            return options.exportDir();
        }
        String env = System.getenv(SqBridgePaths.SQ_EXPORT_DIR_ENV);
        if (env == null || env.isBlank()) {
            return null;
        }
        return Path.of(env.trim());
    }

    static int importExports(Path exportDir, Path pendingDir) throws IOException {
        if (!Files.isDirectory(exportDir)) {
            throw new IOException("Export directory missing: " + exportDir);
        }
        Files.createDirectories(pendingDir);
        int count = 0;
        try (Stream<Path> stream = Files.list(exportDir)) {
            List<Path> xmlFiles = stream
                .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".xml"))
                .sorted()
                .toList();
            for (Path src : xmlFiles) {
                Path dest = pendingDir.resolve(src.getFileName());
                Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                count++;
            }
        }
        return count;
    }

    public static void printSummary(SqNightlyResult result, PrintStream out) {
        out.println("SQ Nightly Pipeline Summary");
        out.println("===========================");
        if (result.jobExitCodes().isEmpty()) {
            out.println("Jobs: (skipped)");
        } else {
            out.println("Jobs:");
            result.jobExitCodes().forEach((id, code) -> out.println("  " + id + ": exit " + code));
        }
        out.println("Export: " + result.filesExported() + " file(s) → pending/");
        SqInboxBatchResult inbox = result.inbox();
        out.println(
            "Inbox: processed=" + inbox.processed()
                + " passed=" + inbox.passed()
                + " failed=" + inbox.failed()
                + " dlq=" + inbox.dlq()
        );
    }

    public static void main(String[] args) {
        try {
            SqNightlyOptions options = SqNightlyCli.parse(args);
            SqNightlyResult result = new SqNightlyPipeline().run(options);
            printSummary(result, System.out);
            System.exit(result.pipelineExitCode());
        } catch (SqJobBusyException | SqCliNotFoundException e) {
            System.err.println(e.getMessage());
            System.exit(2);
        } catch (IllegalArgumentException e) {
            if ("help".equals(e.getMessage())) {
                SqNightlyCli.printUsage();
                System.exit(0);
            }
            System.err.println(e.getMessage());
            SqNightlyCli.printUsage();
            System.exit(1);
        } catch (Exception e) {
            System.err.println("SQ nightly error: " + e.getMessage());
            System.exit(1);
        }
    }

    static final class SqNightlyCli {

        private SqNightlyCli() {}

        static SqNightlyOptions parse(String[] args) {
            if (args.length > 0 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
                throw new IllegalArgumentException("help");
            }

            Path repoRoot = SqBridgePaths.resolveRepoRoot();
            Path sqHome = null;
            Path exportDir = null;
            boolean dryRun = false;
            boolean skipMutex = false;
            boolean skipExport = false;
            boolean skipJobs = false;
            boolean skipInbox = false;
            java.time.Duration timeout = java.time.Duration.ofMinutes(30);

            String symbol = null;
            double capital = SqInboxOptions.DEFAULT_CAPITAL;
            int bars = SqInboxOptions.DEFAULT_SYNTHETIC_BARS;
            List<String> dataPath = List.of();
            boolean sqFeedback = false;

            for (int i = 0; i < args.length; ) {
                String arg = args[i];
                switch (arg) {
                    case "--repo-root" -> {
                        repoRoot = Path.of(requireValue(args, ++i, "--repo-root"));
                        i++;
                    }
                    case "--sq-home" -> {
                        sqHome = Path.of(requireValue(args, ++i, "--sq-home"));
                        i++;
                    }
                    case "--export-dir" -> {
                        exportDir = Path.of(requireValue(args, ++i, "--export-dir"));
                        i++;
                    }
                    case "--dry-run" -> {
                        dryRun = true;
                        i++;
                    }
                    case "--no-lock" -> {
                        skipMutex = true;
                        i++;
                    }
                    case "--skip-export" -> {
                        skipExport = true;
                        i++;
                    }
                    case "--skip-jobs" -> {
                        skipJobs = true;
                        i++;
                    }
                    case "--skip-inbox" -> {
                        skipInbox = true;
                        i++;
                    }
                    case "--timeout-secs" -> {
                        timeout = java.time.Duration.ofSeconds(Long.parseLong(requireValue(args, ++i, "--timeout-secs")));
                        i++;
                    }
                    case "--symbol" -> {
                        symbol = requireValue(args, ++i, "--symbol");
                        i += 2;
                    }
                    case "--capital" -> {
                        capital = Double.parseDouble(requireValue(args, ++i, "--capital"));
                        i += 2;
                    }
                    case "--bars" -> {
                        bars = Integer.parseInt(requireValue(args, ++i, "--bars"));
                        i += 2;
                    }
                    case "--data-path" -> {
                        dataPath = collectDataPathArgs(args, i + 1);
                        i += 1 + dataPath.size();
                    }
                    case "--sq-feedback" -> {
                        sqFeedback = true;
                        i++;
                    }
                    case "--caffeinate" -> {
                        i++;
                    }
                    default -> throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }

            SqInboxOptions inboxOptions = new SqInboxOptions(
                repoRoot, symbol, capital, bars, dataPath,
                SqInboxOptions.DEFAULT_MAX_XML_BYTES, sqFeedback
            );
            return new SqNightlyOptions(
                repoRoot, sqHome, dryRun, skipMutex, timeout,
                exportDir, skipExport, skipJobs, skipInbox, inboxOptions
            );
        }

        private static List<String> collectDataPathArgs(String[] args, int start) {
            List<String> collected = new java.util.ArrayList<>();
            for (int i = start; i < args.length; i++) {
                if (args[i].startsWith("--")) {
                    break;
                }
                collected.add(args[i]);
            }
            if (collected.isEmpty()) {
                throw new IllegalArgumentException("--data-path requires at least one value");
            }
            return List.copyOf(collected);
        }

        private static String requireValue(String[] args, int index, String flag) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + flag);
            }
            return args[index];
        }

        static void printUsage() {
            System.out.println("""
                SqNightlyPipeline — sqcli maintenance + inbox drain (story 21-6)

                Usage:
                  scripts/sq-nightly.sh
                  scripts/sq-nightly.sh --caffeinate

                  mvn exec:java -pl trading-parser \\
                    -Dexec.mainClass=com.martinfou.trading.parser.bridge.SqNightlyPipeline \\
                    -Dexec.args="--dry-run --skip-inbox"

                Jobs (mutex via SqJobRunner): update-data → list-databanks
                Export: SQ_EXPORT_DIR or --export-dir copies *.xml → data/sq-inbox/pending/
                Then: SqInboxProcessor drains pending/

                  --repo-root PATH       Repository root (default: auto-detect)
                  --sq-home PATH         Override SQ_HOME
                  --export-dir PATH      Override SQ_EXPORT_DIR
                  --dry-run              Dry-run sqcli jobs only
                  --no-lock              Skip job mutex (tests only)
                  --skip-export          Do not copy exports
                  --skip-jobs            Skip sqcli jobs
                  --skip-inbox           Skip inbox processor
                  --timeout-secs N       sqcli timeout (default 1800)
                  --symbol EUR_USD       Inbox instrument override
                  --capital 100000       Inbox initial capital
                  --bars 500             Synthetic bars when no data
                  --data-path sample     Inbox data source
                  --sq-feedback          Export fitness CSV and import via sqcli (story 21-8)
                  --caffeinate           Ignored by Java; use scripts/sq-nightly.sh

                See docs/contributing.md.
                """);
        }
    }
}
