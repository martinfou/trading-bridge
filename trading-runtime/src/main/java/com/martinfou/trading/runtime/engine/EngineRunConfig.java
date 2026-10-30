package com.martinfou.trading.runtime.engine;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Declarative run configuration for the Futures Trading Engine (PRD §5 F1, F2, F5; Decision D6, D8).
 *
 * <p>Canonical file path convention: {@code config/runs/<strategy>-<symbol>-<mode>.json}.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EngineRunConfig(
    @JsonProperty("strategy") String strategy,
    @JsonProperty("symbol") String symbol,
    @JsonProperty("timeframe") String timeframe,
    @JsonProperty("mode") String mode,
    @JsonProperty("broker") String broker,
    @JsonProperty("period") PeriodSpec period,
    @JsonProperty("risk") RiskSpec risk,
    @JsonProperty("capital") double capital,
    @JsonProperty("costs") CostsSpec costs,
    @JsonProperty("validatedBy") String validatedBy,
    @JsonProperty("unvalidated") boolean unvalidated,
    @JsonProperty("liveAccountEnabled") boolean liveAccountEnabled
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PeriodSpec(
        @JsonProperty("start") String start,
        @JsonProperty("end") String end,
        @JsonProperty("dataSource") String dataSource,
        @JsonProperty("filePath") String filePath
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RiskSpec(
        @JsonProperty("type") String type,
        @JsonProperty("value") double value
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CostsSpec(
        @JsonProperty("commissionPerContract") double commissionPerContract,
        @JsonProperty("slippageFixed") double slippageFixed,
        @JsonProperty("fillMode") String fillMode,
        @JsonProperty("rolloverEnabled") Boolean rolloverEnabled,
        @JsonProperty("marginTracking") Boolean marginTracking
    ) {
        public boolean isMarginTrackingEnabled() {
            return marginTracking == null || marginTracking;
        }

        public boolean isRolloverEnabled() {
            return rolloverEnabled != null && rolloverEnabled;
        }
    }

    public static EngineRunConfig load(Path configPath) throws IOException {
        Objects.requireNonNull(configPath, "configPath is required");
        if (!Files.exists(configPath)) {
            throw new IOException("Engine run config not found: " + configPath);
        }
        return MAPPER.readValue(Files.readString(configPath), EngineRunConfig.class);
    }
}

