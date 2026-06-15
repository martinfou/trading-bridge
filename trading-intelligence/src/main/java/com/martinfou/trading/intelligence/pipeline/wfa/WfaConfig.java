package com.martinfou.trading.intelligence.pipeline.wfa;

import com.martinfou.trading.core.Bar;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration for Walk-Forward Analysis.
 *
 * Defaults: 12 months IS, 4 weeks OOS, 1 month step, purge 240 bars at boundaries.
 */
public record WfaConfig(
    int inSampleMonths,
    int outOfSampleWeeks,
    int stepMonths,
    int boundaryPurgeBars
) {
    public static final int DEFAULT_IS_MONTHS = 12;
    public static final int DEFAULT_OOS_WEEKS = 4;
    public static final int DEFAULT_STEP_MONTHS = 1;
    public static final int DEFAULT_PURGE_BARS = 240;  // ~10 days H1

    public WfaConfig {
        if (inSampleMonths <= 0) inSampleMonths = DEFAULT_IS_MONTHS;
        if (outOfSampleWeeks <= 0) outOfSampleWeeks = DEFAULT_OOS_WEEKS;
        if (stepMonths <= 0) stepMonths = DEFAULT_STEP_MONTHS;
        if (boundaryPurgeBars <= 0) boundaryPurgeBars = DEFAULT_PURGE_BARS;
    }

    public WfaConfig() {
        this(DEFAULT_IS_MONTHS, DEFAULT_OOS_WEEKS, DEFAULT_STEP_MONTHS, DEFAULT_PURGE_BARS);
    }
}
