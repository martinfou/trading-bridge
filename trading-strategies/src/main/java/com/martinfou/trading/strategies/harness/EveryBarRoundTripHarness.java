package com.martinfou.trading.strategies.harness;

import com.martinfou.trading.core.Bar;

/** Same-bar round-trip on every bar passed to {@link #onBar(Bar)}. */
abstract class EveryBarRoundTripHarness extends HarnessScriptedStrategy {

    protected EveryBarRoundTripHarness(String symbol) {
        super(symbol);
    }

    @Override
    public void onBar(Bar bar) {
        clearPending();
        if (!symbolMatches(bar)) {
            return;
        }
        emitPair(marketBuy(bar), limitSellCloseAtClose(bar));
    }
}
