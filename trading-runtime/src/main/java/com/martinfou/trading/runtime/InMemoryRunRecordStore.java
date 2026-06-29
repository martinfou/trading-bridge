package com.martinfou.trading.runtime;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory implementation of {@link RunRecordStore} for testing (Story 34-1). */
public final class InMemoryRunRecordStore implements RunRecordStore {
    private final Map<String, RunRecord> records = new ConcurrentHashMap<>();

    @Override
    public void save(RunRecord record) {
        if (record != null) {
            records.put(record.runId(), record);
        }
    }

    @Override
    public Optional<RunRecord> get(String runId) {
        if (runId == null) return Optional.empty();
        return Optional.ofNullable(records.get(runId));
    }

    @Override
    public List<RunRecord> listAll() {
        return List.copyOf(records.values());
    }

    @Override
    public void delete(String runId) {
        if (runId != null) {
            records.remove(runId);
        }
    }

    @Override
    public void close() {}
}
