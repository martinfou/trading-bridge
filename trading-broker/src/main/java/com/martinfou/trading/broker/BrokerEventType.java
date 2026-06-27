package com.martinfou.trading.broker;

/** Broker-side execution events (Story 16.2 — maps to RunEvent journal in Epic 16.7+). */
public enum BrokerEventType {
    ORDER_SUBMITTED,
    FILL,
    PARTIAL_CLOSE,
    FINANCING,
    REJECT,
    CONNECTION,
    RATE_LIMIT,
    STALE_PRICE
}
