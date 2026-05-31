package com.martinfou.trading.parser.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Runs named sqcli jobs from the script registry under mutex (story 21-5).
 */
public final class SqJobRunner {

    private static final Logger log = LoggerFactory.getLogger(SqJobRunner.class);

    private final SqCliRunner cliRunner;

    public SqJobRunner() {
        this(new SqCliRunner());
    }

    SqJobRunner(SqCliRunner cliRunner) {
        this.cliRunner = cliRunner;
    }

    public SqJobScriptRegistry loadRegistry(SqJobOptions options) throws IOException {
        return SqJobScriptRegistry.load(SqJobPaths.registryFile(options.repoRoot()));
    }

    public SqCliRunResult runScript(SqJobOptions options, String scriptId) throws IOException, InterruptedException {
        SqJobScript script = loadRegistry(options).require(scriptId);
        log.info("SQ job {} — {}", scriptId, script.description());
        return runWithMutex(options, script.args());
    }

    SqCliRunResult runWithMutex(SqJobOptions options, List<String> sqCliArgs) throws IOException, InterruptedException {
        if (options.skipMutex() || options.dryRun()) {
            if (options.skipMutex()) {
                log.warn("SQ job mutex skipped (--no-lock)");
            }
            return cliRunner.run(options.toCliOptions(), sqCliArgs);
        }
        try (SqJobMutex ignored = SqJobMutex.acquire(SqJobPaths.lockFile(options.repoRoot()))) {
            return cliRunner.run(options.toCliOptions(), sqCliArgs);
        }
    }

    static Path resolveRepoRoot() {
        return SqBridgePaths.resolveRepoRoot();
    }

    public static void main(String[] args) {
        try {
            SqJobRunnerCli.Parsed parsed = SqJobRunnerCli.parse(args);
            SqJobRunner runner = new SqJobRunner();
            if (parsed.list()) {
                SqJobScriptRegistry registry = runner.loadRegistry(parsed.options());
                registry.all().forEach(script ->
                    System.out.println(script.id() + " — " + script.description())
                );
                System.exit(0);
            }
            SqCliRunResult result = runner.runScript(parsed.options(), parsed.scriptId());
            if (result.dryRun()) {
                System.out.println("DRY-RUN: " + String.join(" ", result.command()));
                System.exit(0);
            }
            if (!result.stdout().isBlank()) {
                System.out.print(result.stdout());
            }
            System.exit(result.exitCode());
        } catch (SqJobBusyException | SqCliNotFoundException e) {
            System.err.println(e.getMessage());
            System.exit(2);
        } catch (IllegalArgumentException e) {
            if ("help".equals(e.getMessage())) {
                SqJobRunnerCli.printUsage();
                System.exit(0);
            }
            System.err.println(e.getMessage());
            SqJobRunnerCli.printUsage();
            System.exit(1);
        } catch (Exception e) {
            System.err.println("SQ job error: " + e.getMessage());
            System.exit(1);
        }
    }

    static final class SqJobRunnerCli {

        private SqJobRunnerCli() {}

        record Parsed(SqJobOptions options, boolean list, String scriptId) {}

        static Parsed parse(String[] args) {
            if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
                throw new IllegalArgumentException("help");
            }

            Path repoRoot = resolveRepoRoot();
            Path sqHome = null;
            boolean dryRun = false;
            boolean skipMutex = false;
            boolean list = false;
            String scriptId = null;
            Duration timeout = Duration.ofMinutes(30);

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--repo-root" -> repoRoot = Path.of(requireValue(args, ++i, "--repo-root"));
                    case "--sq-home" -> sqHome = Path.of(requireValue(args, ++i, "--sq-home"));
                    case "--dry-run" -> dryRun = true;
                    case "--no-lock" -> skipMutex = true;
                    case "--list" -> list = true;
                    case "--run" -> scriptId = requireValue(args, ++i, "--run");
                    case "--timeout-secs" -> timeout = Duration.ofSeconds(Long.parseLong(requireValue(args, ++i, "--timeout-secs")));
                    default -> throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }

            if (list && scriptId != null) {
                throw new IllegalArgumentException("Use --list or --run ID, not both");
            }
            if (!list && scriptId == null) {
                throw new IllegalArgumentException("Required: --list or --run SCRIPT_ID");
            }

            SqJobOptions options = new SqJobOptions(repoRoot, sqHome, dryRun, skipMutex, timeout);
            return new Parsed(options, list, scriptId);
        }

        private static String requireValue(String[] args, int index, String flag) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + flag);
            }
            return args[index];
        }

        static void printUsage() {
            System.out.println("""
                SqJobRunner — named sqcli jobs with mutex (story 21-5)

                Usage:
                  mvn exec:java -pl trading-parser \\
                    -Dexec.mainClass=com.martinfou.trading.parser.bridge.SqJobRunner \\
                    -Dexec.args="--list"

                  mvn exec:java -pl trading-parser \\
                    -Dexec.mainClass=com.martinfou.trading.parser.bridge.SqJobRunner \\
                    -Dexec.args="--dry-run --run list-symbols"

                  --list                 List scripts from data/sq-cli/scripts/registry.json
                  --run SCRIPT_ID        Run a registered script
                  --repo-root PATH       Repository root (default: auto-detect)
                  --sq-home PATH         Override SQ_HOME
                  --dry-run              Print sqcli command without executing
                  --no-lock              Skip mutex (tests only)
                  --timeout-secs N       sqcli timeout (default 1800)

                See docs/contributing.md.
                """);
        }
    }
}
