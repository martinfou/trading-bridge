package com.martinfou.trading.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.martinfou.trading.backtest.RunMode;

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
import java.util.Map;
import java.util.Optional;

/** SQLite implementation of {@link RunRecordStore} (Story 34-1, 34-2). */
public final class SqliteRunRecordStore implements RunRecordStore {

    private static final String JDBC_PREFIX = "jdbc:sqlite:";
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final Connection connection;

    public SqliteRunRecordStore(EventStoreConfig config) {
        try {
            config.ensureParentDirectories();
            Connection conn = DriverManager.getConnection(JDBC_PREFIX + config.dbPath());
            try {
                initSchema(conn);
                this.connection = conn;
            } catch (SQLException e) {
                try {
                    conn.close();
                } catch (SQLException ignored) {}
                throw e;
            }
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Failed to open SqliteRunRecordStore at " + config.dbPath(), e);
        }
    }

    @Override
    public void save(RunRecord record) {
        synchronized (connection) {
            try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO run_records (
                    run_id, strategy_id, symbol, mode, config_snapshot, config_hash, status, started_at,
                    completed_at, error_message, ended_payload, last_event_at, restart_count, last_restart_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(run_id) DO UPDATE SET
                    status = excluded.status,
                    started_at = excluded.started_at,
                    completed_at = excluded.completed_at,
                    error_message = excluded.error_message,
                    ended_payload = excluded.ended_payload,
                    last_event_at = excluded.last_event_at,
                    restart_count = excluded.restart_count,
                    last_restart_at = excluded.last_restart_at
                """)) {
                ps.setString(1, record.runId());
                ps.setString(2, record.strategyId());
                ps.setString(3, record.symbol());
                ps.setString(4, record.mode().name());
                ps.setString(5, toJson(record.configSnapshot()));
                ps.setString(6, record.configHash());
                ps.setString(7, record.status().name());
                ps.setString(8, record.startedAt().toString());
                ps.setString(9, record.completedAt().map(Instant::toString).orElse(null));
                ps.setString(10, record.errorMessage().orElse(null));
                ps.setString(11, record.endedPayload().map(this::toJson).orElse(null));
                ps.setString(12, record.lastEventAt().map(Instant::toString).orElse(null));
                ps.setInt(13, record.restartCount());
                ps.setString(14, record.lastRestartAt().map(Instant::toString).orElse(null));
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to save run record: " + record.runId(), e);
            }
        }
    }

    @Override
    public Optional<RunRecord> get(String runId) {
        synchronized (connection) {
            try (PreparedStatement ps = connection.prepareStatement("""
                SELECT run_id, strategy_id, symbol, mode, config_snapshot, config_hash, status, started_at,
                       completed_at, error_message, ended_payload, last_event_at, restart_count, last_restart_at
                  FROM run_records WHERE run_id = ?
                """)) {
                ps.setString(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    return Optional.of(readRecord(rs));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to read run record: " + runId, e);
            }
        }
    }

    @Override
    public List<RunRecord> listAll() {
        synchronized (connection) {
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("""
                     SELECT run_id, strategy_id, symbol, mode, config_snapshot, config_hash, status, started_at,
                            completed_at, error_message, ended_payload, last_event_at, restart_count, last_restart_at
                       FROM run_records
                     """)) {
                List<RunRecord> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(readRecord(rs));
                }
                return List.copyOf(list);
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to list run records", e);
            }
        }
    }

    @Override
    public void delete(String runId) {
        synchronized (connection) {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM run_records WHERE run_id = ?")) {
                ps.setString(1, runId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to delete run record: " + runId, e);
            }
        }
    }

    @Override
    public void close() {
        synchronized (connection) {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to close SqliteRunRecordStore", e);
            }
        }
    }

    private static void initSchema(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS run_records (
                    run_id             TEXT    PRIMARY KEY,
                    strategy_id        TEXT    NOT NULL,
                    symbol             TEXT    NOT NULL,
                    mode               TEXT    NOT NULL,
                    config_snapshot    TEXT    NOT NULL,
                    config_hash        TEXT    NOT NULL,
                    status             TEXT    NOT NULL,
                    started_at         TEXT    NOT NULL,
                    completed_at       TEXT,
                    error_message      TEXT,
                    ended_payload      TEXT,
                    last_event_at      TEXT,
                    restart_count      INTEGER NOT NULL DEFAULT 0,
                    last_restart_at    TEXT
                )""");
        }
    }

    private String toJson(Map<String, Object> map) {
        try {
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize map to JSON", e);
        }
    }

    private static RunRecord readRecord(ResultSet rs) throws SQLException {
        try {
            String runId = rs.getString("run_id");
            String strategyId = rs.getString("strategy_id");
            String symbol = rs.getString("symbol");
            RunMode mode = RunMode.valueOf(rs.getString("mode"));
            Instant startedAt = Instant.parse(rs.getString("started_at"));
            Map<String, Object> configSnapshot = MAPPER.readValue(rs.getString("config_snapshot"), new TypeReference<>() {});
            String configHash = rs.getString("config_hash");
            RunRecord.Status status = RunRecord.Status.valueOf(rs.getString("status"));
            
            String completedAtStr = rs.getString("completed_at");
            Instant completedAt = completedAtStr != null ? Instant.parse(completedAtStr) : null;
            
            String errorMessage = rs.getString("error_message");
            
            String endedPayloadStr = rs.getString("ended_payload");
            Map<String, Object> endedPayload = endedPayloadStr != null ? MAPPER.readValue(endedPayloadStr, new TypeReference<>() {}) : null;
            
            String lastEventAtStr = rs.getString("last_event_at");
            Instant lastEventAt = lastEventAtStr != null ? Instant.parse(lastEventAtStr) : null;
            
            int restartCount = rs.getInt("restart_count");
            
            String lastRestartAtStr = rs.getString("last_restart_at");
            Instant lastRestartAt = lastRestartAtStr != null ? Instant.parse(lastRestartAtStr) : null;

            return new RunRecord(
                runId,
                strategyId,
                symbol,
                mode,
                startedAt,
                configSnapshot,
                configHash,
                status,
                completedAt,
                errorMessage,
                endedPayload,
                lastEventAt,
                restartCount,
                lastRestartAt
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize JSON snapshot", e);
        }
    }
}
