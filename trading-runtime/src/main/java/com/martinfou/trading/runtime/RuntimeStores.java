package com.martinfou.trading.runtime;

/**
 * Wires runtime persistence: events, broadcast hub, and deployments.
 */
public final class RuntimeStores {

    public record Bundle(EventStore eventStore, RunEventHub hub, DeploymentStore deploymentStore)
        implements AutoCloseable {

        @Override
        public void close() {
            eventStore.close();
            deploymentStore.close();
        }
    }

    private RuntimeStores() {}

    public static Bundle inMemoryWithBroadcast() {
        RunEventHub hub = new RunEventHub();
        EventStore delegate = EventStores.inMemory();
        return new Bundle(
            new BroadcastingEventStore(delegate, hub),
            hub,
            new InMemoryDeploymentStore());
    }

    public static Bundle sqliteWithBroadcast(EventStoreConfig config) {
        RunEventHub hub = new RunEventHub();
        EventStore delegate = EventStores.sqlite(config);
        return new Bundle(
            new BroadcastingEventStore(delegate, hub),
            hub,
            new SqliteDeploymentStore(config));
    }
}
