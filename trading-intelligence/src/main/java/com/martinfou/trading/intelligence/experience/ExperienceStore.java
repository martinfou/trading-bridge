package com.martinfou.trading.intelligence.experience;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.martinfou.trading.core.agent.PipelineResult;
import com.martinfou.trading.core.agent.StrategySpec;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Append-only experience store using JSONL format.
 *
 * Every pipeline run appends a record. Records are used to inject
 * lessons learned into the LLM context for subsequent runs.
 *
 * File: data/experience-store/experience.jsonl
 * Rotation: at 100 entries, old entries are archived to experience-YYYY-MM-DD.jsonl
 */
public class ExperienceStore {

    private static final Path DEFAULT_DIR = Path.of("data/experience-store");
    private static final String ACTIVE_FILE = "experience.jsonl";
    private static final int MAX_ENTRIES = 100;
    private static final int CONTEXT_WINDOW = 10;  // Last N entries fed to LLM

    private static final ObjectMapper JSON = new ObjectMapper()
        .registerModule(new JavaTimeModule());

    private final Path storeDir;
    private final int contextWindow;

    public ExperienceStore() {
        this(DEFAULT_DIR, CONTEXT_WINDOW);
    }

    public ExperienceStore(Path storeDir, int contextWindow) {
        this.storeDir = storeDir;
        this.contextWindow = contextWindow;
        try {
            Files.createDirectories(storeDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create experience store: " + storeDir, e);
        }
    }

    /**
     * Append a record to the experience store.
     */
    public void record(StrategySpec spec, PipelineResult result, List<String> lessons) {
        var record = new ExperienceRecord(
            spec.name(),
            spec.category(),
            result.qualified(),
            result.averagePf(),
            result.maxDrawdown(),
            result.pairResults().size(),
            result.qualifiedPairCount(1.05, 20, 100),
            result.failureReason(),
            lessons,
            Instant.now()
        );
        append(record);
    }

    /**
     * Get the latest N records for LLM context injection.
     */
    public List<ExperienceRecord> recent() {
        List<ExperienceRecord> all = readAll();
        int from = Math.max(0, all.size() - contextWindow);
        return all.subList(from, all.size());
    }

    /**
     * Get all records (for periodic synthesis).
     */
    public List<ExperienceRecord> all() {
        return readAll();
    }

    /**
     * Get failure records only (for focused learning).
     */
    public List<ExperienceRecord> failures() {
        return readAll().stream()
            .filter(r -> !r.qualified())
            .collect(Collectors.toList());
    }

    /**
     * Build a compact context string for LLM injection.
     */
    public String buildContext() {
        List<ExperienceRecord> recent = recent();
        if (recent.isEmpty()) return "";

        var sb = new StringBuilder();
        sb.append("\n### Recent Strategy Generation History\n");
        sb.append("The following strategies were recently tested. Learn from failures, adapt from successes.\n\n");

        for (var r : recent) {
            String status = r.qualified() ? "✅ QUALIFIED" : "❌ REJECTED";
            sb.append(String.format("- %s [%s]: %s — PF %.2f, DD %.1f%%, pairs %d/%d",
                r.strategyName(), status, r.category(),
                r.avgPf(), r.maxDd(), r.qualifiedPairs(), r.totalPairs()));
            if (!r.qualified() && r.failureReason() != null && !r.failureReason().isBlank()) {
                sb.append(" — ").append(r.failureReason().replace('\n', ' ').trim());
            }
            if (r.lessons() != null && !r.lessons().isEmpty()) {
                sb.append(". Lessons: ");
                sb.append(String.join("; ", r.lessons()));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private void append(ExperienceRecord record) {
        Path file = storeDir.resolve(ACTIVE_FILE);
        try (var writer = new BufferedWriter(new FileWriter(file.toFile(), true))) {
            writer.write(JSON.writeValueAsString(record));
            writer.newLine();
        } catch (IOException e) {
            System.err.println("ExperienceStore: cannot write: " + e.getMessage());
        }
        rotateIfNeeded();
    }

    private List<ExperienceRecord> readAll() {
        Path file = storeDir.resolve(ACTIVE_FILE);
        if (!Files.exists(file)) return List.of();

        var records = new ArrayList<ExperienceRecord>();
        try (var reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    try {
                        records.add(JSON.readValue(line, ExperienceRecord.class));
                    } catch (Exception e) {
                        System.err.println("ExperienceStore: skipping malformed line: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("ExperienceStore: cannot read: " + e.getMessage());
        }
        return records;
    }

    private void rotateIfNeeded() {
        Path file = storeDir.resolve(ACTIVE_FILE);
        if (!Files.exists(file)) return;
        try {
            long lines = Files.lines(file).count();
            if (lines > MAX_ENTRIES) {
                String date = Instant.now().toString().substring(0, 10);
                Files.move(file, storeDir.resolve("experience-" + date + ".jsonl"));
                System.out.println("ExperienceStore: rotated to experience-" + date + ".jsonl");
            }
        } catch (IOException e) {
            System.err.println("ExperienceStore: rotation failed: " + e.getMessage());
        }
    }
}
