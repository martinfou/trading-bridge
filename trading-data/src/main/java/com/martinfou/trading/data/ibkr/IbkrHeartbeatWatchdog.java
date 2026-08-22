package com.martinfou.trading.data.ibkr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Monitors IBKR TWS / IB Gateway connection liveness with 10s periodic ping probes,
 * 15s disconnection detection, and reconnection lifecycle transitions.
 */
public final class IbkrHeartbeatWatchdog implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IbkrHeartbeatWatchdog.class);

    public enum ConnectionState {
        CONNECTED,
        CONNECTING,
        DISCONNECTED
    }

    public record HeartbeatEvent(
        String brokerId,
        ConnectionState state,
        Instant lastHeartbeatAt,
        String message
    ) {}

    private final String brokerId;
    private final Duration probeInterval;
    private final Duration timeoutThreshold;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ibkr-heartbeat-watchdog");
        t.setDaemon(true);
        return t;
    });

    private final AtomicReference<ConnectionState> currentState = new AtomicReference<>(ConnectionState.DISCONNECTED);
    private final AtomicReference<Instant> lastHeartbeatAt = new AtomicReference<>(Instant.EPOCH);
    private final List<Consumer<HeartbeatEvent>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public IbkrHeartbeatWatchdog() {
        this("ibkr", Duration.ofSeconds(10), Duration.ofSeconds(15));
    }

    public IbkrHeartbeatWatchdog(String brokerId, Duration probeInterval, Duration timeoutThreshold) {
        this.brokerId = Objects.requireNonNull(brokerId, "brokerId");
        this.probeInterval = Objects.requireNonNull(probeInterval, "probeInterval");
        this.timeoutThreshold = Objects.requireNonNull(timeoutThreshold, "timeoutThreshold");
    }

    public void start() {
        scheduler.scheduleAtFixedRate(this::checkLiveness, probeInterval.toMillis(), probeInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void recordHeartbeat() {
        lastHeartbeatAt.set(Instant.now());
        transitionTo(ConnectionState.CONNECTED, "Heartbeat acknowledged");
    }

    public void addListener(Consumer<HeartbeatEvent> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public ConnectionState currentState() {
        return currentState.get();
    }

    public Instant lastHeartbeatAt() {
        return lastHeartbeatAt.get();
    }

    public boolean isAlive() {
        return currentState.get() == ConnectionState.CONNECTED &&
            Duration.between(lastHeartbeatAt.get(), Instant.now()).compareTo(timeoutThreshold) <= 0;
    }

    private void checkLiveness() {
        if (closed.get()) return;

        Instant last = lastHeartbeatAt.get();
        if (last != Instant.EPOCH && Duration.between(last, Instant.now()).compareTo(timeoutThreshold) > 0) {
            if (currentState.get() == ConnectionState.CONNECTED) {
                transitionTo(ConnectionState.DISCONNECTED, "Heartbeat timeout (no response within " + timeoutThreshold.toSeconds() + "s)");
            }
        }
    }

    private void transitionTo(ConnectionState newState, String message) {
        ConnectionState oldState = currentState.getAndSet(newState);
        if (oldState != newState) {
            log.info("IBKR Watchdog state change: {} -> {} ({})", oldState, newState, message);
            HeartbeatEvent event = new HeartbeatEvent(brokerId, newState, lastHeartbeatAt.get(), message);
            for (Consumer<HeartbeatEvent> listener : listeners) {
                try {
                    listener.accept(event);
                } catch (Exception ignored) {}
            }
        }
    }

    @Override
    public void close() {
        closed.set(true);
        scheduler.shutdownNow();
    }
}
