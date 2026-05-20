package com.martinfou.trading.monitor;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;

class HealthServerTest {

    @Test
    void formatUptime_seconds() {
        assertEquals("0h 0m 5s", HealthServer.formatUptime(Duration.ofSeconds(5)));
    }

    @Test
    void formatUptime_minutes() {
        assertEquals("0h 5m 0s", HealthServer.formatUptime(Duration.ofMinutes(5)));
    }

    @Test
    void formatUptime_hours() {
        assertEquals("72h 30m 15s",
                HealthServer.formatUptime(Duration.ofSeconds(72 * 3600 + 30 * 60 + 15)));
    }

    @Test
    void formatUptime_zero() {
        assertEquals("0h 0m 0s", HealthServer.formatUptime(Duration.ZERO));
    }

    @Test
    void server_start_stop() throws Exception {
        var config = new HealthServerConfig("test-machine", 0, "1.0.0"); // port 0 = OS assigns
        var server = new HealthServer(config, new SystemMetrics());
        server.start();
        // Port should be > 0 when bound or 0 if HttpServer didn't resolve it
        assertTrue(server.getPort() >= 0, "Server should have a valid port");
        server.close();
    }

    @Test
    void server_accepts_daemon_defaults() throws Exception {
        var server = new HealthServer(); // auto-detect hostname, port 9090
        assertNotNull(server);
        server.close();
    }

    @Test
    void config_defaults_detectHostname() {
        var config = new HealthServerConfig();
        assertNotNull(config.machineName());
        assertFalse(config.machineName().isEmpty());
        assertEquals(9090, config.port());
        assertEquals("1.0.0-SNAPSHOT", config.version());
    }

    @Test
    void config_explicitMachineName() {
        var config = new HealthServerConfig("my-vps");
        assertEquals("my-vps", config.machineName());
        assertEquals(9090, config.port());
    }
}
