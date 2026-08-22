package com.martinfou.trading.backtest.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

/**
 * SQLite repository for Walk-Forward Analysis (WFA) run records.
 */
public final class SqliteWfaRunStore implements AutoCloseable {

    private static final String JDBC_PREFIX = "jdbc:sqlite:";

    private final Connection connection;
    private final boolean ownsConnection;

    public SqliteWfaRunStore(Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("Connection must not be null");
        }
        this.connection = connection;
        this.ownsConnection = false;
        try {
            initSchema(this.connection);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize SQLite WFA run schema", e);
        }
    }

    public SqliteWfaRunStore(Path dbPath) {
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
            enableWalMode(this.connection);
            initSchema(this.connection);
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Failed to open SQLite database at " + dbPath, e);
        }
    }

    private static void enableWalMode(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode=WAL;");
            stmt.execute("PRAGMA synchronous=NORMAL;");
        }
    }

    private static void initSchema(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS wfa_runs (
                    wfa_id                TEXT    PRIMARY KEY,
                    strategy_name         TEXT    NOT NULL,
                    symbol                TEXT    NOT NULL,
                    asset_class           TEXT    NOT NULL,
                    timeframe             TEXT,
                    in_sample_days        INTEGER NOT NULL,
                    out_of_sample_days    INTEGER NOT NULL,
                    anchored              INTEGER NOT NULL,
                    initial_capital       REAL    NOT NULL,
                    wfe                   REAL    DEFAULT 0.0,
                    oos_sharpe            REAL    DEFAULT 0.0,
                    oos_max_drawdown_pct  REAL    DEFAULT 0.0,
                    oos_profit_factor     REAL    DEFAULT 0.0,
                    oos_return_pct        REAL    DEFAULT 0.0,
                    oos_trades_count      INTEGER DEFAULT 0,
                    status                TEXT    NOT NULL,
                    created_at            TEXT    NOT NULL,
                    completed_at          TEXT,
                    error_message         TEXT
                );
                """);
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_wfa_runs_strategy ON wfa_runs (strategy_name);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_wfa_runs_symbol ON wfa_runs (symbol);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_wfa_runs_created ON wfa_runs (created_at DESC);");
        }
    }

    public void save(WfaRunRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("Record cannot be null");
        }
        String sql = """
            INSERT INTO wfa_runs (
                wfa_id, strategy_name, symbol, asset_class, timeframe,
                in_sample_days, out_of_sample_days, anchored, initial_capital,
                wfe, oos_sharpe, oos_max_drawdown_pct, oos_profit_factor,
                oos_return_pct, oos_trades_count, status, created_at, completed_at, error_message
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(wfa_id) DO UPDATE SET
                wfe = excluded.wfe,
                oos_sharpe = excluded.oos_sharpe,
                oos_max_drawdown_pct = excluded.oos_max_drawdown_pct,
                oos_profit_factor = excluded.oos_profit_factor,
                oos_return_pct = excluded.oos_return_pct,
                oos_trades_count = excluded.oos_trades_count,
                status = excluded.status,
                completed_at = excluded.completed_at,
                error_message = excluded.error_message;
            """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, record.wfaId());
            stmt.setString(2, record.strategyName());
            stmt.setString(3, record.symbol());
            stmt.setString(4, record.assetClass() != null ? record.assetClass() : "FOREX");
            stmt.setString(5, record.timeframe());
            stmt.setInt(6, record.inSampleDays());
            stmt.setInt(7, record.outOfSampleDays());
            stmt.setInt(8, record.anchored() ? 1 : 0);
            stmt.setDouble(9, record.initialCapital());
            stmt.setDouble(10, record.wfe());
            stmt.setDouble(11, record.oosSharpe());
            stmt.setDouble(12, record.oosMaxDrawdownPct());
            stmt.setDouble(13, record.oosProfitFactor());
            stmt.setDouble(14, record.oosReturnPct());
            stmt.setInt(15, record.oosTradesCount());
            stmt.setString(16, record.status());
            stmt.setString(17, record.createdAt().toString());
            stmt.setString(18, record.completedAt() != null ? record.completedAt().toString() : null);
            stmt.setString(19, record.errorMessage());
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save WFA run record: " + record.wfaId(), e);
        }
    }

    public Optional<WfaRunRecord> findById(String wfaId) {
        if (wfaId == null || wfaId.isBlank()) {
            return Optional.empty();
        }
        String sql = "SELECT * FROM wfa_runs WHERE wfa_id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, wfaId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query WFA run: " + wfaId, e);
        }
        return Optional.empty();
    }

    public List<WfaRunRecord> listAll(int limit) {
        int max = Math.max(1, limit);
        String sql = "SELECT * FROM wfa_runs ORDER BY created_at DESC LIMIT ?";
        List<WfaRunRecord> list = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, max);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list WFA runs", e);
        }
        return List.copyOf(list);
    }

    public Optional<WfaRunRecord> findLatestCompleted(String strategyName, String symbol) {
        String sql = "SELECT * FROM wfa_runs WHERE strategy_name = ? AND symbol = ? AND status = 'COMPLETED' ORDER BY completed_at DESC LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, strategyName);
            stmt.setString(2, symbol);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to find latest completed WFA run for " + strategyName + " on " + symbol, e);
        }
        return Optional.empty();
    }

    private static WfaRunRecord mapRow(ResultSet rs) throws SQLException {
        String completedAtStr = rs.getString("completed_at");
        Instant completedAt = completedAtStr != null ? Instant.parse(completedAtStr) : null;
        return new WfaRunRecord(
            rs.getString("wfa_id"),
            rs.getString("strategy_name"),
            rs.getString("symbol"),
            rs.getString("asset_class"),
            rs.getString("timeframe"),
            rs.getInt("in_sample_days"),
            rs.getInt("out_of_sample_days"),
            rs.getInt("anchored") == 1,
            rs.getDouble("initial_capital"),
            rs.getDouble("wfe"),
            rs.getDouble("oos_sharpe"),
            rs.getDouble("oos_max_drawdown_pct"),
            rs.getDouble("oos_profit_factor"),
            rs.getDouble("oos_return_pct"),
            rs.getInt("oos_trades_count"),
            rs.getString("status"),
            Instant.parse(rs.getString("created_at")),
            completedAt,
            rs.getString("error_message")
        );
    }

    @Override
    public void close() {
        if (ownsConnection && connection != null) {
            try {
                if (!connection.isClosed()) {
                    connection.close();
                }
            } catch (SQLException ignored) {}
        }
    }
}
