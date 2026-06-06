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
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(temp.toFile(), brief);
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
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
