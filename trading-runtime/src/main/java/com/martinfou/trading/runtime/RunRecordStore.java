package com.martinfou.trading.runtime;

import java.util.List;
import java.util.Optional;

/** Persistent or in-memory storage for {@link RunRecord} metadata (Story 34-1, 34-2). */
public interface RunRecordStore extends AutoCloseable {
    void save(RunRecord record);
    Optional<RunRecord> get(String runId);
    List<RunRecord> listAll();
    void delete(String runId);
    @Override
    void close();
}
