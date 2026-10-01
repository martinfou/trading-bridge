package com.martinfou.trading.strategies;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.strategies.creative.CompositeMomentumRankingStrategy;
import com.martinfou.trading.strategies.creative.MonthWeekPhaseStrategy;
import com.martinfou.trading.strategies.longterm.LtRSI3Momentum;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression tests for the "new silent failure" the 2026-10-01 review flagged (spec §12.1 E4): once
 * the runner resolves the instrument from config, a strategy whose own {@code symbol} field still
 * holds a stale compiled default will filter 100% of its bars on {@code bar.symbol()} and trade zero
 * without a single log. {@code Strategy.reconcileInstrument} (story 1.8) is what makes the strategy's
 * internal pair track the resolved one.
 */
class StrategySymbolReconciliationTest {

    private static Bar bar(String symbol) {
        return new Bar(symbol, Instant.parse("2026-09-01T00:00:00Z"), 208.0, 208.5, 207.5, 208.2, 1000L);
    }

    private static int historySize(Strategy s) {
        return s.getHistory().orElseThrow().size();
    }

    @Test
    @DisplayName("a stale class symbol no longer filters bars once reconciled (CompositeMomentumRanking)")
    void compositeMomentumStopsFilteringAfterReconcile() {
        CompositeMomentumRankingStrategy s = new CompositeMomentumRankingStrategy(); // default symbol EUR_USD

        // Before reconciliation, a USD_JPY bar is dropped by the stale EUR_USD symbol.
        s.onBar(bar("USD_JPY"));
        assertEquals(0, historySize(s),
            "before reconcile, the stale EUR_USD symbol filters a USD_JPY bar");

        s.reconcileInstrument("USD_JPY");
        s.onBar(bar("USD_JPY"));
        assertEquals(1, historySize(s),
            "after reconcile, the USD_JPY bar is no longer filtered");
    }

    @Test
    @DisplayName("MonthWeekPhase reconciles from its stale EUR_USD default to USD_JPY")
    void monthWeekPhaseStopsFilteringAfterReconcile() {
        MonthWeekPhaseStrategy s = new MonthWeekPhaseStrategy(); // default symbol EUR_USD

        s.onBar(bar("USD_JPY"));
        assertEquals(0, historySize(s), "stale EUR_USD filters USD_JPY before reconcile");

        s.reconcileInstrument("USD_JPY");
        s.onBar(bar("USD_JPY"));
        assertEquals(1, historySize(s), "USD_JPY bar reaches the strategy after reconcile");
    }

    @Test
    @DisplayName("LtRSI3Momentum tracks the resolved instrument, so a future pair change cannot silence it")
    void ltRsi3TracksResolvedInstrument() {
        LtRSI3Momentum s = new LtRSI3Momentum(); // default symbol EUR_USD

        s.reconcileInstrument("USD_JPY");
        s.onBar(bar("USD_JPY"));
        assertEquals(1, historySize(s), "a reconciled USD_JPY bar is accepted");
        s.onBar(bar("EUR_USD"));
        assertEquals(1, historySize(s), "a EUR_USD bar is now filtered out (symbol moved)");
    }

    @Test
    @DisplayName("reconcileInstrument is a no-op for a strategy with no symbol field")
    void reconcileIsNoopWithoutSymbolField() {
        Strategy noSymbol = new Strategy() {
            @Override public String name() { return "NoSymbol"; }
            @Override public void onBar(Bar bar) { }
            @Override public void onTick(double bid, double ask, long volume) { }
            @Override public List<Order> getPendingOrders() { return new ArrayList<>(); }
            @Override public void reset() { }
        };

        assertDoesNotThrow(() -> noSymbol.reconcileInstrument("USD_CHF"));
    }
}
