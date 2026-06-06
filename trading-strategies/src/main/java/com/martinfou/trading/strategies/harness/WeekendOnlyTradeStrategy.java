package com.martinfou.trading.strategies.harness;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.ForexMarketCalendar;

/**
 * Only tries to trade on Saturday/Sunday UTC bars.
 * <p>With a correct engine, {@code onBar} is never called on weekends for forex, so
 * expect <strong>0 trades</strong> even if the input series contains Sat/Sun bars.</p>
 */
public final class WeekendOnlyTradeStrategy extends HarnessScriptedStrategy {

    public WeekendOnlyTradeStrategy(String symbol) {
        super(symbol);
    }

    @Override
    public String name() {
        return "Harness_WeekendOnlyTrade";
    }

    @Override
    public void onBar(Bar bar) {
        clearPending();
        if (!symbolMatches(bar)) {
            return;
        }
        if (!ForexMarketCalendar.isWeekendUtc(bar.timestamp())) {
            return;
        }
        emitPair(marketBuy(bar), limitSellCloseAtClose(bar));
    }
}
