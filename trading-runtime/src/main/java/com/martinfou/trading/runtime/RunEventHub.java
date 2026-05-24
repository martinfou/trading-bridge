package com.martinfou.trading.runtime;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * In-process pub/sub for live {@link StoredRunEvent} broadcast per run id.
 */
public final class RunEventHub {

    private final Map<String, CopyOnWriteArraySet<Consumer<String>>> subscribers = new ConcurrentHashMap<>();

    /** Subscribe to live events for {@code runId}. Returns a closeable unsubscribe handle. */
    public AutoCloseable subscribe(String runId, Consumer<String> listener) {
        EventStoreValidation.requireRunId(runId);
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        subscribers.computeIfAbsent(runId, id -> new CopyOnWriteArraySet<>()).add(listener);
        return () -> unsubscribe(runId, listener);
    }

    public void publish(String runId, StoredRunEvent stored) {
        EventStoreValidation.requireRunId(runId);
        if (stored == null) {
            throw new IllegalArgumentException("stored event must not be null");
        }
        String json = RunEventMessages.toJson(stored);
        CopyOnWriteArraySet<Consumer<String>> listeners = subscribers.get(runId);
        if (listeners == null) {
            return;
        }
        for (Consumer<String> listener : listeners) {
            listener.accept(json);
        }
    }

    public void unsubscribe(String runId, Consumer<String> listener) {
        CopyOnWriteArraySet<Consumer<String>> listeners = subscribers.get(runId);
        if (listeners != null) {
            listeners.remove(listener);
            if (listeners.isEmpty()) {
                subscribers.remove(runId, listeners);
            }
        }
    }
}
