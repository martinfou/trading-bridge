package com.martinfou.trading.monitor;

import java.util.StringJoiner;

/**
 * Builds the JSON response for the /health endpoint.
 * <p>
 * Separated from HealthServer for testability — produces a string
 * that can be validated without starting a real HTTP server.
 */
public class HealthResponseFormatter {

    /**
     * Builds a compact JSON health response.
     */
    public String format(
            String machineName,
            String uptime,
            String gitCommit,
            String version,
            String activeStrategies,
            String lastTradeTimestamp,
            int errors24h,
            String oandaApiStatus,
            double cpuPercent,
            double memoryPercent,
            double diskPercent,
            String deploymentId
    ) {
        var sb = new StringJoiner(",\n", "{\n", "\n}");

        sb.add(quote("machine") + ": " + quote(machineName));
        sb.add(quote("uptime") + ": " + quote(uptime));
        sb.add(quote("version") + ": " + quote(version));
        sb.add(quote("git_commit") + ": " + quote(gitCommit));
        sb.add(quote("deployment_id") + ": " + quote(deploymentId));
        sb.add(quote("active_strategies") + ": " + activeStrategies);
        sb.add(quote("last_trade") + ": " + quote(lastTradeTimestamp));
        sb.add(quote("errors_24h") + ": " + errors24h);
        sb.add(quote("oanda_api_status") + ": " + quote(oandaApiStatus));

        // Resources block
        sb.add("\"resources\": {\n" +
               "    \"cpu\": " + fmtDouble(cpuPercent) + ",\n" +
               "    \"memory\": " + fmtDouble(memoryPercent) + ",\n" +
               "    \"disk\": " + fmtDouble(diskPercent) + "\n" +
               "  }");

        return sb.toString();
    }

    private static String quote(String s) {
        return "\"" + escapeJson(s) + "\"";
    }

    private static String escapeJson(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String fmtDouble(double value) {
        if (value < 0) return "\"unavailable\"";
        return String.valueOf(value);
    }
}
