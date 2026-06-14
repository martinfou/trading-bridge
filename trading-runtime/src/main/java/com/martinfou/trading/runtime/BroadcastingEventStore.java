package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.events.RunEvent;

import java.util.List;

/**
 * {@link EventStore} decorator that broadcasts appended events to a {@link RunEventHub}.
 */
public final class BroadcastingEventStore implements EventStore {

    private final EventStore delegate;
    private final RunEventHub hub;

    public BroadcastingEventStore(EventStore delegate, RunEventHub hub) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (hub == null) {
            throw new IllegalArgumentException("hub must not be null");
        }
        this.delegate = delegate;
        this.hub = hub;
    }

    @Override
    public long append(String runId, RunEvent event) {
        long sequence = delegate.append(runId, event);
        hub.publish(runId, new StoredRunEvent(sequence, event));
        return sequence;
    }

    @Override
    public void publishEphemeral(String runId, RunEvent event) {
        hub.publish(runId, new StoredRunEvent(-1L, event));
    }

    @Override
    public List<RunEvent> query(String runId, long afterSequence, int limit) {
        return delegate.query(runId, afterSequence, limit);
    }

    @Override
    public long count(String runId) {
        return delegate.count(runId);
    }

    @Override
    public List<RunEvent> replayAll(String runId) {
        return delegate.replayAll(runId);
    }

    @Override
    public List<StoredRunEvent> queryWithSequence(String runId, long afterSequence, int limit) {
        return delegate.queryWithSequence(runId, afterSequence, limit);
    }

    @Override
    public void close() {
        delegate.close();
    }

    EventStore delegate() {
        return delegate;
    }
}
