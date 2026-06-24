package com.martinfou.trading.intelligence.plan;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class WeeklyPlanIO {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private WeeklyPlanIO() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static void write(WeeklyPlan plan, Path target) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            try (var out = Files.newOutputStream(temp)) {
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(out, plan);
            }
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public static WeeklyPlan read(Path path) throws IOException {
        try (var in = Files.newInputStream(path)) {
            return MAPPER.readValue(in, WeeklyPlan.class);
        }
    }

    public static Path planPath(Path pendingRoot, String weekId) {
        return pendingRoot.resolve("weekly-plan-" + weekId + ".json");
    }

    public static Path manifestPath(Path pendingRoot, String weekId) {
        return pendingRoot.resolve("weekly-plan-" + weekId + ".manifest.json");
    }
}
