package com.martinfou.trading.intelligence.research;

import com.martinfou.trading.intelligence.RepoRoots;

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

/** SQLite-backed store for strategy inspirations, sharing the same DB as the control plane. */
public final class ResearchInspirationStore implements AutoCloseable {

    private static final String JDBC_PREFIX = "jdbc:sqlite:";
    private final Connection connection;

    public ResearchInspirationStore(Path dbPath) {
        try {
            Class.forName("org.sqlite.JDBC");
            Path parent = dbPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            connection = DriverManager.getConnection(JDBC_PREFIX + dbPath.toAbsolutePath().normalize());
            initSchema();
        } catch (IOException | SQLException | ClassNotFoundException e) {
            throw new IllegalStateException("Failed to open ResearchInspirationStore at " + dbPath, e);
        }
    }

    public static ResearchInspirationStore resolveDefault() {
        return new ResearchInspirationStore(defaultDbPath());
    }

    public static Path defaultDbPath() {
        String explicitFile = System.getenv("TRADING_BRIDGE_EVENT_STORE");
        if (explicitFile != null && !explicitFile.isBlank()) {
            return Path.of(explicitFile).toAbsolutePath().normalize();
        }

        String dataDir = System.getenv("TRADING_BRIDGE_DATA_DIR");
        if (dataDir != null && !dataDir.isBlank()) {
            return Path.of(dataDir).toAbsolutePath().normalize().resolve("events.db");
        }

        Path repoRoot = RepoRoots.findRepoRoot();
        if (repoRoot != null) {
            return repoRoot.resolve("data/runtime/events.db").toAbsolutePath().normalize();
        }

        return Path.of(System.getProperty("user.home"), ".trading-bridge", "events.db").toAbsolutePath().normalize();
    }

    public Optional<ResearchInspiration> get(String id) {
        String sql = "SELECT id, title, description, status, result_status, strategy_id, metrics_json, created_at, updated_at " +
                     "FROM research_inspirations WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(readRow(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to get inspiration " + id, e);
        }
    }

    public List<ResearchInspiration> listAll() {
        String sql = "SELECT id, title, description, status, result_status, strategy_id, metrics_json, created_at, updated_at " +
                     "FROM research_inspirations ORDER BY created_at DESC";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            List<ResearchInspiration> list = new ArrayList<>();
            while (rs.next()) {
                list.add(readRow(rs));
            }
            return List.copyOf(list);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list inspirations", e);
        }
    }

    public void save(ResearchInspiration item) {
        String sql = "INSERT INTO research_inspirations (id, title, description, status, result_status, strategy_id, metrics_json, created_at, updated_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT(id) DO UPDATE SET " +
                     "  title = excluded.title, " +
                     "  description = excluded.description, " +
                     "  status = excluded.status, " +
                     "  result_status = excluded.result_status, " +
                     "  strategy_id = excluded.strategy_id, " +
                     "  metrics_json = excluded.metrics_json, " +
                     "  updated_at = excluded.updated_at";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, item.id());
            ps.setString(2, item.title());
            ps.setString(3, item.description());
            ps.setString(4, item.status());
            ps.setString(5, item.resultStatus());
            ps.setString(6, item.strategyId());
            ps.setString(7, item.metricsJson());
            ps.setString(8, item.createdAt().toString());
            ps.setString(9, item.updatedAt().toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save inspiration " + item.id(), e);
        }
    }

    public void delete(String id) {
        String sql = "DELETE FROM research_inspirations WHERE id = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete inspiration " + id, e);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to close SQLite connection", e);
        }
    }

    private void initSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS research_inspirations (
                  id              TEXT PRIMARY KEY,
                  title           TEXT NOT NULL,
                  description     TEXT NOT NULL,
                  status          TEXT NOT NULL,
                  result_status   TEXT,
                  strategy_id     TEXT,
                  metrics_json    TEXT,
                  created_at      TEXT NOT NULL,
                  updated_at      TEXT NOT NULL
                )""");
        }
    }

    private static ResearchInspiration readRow(ResultSet rs) throws SQLException {
        return new ResearchInspiration(
            rs.getString("id"),
            rs.getString("title"),
            rs.getString("description"),
            rs.getString("status"),
            rs.getString("result_status"),
            rs.getString("strategy_id"),
            rs.getString("metrics_json"),
            Instant.parse(rs.getString("created_at")),
            Instant.parse(rs.getString("updated_at"))
        );
    }
}
