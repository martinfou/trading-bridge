package com.martinfou.trading.parser.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** Collects {@link TbFitnessRecord} rows from inbox {@code passed/} results (story 21-8). */
public final class TbFitnessCollector {

    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private TbFitnessCollector() {}

    public static List<TbFitnessRecord> collectFromPassed(Path repoRoot) throws IOException {
        Path passed = SqInboxPaths.passed(repoRoot);
        if (!Files.isDirectory(passed)) {
            return List.of();
        }
        List<TbFitnessRecord> records = new ArrayList<>();
        try (Stream<Path> stream = Files.list(passed)) {
            List<Path> resultFiles = stream
                .filter(p -> p.getFileName().toString().endsWith("-result.json"))
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();
            for (Path resultPath : resultFiles) {
                SqInboxResult result = MAPPER.readValue(resultPath.toFile(), SqInboxResult.class);
                if (!result.success() || result.compositeScore() == null) {
                    continue;
                }
                records.add(TbFitnessRecord.fromInboxResult(result));
            }
        }
        return List.copyOf(records);
    }
}
