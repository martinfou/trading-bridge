package com.martinfou.trading.backtest;

/**
 * Execution fill modes for limit and stop order simulation in {@link BacktestEngine}.
 */
public enum FillMode {
    /**
     * Legacy optimistic fill: Fills limit orders when bar High/Low touches the order price.
     */
    TOUCH,

    /**
     * Conservative realistic fill: Fills limit orders only when price trades strictly THROUGH
     * the order price (bar low < buy limit, bar high > sell limit) by at least one price increment.
     */
    TRADE_THROUGH
}
