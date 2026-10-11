package com.martinfou.trading.strategies.newsweekly;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The week of October 12-16, 2026 news setups (playbook Setups 1 to 4): registration, entry mode,
 * stop/target geometry and the temporal exits.
 */
class NewsWeek12OctTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    private static Bar nyBar(String symbol, int y, int mo, int d, int h, int mi,
                             double open, double high, double low, double close) {
        return new Bar(symbol, ZonedDateTime.of(y, mo, d, h, mi, 0, 0, NY).toInstant(),
            open, high, low, close, 1_000);
    }

    private static Bar utcBar(String symbol, Instant ts,
                              double open, double high, double low, double close) {
        return new Bar(symbol, ts, open, high, low, close, 1_000);
    }

    private static Order entry(List<Order> orders) {
        return orders.stream().filter(o -> !o.isCloseOnly())
            .findFirst().orElseThrow(() -> new AssertionError("no entry order in " + orders));
    }

    private static Order stop(List<Order> orders) {
        return orders.stream().filter(o -> o.isCloseOnly() && o.type() == Order.Type.STOP)
            .findFirst().orElseThrow(() -> new AssertionError("no protective stop in " + orders));
    }

    // ===== registration =====

    @Test
    void catalogExposesThisWeeksFourSetups() {
        for (String key : new String[]{
            "NewsWeek12Oct_Cpi_EUR_USD",
            "NewsWeek12Oct_PpiRetail_EUR_USD",
            "NewsWeek12Oct_ChinaCpi_AUD_JPY",
            "NewsWeek12Oct_FridayFade_GBP_CHF"}) {
            String symbol = NewsWeeklyStrategyCatalog.defaultSymbol(key);
            Strategy strategy = NewsWeeklyStrategyCatalog.create(key, symbol);
            assertNotNull(strategy, key);
            assertTrue(strategy.getPendingOrders().isEmpty(),
                key + " must not trade before its event");
        }
        assertEquals("AUD_JPY",
            NewsWeeklyStrategyCatalog.defaultSymbol("NewsWeek12Oct_ChinaCpi_AUD_JPY"));
        assertEquals("GBP_CHF",
            NewsWeeklyStrategyCatalog.defaultSymbol("NewsWeek12Oct_FridayFade_GBP_CHF"));
    }

    // ===== Setup 1 — US CPI, bidirectional =====

    @Test
    void cpiWaitsForTheReleaseThenFollowsTheFirstBar() {
        var strategy = new NewsWeek12Oct_CpiPpi.CpiEurUsd();

        strategy.onBar(nyBar("EUR_USD", 2026, 10, 14, 8, 0, 1.1000, 1.1010, 1.0990, 1.1005));
        assertTrue(strategy.getPendingOrders().isEmpty(), "nothing before the 08:30 release");

        strategy.onBar(nyBar("EUR_USD", 2026, 10, 14, 9, 0, 1.1000, 1.1035, 1.0995, 1.1030));
        List<Order> orders = strategy.getPendingOrders();
        assertEquals(Order.Side.BUY, entry(orders).side(),
            "a bullish first bar is bought, not sold");
        assertEquals(1_100.0, entry(orders).quantity(),
            0.5, "0.5% of $1,000 over a 45-pip stop = 1,100 units");
        assertEquals(1.1030 - 0.0045, stop(orders).price(), 1e-9,
            "SL 45 pips against the entry for a long");

        // The target is re-emitted from the second bar on (the entry bar only carries the stop).
        strategy.onBar(nyBar("EUR_USD", 2026, 10, 14, 10, 0, 1.1030, 1.1050, 1.1035, 1.1045));
        Order limit = strategy.getPendingOrders().stream()
            .filter(o -> o.isCloseOnly() && o.type() == Order.Type.LIMIT)
            .findFirst().orElseThrow(
                () -> new AssertionError("TP 68 (1.5 R) must ride as a close-only limit"));
        assertEquals(1.1030 + 0.0068, limit.price(), 1e-9, "TP 68 pips above the entry");
    }

    @Test
    void cpiTakesTheShortSideWhenTheFirstBarIsBearish() {
        var strategy = new NewsWeek12Oct_CpiPpi.CpiEurUsd();
        strategy.onBar(nyBar("EUR_USD", 2026, 10, 14, 9, 0, 1.1030, 1.1035, 1.0990, 1.0995));
        List<Order> orders = strategy.getPendingOrders();
        assertEquals(Order.Side.SELL, entry(orders).side());
        assertEquals(1.0995 + 0.0045, stop(orders).price(), 1e-9);
    }

    // ===== Setup 2 — PPI + retail sales, smaller risk =====

    @Test
    void ppiRetailSizesSmallerThanTheCpi() {
        var strategy = new NewsWeek12Oct_CpiPpi.PpiRetailSalesEurUsd();
        strategy.onBar(nyBar("EUR_USD", 2026, 10, 15, 9, 0, 1.1000, 1.1035, 1.0995, 1.1030));
        assertEquals(800.0, entry(strategy.getPendingOrders()).quantity(), 0.5,
            "0.3% of $1,000 over a 40-pip stop = 800 units, deliberately below the CPI leg");
    }

    // ===== Setup 3 — Chinese CPI on the AUD, JPY cross pip =====

    @Test
    void chinaCpiTradesTheJpyCrossAndFlattensWednesdayAt1600Et() {
        var strategy = new NewsWeek12Oct_ChinaCpi.ChinaCpiAudJpy();

        strategy.onBar(nyBar("AUD_JPY", 2026, 10, 13, 20, 0, 95.00, 95.20, 94.90, 95.10));
        assertTrue(strategy.getPendingOrders().isEmpty(), "nothing before the 21:30 release");

        strategy.onBar(nyBar("AUD_JPY", 2026, 10, 13, 22, 0, 95.00, 95.10, 94.60, 94.70));
        List<Order> orders = strategy.getPendingOrders();
        assertEquals(Order.Side.SELL, entry(orders).side());
        assertEquals(94.70 + 0.50, stop(orders).price(), 1e-9,
            "SL 50 pips on a JPY cross is 0.50, not 0.005: the pip is 0.01");

        strategy.onBar(nyBar("AUD_JPY", 2026, 10, 14, 16, 0, 94.70, 94.80, 94.50, 94.60));
        List<Order> flat = strategy.getPendingOrders();
        assertTrue(flat.stream().allMatch(Order::isCloseOnly),
            "Wednesday 16:00 ET is a mandatory flat, never a new entry");
        assertEquals(Order.Side.BUY, flat.get(0).side(), "closing a short buys it back");
    }

    // ===== Setup 4 — Friday fade, fixed side, weekend hold =====

    @Test
    void fridayFadeSellsAtTheCloseGridAndExitsAtTheMondayGrid() {
        var strategy = new NewsWeek12Oct_FridayFade.FridayFadeGbpChf();

        strategy.onBar(nyBar("GBP_CHF", 2026, 10, 16, 15, 0, 1.0750, 1.0760, 1.0740, 1.0755));
        assertTrue(strategy.getPendingOrders().isEmpty(), "nothing before the 16:00 ET grid");

        strategy.onBar(nyBar("GBP_CHF", 2026, 10, 16, 16, 0, 1.0755, 1.0765, 1.0745, 1.0750));
        List<Order> orders = strategy.getPendingOrders();
        assertEquals(Order.Side.SELL, entry(orders).side(), "the fade is a fixed short");
        assertEquals(1.0755 + 0.0150, stop(orders).price(), 1e-9,
            "SL 150 pips from the OPEN it enters on is an emergency backstop, not the exit");
        assertTrue(orders.stream().noneMatch(o -> o.isCloseOnly() && o.type() == Order.Type.LIMIT),
            "the fade has no target: its exit is temporal");

        strategy.onBar(utcBar("GBP_CHF", Instant.parse("2026-10-18T22:00:00Z"),
            1.0750, 1.0760, 1.0730, 1.0740));
        List<Order> flat = strategy.getPendingOrders();
        assertTrue(flat.stream().allMatch(Order::isCloseOnly),
            "the weekend hold is closed at the Monday grid");
        assertEquals(Order.Side.BUY, flat.get(0).side());
    }

    // ===== known blocker for the live path (story: wiring the week into the runner) =====

    @Test
    void entryOrderCarriesNoStopLoss_whichTheLiveEntryGuardRefuses() {
        var strategy = new NewsWeek12Oct_CpiPpi.CpiEurUsd();
        strategy.onBar(nyBar("EUR_USD", 2026, 10, 14, 9, 0, 1.1000, 1.1035, 1.0995, 1.1030));
        Order entry = entry(strategy.getPendingOrders());
        assertFalse(entry.hasProtectiveStop(),
            "CHARACTERIZATION: the entry order itself carries no stop (the stop is a separate "
                + "close-only order), and LiveStrategyRunner.rejectEntryWithoutStop() refuses any "
                + "entry without one. This test FAILS the day the entry carries its stop, which is "
                + "what the live wiring needs.");
    }
}
