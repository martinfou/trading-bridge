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
 * SQLite implementation of the backtest run repository, storing run metrics, parameters,
 * and equity curves in the shared SQLite database.
 */
public final class SqliteBacktestRunStore implements AutoCloseable {

    private static final String JDBC_PREFIX = "jdbc:sqlite:";

    private final Connection connection;
    private final boolean ownsConnection;

    /**
     * Constructs a store using an existing connection. The caller remains responsible
     * for closing the connection.
     */
    public SqliteBacktestRunStore(Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("Connection must not be null");
        }
        this.connection = connection;
        this.ownsConnection = false;
        try {
            initSchema(this.connection);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize SQLite BacktestRun schema", e);
        }
    }

    /**
     * Constructs a store that opens a connection to the specified database path.
     * The connection is owned by this store and will be closed upon calling {@link #close()}.
     */
    public SqliteBacktestRunStore(Path dbPath) {
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
                CREATE TABLE IF NOT EXISTS backtest_runs (
                    run_id             TEXT    PRIMARY KEY,
                    strategy_id        TEXT    NOT NULL,
                    symbol             TEXT    NOT NULL,
                    period_start       TEXT    NOT NULL,
                    period_end         TEXT    NOT NULL,
                    parameters         TEXT    NOT NULL,
                    parameter_hash     TEXT    NOT NULL,
                    initial_capital    REAL    NOT NULL,
                    final_equity       REAL    NOT NULL,
                    total_pnl          REAL    NOT NULL,
                    total_return_pct   REAL    NOT NULL,
                    total_trades       INTEGER NOT NULL,
                    winning_trades     INTEGER NOT NULL,
                    losing_trades      INTEGER NOT NULL,
                    win_rate_pct       REAL    NOT NULL,
                    max_drawdown_pct   REAL    NOT NULL,
                    avg_trade_pnl      REAL    NOT NULL,
                    sharpe_ratio       REAL    NOT NULL,
                    sortino_ratio      REAL    NOT NULL,
                    profit_factor      REAL    NOT NULL,
                    calmar_ratio       REAL    NOT NULL,
                    total_commission   REAL    NOT NULL,
                    total_slippage     REAL    NOT NULL,
                    equity_curve       TEXT    NOT NULL,
                    created_at         TEXT    NOT NULL
                )""");

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_backtest_runs_strategy_hash 
                ON backtest_runs(strategy_id, parameter_hash)
                """);
        }
    }

    /**
     * Inserts a detailed backtest run record.
     */
    public synchronized void insert(BacktestRunDetails run) {
        if (run == null) {
            throw new IllegalArgumentException("Run details must not be null");
        }
        String sql = """
            INSERT INTO backtest_runs (
                run_id, strategy_id, symbol, period_start, period_end, parameters, parameter_hash,
                initial_capital, final_equity, total_pnl, total_return_pct, total_trades,
                winning_trades, losing_trades, win_rate_pct, max_drawdown_pct, avg_trade_pnl,
                sharpe_ratio, sortino_ratio, profit_factor, calmar_ratio, total_commission,
                total_slippage, equity_curve, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, run.runId());
            ps.setString(2, run.strategyId());
            ps.setString(3, run.symbol());
            ps.setString(4, run.periodStart().toString());
            ps.setString(5, run.periodEnd().toString());
            ps.setString(6, run.parameters());
            ps.setString(7, run.parameterHash());
            ps.setDouble(8, run.initialCapital());
            ps.setDouble(9, run.finalEquity());
            ps.setDouble(10, run.totalPnl());
            ps.setDouble(11, run.totalReturnPct());
            ps.setInt(12, run.totalTrades());
            ps.setInt(13, run.winningTrades());
            ps.setInt(14, run.losingTrades());
            ps.setDouble(15, run.winRatePct());
            ps.setDouble(16, run.maxDrawdownPct());
            ps.setDouble(17, run.avgTradePnl());
            ps.setDouble(18, run.sharpeRatio());
            ps.setDouble(19, run.sortinoRatio());
            ps.setDouble(20, run.profitFactor());
            ps.setDouble(21, run.calmarRatio());
            ps.setDouble(22, run.totalCommission());
            ps.setDouble(23, run.totalSlippage());
            ps.setString(24, run.equityCurve());
            ps.setString(25, run.createdAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert backtest run: " + run.runId(), e);
        }
    }

    /**
     * Retrieves a detailed backtest run by its ID.
     */
    public synchronized Optional<BacktestRunDetails> get(String runId) {
        if (runId == null || runId.isBlank()) {
            return Optional.empty();
        }
        String sql = """
            SELECT run_id, strategy_id, symbol, period_start, period_end, parameters, parameter_hash,
                   initial_capital, final_equity, total_pnl, total_return_pct, total_trades,
                   winning_trades, losing_trades, win_rate_pct, max_drawdown_pct, avg_trade_pnl,
                   sharpe_ratio, sortino_ratio, profit_factor, calmar_ratio, total_commission,
                   total_slippage, equity_curve, created_at
            FROM backtest_runs
            WHERE run_id = ?
            """;

        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(readDetails(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to query backtest run: " + runId, e);
        }
    }

    /**
     * Lists backtest run summaries matching filters with pagination and sorting.
     */
    public synchronized List<BacktestRunSummary> list(BacktestQueryFilters filters) {
        StringBuilder sql = new StringBuilder("""
            SELECT run_id, strategy_id, symbol, period_start, period_end, parameters, parameter_hash,
                   initial_capital, final_equity, total_pnl, total_return_pct, total_trades,
                   winning_trades, losing_trades, win_rate_pct, max_drawdown_pct, avg_trade_pnl,
                   sharpe_ratio, sortino_ratio, profit_factor, calmar_ratio, total_commission,
                   total_slippage, created_at
            FROM backtest_runs
            """);

        List<Object> params = new ArrayList<>();
        appendWhereClause(sql, filters, params);

        String sortCol = validateSortColumn(filters.sortBy());
        String sortOrder = validateSortOrder(filters.sortOrder());
        sql.append(" ORDER BY ").append(sortCol).append(" ").append(sortOrder);

        if (filters.limit() != null && filters.limit() > 0) {
            sql.append(" LIMIT ?");
            params.add(filters.limit());
            if (filters.offset() != null && filters.offset() >= 0) {
                sql.append(" OFFSET ?");
                params.add(filters.offset());
            }
        }

        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<BacktestRunSummary> list = new ArrayList<>();
                while (rs.next()) {
                    list.add(readSummary(rs));
                }
                return List.copyOf(list);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list backtest runs", e);
        }
    }

    /**
     * Counts the total number of backtest runs matching the given filters.
     */
    public synchronized int count(BacktestQueryFilters filters) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM backtest_runs");
        List<Object> params = new ArrayList<>();
        appendWhereClause(sql, filters, params);

        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
                return 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count backtest runs", e);
        }
    }

    private void appendWhereClause(StringBuilder sql, BacktestQueryFilters filters, List<Object> params) {
        if (filters == null) {
            return;
        }
        List<String> conditions = new ArrayList<>();

        if (filters.symbol() != null && !filters.symbol().isBlank()) {
            conditions.add("symbol = ?");
            params.add(filters.symbol().trim());
        }
        if (filters.strategyId() != null && !filters.strategyId().isBlank()) {
            conditions.add("strategy_id = ?");
            params.add(filters.strategyId().trim());
        }
        if (filters.minSharpe() != null) {
            conditions.add("sharpe_ratio >= ?");
            params.add(filters.minSharpe());
        }
        if (filters.minProfitFactor() != null) {
            conditions.add("profit_factor >= ?");
            params.add(filters.minProfitFactor());
        }

        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
    }

    private String validateSortColumn(String sortBy) {
        if (sortBy == null || sortBy.isBlank()) {
            return "created_at";
        }
        return switch (sortBy.toLowerCase().trim()) {
            case "sharpe", "sharpe_ratio" -> "sharpe_ratio";
            case "profit_factor", "profitfactor" -> "profit_factor";
            case "pnl", "total_pnl" -> "total_pnl";
            case "drawdown", "max_drawdown_pct", "maxdrawdown" -> "max_drawdown_pct";
            case "created_at", "date" -> "created_at";
            case "run_id", "runid" -> "run_id";
            case "strategy_id", "strategyid", "strategy" -> "strategy_id";
            case "symbol" -> "symbol";
            case "total_trades", "trades", "totaltrades" -> "total_trades";
            case "win_rate_pct", "winratepct", "winrate", "win_rate" -> "win_rate_pct";
            case "initial_capital", "initialcapital", "capital" -> "initial_capital";
            default -> "created_at";
        };
    }

    private String validateSortOrder(String sortOrder) {
        if (sortOrder == null) {
            return "DESC";
        }
        String order = sortOrder.trim().toUpperCase();
        return "ASC".equals(order) ? "ASC" : "DESC";
    }

    private static BacktestRunDetails readDetails(ResultSet rs) throws SQLException {
        return new BacktestRunDetails(
            rs.getString("run_id"),
            rs.getString("strategy_id"),
            rs.getString("symbol"),
            Instant.parse(rs.getString("period_start")),
            Instant.parse(rs.getString("period_end")),
            rs.getString("parameters"),
            rs.getString("parameter_hash"),
            rs.getDouble("initial_capital"),
            rs.getDouble("final_equity"),
            rs.getDouble("total_pnl"),
            rs.getDouble("total_return_pct"),
            rs.getInt("total_trades"),
            rs.getInt("winning_trades"),
            rs.getInt("losing_trades"),
            rs.getDouble("win_rate_pct"),
            rs.getDouble("max_drawdown_pct"),
            rs.getDouble("avg_trade_pnl"),
            rs.getDouble("sharpe_ratio"),
            rs.getDouble("sortino_ratio"),
            rs.getDouble("profit_factor"),
            rs.getDouble("calmar_ratio"),
            rs.getDouble("total_commission"),
            rs.getDouble("total_slippage"),
            rs.getString("equity_curve"),
            Instant.parse(rs.getString("created_at"))
        );
    }

    private static BacktestRunSummary readSummary(ResultSet rs) throws SQLException {
        return new BacktestRunSummary(
            rs.getString("run_id"),
            rs.getString("strategy_id"),
            rs.getString("symbol"),
            Instant.parse(rs.getString("period_start")),
            Instant.parse(rs.getString("period_end")),
            rs.getString("parameters"),
            rs.getString("parameter_hash"),
            rs.getDouble("initial_capital"),
            rs.getDouble("final_equity"),
            rs.getDouble("total_pnl"),
            rs.getDouble("total_return_pct"),
            rs.getInt("total_trades"),
            rs.getInt("winning_trades"),
            rs.getInt("losing_trades"),
            rs.getDouble("win_rate_pct"),
            rs.getDouble("max_drawdown_pct"),
            rs.getDouble("avg_trade_pnl"),
            rs.getDouble("sharpe_ratio"),
            rs.getDouble("sortino_ratio"),
            rs.getDouble("profit_factor"),
            rs.getDouble("calmar_ratio"),
            rs.getDouble("total_commission"),
            rs.getDouble("total_slippage"),
            Instant.parse(rs.getString("created_at"))
        );
    }

    /**
     * Deletes a backtest run by its ID.
     */
    public synchronized void delete(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        String sql = "DELETE FROM backtest_runs WHERE run_id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, runId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete backtest run: " + runId, e);
        }
    }

    /**
     * Deletes all backtest runs.
     */
    public synchronized void deleteAll() {
        String sql = "DELETE FROM backtest_runs";
        try (Statement stmt = connection.createStatement()) {
            stmt.executeUpdate(sql);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete all backtest runs", e);
        }
    }

    @Override
    public synchronized void close() {
        if (ownsConnection) {
            try {
                connection.close();
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to close SQLite database connection", e);
            }
        }
    }
}
