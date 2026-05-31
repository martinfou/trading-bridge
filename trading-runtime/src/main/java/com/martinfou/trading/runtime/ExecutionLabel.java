package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.RunMode;

/**
 * Canonical execution environment label (Story 15.6 / PS-GR11, SM-2).
 * Single source of truth for API, deployments, events, and evidence exports.
 */
public enum ExecutionLabel {
    BACKTEST,
    PAPER_STUB,
    PAPER_OANDA,
    PAPER_IBKR,
    LIVE_OANDA,
    LIVE_IBKR;

    /** Default label for a {@link RunMode} before broker integration selects OANDA/IBKR. */
    public static ExecutionLabel forRunMode(RunMode mode) {
        return switch (mode) {
            case BACKTEST -> BACKTEST;
            case PAPER -> PAPER_STUB;
            case LIVE -> LIVE_OANDA;
        };
    }

    /** Default label when a strategy is promoted to a deployment stage. */
    public static ExecutionLabel forPromotedMode(RunMode mode) {
        return switch (mode) {
            case PAPER -> PAPER_STUB;
            case LIVE -> LIVE_OANDA;
            default -> BACKTEST;
        };
    }

    public static ExecutionLabel parse(String value) {
        if (value == null || value.isBlank()) {
            return PAPER_STUB;
        }
        return ExecutionLabel.valueOf(value.trim().toUpperCase());
    }

    /** Whether elapsed time on this label counts toward the LIVE promote paper period. */
    public boolean countsTowardPaperPeriod() {
        return this == PAPER_OANDA;
    }

    public boolean isOandaBroker() {
        return this == PAPER_OANDA || this == LIVE_OANDA;
    }

    public boolean isIbkrBroker() {
        return this == PAPER_IBKR || this == LIVE_IBKR;
    }

    public boolean isBrokerBacked() {
        return isOandaBroker() || isIbkrBroker();
    }

    public boolean isLiveBroker() {
        return this == LIVE_OANDA || this == LIVE_IBKR;
    }
}
