package com.martinfou.trading.parser.bridge;

import java.nio.file.Path;
import java.util.Objects;

/** Standard SQ hot-folder layout under {@code data/sq-inbox/} (story 21-1). */
public final class SqInboxPaths {

    public static final String ROOT_DIR = "data/sq-inbox";
    public static final String PENDING = "pending";
    public static final String PASSED = "passed";
    public static final String FAILED = "failed";
    public static final String DLQ = "dlq";
    public static final String MANIFEST_SUFFIX = ".manifest.json";

    private SqInboxPaths() {}

    public static Path root(Path repoRoot) {
        return repoRoot.resolve(ROOT_DIR);
    }

    public static Path pending(Path repoRoot) {
        return root(repoRoot).resolve(PENDING);
    }

    public static Path passed(Path repoRoot) {
        return root(repoRoot).resolve(PASSED);
    }

    public static Path failed(Path repoRoot) {
        return root(repoRoot).resolve(FAILED);
    }

    public static Path dlq(Path repoRoot) {
        return root(repoRoot).resolve(DLQ);
    }

    public static Path manifestPathFor(Path xmlPath) {
        Objects.requireNonNull(xmlPath, "xmlPath");
        String fileName = xmlPath.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        return xmlPath.resolveSibling(stem + MANIFEST_SUFFIX);
    }
}
