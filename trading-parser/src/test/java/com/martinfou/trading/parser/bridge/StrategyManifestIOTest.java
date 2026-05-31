package com.martinfou.trading.parser.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class StrategyManifestIOTest {

    private static final String VALID_HASH =
        "0000000000000000000000000000000000000000000000000000000000000001";

    @Test
    void writeAndRead_roundTripsAllFields(@TempDir Path tempDir) throws Exception {
        StrategyManifest original = new StrategyManifest(
            "strategy-1.6.221B",
            "EUR_USD",
            "H1",
            "3.9.132",
            VALID_HASH,
            Instant.parse("2026-05-31T12:00:00Z")
        );
        Path manifestPath = tempDir.resolve("sample.manifest.json");

        StrategyManifestIO.write(original, manifestPath);
        StrategyManifest loaded = StrategyManifestIO.read(manifestPath);

        assertEquals(original, loaded);
    }

    @Test
    void contentSha256_rejectsInvalidFormat() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new StrategyManifest(
                "id",
                "EUR_USD",
                "H1",
                "1.0",
                "fixedhash",
                Instant.parse("2020-01-01T00:00:00Z")
            )
        );
    }

    @Test
    void fromXml_fixture_populatesProbeMetadata(@TempDir Path tempDir) throws Exception {
        Path xml = copyFixture(tempDir, "strategy-1.6.221B.xml");

        StrategyManifest manifest = StrategyManifestFactory.fromXml(xml);

        assertFalse(manifest.id().isBlank());
        assertEquals("EUR_USD", manifest.symbol());
        assertFalse(manifest.sqBuild().isBlank());
        assertEquals(64, manifest.contentSha256().length());
        assertTrue(manifest.contentSha256().matches("[a-f0-9]{64}"));
        assertNotNull(manifest.exportedAt());
    }

    @Test
    void ensureSidecar_generatesWhenMissing(@TempDir Path tempDir) throws Exception {
        Path xml = copyFixture(tempDir, "strategy-1.6.221B.xml");
        Path manifestPath = SqInboxPaths.manifestPathFor(xml);

        assertFalse(Files.exists(manifestPath));
        StrategyManifest manifest = StrategyManifestIO.ensureSidecar(xml);

        assertTrue(Files.exists(manifestPath));
        assertEquals(manifest, StrategyManifestIO.read(manifestPath));
        assertEquals(manifest.contentSha256(), StrategyManifestFactory.fromXml(xml).contentSha256());
    }

    @Test
    void ensureSidecar_reusesWhenHashMatches(@TempDir Path tempDir) throws Exception {
        Path xml = copyFixture(tempDir, "strategy-1.6.221B.xml");
        String hash = StrategyManifestFactory.fromXml(xml).contentSha256();
        StrategyManifest custom = new StrategyManifest(
            "custom-id",
            "GBP_JPY",
            "M15",
            "1.0",
            hash,
            Instant.parse("2020-01-01T00:00:00Z")
        );
        StrategyManifestIO.write(custom, SqInboxPaths.manifestPathFor(xml));

        StrategyManifest loaded = StrategyManifestIO.ensureSidecar(xml);

        assertEquals(custom, loaded);
    }

    @Test
    void ensureSidecar_regeneratesWhenXmlHashMismatch(@TempDir Path tempDir) throws Exception {
        Path xml = copyFixture(tempDir, "strategy-1.6.221B.xml");
        StrategyManifest stale = new StrategyManifest(
            "stale-id",
            "GBP_JPY",
            "M15",
            "1.0",
            VALID_HASH,
            Instant.parse("2020-01-01T00:00:00Z")
        );
        StrategyManifestIO.write(stale, SqInboxPaths.manifestPathFor(xml));

        Files.writeString(xml, Files.readString(xml) + "\n");

        StrategyManifest refreshed = StrategyManifestIO.ensureSidecar(xml);

        assertNotEquals(stale.contentSha256(), refreshed.contentSha256());
        assertEquals(StrategyManifestFactory.fromXml(xml).contentSha256(), refreshed.contentSha256());
        assertEquals(StrategyManifestFactory.fromXml(xml).id(), refreshed.id());
    }

    @Test
    void ensureSidecar_regeneratesWhenCorruptJson(@TempDir Path tempDir) throws Exception {
        Path xml = copyFixture(tempDir, "strategy-1.6.221B.xml");
        Path manifestPath = SqInboxPaths.manifestPathFor(xml);
        Files.writeString(manifestPath, "{ not valid json");

        StrategyManifest manifest = StrategyManifestIO.ensureSidecar(xml);

        assertEquals(StrategyManifestFactory.fromXml(xml), manifest);
        assertEquals(manifest, StrategyManifestIO.read(manifestPath));
    }

    @Test
    void manifestPathFor_relativeXml_doesNotThrow() {
        Path manifest = SqInboxPaths.manifestPathFor(Path.of("drop.xml"));
        assertEquals("drop.manifest.json", manifest.getFileName().toString());
    }

    @Test
    void inboxPaths_resolveUnderRepoRoot() {
        Path repo = Path.of("/tmp/trading-bridge");
        assertEquals(repo.resolve("data/sq-inbox/pending"), SqInboxPaths.pending(repo));
        assertEquals(repo.resolve("data/sq-inbox/passed"), SqInboxPaths.passed(repo));
        assertEquals(repo.resolve("data/sq-inbox/failed"), SqInboxPaths.failed(repo));
        assertEquals(repo.resolve("data/sq-inbox/dlq"), SqInboxPaths.dlq(repo));
    }

    private static Path copyFixture(Path tempDir, String resourceName) throws Exception {
        InputStream in = StrategyManifestIOTest.class.getResourceAsStream("/sq/" + resourceName);
        assertNotNull(in, "fixture missing: " + resourceName);
        Path target = tempDir.resolve(resourceName);
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }
}
