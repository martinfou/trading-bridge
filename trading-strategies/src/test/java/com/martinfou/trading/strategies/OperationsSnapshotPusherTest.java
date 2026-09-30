package com.martinfou.trading.strategies;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Behaviour of the snapshot pusher: 2xx success, non-2xx rejection, timeout, and
 * the "disabled when unconfigured" path. Every path must return, never throw, so a
 * slow or failing hub can never stop the monitor thread (and never a trading tick).
 */
class OperationsSnapshotPusherTest {

    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger delayMs = new AtomicInteger(0);

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private OperationsSnapshotPusher pusher(HttpClient client, Duration timeout) {
        return new OperationsSnapshotPusher(
            "http://localhost:" + server.getAddress().getPort() + OperationsSnapshotPusher.SNAPSHOT_PATH,
            "test-token", client, timeout);
    }

    private void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext(OperationsSnapshotPusher.SNAPSHOT_PATH, exchange -> {
            int delay = delayMs.get();
            if (delay > 0) {
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] body = "{}".getBytes();
            exchange.sendResponseHeaders(status.get(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @Test
    void accepts2xxResponse() throws Exception {
        status.set(201);
        startServer();
        assertTrue(pusher(HttpClient.newHttpClient(), Duration.ofSeconds(5)).push("{\"snapshot_id\":\"a\"}"));
    }

    @Test
    void rejectsServerError() throws Exception {
        status.set(500);
        startServer();
        assertFalse(pusher(HttpClient.newHttpClient(), Duration.ofSeconds(5)).push("{\"snapshot_id\":\"a\"}"));
    }

    @Test
    void timesOutWithoutThrowing() throws Exception {
        status.set(200);
        delayMs.set(2000);
        startServer();
        assertFalse(pusher(HttpClient.newHttpClient(), Duration.ofMillis(300)).push("{\"snapshot_id\":\"a\"}"));
    }

    @Test
    void disabledWhenUrlOrTokenMissing() {
        assertNull(OperationsSnapshotPusher.fromEnv(Map.of()));
        assertNull(OperationsSnapshotPusher.fromEnv(Map.of(
            OperationsSnapshotPusher.ENV_URL, "https://hub.example.com")));
        assertNull(OperationsSnapshotPusher.fromEnv(Map.of(
            OperationsSnapshotPusher.ENV_TOKEN, "tok")));
        assertNull(OperationsSnapshotPusher.fromEnv(Map.of(
            OperationsSnapshotPusher.ENV_URL, "   ",
            OperationsSnapshotPusher.ENV_TOKEN, "tok")));
    }

    @Test
    void enabledWhenUrlAndTokenBothSet() {
        assertNotNull(OperationsSnapshotPusher.fromEnv(Map.of(
            OperationsSnapshotPusher.ENV_URL, "https://hub.example.com/",
            OperationsSnapshotPusher.ENV_TOKEN, "tok")));
    }
}
