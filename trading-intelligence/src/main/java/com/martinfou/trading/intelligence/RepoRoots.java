package com.martinfou.trading.intelligence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Locates the Maven monorepo root from the working directory. */
public final class RepoRoots {

    private RepoRoots() {}

    public static Path findRepoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (dir != null) {
            Path pom = dir.resolve("pom.xml");
            if (Files.isRegularFile(pom)) {
                try {
                    String text = Files.readString(pom);
                    if (text.contains("<artifactId>trading-bridge</artifactId>")) {
                        return dir;
                    }
                } catch (IOException ignored) {
                    // continue walking up
                }
            }
            dir = dir.getParent();
        }
        return Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }
}
