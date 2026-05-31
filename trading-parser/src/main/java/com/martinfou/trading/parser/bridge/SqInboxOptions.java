package com.martinfou.trading.parser.bridge;

import java.nio.file.Path;
import java.util.List;

/**
 * CLI options for {@link SqInboxProcessor} (story 21-2).
 *
 * @param repoRoot    repository root containing {@code data/sq-inbox/}
 * @param symbol      override instrument (default: manifest symbol)
 * @param capital     initial backtest capital
 * @param syntheticBars bar count when no {@code --data-path} (default 500)
 * @param dataPath    optional historical data path or year args (e.g. EUR_USD 2012)
 * @param maxXmlBytes maximum XML payload size (default 10 MiB)
 * @param sqFeedback  after batch, export fitness CSV and import via sqcli (story 21-8)
 */
public record SqInboxOptions(
    Path repoRoot,
    String symbol,
    double capital,
    int syntheticBars,
    List<String> dataPath,
    long maxXmlBytes,
    boolean sqFeedback,
    boolean feedbackSkipMutex
) {
    public static final double DEFAULT_CAPITAL = 100_000.0;
    public static final int DEFAULT_SYNTHETIC_BARS = 500;
    public static final long DEFAULT_MAX_XML_BYTES = 10L * 1024 * 1024;

    public SqInboxOptions {
        if (syntheticBars < 10) {
            syntheticBars = DEFAULT_SYNTHETIC_BARS;
        }
        if (maxXmlBytes <= 0) {
            maxXmlBytes = DEFAULT_MAX_XML_BYTES;
        }
    }

    public SqInboxOptions(Path repoRoot, String symbol, double capital, int syntheticBars, List<String> dataPath) {
        this(repoRoot, symbol, capital, syntheticBars, dataPath, DEFAULT_MAX_XML_BYTES, false, false);
    }

    public SqInboxOptions(
        Path repoRoot,
        String symbol,
        double capital,
        int syntheticBars,
        List<String> dataPath,
        long maxXmlBytes
    ) {
        this(repoRoot, symbol, capital, syntheticBars, dataPath, maxXmlBytes, false, false);
    }

    public SqInboxOptions(
        Path repoRoot,
        String symbol,
        double capital,
        int syntheticBars,
        List<String> dataPath,
        long maxXmlBytes,
        boolean sqFeedback
    ) {
        this(repoRoot, symbol, capital, syntheticBars, dataPath, maxXmlBytes, sqFeedback, false);
    }

    SqJobOptions toJobOptions(boolean dryRun, boolean skipMutex, java.time.Duration timeout) {
        return new SqJobOptions(repoRoot, null, dryRun, skipMutex, timeout);
    }

    public String effectiveSymbol(String manifestSymbol) {
        if (symbol != null && !symbol.isBlank()) {
            return symbol.trim();
        }
        return manifestSymbol != null && !manifestSymbol.isBlank() ? manifestSymbol : "EUR_USD";
    }
}
