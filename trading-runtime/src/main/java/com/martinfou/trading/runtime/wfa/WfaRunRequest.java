package com.martinfou.trading.runtime.wfa;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;

/**
 * DTO for starting a Walk-Forward Analysis run.
 */
public record WfaRunRequest(
    String strategyName,
    String symbol,
    String assetClass,
    String timeframe,
    String startDate,
    String endDate,
    int inSampleDays,
    int outOfSampleDays,
    boolean isAnchored,
    double initialCapital,
    List<ParameterRangeDto> parameterRanges,
    String yearSpec,
    Double lotSize,
    Double commissionPerTrade,
    Double slippagePct
) {
    @JsonIgnore
    public WfaRunRequest(
        String strategyName,
        String symbol,
        String assetClass,
        String timeframe,
        String startDate,
        String endDate,
        int inSampleDays,
        int outOfSampleDays,
        boolean isAnchored,
        double initialCapital,
        List<ParameterRangeDto> parameterRanges
    ) {
        this(
            strategyName, symbol, assetClass, timeframe, startDate, endDate,
            inSampleDays, outOfSampleDays, isAnchored, initialCapital,
            parameterRanges, null, null, null, null
        );
    }

    public WfaRunRequest {
        if (strategyName == null || strategyName.isBlank()) {
            throw new IllegalArgumentException("strategyName cannot be null or blank");
        }
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol cannot be null or blank");
        }
        if (assetClass == null || assetClass.isBlank()) {
            assetClass = "FOREX";
        }
        if (timeframe == null || timeframe.isBlank()) {
            timeframe = "H1";
        } else if ("DAILY".equalsIgnoreCase(timeframe.trim()) || "1D".equalsIgnoreCase(timeframe.trim())) {
            timeframe = "D1";
        } else {
            timeframe = timeframe.trim().toUpperCase();
        }
        if (inSampleDays <= 0) {
            inSampleDays = 180;
        }
        if (outOfSampleDays <= 0) {
            outOfSampleDays = 60;
        }
        if (initialCapital <= 0 || Double.isNaN(initialCapital) || Double.isInfinite(initialCapital)) {
            initialCapital = 10000.0;
        }
        if (parameterRanges == null || parameterRanges.isEmpty()) {
            throw new IllegalArgumentException("parameterRanges cannot be null or empty");
        }
    }
}
