package com.martinfou.trading.parser.bridge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Moves XML + manifest sidecar between inbox folders (story 21-2). */
public final class SqInboxTransfers {

    private SqInboxTransfers() {}

    public static Path resultPathIn(Path destinationDir, Path xmlFileName) {
        String name = xmlFileName.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return destinationDir.resolve(stem + "-result.json");
    }

    public static Path coveragePathIn(Path destinationDir, Path xmlFileName) {
        String name = xmlFileName.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        return destinationDir.resolve(stem + "-coverage.json");
    }

    public static void moveToFolder(Path xml, Path manifest, Path destinationDir) throws IOException {
        Files.createDirectories(destinationDir);
        Path targetXml = destinationDir.resolve(xml.getFileName());
        Path targetManifest = destinationDir.resolve(manifest.getFileName());
        if (Files.exists(targetXml)) {
            throw new IOException("XML already in destination: " + targetXml);
        }
        Files.move(xml, targetXml);
        if (Files.exists(manifest)) {
            if (Files.exists(targetManifest)) {
                throw new IOException("Manifest already in destination: " + targetManifest);
            }
            Files.move(manifest, targetManifest);
        }
    }
}
