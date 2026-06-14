package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.events.RunEvent;

import java.util.List;

/**
 * Append-only store for {@link RunEvent} records, keyed by run id with global sequence ordering.
 */
public interface EventStore extends AutoCloseable {

    long append(String runId, RunEvent event);

    List<RunEvent> query(String runId, long afterSequence, int limit);

    long count(String runId);

    List<RunEvent> replayAll(String runId);

    List<StoredRunEvent> queryWithSequence(String runId, long afterSequence, int limit);

    default void publishEphemeral(String runId, RunEvent event) {}

    @Override
    void close();
}
