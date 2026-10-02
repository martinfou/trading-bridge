package com.martinfou.trading.strategies;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.strategies.creative.VWPReversionStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Story 47.1 — an exit the strategy did not decide must still arm its cooldown.
 *
 * <p>Measured on the paper window (2026-10-02): {@code vwpreversion} was stopped out by the broker at
 * 07:57:37 UTC, flattened by the runner's reconciliation at 07:58:28 (the C1 notification,
 * {@code syncPosition(null, 0, 0, 0)}), and re-entered at 08:00:29 on the next H1 bar, same direction,
 * at a fresh 0.6 % risk budget. The cooldown existed only in the strategy's own close path, so the one
 * exit it exists for skipped it.
 *
 * <p>Each test below fails on the pre-fix code: the strategy re-enters on the very next bar that
 * satisfies its entry condition (1 test), arms nothing to observe (2, 3), or keeps the strategy's
 * already-running cooldown untouched for the wrong reason (4).
 */
class ExternalCloseCooldownTest {

    // ========================================================================
    // The shared contract on the Strategy default (AC2, AC4, no-cooldown safety)
    // ========================================================================

    /** Follows the codebase convention: {@code inTrade} + {@code cooldownBars} + a declared constant. */
    private static final class CooldownStrategy implements Strategy {
        @SuppressWarnings("unused")
        private static final int COOLDOWN_BARS = 4;
        @SuppressWarnings("unused")
        private boolean inTrade = false;
        @SuppressWarnings("unused")
        private int cooldownBars = 0;

        @Override public String name() { return "cooldown-synthetic"; }
        @Override public void onBar(Bar bar) { }
        @Override public void onTick(double bid, double ask, long volume) { }
        @Override public List<Order> getPendingOrders() { return List.of(); }
        @Override public void reset() { }

        /** Test-only read of the private state the platform manipulates by reflection. */
        int cooldown() { return cooldownBars; }
        boolean inTrade() { return inTrade; }
        void pretendSelfExitWithCooldownRunning() {
            this.inTrade = false;
            this.cooldownBars = 2; // already 2 bars into a cooldown the strategy armed itself
        }
    }

    /** A strategy whose state lives elsewhere and that declares no cooldown at all. */
    private static final class NoCooldownStrategy implements Strategy {
        @Override public String name() { return "no-cooldown"; }
        @Override public void onBar(Bar bar) { }
        @Override public void onTick(double bid, double ask, long volume) { }
        @Override public List<Order> getPendingOrders() { return List.of(); }
        @Override public void reset() { }
    }

    /** The one other counter spelling this codebase uses ({@code ATRExpansionMomentumStrategy}). */
    private static final class AlternateNameStrategy implements Strategy {
        @SuppressWarnings("unused")
        private static final int COOLDOWN_BARS = 6;
        @SuppressWarnings("unused")
        private boolean inTrade = false;
        @SuppressWarnings("unused")
        private int cooldownCounter = 0;

        @Override public String name() { return "cooldown-alt-name"; }
        @Override public void onBar(Bar bar) { }
        @Override public void onTick(double bid, double ask, long volume) { }
        @Override public List<Order> getPendingOrders() { return List.of(); }
        @Override public void reset() { }

        int cooldown() { return cooldownCounter; }
    }

    @Test
    @DisplayName("the counter is armed under either spelling the codebase uses (cooldownBars, cooldownCounter)")
    void externalCloseArmsCounterUnderEitherSpelling() {
        AlternateNameStrategy s = new AlternateNameStrategy();
        s.syncPosition(Order.Side.SELL, 1000.0, 1.1300, 1.0900);

        s.syncPosition(null, 0.0, 0.0, 0.0);

        assertEquals(6, s.cooldown(),
            "a strategy that names its counter cooldownCounter must be armed too, not silently skipped");
    }

    /** A counter declared on a BASE class, which the flat notification's field scope alone would miss. */
    private static class InheritedCounterBase {
        @SuppressWarnings("unused")
        private static final int COOLDOWN_BARS = 7;
        @SuppressWarnings("unused")
        protected int cooldownBars = 0;

        int baseCooldown() { return cooldownBars; }
    }

    private static final class InheritedCounterStrategy extends InheritedCounterBase implements Strategy {
        @SuppressWarnings("unused")
        private boolean inTrade = false;

