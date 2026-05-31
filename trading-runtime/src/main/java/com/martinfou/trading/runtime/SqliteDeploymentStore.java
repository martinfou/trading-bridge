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
import java.util.Optional;

/** SQLite-backed {@link DeploymentStore} (same database file as {@link SqliteEventStore}). */
public final class SqliteDeploymentStore implements DeploymentStore {

    private static final String JDBC_PREFIX = "jdbc:sqlite:";
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private final Connection connection;

    public SqliteDeploymentStore(EventStoreConfig config) {
        try {
            config.ensureParentDirectories();
            connection = DriverManager.getConnection(JDBC_PREFIX + config.dbPath());
            initSchema(connection);
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Failed to open DeploymentStore at " + config.dbPath(), e);
        }
    }

    @Override
    public Optional<DeploymentRecord> get(String strategyId) {
        try (PreparedStatement ps = connection.prepareStatement(
            "SELECT strategy_id, mode, promoted_at, source_run_id, checks_json, execution_label, broker_account_id FROM deployments WHERE strategy_id = ?")) {
            ps.setString(1, strategyId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(readRecord(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read deployment for " + strategyId, e);
        }
    }

    @Override
    public void save(DeploymentRecord record) {
        try (PreparedStatement ps = connection.prepareStatement("""
            INSERT INTO deployments (strategy_id, mode, promoted_at, source_run_id, checks_json, execution_label, broker_account_id)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(strategy_id) DO UPDATE SET
              mode = excluded.mode,
              promoted_at = excluded.promoted_at,
              source_run_id = excluded.source_run_id,
              checks_json = excluded.checks_json,
              execution_label = excluded.execution_label,
              broker_account_id = excluded.broker_account_id
            """)) {
            ps.setString(1, record.strategyId());
            ps.setString(2, record.mode().name());
            ps.setString(3, record.promotedAt().toString());
            ps.setString(4, record.sourceRunId());
            ps.setString(5, toChecksJson(record.checks()));
            ps.setString(6, record.executionLabel().name());
            ps.setString(7, record.brokerAccountId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save deployment for " + record.strategyId(), e);
        }
    }

    @Override
    public List<DeploymentRecord> listAll() {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT strategy_id, mode, promoted_at, source_run_id, checks_json, execution_label, broker_account_id FROM deployments ORDER BY promoted_at")) {
            List<DeploymentRecord> records = new ArrayList<>();
            while (rs.next()) {
                records.add(readRecord(rs));
            }
            return List.copyOf(records);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list deployments", e);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to close DeploymentStore", e);
        }
    }

    private static DeploymentRecord readRecord(ResultSet rs) throws SQLException {
        try {
            List<GateCheckResult> checks = MAPPER.readValue(
                rs.getString("checks_json"), new TypeReference<>() {});
            String label = rs.getString("execution_label");
            ExecutionLabel executionLabel = ExecutionLabel.parse(label);
            return new DeploymentRecord(
                rs.getString("strategy_id"),
                RunMode.valueOf(rs.getString("mode")),
                Instant.parse(rs.getString("promoted_at")),
                rs.getString("source_run_id"),
                checks,
                executionLabel,
                rs.getString("broker_account_id"));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid checks_json for deployment", e);
        }
    }

    private static String toChecksJson(List<GateCheckResult> checks) {
        try {
            return MAPPER.writeValueAsString(checks);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize gate checks", e);
        }
    }

    private static void initSchema(Connection connection) throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS deployments (
                  strategy_id   TEXT PRIMARY KEY,
                  mode          TEXT NOT NULL,
                  promoted_at   TEXT NOT NULL,
                  source_run_id TEXT,
                  checks_json   TEXT NOT NULL,
                  execution_label TEXT NOT NULL DEFAULT 'PAPER_STUB'
                )""");
            ensureColumn(connection, "deployments", "execution_label",
                "TEXT NOT NULL DEFAULT 'PAPER_STUB'");
            ensureColumn(connection, "deployments", "broker_account_id", "TEXT");
        }
    }

    private static void ensureColumn(Connection connection, String table, String column, String ddl)
        throws SQLException {
        try (ResultSet rs = connection.getMetaData().getColumns(null, null, table, column)) {
            if (!rs.next()) {
                try (Statement alter = connection.createStatement()) {
                    alter.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + ddl);
                }
            }
        }
    }
}
