package com.martinfou.trading.parser.bridge;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Sidecar metadata for a StrategyQuant XML drop in {@code data/sq-inbox/} (story 21-1).
 */
public record StrategyManifest(
    String id,
    String symbol,
    String timeframe,
    String sqBuild,
    String contentSha256,
    Instant exportedAt
) {
    private static final Pattern SHA256_HEX = Pattern.compile("[a-f0-9]{64}");

    public StrategyManifest {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(contentSha256, "contentSha256");
        Objects.requireNonNull(exportedAt, "exportedAt");
        if (id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (contentSha256.isBlank()) {
            throw new IllegalArgumentException("contentSha256 must not be blank");
        }
        if (!SHA256_HEX.matcher(contentSha256).matches()) {
            throw new IllegalArgumentException("contentSha256 must be 64 lowercase hex digits");
        }
        if (symbol == null || symbol.isBlank()) {
            symbol = "EUR_USD";
        }
        if (timeframe == null || timeframe.isBlank()) {
            timeframe = "UNKNOWN";
        }
        if (sqBuild == null) {
            sqBuild = "";
        }
    }
}
