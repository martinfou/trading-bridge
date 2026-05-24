package com.martinfou.trading.runtime;

/**
 * Factory helpers for {@link EventStore} implementations.
 */
public final class EventStores {

    private EventStores() {}

    public static EventStore inMemory() {
        return new InMemoryEventStore();
    }

    public static EventStore sqlite(EventStoreConfig config) {
        return new SqliteEventStore(config);
    }

    public static EventStore sqliteDefault() {
        return sqlite(EventStoreConfig.defaults());
    }
}
