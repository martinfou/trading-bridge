package com.martinfou.trading.parser.bridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqInboxProcessorTest {

    @Test
    void processPending_movesValidXmlToPassedWithResult(@TempDir Path repo) throws Exception {
        Path pending = SqInboxPaths.pending(repo);
        Path passed = SqInboxPaths.passed(repo);
        Path failed = SqInboxPaths.failed(repo);
        Files.createDirectories(pending);
        Files.createDirectories(passed);
        Files.createDirectories(failed);

        Path xml = copyFixture(pending, "strategy-1.6.221B.xml");

        SqInboxOptions options = new SqInboxOptions(repo, null, 100_000.0, 200, List.of());
        int exit = new SqInboxProcessor().run(options);

        assertEquals(0, exit);
        assertFalse(Files.exists(xml));
        assertTrue(Files.exists(passed.resolve("strategy-1.6.221B.xml")));
        assertTrue(Files.exists(passed.resolve("strategy-1.6.221B.manifest.json")));
        Path resultPath = passed.resolve("strategy-1.6.221B-result.json");
        assertTrue(Files.exists(resultPath));
        assertTrue(Files.exists(passed.resolve("strategy-1.6.221B-coverage.json")));

        SqInboxResult result = new ObjectMapper()
            .findAndRegisterModules()
            .readValue(resultPath.toFile(), SqInboxResult.class);
        assertTrue(result.success());
        assertEquals("passed", result.disposition());
        assertNotNull(result.manifestId());
        assertEquals("EUR_USD", result.symbol());
        assertNotNull(result.sharpeRatio());
        assertNotNull(result.profitFactor());
        assertNotNull(result.maxDrawdownPct());
        assertNotNull(result.compositeScore());
        assertFalse(Files.exists(failed.resolve("strategy-1.6.221B.xml")));
    }

    @Test
    void processPending_movesInvalidXmlToFailed(@TempDir Path repo) throws Exception {
        Path pending = SqInboxPaths.pending(repo);
        Files.createDirectories(pending);
        Files.createDirectories(SqInboxPaths.passed(repo));
        Files.createDirectories(SqInboxPaths.failed(repo));

        Path xml = pending.resolve("broken.xml");
        Files.writeString(xml, "<not-sq-xml/>");

        SqInboxOptions options = new SqInboxOptions(repo, "EUR_USD", 50_000.0, 100, List.of());
        int exit = new SqInboxProcessor().run(options);

        assertEquals(1, exit);
        assertFalse(Files.exists(xml));
        assertTrue(Files.exists(SqInboxPaths.failed(repo).resolve("broken.xml")));
        Path resultPath = SqInboxPaths.failed(repo).resolve("broken-result.json");
        assertTrue(Files.exists(resultPath));
        assertTrue(Files.exists(SqInboxPaths.failed(repo).resolve("broken-coverage.json")));
        SqInboxResult result = new ObjectMapper()
            .findAndRegisterModules()
            .readValue(resultPath.toFile(), SqInboxResult.class);
        assertFalse(result.success());
        assertEquals("failed", result.disposition());
        assertNotNull(result.errorMessage());
    }

    @Test
    void gapStrategy_movesToDlq(@TempDir Path repo) throws Exception {
        Path pending = SqInboxPaths.pending(repo);
        Files.createDirectories(pending);
        Files.createDirectories(SqInboxPaths.passed(repo));
        Files.createDirectories(SqInboxPaths.failed(repo));
        Files.createDirectories(SqInboxPaths.dlq(repo));

        copyFixture(pending, "strategy-gap-ichimoku.xml");

        SqInboxOptions options = new SqInboxOptions(repo, "EUR_USD", 50_000.0, 100, List.of());
        int exit = new SqInboxProcessor().run(options);

        assertEquals(1, exit);
        assertTrue(Files.exists(SqInboxPaths.dlq(repo).resolve("strategy-gap-ichimoku.xml")));
        assertTrue(Files.exists(SqInboxPaths.dlq(repo).resolve("strategy-gap-ichimoku-coverage.json")));
        Path resultPath = SqInboxPaths.dlq(repo).resolve("strategy-gap-ichimoku-result.json");
        SqInboxResult result = new ObjectMapper()
            .findAndRegisterModules()
            .readValue(resultPath.toFile(), SqInboxResult.class);
        assertEquals("dlq", result.disposition());
    }

    @Test
    void oversizedXml_movesToDlq(@TempDir Path repo) throws Exception {
        Path pending = SqInboxPaths.pending(repo);
        Files.createDirectories(pending);
        Files.createDirectories(SqInboxPaths.dlq(repo));

        Path xml = pending.resolve("huge.xml");
        Files.writeString(xml, "x".repeat(64));

        SqInboxOptions options = new SqInboxOptions(repo, null, 100_000.0, 100, List.of(), 32);
        int exit = new SqInboxProcessor().run(options);

        assertEquals(1, exit);
        assertTrue(Files.exists(SqInboxPaths.dlq(repo).resolve("huge.xml")));
    }

    @Test
    void emptyPending_returnsZero(@TempDir Path repo) throws Exception {
        Files.createDirectories(SqInboxPaths.pending(repo));
        SqInboxOptions options = new SqInboxOptions(repo, null, 100_000.0, 100, List.of());
        assertEquals(0, new SqInboxProcessor().run(options));
    }

    @Test
    void syntheticBars_producesEnoughBars() {
        assertEquals(50, SqInboxProcessor.syntheticBars("EUR_USD", 50).size());
    }

    private static Path copyFixture(Path dir, String resourceName) throws Exception {
        InputStream in = SqInboxProcessorTest.class.getResourceAsStream("/sq/" + resourceName);
        assertNotNull(in, "fixture missing: " + resourceName);
        Path target = dir.resolve(resourceName);
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }
}
