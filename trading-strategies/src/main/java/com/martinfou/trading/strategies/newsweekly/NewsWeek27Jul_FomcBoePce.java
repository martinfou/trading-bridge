package com.martinfou.trading.strategies.newsweekly;

import com.martinfou.trading.core.Order;

/**
 * FOMC + BOE + PCE news trading strategies for week July 27-31, 2026.
 *
 * Three major events this week:
 * - FOMC Wed Jul 29 14:00 ET — hawkish expected, SELL USD pairs
 * - BOE Thu Jul 30 07:00 ET — hawkish hold expected, BUY GBP
 * - PCE Core Thu Jul 30 08:30 ET — bidirectional, read first bar
 *
 * Each inner class is one trade on one pair.
 */
public class NewsWeek27Jul_FomcBoePce {

    public static class FomcAudUsd extends NewsWeeklyStrategy {
        public FomcAudUsd() {
            super("FomcAudUsd", "AUD_USD",
                nyEvent(2026, 7, 29, 14, 0), weekEndAfter(2026, 7, 31),
                60, 90, Order.Side.SELL, 0.01);
        }
    }

    public static class FomcNzdUsd extends NewsWeeklyStrategy {
        public FomcNzdUsd() {
            super("FomcNzdUsd", "NZD_USD",
                nyEvent(2026, 7, 29, 14, 0), weekEndAfter(2026, 7, 31),
                60, 90, Order.Side.SELL, 0.01);
        }
    }

    public static class FomcEurUsd extends NewsWeeklyStrategy {
        public FomcEurUsd() {
            super("FomcEurUsd", "EUR_USD",
                nyEvent(2026, 7, 29, 14, 0), weekEndAfter(2026, 7, 31),
                50, 80, Order.Side.SELL, 0.007);
        }
    }

    public static class FomcUsdCad extends NewsWeeklyStrategy {
        public FomcUsdCad() {
            super("FomcUsdCad", "USD_CAD",
                nyEvent(2026, 7, 29, 14, 0), weekEndAfter(2026, 7, 31),
                50, 80, Order.Side.BUY, 0.007);
        }
    }

    /** BOE Thu Jul 30 07:00 ET — BUY GBP */
    public static class BoeGbpUsd extends NewsWeeklyStrategy {
        public BoeGbpUsd() {
            super("BoeGbpUsd", "GBP_USD",
                nyEvent(2026, 7, 30, 7, 0), weekEndAfter(2026, 7, 31),
                60, 100, Order.Side.BUY, 0.01);
        }
    }

    public static class BoeGbpJpy extends NewsWeeklyStrategy {
        public BoeGbpJpy() {
            super("BoeGbpJpy", "GBP_JPY",
                nyEvent(2026, 7, 30, 7, 0), weekEndAfter(2026, 7, 31),
                60, 100, Order.Side.BUY, 0.007);
        }
    }

    /** PCE Thu Jul 30 08:30 ET — Bidirectional */
    public static class PceEurUsd extends NewsWeeklyStrategy {
        public PceEurUsd() {
            super("PceEurUsd", "EUR_USD",
                nyEvent(2026, 7, 30, 8, 30), weekEndAfter(2026, 7, 31),
                40, 70, 0.007);
        }
    }

    public static class PceGbpUsd extends NewsWeeklyStrategy {
        public PceGbpUsd() {
            super("PceGbpUsd", "GBP_USD",
                nyEvent(2026, 7, 30, 8, 30), weekEndAfter(2026, 7, 31),
                40, 70, 0.007);
        }
    }
}
