package com.martinfou.trading.backtest.events;

/**
 * Run lifecycle and execution events (schema v1).
 */
public enum RunEventType {
    RUN_STARTED,
    RUN_ENDED,
    BAR,
    ORDER_SUBMITTED,
    FILL,
    REJECT,
    ERROR,
    OPERATOR_ACTION,
    RECONCILIATION_ALERT,
    HEARTBEAT,
    SQ_EXPORT_RECEIVED
}
