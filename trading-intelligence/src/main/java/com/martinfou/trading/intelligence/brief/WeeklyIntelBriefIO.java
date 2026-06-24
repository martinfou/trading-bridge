package com.martinfou.trading.intelligence.brief;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public final class WeeklyIntelBriefIO {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private WeeklyIntelBriefIO() {}

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static void write(WeeklyIntelBrief brief, Path target) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            try (var out = Files.newOutputStream(temp)) {
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(out, brief);
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

    public static WeeklyIntelBrief read(Path path) throws IOException {
        return MAPPER.readValue(path.toFile(), WeeklyIntelBrief.class);
    }

    public static Path briefPathForDate(Path intelRoot, LocalDate ingestDate) {
        String name = "brief-" + ingestDate.format(DateTimeFormatter.ISO_LOCAL_DATE) + ".json";
        return intelRoot.resolve(name);
    }
}
