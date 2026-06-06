package com.martinfou.trading.strategies.harness;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.strategies.prop.PropSessions;

/**
 * One long round-trip per UTC calendar day: enter at hour {@code 0}, exit at hour {@code 23}.
 * If still open when the day rolls, close on the first bar of the new day.
 */
public final class DailyOpenCloseStrategy extends HarnessScriptedStrategy {

    public static final int ENTRY_HOUR = 0;
    public static final int EXIT_HOUR = 23;

    private int lastDayKey = -1;
    private boolean inPosition;

    public DailyOpenCloseStrategy(String symbol) {
        super(symbol);
    }

    @Override
    public String name() {
        return "Harness_DailyOpenClose";
    }

    @Override
    public void onBar(Bar bar) {
        clearPending();
        if (!symbolMatches(bar)) {
            return;
        }
        int dayKey = PropSessions.dayKey(bar);
        if (lastDayKey != -1 && dayKey != lastDayKey && inPosition) {
            emit(marketSellClose(bar));
            inPosition = false;
        }
        lastDayKey = dayKey;

        int hour = PropSessions.hour(bar);
        if (!inPosition && hour == ENTRY_HOUR) {
            emit(marketBuy(bar));
            inPosition = true;
        } else if (inPosition && hour == EXIT_HOUR) {
            emit(marketSellClose(bar));
            inPosition = false;
        }
    }

    @Override
    protected void onReset() {
        lastDayKey = -1;
        inPosition = false;
    }
}
