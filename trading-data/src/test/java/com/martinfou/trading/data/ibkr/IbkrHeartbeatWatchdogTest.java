package com.martinfou.trading.data.ibkr;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class IbkrHeartbeatWatchdogTest {

    @Test
    void testWatchdogTransitions() {
        IbkrHeartbeatWatchdog watchdog = new IbkrHeartbeatWatchdog("ibkr", Duration.ofMillis(50), Duration.ofMillis(100));
        AtomicReference<IbkrHeartbeatWatchdog.HeartbeatEvent> lastEvent = new AtomicReference<>();
        watchdog.addListener(lastEvent::set);

        assertEquals(IbkrHeartbeatWatchdog.ConnectionState.DISCONNECTED, watchdog.currentState());

        watchdog.recordHeartbeat();
        assertEquals(IbkrHeartbeatWatchdog.ConnectionState.CONNECTED, watchdog.currentState());
        assertTrue(watchdog.isAlive());
        assertNotNull(lastEvent.get());
        assertEquals(IbkrHeartbeatWatchdog.ConnectionState.CONNECTED, lastEvent.get().state());

        watchdog.close();
    }
}
