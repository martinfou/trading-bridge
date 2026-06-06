package com.martinfou.trading.strategies.harness;

/**
 * Every bar: {@code MARKET BUY} (fills at bar open) then close-only {@code LIMIT SELL} at bar close.
 * Expect {@code totalTrades == barCount} on a flat series with open/high/low/close reachable.
 * For weekend calendar checks, use {@link WeekendProbeStrategy}.
 */
public final class OpenCloseSameBarStrategy extends EveryBarRoundTripHarness {

    public OpenCloseSameBarStrategy(String symbol) {
        super(symbol);
    }

    @Override
    public String name() {
        return "Harness_OpenCloseSameBar";
    }
}
