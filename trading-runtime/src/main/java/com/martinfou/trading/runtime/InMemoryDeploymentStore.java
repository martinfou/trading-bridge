package com.martinfou.trading.runtime;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory {@link DeploymentStore} for tests. */
public final class InMemoryDeploymentStore implements DeploymentStore {

    private final Map<String, DeploymentRecord> records = new ConcurrentHashMap<>();

    @Override
    public Optional<DeploymentRecord> get(String strategyId) {
        return Optional.ofNullable(records.get(strategyId));
    }

    @Override
    public void save(DeploymentRecord record) {
        records.put(record.strategyId(), record);
    }

    @Override
    public void delete(String strategyId) {
        records.remove(strategyId);
    }

    @Override
    public List<DeploymentRecord> listAll() {
        return List.copyOf(records.values());
    }

    @Override
    public void close() {
        records.clear();
    }
}
