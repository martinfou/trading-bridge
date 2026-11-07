package com.martinfou.trading.runtime.generator;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.martinfou.trading.runtime.generator.StrategyGeneratorService.DatabankCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * SQLite-backed repository for generated and promoted {@link DatabankCandidate} strategies.
 *
 * <p>Replaces file-based {@code databank.json}/{@code promoted.json} and static Java catalog
 * code generation ({@code SqImportedStrategyCatalog}/{@code GeneratedStrategyCatalog}) with a
 * single durable SQLite table ({@code generated_strategies}) in {@code strategies.db}.</p>
 */
public final class SqliteStrategyDatabank {

    private static final Logger log = LoggerFactory.getLogger(SqliteStrategyDatabank.class);
    private static final String JDBC_PREFIX = "jdbc:sqlite:";

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final Path dbPath;

    public SqliteStrategyDatabank(Path dbPath) {
        if (dbPath == null) {
            throw new IllegalArgumentException("dbPath must not be null");
        }
        this.dbPath = dbPath.toAbsolutePath().normalize();
        initSchema();
    }

    public Path dbPath() {
        return dbPath;
    }

    private Connection openConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(JDBC_PREFIX + dbPath);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL;");
            st.execute("PRAGMA busy_timeout=5000;");
        }
        return conn;
    }

    private void initSchema() {
        try {
            Path parent = dbPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Connection conn = openConnection(); Statement st = conn.createStatement()) {
                st.execute("""
                    CREATE TABLE IF NOT EXISTS generated_strategies (
                        id TEXT PRIMARY KEY,
                        symbol TEXT NOT NULL,
                        timeframe TEXT NOT NULL,
                        archetype TEXT NOT NULL,
                        tier TEXT NOT NULL,
                        composite_score REAL NOT NULL,
                        promoted INTEGER NOT NULL DEFAULT 0,
                        created_at TEXT NOT NULL,
                        candidate_json TEXT NOT NULL
                    )
                    """);
                st.execute("""
                    CREATE INDEX IF NOT EXISTS idx_generated_strategies_score
                    ON generated_strategies(composite_score DESC)
                    """);
                st.execute("""
                    CREATE INDEX IF NOT EXISTS idx_generated_strategies_promoted
                    ON generated_strategies(promoted)
                    """);
            }
        } catch (IOException | SQLException e) {
            throw new IllegalStateException("Failed to initialize SqliteStrategyDatabank at " + dbPath, e);
        }
    }

    public synchronized List<DatabankCandidate> loadAll() {
        List<DatabankCandidate> result = new ArrayList<>();
        String sql = "SELECT candidate_json FROM generated_strategies ORDER BY composite_score DESC";
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String json = rs.getString(1);
                if (json != null && !json.isBlank()) {
                    try {
                        DatabankCandidate candidate = MAPPER.readValue(json, DatabankCandidate.class);
                        if (candidate != null) {
                            result.add(candidate.withSynthesizedJavaSourceCode());
                        }
                    } catch (Exception ex) {
                        log.warn("Skipping unreadable strategy row in {}: {}", dbPath, ex.getMessage());
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("Failed to load strategies from {}: {}", dbPath, e.getMessage());
        }
        return result;
    }

    public synchronized boolean upsert(DatabankCandidate candidate) {
        if (candidate == null || candidate.id() == null) {
            return false;
        }
        return upsertAll(List.of(candidate));
    }

    public synchronized boolean upsertAll(Collection<DatabankCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return true;
        }
        String sql = """
            INSERT INTO generated_strategies (
                id, symbol, timeframe, archetype, tier, composite_score, promoted, created_at, candidate_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                symbol = excluded.symbol,
                timeframe = excluded.timeframe,
                archetype = excluded.archetype,
                tier = excluded.tier,
                composite_score = excluded.composite_score,
                promoted = excluded.promoted,
                created_at = excluded.created_at,
                candidate_json = excluded.candidate_json
            """;
        try (Connection conn = openConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (DatabankCandidate c : candidates) {
                    if (c == null || c.id() == null) {
                        continue;
                    }
                    DatabankCandidate compact = c.withoutJavaSourceCode();
                    String json = MAPPER.writeValueAsString(compact);
                    double score = Double.isFinite(c.compositeScore()) ? c.compositeScore() : 0.0;
                    ps.setString(1, c.id());
                    ps.setString(2, c.symbol() != null ? c.symbol() : "EUR_USD");
                    ps.setString(3, c.timeframe() != null ? c.timeframe() : "H1");
                    ps.setString(4, c.archetype() != null ? c.archetype() : "TREND_FOLLOWING");
                    ps.setString(5, c.tier() != null ? c.tier() : "QUALIFIED");
                    ps.setDouble(6, score);
                    ps.setInt(7, c.promoted() ? 1 : 0);
                    ps.setString(8, c.createdAt() != null ? c.createdAt().toString() : "");
                    ps.setString(9, json);
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
                return true;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (Exception e) {
            log.warn("Failed to upsert {} strategy candidate(s) into {}: {}", candidates.size(), dbPath, e.getMessage());
            return false;
        }
    }

    public synchronized void vacuum() {
        try (Connection conn = openConnection();
             Statement st = conn.createStatement()) {
            st.execute("VACUUM");
        } catch (SQLException e) {
            log.warn("Failed to vacuum {}: {}", dbPath, e.getMessage());
        }
    }

    public synchronized void delete(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement("DELETE FROM generated_strategies WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to delete strategy {} from {}: {}", id, dbPath, e.getMessage());
        }
    }

    public synchronized void deleteAll() {
        try (Connection conn = openConnection();
             Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM generated_strategies");
        } catch (SQLException e) {
            log.warn("Failed to clear strategies in {}: {}", dbPath, e.getMessage());
        }
    }
}
