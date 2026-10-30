package com.martinfou.trading.runtime.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Append-only JSON Lines ({@code .jsonl}) writer and reader for unified fill logs (PRD §5 F4, Decision D10).
 *
 * <p>Default path: {@code data-futures/fills/<strategy>-<symbol>-<mode>.jsonl}.</p>
 */
public final class FillLogWriter {

    private static final Logger log = LoggerFactory.getLogger(FillLogWriter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    public static final Path DEFAULT_FILLS_DIR = Paths.get("data-futures/fills");

    private final Path filePath;

    public FillLogWriter(Path filePath) {
        this.filePath = Objects.requireNonNull(filePath, "filePath is required");
    }

    public static FillLogWriter forRun(Path baseDir, String strategy, String symbol, String mode) {
        Objects.requireNonNull(baseDir, "baseDir is required");
        String cleanStrategy = Objects.requireNonNull(strategy, "strategy").trim();
        String cleanSymbol = Objects.requireNonNull(symbol, "symbol").trim().toUpperCase();
        String cleanMode = Objects.requireNonNull(mode, "mode").trim().toLowerCase();
        Path file = baseDir.resolve(cleanStrategy + "-" + cleanSymbol + "-" + cleanMode + ".jsonl");
        return new FillLogWriter(file);
    }

    public Path filePath() {
        return filePath;
    }

    /** Clears any prior backtest JSONL file before writing a fresh deterministic backtest run. */
    public synchronized void truncate() throws IOException {
        Path parent = filePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
            filePath,
            "",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        );
    }

    public synchronized void append(FillLogRecord record) {
        Objects.requireNonNull(record, "record is required");
        try {
            Path parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String line = MAPPER.writeValueAsString(record) + System.lineSeparator();
            Files.writeString(
                filePath,
                line,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
                StandardOpenOption.WRITE
            );
        } catch (IOException e) {
            log.error("Failed to append FillLogRecord to {}: {}", filePath, e.getMessage(), e);
        }
    }

    public static List<FillLogRecord> readAll(Path path) throws IOException {
        if (path == null || !Files.exists(path)) {
            return List.of();
        }
        List<FillLogRecord> records = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    records.add(MAPPER.readValue(trimmed, FillLogRecord.class));
                }
            }
        }
        return List.copyOf(records);
    }
}