        @Override public String name() { return "cooldown-inherited"; }
        @Override public void onBar(Bar bar) { }
        @Override public void onTick(double bid, double ask, long volume) { }
        @Override public List<Order> getPendingOrders() { return List.of(); }
        @Override public void reset() { }
    }

    @Test
    @DisplayName("a counter declared on a base class is still armed (the write walks the hierarchy)")
    void externalCloseArmsInheritedCounter() {
        InheritedCounterStrategy s = new InheritedCounterStrategy();
        s.syncPosition(Order.Side.BUY, 1000.0, 1.0900, 1.1200);

        s.syncPosition(null, 0.0, 0.0, 0.0);

        assertEquals(7, s.baseCooldown(),
            "a counter inherited from a base class must be armed, not left silently at zero");
    }

    /**
     * The in-trade flag can be declared on a BASE class: the read and the write then both walk the
     * hierarchy, so such a strategy is flattened AND armed. With a concrete-class-only pair it would stay
     * latched in-trade forever and the guard would never fire — the trap the adversarial review pass named.
     */
    private static final class InheritedFlagSubclass extends InheritedFlagBase implements Strategy {
        @SuppressWarnings("unused")
        private static final int COOLDOWN_BARS = 5;
        @SuppressWarnings("unused")
        private int cooldownBars = 0;

        @Override public String name() { return "flag-inherited-sub"; }
        @Override public void onBar(Bar bar) { }
        @Override public void onTick(double bid, double ask, long volume) { }
        @Override public List<Order> getPendingOrders() { return List.of(); }
        @Override public void reset() { }

        int cooldown() { return cooldownBars; }
    }

    private static class InheritedFlagBase {
        @SuppressWarnings("unused")
        protected boolean inTrade = false;

        boolean baseInTrade() { return inTrade; }
    }

    @Test
    @DisplayName("a strategy whose in-trade flag is inherited is flattened AND armed")
    void inheritedInTradeFlagIsClearedAndArmed() {
        InheritedFlagSubclass s = new InheritedFlagSubclass();
        s.syncPosition(Order.Side.BUY, 1000.0, 1.0900, 1.1200);
        assertTrue(s.baseInTrade(), "precondition: the inherited flag says the strategy is in a position");

        s.syncPosition(null, 0.0, 0.0, 0.0);

        assertFalse(s.baseInTrade(),
            "an inherited in-trade flag must still be cleared, or the strategy stays latched forever "
                + "and never trades again while the cooldown guard stays silent");
        assertEquals(5, s.cooldown(), "and the declared cooldown must be armed on the same transition");
    }

    @Test
    @DisplayName("a broker-side exit arms the strategy's own declared cooldown")
    void externalCloseArmsDeclaredCooldown() {
        CooldownStrategy s = new CooldownStrategy();
        s.syncPosition(Order.Side.BUY, 1000.0, 1.0900, 1.1200); // position adopted from the broker
        assertTrue(s.inTrade(), "precondition: the strategy believes it holds a position");
        assertEquals(0, s.cooldown(), "precondition: adopting a position arms nothing");

        s.syncPosition(null, 0.0, 0.0, 0.0); // the broker closed it

        assertFalse(s.inTrade(), "the strategy must learn it is flat");
        assertEquals(4, s.cooldown(),
            "the cooldown the strategy would have armed on its own exit must be armed here");
    }

    @Test
    @DisplayName("a flat notification that confirms the strategy's own exit does not touch a running cooldown")
    void confirmingFlatDoesNotResetRunningCooldown() {
        CooldownStrategy s = new CooldownStrategy();
        s.pretendSelfExitWithCooldownRunning();

        s.syncPosition(null, 0.0, 0.0, 0.0); // reconciliation confirms what the strategy already did

        assertEquals(2, s.cooldown(),
            "2 bars of an already-running cooldown must stay 2, not be reset to the full constant");
    }

    @Test
    @DisplayName("a strategy that declares no cooldown is left alone and does not throw")
    void strategyWithoutCooldownIsUnaffected() {
        NoCooldownStrategy s = new NoCooldownStrategy();
        assertDoesNotThrow(() -> s.syncPosition(null, 0.0, 0.0, 0.0),
            "the shared hook must be a no-op for a strategy with nothing to arm");
    }

