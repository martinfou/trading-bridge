package com.martinfou.trading.backtest.wfa;

import java.time.Instant;

/**
 * Record representing a Walk-Forward fold containing In-Sample (training)
 * and Out-of-Sample (testing/validation) chronological boundaries.
 */
public record WfaFold(
    int index,
    Instant isStart,
    Instant isEnd,
    Instant oosStart,
    Instant oosEnd
) {}
