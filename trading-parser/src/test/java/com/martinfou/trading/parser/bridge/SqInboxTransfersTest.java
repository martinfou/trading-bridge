package com.martinfou.trading.parser.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SqInboxTransfersTest {

    @Test
    void moveToFolder_rejectsExistingXml(@TempDir Path tempDir) throws Exception {
        Path source = tempDir.resolve("pending");
        Path dest = tempDir.resolve("passed");
        Files.createDirectories(source);
        Files.createDirectories(dest);

        Path xml = source.resolve("strategy.xml");
        Files.writeString(xml, "<StrategyFile Version=\"1\"/>");
        Files.writeString(dest.resolve("strategy.xml"), "existing");

        IOException ex = assertThrows(
            IOException.class,
            () -> SqInboxTransfers.moveToFolder(xml, source.resolve("missing.manifest.json"), dest)
        );
        assertTrue(ex.getMessage().contains("already in destination"));
        assertTrue(Files.exists(source.resolve("strategy.xml")));
    }
}