    // ========================================================================
    // The real strategy that produced the loss (AC1, AC3)
    // ========================================================================

    private static final String SYMBOL = "USD_CHF";

    private static Bar bar(Instant t, double open, double high, double low, double close, long volume) {
        return new Bar(SYMBOL, t, open, high, low, close, volume);
    }

    /** Quiet bars around 1.1000: a real ATR to measure deviation against, and a VWAP ≈ 1.1000. */
    private static List<Bar> quiet(int n, Instant start) {
        List<Bar> out = new ArrayList<>();
        double prev = 1.1000;
        for (int i = 0; i < n; i++) {
            double close = 1.1000 + ((i % 2 == 0) ? 0.0003 : -0.0003);
            out.add(bar(start.plusSeconds(3600L * i), prev,
                Math.max(prev, close) + 0.0008, Math.min(prev, close) - 0.0008, close, 1000L));
            prev = close;
        }
        return out;
    }

    /** Well below the running VWAP with a 5× volume spike: the strategy's own BUY condition. */
    private static Bar buySignal(Instant t, double prev) {
        return bar(t, prev, prev + 0.0002, 1.0900, 1.0905, 5000L);
    }

    private static VWPReversionStrategy primed(Instant start) {
        VWPReversionStrategy s = new VWPReversionStrategy("vwpreversion", SYMBOL);
        for (Bar b : quiet(30, start)) s.onBar(b);
        return s;
    }

    @Test
    @DisplayName("after a broker stop-out, vwpreversion does not re-enter until its own 10-bar cooldown is spent")
    void vwpDoesNotReenterUntilCooldownIsSpent() {
        Instant start = Instant.parse("2026-09-01T00:00:00Z");
        VWPReversionStrategy s = primed(start);

        // Precondition, and the assertion that makes the refusals below non-vacuous: this bar shape IS
        // a valid buy signal, so every later refusal is the cooldown talking, not a dead signal.
        Instant t = start.plusSeconds(3600L * 30);
        s.onBar(buySignal(t, 1.1000));
        List<Order> first = s.getPendingOrders();
        assertEquals(1, first.size(), "precondition: the signal bar opens the position");
        assertEquals(Order.Side.BUY, first.get(0).side());
        assertTrue(first.get(0).stopLoss() > 0, "precondition: the entry carries its ATR stop");

        // The broker's stop closed it. The runner's reconciliation notifies the strategy flat: exactly
        // the call that LiveStrategyRunnerPositionSyncTest pins down.
        s.syncPosition(null, 0.0, 0.0, 0.0);
        assertTrue(s.getPendingOrders().isEmpty(),
            "reconciliation must not emit a close order for a position that is already closed");

        // Bar 1 of the cooldown: the running VWAP is still far above, so this is another valid buy
        // signal. It is the sequence that lost money in the window, and it must now be refused.
        t = t.plusSeconds(3600L);
        s.onBar(buySignal(t, 1.1000));
        assertTrue(s.getPendingOrders().isEmpty(),
            "the bar right after a broker stop-out must NOT produce an entry (cooldown armed, bar 1 of 10)");

        // Bars 2 to 10: quiet bars spend the rest of the cooldown, and none of them may trade.
        for (int i = 2; i <= 10; i++) {
            t = t.plusSeconds(3600L);
            s.onBar(quiet(1, t).get(0));
            assertTrue(s.getPendingOrders().isEmpty(), "no entry during the cooldown (bar " + i + " of 10)");
        }

        // Cooldown spent: let the volatility settle, then a fresh signal — the strategy must not be a zombie.
        for (int i = 0; i < 3; i++) {
            t = t.plusSeconds(3600L);
            s.onBar(quiet(1, t).get(0));
        }
        t = t.plusSeconds(3600L);
        s.onBar(buySignal(t, 1.1000));

        List<Order> again = s.getPendingOrders();
        assertEquals(1, again.size(),
            "once the 10-bar cooldown is spent the strategy enters again (no silent zombie)");
        assertEquals(Order.Side.BUY, again.get(0).side());
        assertFalse(again.get(0).isCloseOnly(), "the new order is an entry, not a close for the dead position");
        assertTrue(again.get(0).stopLoss() > 0, "the re-entry carries its own stop");
    }
}
