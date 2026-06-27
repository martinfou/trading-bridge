package com.martinfou.trading.backtest.persistence;

import com.martinfou.trading.core.Trade;
import com.martinfou.trading.core.Order;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class SqliteTradeStore implements AutoCloseable {

    private static final String JDBC_PREFIX = "jdbc:sqlite:";

    private final Connection connection;
    private final boolean ownsConnection;

    public SqliteTradeStore(Connection connection) {
        this(connection, false);
    }

    public SqliteTradeStore(Connection connection, boolean ownsConnection) {
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
            throw new IllegalStateException("Failed to initialize SQLite trades schema", e);
        }
    }

    public SqliteTradeStore(Path dbPath) {
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
                CREATE TABLE IF NOT EXISTS trades (
                    trade_id           TEXT    PRIMARY KEY,
                    run_id             TEXT    NOT NULL,
                    symbol             TEXT    NOT NULL,
                    side               TEXT    NOT NULL,
                    entry_price        REAL    NOT NULL,
                    exit_price         REAL    NOT NULL,
                    quantity           REAL    NOT NULL,
                    entry_time         TEXT    NOT NULL,
                    exit_time          TEXT    NOT NULL,
                    pnl                REAL    NOT NULL,
                    stop_loss          REAL    NOT NULL,
                    take_profit        REAL    NOT NULL,
                    created_at         TEXT    NOT NULL
                )""");

            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_trades_run_id_entry_time 
                ON trades(run_id, entry_time)
                """);
        }
    }

    public void insert(String runId, Trade trade) {
        synchronized (connection) {
            String sql = """
                INSERT INTO trades (
                    trade_id, run_id, symbol, side, entry_price, exit_price, quantity, entry_time, exit_time, pnl, stop_loss, take_profit, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, trade.id());
                ps.setString(2, runId);
                ps.setString(3, trade.symbol());
                ps.setString(4, trade.side().name());
                ps.setDouble(5, trade.entryPrice());
                ps.setDouble(6, trade.exitPrice());
                ps.setDouble(7, trade.quantity());
                ps.setString(8, trade.entryTime().toString());
                ps.setString(9, trade.exitTime().toString());
                ps.setDouble(10, trade.pnl());
                ps.setDouble(11, trade.stopLoss());
                ps.setDouble(12, trade.takeProfit());
                ps.setString(13, Instant.now().toString());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to insert trade", e);
            }
        }
    }

    public void insertAll(String runId, List<Trade> trades) {
        if (trades == null || trades.isEmpty()) return;
        synchronized (connection) {
            String sql = """
                INSERT INTO trades (
                    trade_id, run_id, symbol, side, entry_price, exit_price, quantity, entry_time, exit_time, pnl, stop_loss, take_profit, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
            try {
                boolean autoCommit = connection.getAutoCommit();
                connection.setAutoCommit(false);
                try (PreparedStatement ps = connection.prepareStatement(sql)) {
                    String nowStr = Instant.now().toString();
                    for (Trade trade : trades) {
                        ps.setString(1, trade.id());
                        ps.setString(2, runId);
                        ps.setString(3, trade.symbol());
                        ps.setString(4, trade.side().name());
                        ps.setDouble(5, trade.entryPrice());
                        ps.setDouble(6, trade.exitPrice());
                        ps.setDouble(7, trade.quantity());
                        ps.setString(8, trade.entryTime().toString());
                        ps.setString(9, trade.exitTime().toString());
                        ps.setDouble(10, trade.pnl());
                        ps.setDouble(11, trade.stopLoss());
                        ps.setDouble(12, trade.takeProfit());
                        ps.setString(13, nowStr);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                    connection.commit();
                } catch (SQLException e) {
                    try {
                        connection.rollback();
                    } catch (SQLException re) {
                        e.addSuppressed(re);
                    }
                    throw e;
                } finally {
                    try {
                        connection.setAutoCommit(autoCommit);
                    } catch (SQLException se) {
                        // Suppress exception from setAutoCommit in finally block
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to insert batch trades", e);
            }
        }
    }

    public List<Trade> getTrades(String runId) {
        synchronized (connection) {
            String sql = """
                SELECT trade_id, symbol, side, entry_price, exit_price, quantity, entry_time, exit_time, pnl, stop_loss, take_profit
                FROM trades
                WHERE run_id = ?
                ORDER BY entry_time ASC
                """;
            List<Trade> list = new ArrayList<>();
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, runId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String id = rs.getString("trade_id");
                        String symbol = rs.getString("symbol");
                        String sideStr = rs.getString("side");
                        if (sideStr == null) continue;
                        Order.Side side;
                        try {
                            side = Order.Side.valueOf(sideStr);
                        } catch (IllegalArgumentException e) {
                            continue;
                        }
                        double entryPrice = rs.getDouble("entry_price");
                        double exitPrice = rs.getDouble("exit_price");
                        double quantity = rs.getDouble("quantity");
                        String entryTimeStr = rs.getString("entry_time");
                        String exitTimeStr = rs.getString("exit_time");
                        if (entryTimeStr == null || exitTimeStr == null) continue;
                        Instant entryTime;
                        Instant exitTime;
                        try {
                            entryTime = Instant.parse(entryTimeStr);
                            exitTime = Instant.parse(exitTimeStr);
                        } catch (Exception e) {
                            continue;
                        }
                        double stopLoss = rs.getDouble("stop_loss");
                        double takeProfit = rs.getDouble("take_profit");
                        double pnl = rs.getDouble("pnl");

                        Trade t = new Trade(id, symbol, side, entryPrice, exitPrice, quantity, entryTime, exitTime, pnl, stopLoss, takeProfit);
                        list.add(t);
                    }
                    return List.copyOf(list);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to query trades for run: " + runId, e);
            }
        }
    }

    public void deleteForRun(String runId) {
        synchronized (connection) {
            String sql = "DELETE FROM trades WHERE run_id = ?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, runId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to delete trades for run: " + runId, e);
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
