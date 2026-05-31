package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.events.RunEvent;
import com.martinfou.trading.backtest.events.RunEventType;
import com.martinfou.trading.parser.bridge.SqInboxPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqBridgeServiceTest {

    private SqBridgeService service;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
    }

    @Test
    void status_whenSqHomeNotConfigured(@TempDir Path repo) {
        InMemoryEventStore store = new InMemoryEventStore();
        Clock clock = Clock.fixed(Instant.parse("2026-05-31T12:00:00Z"), ZoneOffset.UTC);
        service = new SqBridgeService(store, repo, clock, Executors.newSingleThreadExecutor(),
            new com.martinfou.trading.parser.bridge.SqInboxProcessor(),
            new com.martinfou.trading.parser.bridge.SqCliRunner());

        Map<String, Object> status = service.status();

        assertFalse((Boolean) status.get("sqHomeConfigured"));
        assertFalse((Boolean) status.get("sqcliReachable"));
        assertEquals(0, status.get("inboxPendingCount"));
    }

    @Test
    void status_countsPendingXml(@TempDir Path repo) throws Exception {
        Files.createDirectories(SqInboxPaths.pending(repo));
        Files.writeString(SqInboxPaths.pending(repo).resolve("a.xml"), "<x/>");
        Files.writeString(SqInboxPaths.pending(repo).resolve("b.xml"), "<x/>");

        service = new SqBridgeService(new InMemoryEventStore(), repo);
        assertEquals(2, service.status().get("inboxPendingCount"));
    }

    @Test
    void status_cachesProbeWithinTtl(@TempDir Path repo) throws Exception {
        Path sqHome = repo.resolve("sq");
        Files.createDirectories(sqHome);
        Path binary = sqHome.resolve("sqcli");
        Files.writeString(binary, """
            #!/bin/sh
            exit 0
            """);
        binary.toFile().setExecutable(true);

        Instant t0 = Instant.parse("2026-05-31T12:00:00Z");
        Clock clock = Clock.fixed(t0, ZoneOffset.UTC);
        String previous = System.getProperty("sq.bridge.sq.home");
        System.setProperty("sq.bridge.sq.home", sqHome.toString());
        try {
            service = new SqBridgeService(new InMemoryEventStore(), repo, clock,
                Executors.newSingleThreadExecutor(),
                new com.martinfou.trading.parser.bridge.SqInboxProcessor(),
                new com.martinfou.trading.parser.bridge.SqCliRunner());

            Map<String, Object> first = service.status();
            assertTrue((Boolean) first.get("sqHomeConfigured"));
            assertTrue((Boolean) first.get("sqcliReachable"));

            Files.delete(binary);
            Map<String, Object> cached = service.status();
            assertTrue((Boolean) cached.get("sqcliReachable"));
            assertEquals(t0.toString(), cached.get("lastProbeAt"));
        } finally {
            if (previous == null) {
                System.clearProperty("sq.bridge.sq.home");
            } else {
                System.setProperty("sq.bridge.sq.home", previous);
            }
        }
    }

    @Test
    void processInboxAsync_emitsEvents(@TempDir Path repo) throws Exception {
        Path pending = SqInboxPaths.pending(repo);
        Files.createDirectories(pending);
        Files.createDirectories(SqInboxPaths.passed(repo));
        Files.createDirectories(SqInboxPaths.failed(repo));
        Files.createDirectories(SqInboxPaths.dlq(repo));
        Files.writeString(pending.resolve("broken.xml"), "<not-sq/>");

        InMemoryEventStore store = new InMemoryEventStore();
        service = new SqBridgeService(store, repo, Clock.systemUTC(),
            Executors.newSingleThreadExecutor(),
            new com.martinfou.trading.parser.bridge.SqInboxProcessor(),
            new com.martinfou.trading.parser.bridge.SqCliRunner());

        assertTrue(service.processInboxAsync().accepted());
        awaitInboxComplete(service);

        List<RunEvent> events = store.replayAll("sq-bridge");
        assertFalse(events.isEmpty());
        assertEquals(RunEventType.SQ_EXPORT_RECEIVED, events.getFirst().type());
        assertTrue(service.status().containsKey("lastInboxRun"));
    }

    @Test
    void processInboxAsync_rejectsWhenAlreadyRunning(@TempDir Path repo) throws Exception {
        Files.createDirectories(SqInboxPaths.pending(repo));
        service = new SqBridgeService(new InMemoryEventStore(), repo);
        assertTrue(service.processInboxAsync().accepted());
        assertFalse(service.processInboxAsync().accepted());
        awaitInboxComplete(service);
    }

    private static void awaitInboxComplete(SqBridgeService service) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (!(Boolean) service.status().get("inboxProcessing")) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }
}
