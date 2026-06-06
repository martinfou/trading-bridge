package com.martinfou.trading.strategies.harness;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.strategies.prop.PropSessions;

/**
 * One long round-trip per ISO week: enter on first Monday bar, exit on Friday bar
 * (or first bar of the next week if still open).
 */
public final class WeeklyRoundTripStrategy extends HarnessScriptedStrategy {

    private int lastWeekKey = -1;
    private int entryWeekKey = -1;
    private boolean inPosition;

    public WeeklyRoundTripStrategy(String symbol) {
        super(symbol);
    }

    @Override
    public String name() {
        return "Harness_WeeklyRoundTrip";
    }

    @Override
    public void onBar(Bar bar) {
        clearPending();
        if (!symbolMatches(bar)) {
            return;
        }
        int weekKey = PropSessions.weekKey(bar);
        if (lastWeekKey != -1 && weekKey != lastWeekKey && inPosition) {
            emit(marketSellClose(bar));
            inPosition = false;
            entryWeekKey = -1;
        }
        lastWeekKey = weekKey;

        if (!inPosition && PropSessions.isMonday(bar)) {
            emit(marketBuy(bar));
            inPosition = true;
            entryWeekKey = weekKey;
        } else if (inPosition && PropSessions.isFriday(bar) && weekKey == entryWeekKey) {
            emit(marketSellClose(bar));
            inPosition = false;
            entryWeekKey = -1;
        }
    }

    @Override
    protected void onReset() {
        lastWeekKey = -1;
        entryWeekKey = -1;
        inPosition = false;
    }
}
