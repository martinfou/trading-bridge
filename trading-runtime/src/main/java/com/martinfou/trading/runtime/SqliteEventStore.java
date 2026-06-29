package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventJson;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(SqliteEventStore.class);
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
            List<String> integrityErrors = checkDatabaseIntegrity(connection);
            if (!integrityErrors.isEmpty()) {
                log.error("CRITICAL: SQLite integrity check failed for {}: {}", config.dbPath(), integrityErrors);
            } else {
                log.info("SQLite database integrity check passed successfully.");
            }
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

        long startTime = System.currentTimeMillis();
        synchronized (connection) {
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
                    long sequence = keys.getLong(1);
                    long duration = System.currentTimeMillis() - startTime;
                    if (duration > 100) {
                        log.warn("CRITICAL DATABASE SLOWDOWN: SQLite event write took {}ms (threshold: 100ms)", duration);
                    }
                    return sequence;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to append event for run " + runId, e);
            }
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
        synchronized (connection) {
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
        synchronized (connection) {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to close EventStore", e);
            }
        }
    }

    EventStoreConfig config() {
        return config;
    }

    private List<StoredRunEvent> fetchRecords(String sql, StatementBinder binder) {
        long startTime = System.currentTimeMillis();
        synchronized (connection) {
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                binder.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    List<StoredRunEvent> result = new ArrayList<>();
                    while (rs.next()) {
                        long sequence = rs.getLong("sequence");
                        RunEvent event = RunEventJson.fromJsonLine(rs.getString("json_line"));
                        result.add(new StoredRunEvent(sequence, event));
                    }
                    long duration = System.currentTimeMillis() - startTime;
                    if (duration > 100) {
                        log.warn("CRITICAL DATABASE SLOWDOWN: SQLite event query took {}ms (threshold: 100ms)", duration);
                    }
                    return List.copyOf(result);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to query events", e);
            }
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

    public Connection connection() {
        return connection;
    }

    public static List<String> checkDatabaseIntegrity(Connection connection) {
        List<String> errors = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA integrity_check")) {
            while (rs.next()) {
                String status = rs.getString(1);
                if (!"ok".equalsIgnoreCase(status)) {
                    errors.add(status);
                }
            }
        } catch (SQLException e) {
            errors.add("Failed to run integrity check: " + e.getMessage());
        }
        return errors;
    }

    public int pruneEventsOlderThanDays(int days) {
        synchronized (connection) {
            try (PreparedStatement ps = connection.prepareStatement(
                "DELETE FROM events WHERE datetime(created_at) < datetime('now', ?)")) {
                ps.setString(1, "-" + days + " days");
                int pruned = ps.executeUpdate();
                log.info("Pruned {} event(s) older than {} days from database", pruned, days);
                return pruned;
            } catch (SQLException e) {
                log.error("Failed to prune old events from database", e);
                return 0;
            }
        }
    }
}
