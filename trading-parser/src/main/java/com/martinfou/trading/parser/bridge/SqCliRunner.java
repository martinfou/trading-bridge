package com.martinfou.trading.parser.bridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs StrategyQuant {@code sqcli} from {@code SQ_HOME} (story 21-4).
 *
 * @see <a href="https://strategyquant.com/doc/cli-command-line/introduction-to-cli/">SQ CLI docs</a>
 */
public final class SqCliRunner {

    private static final Logger log = LoggerFactory.getLogger(SqCliRunner.class);

    public SqCliRunResult run(SqCliOptions options, List<String> sqCliArgs) throws IOException, InterruptedException {
        if (sqCliArgs == null || sqCliArgs.isEmpty()) {
            throw new IllegalArgumentException("sqcli arguments required (e.g. -symbol action=list)");
        }

        Path sqHome = SqCliPaths.resolveSqHome(options.sqHomeOverride());
        Path binary = options.dryRun()
            ? SqCliPaths.cliBinaryPath(sqHome)
            : SqCliPaths.sqCliBinary(sqHome);
        List<String> command = buildCommand(binary, sqCliArgs);

        if (options.dryRun()) {
            log.info("DRY-RUN sqcli: {}", String.join(" ", command));
            return SqCliRunResult.dryRun(command);
        }

        long start = System.currentTimeMillis();
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(sqHome.toFile());
        builder.redirectErrorStream(true);

        Process process = builder.start();

        if (options.timeout() != null) {
            if (!process.waitFor(options.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("sqcli timed out after " + options.timeout());
            }
        } else {
            process.waitFor();
        }

        String combined = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.exitValue();
        long elapsed = System.currentTimeMillis() - start;
        log.info("sqcli exit={} elapsedMs={}", exitCode, elapsed);
        if (!combined.isBlank()) {
            log.debug("sqcli output: {}", combined.trim());
        }

        return new SqCliRunResult(command, exitCode, combined, "", false, elapsed);
    }

    static List<String> buildCommand(Path binary, List<String> sqCliArgs) {
        List<String> command = new ArrayList<>(1 + sqCliArgs.size());
        command.add(binary.toString());
        command.addAll(sqCliArgs);
        return List.copyOf(command);
    }

    public static void main(String[] args) {
        try {
            SqCliRunner runner = new SqCliRunner();
            SqCliRunnerCli.ParsedCli parsed = SqCliRunnerCli.parse(args);
            SqCliRunResult result = runner.run(parsed.options(), parsed.sqCliArgs());
            if (result.dryRun()) {
                System.out.println("DRY-RUN: " + String.join(" ", result.command()));
                System.exit(0);
            }
            if (!result.stdout().isBlank()) {
                System.out.print(result.stdout());
            }
            if (!result.stderr().isBlank()) {
                System.err.print(result.stderr());
            }
            System.exit(result.exitCode());
        } catch (SqCliNotFoundException e) {
            System.err.println(e.getMessage());
            SqCliRunnerCli.printUsage();
            System.exit(2);
        } catch (IllegalArgumentException e) {
            if ("help".equals(e.getMessage())) {
                SqCliRunnerCli.printUsage();
                System.exit(0);
            }
            System.err.println(e.getMessage());
            SqCliRunnerCli.printUsage();
            System.exit(1);
        } catch (Exception e) {
            System.err.println("sqcli runner error: " + e.getMessage());
            System.exit(1);
        }
    }

    /** CLI parsing for {@link #main}. */
    static final class SqCliRunnerCli {

        private SqCliRunnerCli() {}

        record ParsedCli(SqCliOptions options, List<String> sqCliArgs) {}

        static ParsedCli parse(String[] args) {
            if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
                throw new IllegalArgumentException("help");
            }

            Path sqHome = null;
            boolean dryRun = false;
            Duration timeout = Duration.ofMinutes(30);
            List<String> sqCliArgs = List.of();
            boolean passthrough = false;

            for (int i = 0; i < args.length; i++) {
                if (passthrough) {
                    sqCliArgs = List.of(java.util.Arrays.copyOfRange(args, i, args.length));
                    break;
                }
                String arg = args[i];
                switch (arg) {
                    case "--" -> passthrough = true;
                    case "--sq-home" -> {
                        sqHome = Path.of(requireValue(args, ++i, "--sq-home"));
                    }
                    case "--dry-run" -> dryRun = true;
                    case "--timeout-secs" -> {
                        timeout = Duration.ofSeconds(Long.parseLong(requireValue(args, ++i, "--timeout-secs")));
                    }
                    default -> throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }

            if (sqCliArgs.isEmpty()) {
                throw new IllegalArgumentException("sqcli args required after '--' (e.g. -- -symbol action=list)");
            }

            SqCliOptions options = new SqCliOptions(sqHome, dryRun, dryRun ? null : timeout);
            return new ParsedCli(options, sqCliArgs);
        }

        private static String requireValue(String[] args, int index, String flag) {
            if (index >= args.length) {
                throw new IllegalArgumentException("Missing value for " + flag);
            }
            return args[index];
        }

        static void printUsage() {
            System.out.println("""
                SqCliRunner — invoke StrategyQuant sqcli from SQ_HOME (story 21-4)

                Usage:
                  export SQ_HOME="$HOME/sq-bridge/SQ_HOME"
                  mvn exec:java -pl trading-parser \\
                    -Dexec.mainClass=com.martinfou.trading.parser.bridge.SqCliRunner \\
                    -Dexec.args="--dry-run -- -symbol action=list"

                  --sq-home PATH     Override SQ_HOME
                  --dry-run          Print command without executing
                  --timeout-secs N   Kill after N seconds (default 1800)
                  --                 sqcli arguments (required)

                Examples:
                  -- -symbol action=list
                  -- -data action=update
                  -- -run file=/path/commands.txt

                SQ output redirect (>) is sqcli syntax — pass tokens after --, e.g.:
                  -- -symbol action=list ">" /path/out.log

                See docs/contributing.md § StrategyQuant X sur Mac.
                """);
        }
    }
}
