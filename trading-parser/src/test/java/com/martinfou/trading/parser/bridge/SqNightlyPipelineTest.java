package com.martinfou.trading.parser.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SqNightlyPipelineTest {

    @Test
    void parse_emptyArgs_runsFullPipelineDefaults(@TempDir Path repo) throws Exception {
        Files.createDirectories(SqInboxPaths.pending(repo));
        Path nested = repo.resolve("module");
        Files.createDirectories(nested);

        Path previous = Path.of(System.getProperty("user.dir"));
        try {
            System.setProperty("user.dir", nested.toString());
            SqNightlyOptions options = SqNightlyPipeline.SqNightlyCli.parse(new String[] {});

            assertEquals(repo, options.repoRoot());
            assertFalse(options.skipJobs());
            assertFalse(options.skipExport());
            assertFalse(options.skipInbox());
            assertFalse(options.dryRun());
        } finally {
            System.setProperty("user.dir", previous.toString());
        }
    }

    @Test
    void run_defaultOptions_executesJobsAndEmptyInbox(@TempDir Path repo) throws Exception {
        installRegistry(repo);
        Files.createDirectories(SqInboxPaths.pending(repo));
        Files.createDirectories(SqInboxPaths.passed(repo));
        Files.createDirectories(SqInboxPaths.failed(repo));
        Files.createDirectories(SqInboxPaths.dlq(repo));

        SqNightlyOptions options = new SqNightlyOptions(
            repo, repo, true, true, null,
            null, true, false, false,
            new SqInboxOptions(repo, null, 100_000.0, 100, List.of())
        );

        SqNightlyResult result = new SqNightlyPipeline().run(options);

        assertEquals(Map.of("update-data", 0, "list-databanks", 0), result.jobExitCodes());
        assertEquals(0, result.inbox().processed());
        assertEquals(0, result.pipelineExitCode());
    }

    @Test
    void importExports_copiesXmlIntoPending(@TempDir Path repo) throws Exception {
        Path exportDir = repo.resolve("exports");
        Path pending = SqInboxPaths.pending(repo);
        Files.createDirectories(exportDir);
        Files.writeString(exportDir.resolve("alpha.xml"), "<xml/>");
        Files.writeString(exportDir.resolve("readme.txt"), "skip");

        int count = SqNightlyPipeline.importExports(exportDir, pending);

        assertEquals(1, count);
        assertTrue(Files.exists(pending.resolve("alpha.xml")));
    }

    @Test
    void run_dryRunJobs_skipsInbox(@TempDir Path repo) throws Exception {
        installRegistry(repo);
        SqNightlyOptions options = new SqNightlyOptions(
            repo, repo, true, true, null,
            null, true, false, true,
            new SqInboxOptions(repo, null, 100_000.0, 100, List.of())
        );

        SqNightlyResult result = new SqNightlyPipeline().run(options);

        assertEquals(Map.of("update-data", 0, "list-databanks", 0), result.jobExitCodes());
        assertEquals(0, result.filesExported());
        assertEquals(0, result.inbox().processed());
        assertEquals(0, result.pipelineExitCode());
    }

    @Test
    void run_skipJobs_drainsInbox(@TempDir Path repo) throws Exception {
        Path pending = SqInboxPaths.pending(repo);
        Files.createDirectories(pending);
        Files.createDirectories(SqInboxPaths.passed(repo));
        Files.createDirectories(SqInboxPaths.failed(repo));
        Files.createDirectories(SqInboxPaths.dlq(repo));
        Files.writeString(pending.resolve("broken.xml"), "<not-sq/>");

        SqNightlyOptions options = new SqNightlyOptions(
            repo, null, false, true, null,
            null, true, true, false,
            new SqInboxOptions(repo, "EUR_USD", 50_000.0, 100, List.of())
        );

        SqNightlyResult result = new SqNightlyPipeline().run(options);

        assertTrue(result.jobExitCodes().isEmpty());
        assertEquals(1, result.inbox().processed());
        assertEquals(0, result.inbox().passed());
        assertEquals(1, result.inbox().failed());
        assertEquals(1, result.pipelineExitCode());
    }

    @Test
    void run_importsThenProcesses(@TempDir Path repo) throws Exception {
        Path exportDir = repo.resolve("exports");
        Files.createDirectories(exportDir);
        Files.createDirectories(SqInboxPaths.passed(repo));
        Files.createDirectories(SqInboxPaths.failed(repo));
        Files.createDirectories(SqInboxPaths.dlq(repo));
        copyFixture(exportDir, "strategy-1.6.221B.xml");

        SqNightlyOptions options = new SqNightlyOptions(
            repo, null, false, true, null,
            exportDir, false, true, false,
            new SqInboxOptions(repo, null, 100_000.0, 200, List.of())
        );

        SqNightlyResult result = new SqNightlyPipeline().run(options);

        assertEquals(1, result.filesExported());
        assertEquals(1, result.inbox().processed());
        assertEquals(1, result.inbox().passed());
        assertEquals(0, result.pipelineExitCode());
        assertFalse(Files.exists(SqInboxPaths.pending(repo).resolve("strategy-1.6.221B.xml")));
    }

    @Test
    void printSummary_includesJobAndInboxCounts() {
        SqNightlyResult result = new SqNightlyResult(
            Map.of("update-data", 0, "list-databanks", 1),
            2,
            new SqInboxBatchResult(3, 2, 0, 1)
        );

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        SqNightlyPipeline.printSummary(result, new PrintStream(buffer));
        String text = buffer.toString();

        assertTrue(text.contains("update-data: exit 0"));
        assertTrue(text.contains("list-databanks: exit 1"));
        assertTrue(text.contains("Export: 2 file(s)"));
        assertTrue(text.contains("processed=3 passed=2 failed=0 dlq=1"));
    }

    @Test
    void resolveExportDir_prefersExplicitOption(@TempDir Path repo) {
        Path explicit = repo.resolve("explicit");
        SqNightlyOptions options = new SqNightlyOptions(
            repo, null, false, true, null,
            explicit, false, true, true,
            new SqInboxOptions(repo, null, 100_000.0, 100, List.of())
        );
        assertEquals(explicit, SqNightlyPipeline.resolveExportDir(options));
    }

    private static void installRegistry(Path repo) throws Exception {
        Path registry = SqJobPaths.registryFile(repo);
        Files.createDirectories(registry.getParent());
        Files.writeString(registry, """
            {
              "scripts": [
                {"id": "update-data", "description": "Update data", "args": ["-data", "action=update"]},
                {"id": "list-databanks", "description": "List databanks", "args": ["-databank", "action=list", "project=Builder"]}
              ]
            }
            """);
    }

    private static void copyFixture(Path dir, String name) throws Exception {
        try (var in = SqInboxProcessorTest.class.getResourceAsStream("/sq/" + name)) {
            assertNotNull(in, "fixture missing: " + name);
            Files.copy(in, dir.resolve(name));
        }
    }
}
