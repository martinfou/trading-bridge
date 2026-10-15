package com.martinfou.trading.data.persistence;

import com.martinfou.trading.core.InstrumentDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * SQLite repository for trading instruments universe, persisting default and custom
 * minicaps, small caps, futures, and forex instruments.
 */
public final class SqliteUniverseStore implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SqliteUniverseStore.class);
    private static final String JDBC_PREFIX = "jdbc:sqlite:";

    private final Connection connection;
    private final boolean ownsConnection;

    public SqliteUniverseStore(Connection connection) {
        if (connection == null) {
            throw new IllegalArgumentException("Connection must not be null");
        }
        this.connection = connection;
        this.ownsConnection = false;
        initSchemaAndSeed(this.connection);
    }

    public SqliteUniverseStore(Path dbPath) {
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
            initSchemaAndSeed(this.connection);
        } catch (SQLException | IOException e) {
            throw new IllegalStateException("Failed to open SQLite database at " + dbPath, e);
        }
    }

    private static void enableWalMode(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA journal_mode = WAL;");
            stmt.execute("PRAGMA synchronous = NORMAL;");
        }
    }

    private void initSchemaAndSeed(Connection conn) {
        String createTableSql = """
            CREATE TABLE IF NOT EXISTS instruments (
                symbol TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                asset_class TEXT NOT NULL,
                point_value REAL NOT NULL,
                tick_size REAL NOT NULL,
                currency TEXT NOT NULL,
                is_custom INTEGER NOT NULL,
                provider_ticker TEXT NOT NULL
            );
            """;
        try (Statement stmt = conn.createStatement()) {
            stmt.execute(createTableSql);
            seedDefaultInstruments(conn);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize instruments table", e);
        }
    }

    private void seedDefaultInstruments(Connection conn) throws SQLException {
        List<InstrumentDefinition> defaults = List.of(
            // Forex Majors
            new InstrumentDefinition("EUR_USD", "Euro / US Dollar", "FOREX", 1.0, 0.0001, "USD", false, "EUR_USD"),
            new InstrumentDefinition("GBP_USD", "British Pound / US Dollar", "FOREX", 1.0, 0.0001, "USD", false, "GBP_USD"),
            new InstrumentDefinition("GBP_JPY", "British Pound / Japanese Yen", "FOREX", 1.0, 0.01, "JPY", false, "GBP_JPY"),
            new InstrumentDefinition("USD_CAD", "US Dollar / Canadian Dollar", "FOREX", 1.0, 0.0001, "CAD", false, "USD_CAD"),
            new InstrumentDefinition("USD_JPY", "US Dollar / Japanese Yen", "FOREX", 1.0, 0.01, "JPY", false, "USD_JPY"),
            new InstrumentDefinition("AUD_USD", "Australian Dollar / US Dollar", "FOREX", 1.0, 0.0001, "USD", false, "AUD_USD"),
            new InstrumentDefinition("NZD_USD", "New Zealand Dollar / US Dollar", "FOREX", 1.0, 0.0001, "USD", false, "NZD_USD"),
            new InstrumentDefinition("USD_CHF", "US Dollar / Swiss Franc", "FOREX", 1.0, 0.0001, "CHF", false, "USD_CHF"),

            // CME Futures
            new InstrumentDefinition("MES", "Micro E-mini S&P 500 ($5/pt)", "FUTURES", 5.0, 0.25, "USD", false, "ES=F"),
            new InstrumentDefinition("M2K", "Micro Russell 2000 Small Cap ($5/pt)", "FUTURES", 5.0, 0.10, "USD", false, "RTY=F"),
            new InstrumentDefinition("EMD", "E-mini S&P MidCap 400 ($100/pt)", "FUTURES", 100.0, 0.10, "USD", false, "EMD=F"),
            new InstrumentDefinition("MNQ", "Micro E-mini Nasdaq 100 ($2/pt)", "FUTURES", 2.0, 0.25, "USD", false, "NQ=F"),

            // US Equities & Minicaps
            new InstrumentDefinition("IWM", "iShares Russell 2000 ETF (Small Cap)", "EQUITIES", 1.0, 0.01, "USD", false, "IWM"),
            new InstrumentDefinition("MDY", "SPDR S&P MidCap 400 ETF (Mid Cap)", "EQUITIES", 1.0, 0.01, "USD", false, "MDY"),
            new InstrumentDefinition("AAPL", "Apple Inc. (Large Cap Stock)", "EQUITIES", 1.0, 0.01, "USD", false, "AAPL"),
            new InstrumentDefinition("SPY", "SPDR S&P 500 ETF Trust", "EQUITIES", 1.0, 0.01, "USD", false, "SPY"),
            new InstrumentDefinition("QQQ", "Invesco QQQ Trust (Nasdaq 100)", "EQUITIES", 1.0, 0.01, "USD", false, "QQQ")
        );

        String insertSql = """
            INSERT OR IGNORE INTO instruments (
                symbol, name, asset_class, point_value, tick_size, currency, is_custom, provider_ticker
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?);
            """;

        try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
            for (InstrumentDefinition def : defaults) {
                ps.setString(1, def.symbol());
                ps.setString(2, def.name());
                ps.setString(3, def.assetClass());
                ps.setDouble(4, def.pointValue());
                ps.setDouble(5, def.tickSize());
                ps.setString(6, def.currency());
                ps.setInt(7, def.isCustom() ? 1 : 0);
                ps.setString(8, def.providerTicker());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    public synchronized List<InstrumentDefinition> listAll() {
        String sql = "SELECT symbol, name, asset_class, point_value, tick_size, currency, is_custom, provider_ticker FROM instruments ORDER BY asset_class, symbol ASC;";
        List<InstrumentDefinition> list = new ArrayList<>();
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to query instruments", e);
        }
        return Collections.unmodifiableList(list);
    }

    public synchronized List<InstrumentDefinition> listByAssetClass(String assetClass) {
        String sql = "SELECT symbol, name, asset_class, point_value, tick_size, currency, is_custom, provider_ticker FROM instruments WHERE asset_class = ? ORDER BY symbol ASC;";
        List<InstrumentDefinition> list = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, assetClass.trim().toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to query instruments by asset class " + assetClass, e);
        }
        return Collections.unmodifiableList(list);
    }

    public synchronized Optional<InstrumentDefinition> getBySymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return Optional.empty();
        String sym = symbol.trim().toUpperCase().replace('/', '_');
        String sql = "SELECT symbol, name, asset_class, point_value, tick_size, currency, is_custom, provider_ticker FROM instruments WHERE symbol = ? OR REPLACE(symbol, '_', '') = ?;";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sym);
            ps.setString(2, sym.replace("_", ""));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to find instrument " + symbol, e);
        }
        return Optional.empty();
    }

    public synchronized boolean save(InstrumentDefinition def) {
        if (def == null) return false;
        String sql = """
            INSERT INTO instruments (
                symbol, name, asset_class, point_value, tick_size, currency, is_custom, provider_ticker
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(symbol) DO UPDATE SET
                name = excluded.name,
                asset_class = excluded.asset_class,
                point_value = excluded.point_value,
                tick_size = excluded.tick_size,
                currency = excluded.currency,
                is_custom = excluded.is_custom,
                provider_ticker = excluded.provider_ticker;
            """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, def.symbol());
            ps.setString(2, def.name());
            ps.setString(3, def.assetClass());
            ps.setDouble(4, def.pointValue());
            ps.setDouble(5, def.tickSize());
            ps.setString(6, def.currency());
            ps.setInt(7, def.isCustom() ? 1 : 0);
            ps.setString(8, def.providerTicker());
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            log.error("Failed to save instrument " + def.symbol(), e);
            return false;
        }
    }

    public synchronized boolean delete(String symbol) {
        if (symbol == null || symbol.isBlank()) return false;
        String sym = symbol.trim().toUpperCase().replace('/', '_');
        // Protect non-custom core seed instruments from accidental deletion
        Optional<InstrumentDefinition> existing = getBySymbol(sym);
        if (existing.isPresent() && !existing.get().isCustom()) {
            log.warn("Cannot delete built-in seed instrument: {}", sym);
            return false;
        }

        String sql = "DELETE FROM instruments WHERE symbol = ? AND is_custom = 1;";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, sym);
            int affected = ps.executeUpdate();
            return affected > 0;
        } catch (SQLException e) {
            log.error("Failed to delete custom instrument " + sym, e);
            return false;
        }
    }

    private static InstrumentDefinition mapRow(ResultSet rs) throws SQLException {
        return new InstrumentDefinition(
            rs.getString("symbol"),
            rs.getString("name"),
            rs.getString("asset_class"),
            rs.getDouble("point_value"),
            rs.getDouble("tick_size"),
            rs.getString("currency"),
            rs.getInt("is_custom") == 1,
            rs.getString("provider_ticker")
        );
    }

    @Override
    public void close() {
        if (ownsConnection) {
            try {
                if (connection != null && !connection.isClosed()) {
                    connection.close();
                }
            } catch (SQLException ignored) {}
        }
    }
}
