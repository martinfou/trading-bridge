package com.martinfou.trading.runtime;

/**
 * Wires {@link EventStore} with optional live {@link RunEventHub} broadcast.
 */
public final class RuntimeStores {

    public record Bundle(EventStore eventStore, RunEventHub hub) implements AutoCloseable {
        @Override
        public void close() {
            eventStore.close();
        }
    }

    private RuntimeStores() {}

    public static Bundle inMemoryWithBroadcast() {
        RunEventHub hub = new RunEventHub();
        EventStore delegate = EventStores.inMemory();
        return new Bundle(new BroadcastingEventStore(delegate, hub), hub);
    }

    public static Bundle sqliteWithBroadcast(EventStoreConfig config) {
        RunEventHub hub = new RunEventHub();
        EventStore delegate = EventStores.sqlite(config);
        return new Bundle(new BroadcastingEventStore(delegate, hub), hub);
    }
}
