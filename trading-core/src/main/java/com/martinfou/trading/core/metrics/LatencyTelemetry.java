package com.martinfou.trading.core.metrics;

/**
 * Global singleton provider for latency telemetry buffer.
 */
public class LatencyTelemetry {
    private static final CircularLatencyBuffer OANDA_LATENCY_BUFFER = new CircularLatencyBuffer(10);

    public static CircularLatencyBuffer getOandaLatencyBuffer() {
        return OANDA_LATENCY_BUFFER;
    }
}
