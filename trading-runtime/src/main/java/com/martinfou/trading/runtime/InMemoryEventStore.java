package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.events.RunEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory {@link EventStore} for unit tests and ephemeral runs.
 */
public final class InMemoryEventStore implements EventStore {

    private record StoredEvent(long sequence, String runId, RunEvent event) {}

    private final List<StoredEvent> events = new ArrayList<>();
    private final AtomicLong nextSequence = new AtomicLong(1);

    @Override
    public synchronized long append(String runId, RunEvent event) {
        EventStoreValidation.requireRunId(runId);
        EventStoreValidation.requireEvent(event);
        long sequence = nextSequence.getAndIncrement();
        events.add(new StoredEvent(sequence, runId, event));
        return sequence;
    }

    @Override
    public synchronized void clear(String runId) {
        EventStoreValidation.requireRunId(runId);
        events.removeIf(e -> e.runId().equals(runId));
    }

    @Override
    public synchronized List<RunEvent> query(String runId, long afterSequence, int limit) {
        return queryWithSequence(runId, afterSequence, limit).stream()
            .map(StoredRunEvent::event)
            .toList();
    }

    @Override
    public synchronized List<StoredRunEvent> queryWithSequence(String runId, long afterSequence, int limit) {
        EventStoreValidation.requireRunId(runId);
        EventStoreValidation.requireLimit(limit);
        List<StoredRunEvent> result = new ArrayList<>(Math.min(limit, events.size()));
        for (StoredEvent stored : events) {
            if (!stored.runId().equals(runId)) {
                continue;
            }
            if (stored.sequence() <= afterSequence) {
                continue;
            }
            result.add(new StoredRunEvent(stored.sequence(), stored.event()));
            if (result.size() >= limit) {
                break;
            }
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized long count(String runId) {
        EventStoreValidation.requireRunId(runId);
        return events.stream().filter(e -> e.runId().equals(runId)).count();
    }

    @Override
    public synchronized List<RunEvent> replayAll(String runId) {
        EventStoreValidation.requireRunId(runId);
        return events.stream()
            .filter(e -> e.runId().equals(runId))
            .map(StoredEvent::event)
            .toList();
    }

    @Override
    public void close() {
        // no resources
    }
}
