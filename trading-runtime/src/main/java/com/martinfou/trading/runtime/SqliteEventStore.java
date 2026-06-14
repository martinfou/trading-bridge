package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventJson;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * SQLite-backed append-only {@link EventStore}.
 */
public final class SqliteEventStore implements EventStore {

    private static final String JDBC_PREFIX = "jdbc:sqlite:";

    private final EventStoreConfig config;
    private final Connection connection;

    public SqliteEventStore(EventStoreConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
        try {
            config.ensureParentDirectories();
            connection = DriverManager.getConnection(JDBC_PREFIX + config.dbPath());
            initSchema(connection);
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Failed to open EventStore at " + config.dbPath(), e);
        }
    }

    @Override
    public long append(String runId, RunEvent event) {
        EventStoreValidation.requireRunId(runId);
        EventStoreValidation.requireEvent(event);
        String jsonLine = RunEventJson.toJsonLine(event);
        String createdAt = Instant.now().toString();

        try (PreparedStatement ps = connection.prepareStatement(
            "INSERT INTO events (run_id, json_line, created_at) VALUES (?, ?, ?)",
            Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, runId);
            ps.setString(2, jsonLine);
            ps.setString(3, createdAt);
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new IllegalStateException("SQLite did not return generated sequence");
                }
                return keys.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to append event for run " + runId, e);
        }
    }

    @Override
    public void clear(String runId) {
        EventStoreValidation.requireRunId(runId);
        try (PreparedStatement ps = connection.prepareStatement("DELETE FROM events WHERE run_id = ?")) {
            ps.setString(1, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear events for run " + runId, e);
        }
    }

    @Override
    public List<RunEvent> query(String runId, long afterSequence, int limit) {
        return queryWithSequence(runId, afterSequence, limit).stream()
            .map(StoredRunEvent::event)
            .toList();
    }

    @Override
    public List<StoredRunEvent> queryWithSequence(String runId, long afterSequence, int limit) {
        EventStoreValidation.requireRunId(runId);
        EventStoreValidation.requireLimit(limit);
        return fetchRecords(
            "SELECT sequence, json_line FROM events WHERE run_id = ? AND sequence > ? ORDER BY sequence ASC LIMIT ?",
            ps -> {
                ps.setString(1, runId);
                ps.setLong(2, afterSequence);
                ps.setInt(3, limit);
            });
    }

    @Override
    public long count(String runId) {
        EventStoreValidation.requireRunId(runId);
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT COUNT(*) FROM events WHERE run_id = ?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count events for run " + runId, e);
        }
    }

    @Override
    public List<RunEvent> replayAll(String runId) {
        EventStoreValidation.requireRunId(runId);
        return fetchRecords(
            "SELECT sequence, json_line FROM events WHERE run_id = ? ORDER BY sequence ASC",
            ps -> ps.setString(1, runId)).stream()
            .map(StoredRunEvent::event)
            .toList();
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to close EventStore", e);
        }
    }

    EventStoreConfig config() {
        return config;
    }

    private List<StoredRunEvent> fetchRecords(String sql, StatementBinder binder) {
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                List<StoredRunEvent> result = new ArrayList<>();
                while (rs.next()) {
                    long sequence = rs.getLong("sequence");
                    RunEvent event = RunEventJson.fromJsonLine(rs.getString("json_line"));
                    result.add(new StoredRunEvent(sequence, event));
                }
                return List.copyOf(result);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query events", e);
        }
    }

    private static void initSchema(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS events (
                  sequence    INTEGER PRIMARY KEY AUTOINCREMENT,
                  run_id      TEXT    NOT NULL,
                  json_line   TEXT    NOT NULL,
                  created_at  TEXT    NOT NULL
                )""");
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_events_run_sequence
                ON events(run_id, sequence)""");
            stmt.execute("PRAGMA journal_mode=WAL");
        }
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement ps) throws SQLException;
    }
}
