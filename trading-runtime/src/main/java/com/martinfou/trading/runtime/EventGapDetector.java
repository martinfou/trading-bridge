package com.martinfou.trading.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * Detects missing sequence numbers in a run event log (ADR-13-11).
 */
public final class EventGapDetector {

    public record Gap(long fromSequence, long toSequence) {}

    public record Result(long eventCount, long maxSequence, List<Gap> gaps, boolean complete) {}

    private EventGapDetector() {}

    public static Result analyze(String runId, EventStore store) {
        List<StoredRunEvent> all = store.queryWithSequence(runId, 0, Integer.MAX_VALUE);
        if (all.isEmpty()) {
            return new Result(0, 0, List.of(), true);
        }

        long maxSequence = all.getLast().sequence();
        List<Gap> gaps = new ArrayList<>();
        long expected = 1;
        for (StoredRunEvent stored : all) {
            long seq = stored.sequence();
            if (seq > expected) {
                gaps.add(new Gap(expected, seq - 1));
            }
            expected = Math.max(expected, seq + 1);
        }

        boolean complete = gaps.isEmpty() && all.size() == maxSequence;
        return new Result(all.size(), maxSequence, List.copyOf(gaps), complete);
    }
}
