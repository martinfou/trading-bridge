package com.martinfou.trading.intelligence.pipeline.wfa;

import com.martinfou.trading.core.Bar;

import java.time.Instant;
import java.util.List;

/**
 * A single IS/OOS window in a Walk-Forward Analysis.
 */
public record WfaWindow(
    int index,
    List<Bar> inSampleBars,
    List<Bar> outOfSampleBars,
    Instant isStart,
    Instant isEnd,
    Instant oosStart,
    Instant oosEnd
) {
    public int totalBars() {
        return inSampleBars.size() + outOfSampleBars.size();
    }
}
