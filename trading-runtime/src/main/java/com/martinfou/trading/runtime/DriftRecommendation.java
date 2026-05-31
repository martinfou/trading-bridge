package com.martinfou.trading.runtime;

/** Drift advisory recommendation (FR-15 / Story 17.5). */
public enum DriftRecommendation {
    HOLD,
    REVIEW_PARAMS,
    PAUSE,
    RETIRE
}
