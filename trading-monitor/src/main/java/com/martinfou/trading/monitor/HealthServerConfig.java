package com.martinfou.trading.monitor;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Configuration for the health-check server.
 *
 * @param machineName Human-readable machine role (default: auto-detected hostname)
 * @param port        HTTP port (default: 9090)
 * @param version     Application version (default: "1.0.0-SNAPSHOT")
 */
public record HealthServerConfig(
        String machineName,
        int port,
        String version
) {
    private static final int DEFAULT_PORT = 9090;

    /** Auto-detect hostname, default port and version. */
    public HealthServerConfig() {
        this(detectHostname(), DEFAULT_PORT, "1.0.0-SNAPSHOT");
    }

    /** Explicit machine name, default port and version. */
    public HealthServerConfig(String machineName) {
        this(machineName, DEFAULT_PORT, "1.0.0-SNAPSHOT");
    }

    private static String detectHostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
