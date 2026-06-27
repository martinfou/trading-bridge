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
    SQ_EXPORT_RECEIVED,
    /** Weekly strategy builder pipeline step (Epic 22). */
    WEEKLY_BUILDER_EVENT,
    /** Broker connection state transition (Epic 32). */
    CONNECTION,
    BROKER_CONNECT,
    BROKER_DISCONNECT,
    RECONNECT_ATTEMPT,
    RECONNECT_FAILURE,
    RATE_LIMIT_TRIGGERED,
    STALE_PRICE_DETECTED
}
