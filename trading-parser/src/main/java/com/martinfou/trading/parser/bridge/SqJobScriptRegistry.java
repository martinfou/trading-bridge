package com.martinfou.trading.parser.bridge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Loads named sqcli job scripts from {@code data/sq-cli/scripts/registry.json} (story 21-5). */
public final class SqJobScriptRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, SqJobScript> scripts;

    public SqJobScriptRegistry(Map<String, SqJobScript> scripts) {
        this.scripts = Map.copyOf(scripts);
    }

    public static SqJobScriptRegistry load(Path registryPath) throws IOException {
        if (!Files.isRegularFile(registryPath)) {
            throw new IOException("Script registry not found: " + registryPath);
        }
        RegistryFile file = MAPPER.readValue(registryPath.toFile(), RegistryFile.class);
        if (file.scripts() == null || file.scripts().isEmpty()) {
            throw new IOException("Script registry is empty: " + registryPath);
        }
        Map<String, SqJobScript> map = new LinkedHashMap<>();
        for (SqJobScript script : file.scripts()) {
            if (map.putIfAbsent(script.id(), script) != null) {
                throw new IOException("Duplicate script id in registry: " + script.id());
            }
        }
        return new SqJobScriptRegistry(map);
    }

    public static SqJobScriptRegistry loadFromClasspath(String resourcePath) throws IOException {
        InputStream in = SqJobScriptRegistry.class.getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IOException("Classpath registry not found: " + resourcePath);
        }
        RegistryFile file = MAPPER.readValue(in, RegistryFile.class);
        if (file.scripts() == null || file.scripts().isEmpty()) {
            throw new IOException("Script registry is empty: " + resourcePath);
        }
        Map<String, SqJobScript> map = new LinkedHashMap<>();
        for (SqJobScript script : file.scripts()) {
            if (map.putIfAbsent(script.id(), script) != null) {
                throw new IOException("Duplicate script id in registry: " + script.id());
            }
        }
        return new SqJobScriptRegistry(map);
    }

    public List<SqJobScript> all() {
        return List.copyOf(scripts.values());
    }

    public Optional<SqJobScript> find(String id) {
        return Optional.ofNullable(scripts.get(id));
    }

    public SqJobScript require(String id) throws IOException {
        return find(id).orElseThrow(() -> new IOException("Unknown SQ job script: " + id));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RegistryFile(List<SqJobScript> scripts) {}
}
