package com.martinfou.trading.core.metrics;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Thread-safe, lock-free circular latency buffer of fixed capacity.
 */
public class CircularLatencyBuffer {
    private final int capacity;
    private final AtomicInteger index = new AtomicInteger(0);
    private final AtomicReferenceArray<Double> buffer;

    public CircularLatencyBuffer(int capacity) {
        this.capacity = capacity;
        this.buffer = new AtomicReferenceArray<>(capacity);
    }

    /**
     * Adds a latency measurement to the circular buffer.
     * Guaranteed to be lock-free and overflow-safe.
     */
    public void add(double latencyMs) {
        int current = index.getAndIncrement();
        int posIndex = current & Integer.MAX_VALUE; // clears sign bit to prevent integer overflow index bounds issues
        int targetIndex = posIndex % capacity;
        buffer.set(targetIndex, latencyMs);
    }

    /**
     * Returns the average latency of all populated entries in the buffer.
     */
    public double getAverage() {
        double sum = 0.0;
        int count = 0;
        for (int i = 0; i < capacity; i++) {
            Double val = buffer.get(i);
            if (val != null) {
                sum += val;
                count++;
            }
        }
        return count == 0 ? 0.0 : sum / count;
    }

    /**
     * Returns the maximum latency of all populated entries in the buffer.
     */
    public double getMax() {
        double max = 0.0;
        int count = 0;
        for (int i = 0; i < capacity; i++) {
            Double val = buffer.get(i);
            if (val != null) {
                if (count == 0 || val > max) {
                    max = val;
                }
                count++;
            }
        }
        return max;
    }
}
