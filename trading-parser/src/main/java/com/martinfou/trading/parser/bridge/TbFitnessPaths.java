package com.martinfou.trading.parser.bridge;

import java.nio.file.Path;

/** Paths for TB → SQ fitness feedback artifacts (story 21-8). */
public final class TbFitnessPaths {

    public static final String FITNESS_DIR = "fitness";
    public static final String EXPORT_CSV = "tb-fitness.csv";
    public static final String KEYS_MANIFEST = "tb-fitness-keys.jsonl";
    public static final String INDICATOR_NAME = "tbFitness";

    private TbFitnessPaths() {}

    public static Path fitnessDir(Path repoRoot) {
        return SqJobPaths.root(repoRoot).resolve(FITNESS_DIR);
    }

    public static Path exportCsv(Path repoRoot) {
        return fitnessDir(repoRoot).resolve(EXPORT_CSV);
    }

    public static Path keysManifest(Path repoRoot) {
        return fitnessDir(repoRoot).resolve(KEYS_MANIFEST);
    }
}
