package com.martinfou.trading.strategies.harness;

import com.martinfou.trading.core.Bar;

/** BUY on bar 0, close-only SELL on bar 1 — expects one round-trip. */
public final class BuyThenCloseNextBarStrategy extends HarnessScriptedStrategy {

    private int barIndex;

    public BuyThenCloseNextBarStrategy(String symbol) {
        super(symbol);
    }

    @Override
    public String name() {
        return "Harness_BuyThenCloseNextBar";
    }

    @Override
    public void onBar(Bar bar) {
        clearPending();
        if (!symbolMatches(bar)) {
            return;
        }
        if (barIndex == 0) {
            emit(marketBuy(bar));
        } else if (barIndex == 1) {
            emit(marketSellClose(bar));
        }
        barIndex++;
    }

    @Override
    protected void onReset() {
        barIndex = 0;
    }
}
