package com.martinfou.trading.data.ibkr;

import com.martinfou.trading.core.Bar;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("IbkrBarSource Tests (Story 46.9)")
class IbkrBarSourceTest {

    @Test
    @DisplayName("Drops the still-forming bar and converts America/New_York timestamps to UTC Instant")
    void latestBars_dropsStillFormingBarAndConvertsTimezone() {
        // Suppose it is 2026-08-19 10:30:05 EDT (14:30:05 UTC).
        // The 09:30 EDT (13:30 UTC) and 10:00 EDT (14:00 UTC) M30 bars are CLOSED.
        // The 10:30 EDT (14:30 UTC) M30 bar is STILL FORMING (ends at 15:00 UTC) and MUST be dropped.
        Instant nowUtc = Instant.parse("2026-08-19T14:30:05Z");
        Clock fixedClock = Clock.fixed(nowUtc, ZoneOffset.UTC);

        List<IbkrBarSource.RawIbkrBar> fakeRaw = List.of(
            new IbkrBarSource.RawIbkrBar("20260819 09:30:00 US/Eastern", 2500.0, 2505.0, 2498.0, 2502.0, 120L),
            new IbkrBarSource.RawIbkrBar("20260819 10:00:00 US/Eastern", 2502.0, 2510.0, 2501.0, 2508.0, 180L),
            new IbkrBarSource.RawIbkrBar("20260819 10:30:00 US/Eastern", 2508.0, 2509.0, 2507.0, 2508.5, 15L) // forming!
        );

        IbkrConnectionConfig cfg = new IbkrConnectionConfig("127.0.0.1", 4002, 22, "DU12345");
        try (IbkrBarSource source = new IbkrBarSource(cfg, fixedClock, (sym, tf, dur) -> fakeRaw)) {
            List<Bar> bars = source.latestBars("MGC", "30 mins", 60);

            assertEquals(2, bars.size(), "Still-forming 10:30 EDT bar must be dropped");
            assertEquals(Instant.parse("2026-08-19T13:30:00Z"), bars.get(0).timestamp());
            assertEquals(Instant.parse("2026-08-19T14:00:00Z"), bars.get(1).timestamp());
            assertEquals(2508.0, bars.get(1).close(), 1e-9);
            assertTrue(bars.get(0).timestamp().isBefore(bars.get(1).timestamp()));
        }
    }

    @Test
    @DisplayName("Trims returned bars to requested count in monotonically increasing order")
    void latestBars_trimsToCountAndSortsMonotonically() {
        Instant nowUtc = Instant.parse("2026-08-19T15:00:05Z");
        Clock fixedClock = Clock.fixed(nowUtc, ZoneOffset.UTC);

        List<IbkrBarSource.RawIbkrBar> fakeRaw = List.of(
            new IbkrBarSource.RawIbkrBar("20260819 10:00:00 US/Eastern", 2502.0, 2510.0, 2501.0, 2508.0, 180L),
            new IbkrBarSource.RawIbkrBar("20260819 09:00:00 US/Eastern", 2495.0, 2501.0, 2490.0, 2500.0, 90L),
            new IbkrBarSource.RawIbkrBar("20260819 09:30:00 US/Eastern", 2500.0, 2505.0, 2498.0, 2502.0, 120L)
        );

        IbkrConnectionConfig cfg = new IbkrConnectionConfig("127.0.0.1", 4002, 22, "DU12345");
        try (IbkrBarSource source = new IbkrBarSource(cfg, fixedClock, (sym, tf, dur) -> fakeRaw)) {
            List<Bar> bars = source.latestBars("MGC", "M30", 2);

            assertEquals(2, bars.size());
            assertEquals(Instant.parse("2026-08-19T13:30:00Z"), bars.get(0).timestamp());
            assertEquals(Instant.parse("2026-08-19T14:00:00Z"), bars.get(1).timestamp());
        }
    }

    @Test
    @DisplayName("Integration test: fetches >= 10 closed MGC M30 bars from live gateway when reachable")
    void latestBars_liveGatewayIntegration() {
        String host = System.getenv().getOrDefault("IBKR_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("IBKR_PORT", "4002"));
        String account = System.getenv().getOrDefault("IBKR_ACCOUNT_ID", "DUR967322");
        int clientId = Integer.parseInt(System.getenv().getOrDefault("IBKR_BAR_CLIENT_ID", "29"));

        Assumptions.assumeTrue(isGatewayReachable(host, port),
            "IB Gateway not reachable at " + host + ":" + port + " — skipping live bar fetch");

        IbkrConnectionConfig cfg = new IbkrConnectionConfig(host, port, clientId, account);
        try (IbkrBarSource source = new IbkrBarSource(cfg)) {
            List<Bar> bars = source.latestBars("MGC", "30 mins", 60);
            Assumptions.assumeFalse(bars.isEmpty(), "Gateway connected but returned no historical bars (market data subscription / pacing)");

            assertTrue(bars.size() >= 10, "Expected at least 10 MGC M30 bars, got " + bars.size());
            Instant now = Instant.now();
            for (int i = 0; i < bars.size(); i++) {
                Bar b = bars.get(i);
                assertTrue(b.timestamp().isBefore(now), "Bar timestamp must be in the past: " + b.timestamp());
                if (i > 0) {
                    assertTrue(bars.get(i - 1).timestamp().isBefore(b.timestamp()),
                        "Bars must be strictly monotonically increasing");
                }
            }
        }
    }

    private static boolean isGatewayReachable(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
