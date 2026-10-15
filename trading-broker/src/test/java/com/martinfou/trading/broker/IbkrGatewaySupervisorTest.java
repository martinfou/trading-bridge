package com.martinfou.trading.broker;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("IbkrGatewaySupervisor Unit Tests (Story 46.1)")
class IbkrGatewaySupervisorTest {

    @Test
    @DisplayName("Should successfully connect, reconcile, and track heartbeat ack")
    void testConnectAndReconcile() {
        AtomicBoolean connected = new AtomicBoolean(false);
        AtomicInteger reconcileCount = new AtomicInteger(0);

        IbkrGatewaySupervisor supervisor = new IbkrGatewaySupervisor(
            () -> connected.set(true),
            () -> connected.set(false),
            reconcileCount::incrementAndGet,
            connected::get
        );

        supervisor.start();

        assertTrue(supervisor.isConnected());
        assertEquals(1, reconcileCount.get());
        assertNotNull(supervisor.lastHeartbeatAck());

        supervisor.stop();
        assertFalse(supervisor.isConnected());
        supervisor.close();
    }

    @Test
    @DisplayName("Should trigger state listeners on transition")
    void testStateListeners() {
        AtomicBoolean connected = new AtomicBoolean(false);
        AtomicInteger connectEvents = new AtomicInteger(0);

        IbkrGatewaySupervisor supervisor = new IbkrGatewaySupervisor(
            () -> connected.set(true),
            () -> connected.set(false),
            () -> {},
            connected::get
        );

        supervisor.addStateListener(state -> {
            if ("CONNECTED".equals(state)) {
                connectEvents.incrementAndGet();
            }
        });

        supervisor.start();
        assertEquals(1, connectEvents.get());
        supervisor.close();
    }
}
