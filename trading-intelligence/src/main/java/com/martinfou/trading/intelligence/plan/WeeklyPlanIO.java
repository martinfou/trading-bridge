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
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), plan);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static WeeklyPlan read(Path path) throws IOException {
        return MAPPER.readValue(path.toFile(), WeeklyPlan.class);
    }

    public static Path planPath(Path pendingRoot, String weekId) {
        return pendingRoot.resolve("weekly-plan-" + weekId + ".json");
    }

    public static Path manifestPath(Path pendingRoot, String weekId) {
        return pendingRoot.resolve("weekly-plan-" + weekId + ".manifest.json");
    }
}
