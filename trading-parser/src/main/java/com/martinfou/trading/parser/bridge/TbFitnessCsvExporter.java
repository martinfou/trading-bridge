package com.martinfou.trading.parser.bridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Writes StrategyQuant-compatible external indicator CSV (story 21-8).
 *
 * <p>SQ format (no header): {@code Date,Time,O,H,L,C,V,sharpe,profitFactor,maxDrawdown,compositeScore}
 * with {@code dd/MM/yyyy} and {@code HH:mm:ss} UTC. Strategy keys live in {@link TbFitnessPaths#keysManifest}.
 */
public final class TbFitnessCsvExporter {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);

    private TbFitnessCsvExporter() {}

    public static Path write(List<TbFitnessRecord> records, Path csvPath) throws IOException {
        requireNoSpaces(csvPath);
        Files.createDirectories(csvPath.getParent());
        List<String> lines = records.stream().map(TbFitnessCsvExporter::toCsvLine).toList();
        Files.write(csvPath, lines, StandardCharsets.UTF_8);
        return csvPath.toAbsolutePath().normalize();
    }

    public static Path writeKeysManifest(List<TbFitnessRecord> records, Path manifestPath) throws IOException {
        Files.createDirectories(manifestPath.getParent());
        List<String> lines = records.stream()
            .map(r -> r.manifestId() + "\t" + r.symbol() + "\t" + r.processedAt())
            .collect(Collectors.toList());
        Files.write(manifestPath, lines, StandardCharsets.UTF_8);
        return manifestPath.toAbsolutePath().normalize();
    }

    static String toCsvLine(TbFitnessRecord record) {
        Instant instant = record.processedAt();
        var zdt = instant.atZone(ZoneOffset.UTC);
        return String.join(",",
            DATE.format(zdt),
            TIME.format(zdt),
            "1",
            "1",
            "1",
            "1",
            "0",
            format(record.sharpeRatio()),
            format(record.profitFactor()),
            format(record.maxDrawdownPct()),
            format(record.compositeScore())
        );
    }

    static void requireNoSpaces(Path path) throws IOException {
        String normalized = path.toAbsolutePath().normalize().toString();
        if (normalized.contains(" ")) {
            throw new IOException("SQ fitness CSV path must not contain spaces: " + normalized);
        }
    }

    private static String format(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "0.000000";
        }
        return String.format(Locale.ROOT, "%.6f", value);
    }
}
