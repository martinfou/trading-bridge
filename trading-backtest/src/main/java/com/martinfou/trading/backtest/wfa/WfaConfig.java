package com.martinfou.trading.backtest.wfa;

import com.martinfou.trading.core.strategy.ParameterRange;
import java.util.List;

/**
 * Configuration for a Walk-Forward Analysis run.
 */
public record WfaConfig(
    String instrument,
    double initialCapital,
    int inSampleDays,
    int outOfSampleDays,
    boolean anchored,
    List<ParameterRange> parameterRanges
) {
    public WfaConfig {
        if (instrument == null || instrument.isBlank()) {
            throw new IllegalArgumentException("Instrument cannot be null or empty");
        }
        if (Double.isNaN(initialCapital) || Double.isInfinite(initialCapital) || initialCapital <= 0) {
            throw new IllegalArgumentException("Initial capital must be positive and finite");
        }
        if (inSampleDays <= 0) {
            throw new IllegalArgumentException("In-sample days must be positive");
        }
        if (outOfSampleDays <= 0) {
            throw new IllegalArgumentException("Out-of-sample days must be positive");
        }
        if (parameterRanges == null || parameterRanges.isEmpty()) {
            throw new IllegalArgumentException("Parameter ranges list cannot be null or empty");
        }
        for (ParameterRange r : parameterRanges) {
            if (r == null) {
                throw new IllegalArgumentException("Parameter ranges cannot contain null");
            }
        }
        long uniqueNames = parameterRanges.stream().map(ParameterRange::name).distinct().count();
        if (uniqueNames < parameterRanges.size()) {
            throw new IllegalArgumentException("Parameter ranges contain duplicate names");
        }
    }
}
