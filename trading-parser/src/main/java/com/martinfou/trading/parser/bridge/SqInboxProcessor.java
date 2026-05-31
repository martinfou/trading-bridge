package com.martinfou.trading.parser.bridge;

import com.martinfou.trading.backtest.BacktestResult;
import com.martinfou.trading.backtest.RunContext;
import com.martinfou.trading.backtest.RunMode;
import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.data.HistoricalDataLoader;
import com.martinfou.trading.parser.codegen.SqInterpretedStrategy;
import com.martinfou.trading.parser.sq.SqStrategyDocument;
import com.martinfou.trading.parser.sq.SqXmlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Scans {@code data/sq-inbox/pending/}, validates XML, runs an interpreted backtest, and
 * classifies files into {@code passed/}, {@code failed/}, or {@code dlq/} (stories 21-2, 21-3).
 */
public final class SqInboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(SqInboxProcessor.class);

    public static void main(String[] args) {
        try {
            SqInboxOptions options = SqInboxCli.parse(args);
            int exit = new SqInboxProcessor().run(options);
            System.exit(exit);
        } catch (IllegalArgumentException e) {
            if ("help".equals(e.getMessage())) {
                SqInboxCli.printUsage();
                System.exit(0);
            }
            System.err.println(e.getMessage());
            SqInboxCli.printUsage();
            System.exit(1);
        } catch (IOException e) {
            System.err.println("Inbox error: " + e.getMessage());
            System.exit(1);
        }
    }

    int run(SqInboxOptions options) throws IOException {
        SqInboxBatchResult batch = runBatch(options);
        if (options.sqFeedback()) {
            runSqFeedback(options);
        }
        return batch.exitCode();
    }

    private void runSqFeedback(SqInboxOptions options) throws IOException {
        try {
            SqFitnessFeedbackService.SqFitnessFeedbackResult feedback = new SqFitnessFeedbackService().run(
                options.toJobOptions(false, options.feedbackSkipMutex(), java.time.Duration.ofMinutes(5))
            );
            log.info("SQ fitness feedback: {}", feedback.message());
            if (feedback.feedbackExitCode() != 0) {
                throw new IOException("SQ fitness feedback failed: " + feedback.message());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("SQ fitness feedback interrupted", e);
        }
    }

    SqInboxBatchResult runBatch(SqInboxOptions options) throws IOException {
        return runBatch(options, null);
    }

    public SqInboxBatchResult runBatch(SqInboxOptions options, SqInboxProgressListener listener) throws IOException {
        Path pending = SqInboxPaths.pending(options.repoRoot());
        Path passed = SqInboxPaths.passed(options.repoRoot());
        Path failed = SqInboxPaths.failed(options.repoRoot());
        Path dlq = SqInboxPaths.dlq(options.repoRoot());

        if (!Files.isDirectory(pending)) {
            throw new IOException("Pending inbox missing: " + pending);
        }

        List<Path> xmlFiles;
        try (Stream<Path> stream = Files.list(pending)) {
            xmlFiles = stream
                .filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".xml"))
                .sorted()
                .toList();
        }

        if (xmlFiles.isEmpty()) {
            log.info("No XML files in {}", pending);
            return SqInboxBatchResult.empty();
        }

        int passedCount = 0;
        int failedCount = 0;
        int dlqCount = 0;
        for (Path xml : xmlFiles) {
            ProcessOutcome outcome = processOne(xml, options, pending, passed, failed, dlq, listener);
            switch (outcome) {
                case PASSED -> passedCount++;
                case FAILED -> failedCount++;
                case DLQ -> dlqCount++;
            }
        }
        log.info(
            "Processed {} file(s): {} passed, {} failed, {} dlq",
            xmlFiles.size(), passedCount, failedCount, dlqCount
        );
        return new SqInboxBatchResult(xmlFiles.size(), passedCount, failedCount, dlqCount);
    }

    private enum ProcessOutcome {
        PASSED, FAILED, DLQ
    }

    ProcessOutcome processOne(
        Path xml,
        SqInboxOptions options,
        Path pendingDir,
        Path passedDir,
        Path failedDir,
        Path dlqDir,
        SqInboxProgressListener listener
    ) throws IOException {
        Path manifestPath = SqInboxPaths.manifestPathFor(xml);
        String symbol = "EUR_USD";
        String manifestId = xml.getFileName().toString();
        SqXmlCoverageReport coverage = emptyCoverage();

        try {
            SqInboxValidation.requireConfinedToPending(xml, pendingDir);
            SqInboxValidation.requireWithinSizeLimit(Files.size(xml), options.maxXmlBytes());

            byte[] xmlBytes = Files.readAllBytes(xml);
            StrategyManifest manifest = StrategyManifestIO.ensureSidecar(xml, xmlBytes);
            manifestId = manifest.id();
            symbol = options.effectiveSymbol(manifest.symbol());

            SqStrategyDocument document = SqXmlParser.parse(new ByteArrayInputStream(xmlBytes));
            coverage = SqXmlCoverageValidator.analyze(document);

            if (coverage.requiresDlq()) {
                return finishDlq(xml, manifestPath, dlqDir, manifestId, symbol, coverage, coverage.dlqReason(), listener);
            }

            Strategy strategy = SqInterpretedStrategy.fromDocument(document, manifestId, symbol);
            List<Bar> bars = loadBars(options, symbol);
            if (bars.isEmpty()) {
                throw new IOException("No bars loaded for backtest");
            }

            BacktestResult result = RunContext.forStrategy(
                strategy,
                symbol,
                RunMode.BACKTEST,
                bars,
                options.capital()
            ).run();

            SqInboxResult summary = SqInboxResult.success(manifestId, symbol, result);

            return finishPassed(xml, manifestPath, passedDir, manifestId, symbol, coverage, summary, result, listener);
        } catch (InboxValidationException e) {
            log.warn("DLQ {}: {}", xml.getFileName(), e.getMessage());
            return finishDlq(xml, manifestPath, dlqDir, manifestId, symbol, coverage, e.getMessage(), listener);
        } catch (Exception e) {
            log.warn("FAIL {}: {}", xml.getFileName(), e.getMessage());
            SqInboxResult summary = SqInboxResult.failure(manifestId, symbol, e.getMessage());
            if (Files.exists(xml)) {
                return finishFailed(xml, manifestPath, failedDir, coverage, summary, listener);
            }
            notifyListener(listener, xml.getFileName().toString(), "failed", manifestId);
            return ProcessOutcome.FAILED;
        }
    }

    private static void notifyListener(
        SqInboxProgressListener listener,
        String fileName,
        String disposition,
        String manifestId
    ) {
        if (listener != null) {
            listener.onFileProcessed(fileName, disposition, manifestId);
        }
    }

    private static ProcessOutcome finishPassed(
        Path xml,
        Path manifestPath,
        Path passedDir,
        String manifestId,
        String symbol,
        SqXmlCoverageReport coverage,
        SqInboxResult summary,
        BacktestResult result,
        SqInboxProgressListener listener
    ) throws IOException {
        SqInboxTransfers.moveToFolder(xml, manifestPath, passedDir);
        Path movedXml = passedDir.resolve(xml.getFileName());
        SqCoverageIO.write(coverage, SqInboxTransfers.coveragePathIn(passedDir, movedXml));
        SqInboxResultIO.write(summary, SqInboxTransfers.resultPathIn(passedDir, movedXml));
        log.info("PASS {} trades={} return%={}", manifestId, result.totalTrades(), result.totalReturnPct());
        notifyListener(listener, movedXml.getFileName().toString(), "passed", manifestId);
        return ProcessOutcome.PASSED;
    }

    private static ProcessOutcome finishDlq(
        Path xml,
        Path manifestPath,
        Path dlqDir,
        String manifestId,
        String symbol,
        SqXmlCoverageReport coverage,
        String reason,
        SqInboxProgressListener listener
    ) throws IOException {
        SqInboxResult summary = SqInboxResult.dlq(manifestId, symbol, reason);
        if (Files.exists(xml)) {
            SqInboxTransfers.moveToFolder(xml, manifestPath, dlqDir);
            Path movedXml = dlqDir.resolve(xml.getFileName());
            SqCoverageIO.write(coverage, SqInboxTransfers.coveragePathIn(dlqDir, movedXml));
            SqInboxResultIO.write(summary, SqInboxTransfers.resultPathIn(dlqDir, movedXml));
        }
        log.info("DLQ {}: {}", manifestId, reason);
        if (Files.exists(dlqDir.resolve(xml.getFileName()))) {
            notifyListener(listener, xml.getFileName().toString(), "dlq", manifestId);
        }
        return ProcessOutcome.DLQ;
    }

    private static ProcessOutcome finishFailed(
        Path xml,
        Path manifestPath,
        Path failedDir,
        SqXmlCoverageReport coverage,
        SqInboxResult summary,
        SqInboxProgressListener listener
    ) throws IOException {
        SqInboxTransfers.moveToFolder(xml, manifestPath, failedDir);
        Path movedXml = failedDir.resolve(xml.getFileName());
        SqCoverageIO.write(coverage, SqInboxTransfers.coveragePathIn(failedDir, movedXml));
        SqInboxResultIO.write(summary, SqInboxTransfers.resultPathIn(failedDir, movedXml));
        notifyListener(listener, movedXml.getFileName().toString(), "failed", summary.manifestId());
        return ProcessOutcome.FAILED;
    }

    private static SqXmlCoverageReport emptyCoverage() {
        return new SqXmlCoverageReport(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    static List<Bar> loadBars(SqInboxOptions options, String symbol) throws IOException {
        if (options.dataPath() != null && !options.dataPath().isEmpty()) {
            List<String> args = options.dataPath();
            if (args.size() == 1 && "sample".equalsIgnoreCase(args.getFirst())) {
                return syntheticBars(symbol, options.syntheticBars());
            }
            if (args.size() == 1 && Files.exists(Path.of(args.getFirst()))) {
                return HistoricalDataLoader.loadFile(Path.of(args.getFirst()), symbol).bars();
            }
            return HistoricalDataLoader.loadFromArgs(symbol, args.toArray(String[]::new)).bars();
        }
        return syntheticBars(symbol, options.syntheticBars());
    }

    static List<Bar> syntheticBars(String symbol, int count) {
        Instant t = Instant.parse("2020-01-01T00:00:00Z");
        List<Bar> bars = new ArrayList<>(count);
        double price = 1.1;
        for (int i = 0; i < count; i++) {
            double open = price;
            double close = price + 0.0005 * (i % 5 - 2);
            double high = Math.max(open, close) + 0.0002;
            double low = Math.min(open, close) - 0.0002;
            bars.add(new Bar(symbol, t.plusSeconds(i * 3600L), open, high, low, close, 0));
            price = close;
        }
        return bars;
    }

    static Path resolveRepoRoot() {
        return SqBridgePaths.resolveRepoRoot();
    }

    /** CLI argument parsing for {@link #main}. */
    static final class SqInboxCli {

        private SqInboxCli() {}

        static SqInboxOptions parse(String[] args) {
            if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
                throw new IllegalArgumentException("help");
            }
            String symbol = null;
            double capital = SqInboxOptions.DEFAULT_CAPITAL;
            int bars = SqInboxOptions.DEFAULT_SYNTHETIC_BARS;
            List<String> dataPath = List.of();
            boolean sqFeedback = false;
            boolean feedbackSkipMutex = false;

            for (int i = 0; i < args.length; ) {
                String arg = args[i];
                switch (arg) {
                    case "--symbol" -> {
                        symbol = requireValue(args, i + 1, "--symbol");
                        i += 2;
                    }
                    case "--capital" -> {
                        capital = Double.parseDouble(requireValue(args, i + 1, "--capital"));
                        i += 2;
                    }
                    case "--bars" -> {
                        bars = Integer.parseInt(requireValue(args, i + 1, "--bars"));
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
                    case "--no-lock" -> {
                        feedbackSkipMutex = true;
                        i++;
                    }
                    default -> throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }

            return new SqInboxOptions(
                resolveRepoRoot(), symbol, capital, bars, dataPath,
                SqInboxOptions.DEFAULT_MAX_XML_BYTES, sqFeedback, feedbackSkipMutex
            );
        }

        private static List<String> collectDataPathArgs(String[] args, int start) {
            List<String> collected = new ArrayList<>();
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
                SqInboxProcessor — process data/sq-inbox/pending/*.xml

                Usage:
                  mvn exec:java -pl trading-parser \\
                    -Dexec.mainClass=com.martinfou.trading.parser.bridge.SqInboxProcessor \\
                    -Dexec.args="--bars 500"

                  --symbol EUR_USD     Override instrument (default: manifest)
                  --capital 100000     Initial capital (default 100000)
                  --bars 500           Synthetic bar count when no data (default 500)
                  --data-path sample   Synthetic bars (alias for default)
                  --data-path EUR_USD 2012
                  --data-path path/to/file.bars
                  --sq-feedback        Export fitness CSV and import via sqcli (story 21-8)
                  --no-lock            Skip sqcli mutex for feedback (tests only)

                Offline: no control plane required. See docs/contributing.md.
                """);
        }
    }
}
