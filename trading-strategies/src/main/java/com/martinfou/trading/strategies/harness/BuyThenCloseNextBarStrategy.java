package com.martinfou.trading.strategies.harness;

import com.martinfou.trading.core.Bar;

/** BUY on bar 0, close-only SELL on bar 1 — expects one round-trip. */
public final class BuyThenCloseNextBarStrategy extends HarnessScriptedStrategy {

    private int barIndex;
    private boolean liveModeStarted;

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

        // Detect transition from historical warm-up to live trading
        long diffSeconds = java.time.Instant.now().getEpochSecond() - bar.timestamp().getEpochSecond();
        if (diffSeconds <= 300 && !liveModeStarted) {
            barIndex = 0;
            liveModeStarted = true;
        }

        if (barIndex % 2 == 0) {
            emit(marketBuy(bar));
        } else {
            emit(marketSellClose(bar));
        }
        barIndex++;
    }

    @Override
    protected void onReset() {
        barIndex = 0;
        liveModeStarted = false;
    }
}
