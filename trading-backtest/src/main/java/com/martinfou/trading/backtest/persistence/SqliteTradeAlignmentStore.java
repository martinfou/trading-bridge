package com.martinfou.trading.backtest.persistence;

import com.martinfou.trading.backtest.reconciliation.ReconciliationAnomaly;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public final class SqliteTradeAlignmentStore implements AutoCloseable {

    private static final String JDBC_PREFIX = "jdbc:sqlite:";

    private final Connection connection;
    private final boolean ownsConnection;

    public SqliteTradeAlignmentStore(Connection connection) {
        this(connection, false);
    }

    public SqliteTradeAlignmentStore(Connection connection, boolean ownsConnection) {
        if (connection == null) {
            throw new IllegalArgumentException("Connection must not be null");
        }
        this.connection = connection;
        this.ownsConnection = ownsConnection;
        try {
            synchronized (this.connection) {
                initSchema(this.connection);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize SQLite trade_alignment schema", e);
        }
    }

    public SqliteTradeAlignmentStore(Path dbPath) {
        if (dbPath == null) {
            throw new IllegalArgumentException("dbPath must not be null");
        }
        try {
            Path parent = dbPath.toAbsolutePath().normalize().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.connection = DriverManager.getConnection(JDBC_PREFIX + dbPath.toAbsolutePath().normalize());
            this.ownsConnection = true;
            synchronized (this.connection) {
                initSchema(this.connection);
            }
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Failed to open SQLite database at " + dbPath, e);
        }
    }

    private static void initSchema(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS trade_alignment (
                    anomaly_id         TEXT    PRIMARY KEY,
                    run_id             TEXT    NOT NULL,
                    anomaly_type       TEXT    NOT NULL,
                    order_id           TEXT,
                    message            TEXT    NOT NULL,
                    delta_price        REAL    NOT NULL,
                    delta_time_ms      INTEGER NOT NULL,
                    created_at         TEXT    NOT NULL
                )""");

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_trade_alignment_run_id 
                ON trade_alignment(run_id)
                """);
        }
    }

    public void insert(String runId, ReconciliationAnomaly anomaly) {
        synchronized (connection) {
            String sql = """
                INSERT INTO trade_alignment (
                    anomaly_id, run_id, anomaly_type, order_id, message, delta_price, delta_time_ms, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                String anomalyId = java.util.UUID.randomUUID().toString();
                ps.setString(1, anomalyId);
                ps.setString(2, runId);
                ps.setString(3, anomaly.type().name());
                ps.setString(4, anomaly.orderId());
                ps.setString(5, anomaly.message());
                ps.setDouble(6, anomaly.deltaPrice());
                ps.setLong(7, anomaly.deltaTimeMs());
                ps.setString(8, java.time.Instant.now().toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to insert trade alignment anomaly", e);
            }
        }
    }

    public void insertAll(String runId, List<ReconciliationAnomaly> anomalies) {
        if (anomalies.isEmpty()) return;
        synchronized (connection) {
            String sql = """
                INSERT INTO trade_alignment (
                    anomaly_id, run_id, anomaly_type, order_id, message, delta_price, delta_time_ms, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
            try {
                boolean autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    String nowStr = java.time.Instant.now().toString();
                    for (ReconciliationAnomaly anomaly : anomalies) {
                        ps.setString(1, java.util.UUID.randomUUID().toString());
                        ps.setString(2, runId);
                        ps.setString(3, anomaly.type().name());
                        ps.setString(4, anomaly.orderId());
                        ps.setString(5, anomaly.message());
                        ps.setDouble(6, anomaly.deltaPrice());
                        ps.setLong(7, anomaly.deltaTimeMs());
                        ps.setString(8, nowStr);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                    connection.commit();
                } catch (SQLException e) {
                    connection.rollback();
                    throw e;
                } finally {
                    connection.setAutoCommit(autoCommit);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to insert batch trade alignment anomalies", e);
            }
        }
    }

    public List<ReconciliationAnomaly> getAnomalies(String runId) {
        synchronized (connection) {
            String sql = """
                SELECT anomaly_type, order_id, message, delta_price, delta_time_ms
                FROM trade_alignment
                WHERE run_id = ?
                """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<ReconciliationAnomaly> list = new ArrayList<>();
                    while (rs.next()) {
                        list.add(new ReconciliationAnomaly(
                            ReconciliationAnomaly.AnomalyType.valueOf(rs.getString("anomaly_type")),
                            rs.getString("order_id"),
                            rs.getString("message"),
                            rs.getDouble("delta_price"),
                            rs.getLong("delta_time_ms")
                        ));
                    }
                    return List.copyOf(list);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to query trade alignment anomalies for run: " + runId, e);
            }
        }
    }

    public void deleteForRun(String runId) {
        synchronized (connection) {
            String sql = "DELETE FROM trade_alignment WHERE run_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, runId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to delete trade alignment anomalies for run: " + runId, e);
            }
        }
    }

    @Override
    public void close() {
        synchronized (connection) {
            if (ownsConnection) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    throw new IllegalStateException("Failed to close SQLite connection", e);
                }
            }
        }
    }
}
