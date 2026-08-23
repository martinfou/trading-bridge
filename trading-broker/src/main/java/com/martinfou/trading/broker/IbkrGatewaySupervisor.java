package com.martinfou.trading.broker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 24/5 Headless IB Gateway connection supervisor & heartbeat watchdog (Story 46.1).
 *
 * <p>Handles mandatory daily 23:45 UTC gateway reboots, network glitches, socket drops,
 * and automatic exponential backoff reconnection with state synchronization.</p>
 */
public final class IbkrGatewaySupervisor implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IbkrGatewaySupervisor.class);

    public static final long DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 30;
    public static final long INITIAL_RECONNECT_DELAY_SECONDS = 5;
    public static final long MAX_RECONNECT_DELAY_SECONDS = 60;

    private final ScheduledExecutorService scheduler;
    private final Runnable connectAction;
    private final Runnable disconnectAction;
    private final Runnable reconcileAction;
    private final Callable<Boolean> connectionCheck;
    private final List<Consumer<String>> stateListeners = new CopyOnWriteArrayList<>();

    private volatile boolean running;
    private volatile boolean connected;
    private volatile Instant lastHeartbeatAck = Instant.now();
    private long currentBackoffDelay = INITIAL_RECONNECT_DELAY_SECONDS;
    private ScheduledFuture<?> heartbeatTask;

    public IbkrGatewaySupervisor(
        Runnable connectAction,
        Runnable disconnectAction,
        Runnable reconcileAction,
        Callable<Boolean> connectionCheck
    ) {
        this(connectAction, disconnectAction, reconcileAction, connectionCheck,
             Executors.newSingleThreadScheduledExecutor(r -> {
                 Thread t = new Thread(r, "ibkr-supervisor-thread");
                 t.setDaemon(true);
                 return t;
             }));
    }

    public IbkrGatewaySupervisor(
        Runnable connectAction,
        Runnable disconnectAction,
        Runnable reconcileAction,
        Callable<Boolean> connectionCheck,
        ScheduledExecutorService scheduler
    ) {
        this.connectAction = Objects.requireNonNull(connectAction, "connectAction is required");
        this.disconnectAction = Objects.requireNonNull(disconnectAction, "disconnectAction is required");
        this.reconcileAction = Objects.requireNonNull(reconcileAction, "reconcileAction is required");
        this.connectionCheck = Objects.requireNonNull(connectionCheck, "connectionCheck is required");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler is required");
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        log.info("Starting IBKR Gateway Supervisor (Heartbeat: {}s)...", DEFAULT_HEARTBEAT_INTERVAL_SECONDS);
        attemptConnection();
        heartbeatTask = scheduler.scheduleAtFixedRate(
            this::checkLiveness,
            DEFAULT_HEARTBEAT_INTERVAL_SECONDS,
            DEFAULT_HEARTBEAT_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        );
    }

    public synchronized void stop() {
        if (!running) return;
        running = false;
        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
        }
        try {
            disconnectAction.run();
        } catch (Exception e) {
            log.warn("Error disconnecting IBKR client on shutdown: {}", e.getMessage());
        }
        connected = false;
        emitState("STOPPED");
        log.info("IBKR Gateway Supervisor stopped.");
    }

    public void onHeartbeatAck() {
        this.lastHeartbeatAck = Instant.now();
    }

    public boolean isConnected() {
        return connected;
    }

    public Instant lastHeartbeatAck() {
        return lastHeartbeatAck;
    }

    public void addStateListener(Consumer<String> listener) {
        if (listener != null) stateListeners.add(listener);
    }

    private void checkLiveness() {
        if (!running) return;
        try {
            boolean isLive = connectionCheck.call();
            if (isLive) {
                connected = true;
                currentBackoffDelay = INITIAL_RECONNECT_DELAY_SECONDS;
                onHeartbeatAck();
            } else {
                log.warn("IBKR socket disconnected or unresponsive. Triggering reconnect sequence...");
                handleDisconnect();
            }
        } catch (Exception e) {
            log.error("Error during liveness check: {}", e.getMessage());
            handleDisconnect();
        }
    }

    private synchronized void handleDisconnect() {
        connected = false;
        emitState("DISCONNECTED");
        try {
            disconnectAction.run();
        } catch (Exception ignored) {}

        scheduleReconnect();
    }

    private synchronized void scheduleReconnect() {
        if (!running) return;
        log.info("Scheduling IBKR reconnect in {} seconds...", currentBackoffDelay);
        scheduler.schedule(this::attemptConnection, currentBackoffDelay, TimeUnit.SECONDS);
        currentBackoffDelay = Math.min(currentBackoffDelay * 2, MAX_RECONNECT_DELAY_SECONDS);
    }

    private synchronized void attemptConnection() {
        if (!running) return;
        try {
            log.info("Attempting connection to IB Gateway...");
            connectAction.run();
            boolean isLive = connectionCheck.call();
            if (isLive) {
                connected = true;
                currentBackoffDelay = INITIAL_RECONNECT_DELAY_SECONDS;
                lastHeartbeatAck = Instant.now();
                emitState("CONNECTED");
                log.info("Successfully connected to IB Gateway! Running post-reconnect state reconciliation...");
                reconcileAction.run();
            } else {
                scheduleReconnect();
            }
        } catch (Exception e) {
            log.warn("Connection attempt failed: {}. Retrying with backoff...", e.getMessage());
            scheduleReconnect();
        }
    }

    private void emitState(String state) {
        for (Consumer<String> l : stateListeners) {
            try {
                l.accept(state);
            } catch (Exception ignored) {}
        }
    }

    @Override
    public void close() {
        stop();
        scheduler.shutdownNow();
    }
}
