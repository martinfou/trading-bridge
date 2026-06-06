package com.martinfou.trading.intelligence.compile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

public final class CompileManifestIO {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private CompileManifestIO() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static CompileManifest read(Path path) throws IOException {
        return MAPPER.readValue(path.toFile(), CompileManifest.class);
    }

    /** First compiled bundle (by weekId folder name) containing manifest.json. */
    public static Optional<CompiledBundle> findNextBundle(Path repoRoot) throws IOException {
        Path compiledRoot = com.martinfou.trading.intelligence.paths.WeeklyBuilderPaths.compiled(repoRoot);
        if (!Files.isDirectory(compiledRoot)) {
            return Optional.empty();
        }
        try (Stream<Path> stream = Files.list(compiledRoot)) {
            return stream
                .filter(Files::isDirectory)
                .filter(dir -> Files.isRegularFile(dir.resolve("manifest.json")))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .findFirst()
                .map(dir -> new CompiledBundle(dir, dir.resolve("manifest.json")));
        }
    }

    public record CompiledBundle(Path bundleDir, Path manifestPath) {}
}
