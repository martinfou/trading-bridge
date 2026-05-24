package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.events.RunEvent;

/**
 * A persisted run event with its global store sequence (for cursor pagination).
 */
public record StoredRunEvent(long sequence, RunEvent event) {}
