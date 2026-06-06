package com.martinfou.trading.strategies.harness;

import com.martinfou.trading.core.Bar;

/** Single MARKET BUY on first bar; engine flattens at end — expects one trade. */
public final class BuyOnceHoldStrategy extends HarnessScriptedStrategy {

    private boolean sent;

    public BuyOnceHoldStrategy(String symbol) {
        super(symbol);
    }

    @Override
    public String name() {
        return "Harness_BuyOnceHold";
    }

    @Override
    public void onBar(Bar bar) {
        clearPending();
        if (!symbolMatches(bar) || sent) {
            return;
        }
        emit(marketBuy(bar));
        sent = true;
    }

    @Override
    protected void onReset() {
        sent = false;
    }
}
