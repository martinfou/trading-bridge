package com.martinfou.trading.parser.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * JSON read/write for {@link StrategyManifest} sidecars (story 21-1).
 */
public final class StrategyManifestIO {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private StrategyManifestIO() {}

    public static StrategyManifest read(Path manifestPath) throws IOException {
        return MAPPER.readValue(manifestPath.toFile(), StrategyManifest.class);
    }

    public static void write(StrategyManifest manifest, Path manifestPath) throws IOException {
        Path parent = manifestPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path directory = parent != null ? parent : Path.of(".");
        Path temp = Files.createTempFile(directory, ".manifest-", ".tmp");
        try {
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), manifest);
            try {
                Files.move(temp, manifestPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, manifestPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Returns an existing sidecar when its {@code contentSha256} matches the current XML bytes;
     * otherwise regenerates from the XML (missing, corrupt, stale hash, or invalid JSON).
     */
    public static StrategyManifest ensureSidecar(Path xmlPath) throws IOException {
        return ensureSidecar(xmlPath, Files.readAllBytes(xmlPath));
    }

    /**
     * Like {@link #ensureSidecar(Path)} but uses pre-read XML bytes (avoids a second disk read).
     */
    public static StrategyManifest ensureSidecar(Path xmlPath, byte[] bytes) throws IOException {
        Path manifestPath = SqInboxPaths.manifestPathFor(xmlPath);
        String currentHash = StrategyManifestFactory.sha256(bytes);

        if (Files.exists(manifestPath)) {
            StrategyManifest existing = tryRead(manifestPath);
            if (existing != null && currentHash.equals(existing.contentSha256())) {
                return existing;
            }
            Files.deleteIfExists(manifestPath);
        }

        StrategyManifest manifest = StrategyManifestFactory.fromBytes(bytes, xmlPath);
        write(manifest, manifestPath);
        return manifest;
    }

    private static StrategyManifest tryRead(Path manifestPath) {
        try {
            return read(manifestPath);
        } catch (IOException e) {
            return null;
        }
    }

    static ObjectMapper mapper() {
        return MAPPER;
    }
}
