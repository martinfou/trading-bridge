package com.martinfou.trading.intelligence.compile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * Manifest written to {@code compiled/{weekId}/manifest.json} after successful codegen + compile.
 *
 * <p>{@code validFrom} / {@code validUntil} — strategy TTL (UTC). Paper runs should not execute
 * outside this window; {@code validUntil} is typically Friday 21:00 UTC of the target trading week.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CompileManifest(
    String weekId,
    String correlationId,
    Instant compiledAt,
    Instant validFrom,
    Instant validUntil,
    List<StrategyEntry> strategies,
    String planFile,
    String origin,
    RiskSnapshot riskEnvelope
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StrategyEntry(
        String strategyId,
        String className,
        String templateId,
        String pair
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RiskSnapshot(
        Double lotSize,
        Double capital
    ) {}

    public static final String ORIGIN_AI = "AI";

    public double resolvedLotSize() {
        if (riskEnvelope != null && riskEnvelope.lotSize() != null && riskEnvelope.lotSize() > 0) {
            return riskEnvelope.lotSize();
        }
        return 0.01;
    }

    public double resolvedCapital() {
        if (riskEnvelope != null && riskEnvelope.capital() != null && riskEnvelope.capital() > 0) {
            return riskEnvelope.capital();
        }
        return 100_000.0;
    }
}
