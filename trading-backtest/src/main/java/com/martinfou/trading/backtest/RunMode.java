package com.martinfou.trading.backtest;

/**
 * Execution mode for a strategy run. Only {@link #BACKTEST} is implemented today;
 * {@link #PAPER} and {@link #LIVE} are reserved for stories 12.6 and Epic 4.
 */
public enum RunMode {
    BACKTEST,
    PAPER,
    LIVE
}
