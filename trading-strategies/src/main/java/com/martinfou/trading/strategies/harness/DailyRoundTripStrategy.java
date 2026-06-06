package com.martinfou.trading.strategies.harness;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.strategies.prop.PropSessions;

/**
 * One long round-trip per UTC day at configurable hours (default 08:00 enter, 21:00 exit).
 * For a simpler calendar pattern (00:00 enter, 23:00 exit), use {@link DailyOpenCloseStrategy}.
 */
public final class DailyRoundTripStrategy extends HarnessScriptedStrategy {

    public static final int DEFAULT_OPEN_HOUR = 8;
    public static final int DEFAULT_CLOSE_HOUR = 21;

    private final int openHour;
    private final int closeHour;

    private int lastDayKey = -1;
    private boolean inPosition;
    private boolean enteredToday;

    public DailyRoundTripStrategy(String symbol) {
        this(symbol, DEFAULT_OPEN_HOUR, DEFAULT_CLOSE_HOUR);
    }

    public DailyRoundTripStrategy(String symbol, int openHour, int closeHour) {
        super(symbol);
        this.openHour = openHour;
        this.closeHour = closeHour;
    }

    @Override
    public String name() {
        return "Harness_DailyRoundTrip";
    }

    @Override
    public void onBar(Bar bar) {
        clearPending();
        if (!symbolMatches(bar)) {
            return;
        }
        int dayKey = PropSessions.dayKey(bar);
        if (lastDayKey != -1 && dayKey != lastDayKey) {
            if (inPosition) {
                emit(marketSellClose(bar));
                inPosition = false;
            }
            enteredToday = false;
        }
        lastDayKey = dayKey;

        int hour = PropSessions.hour(bar);
        if (!inPosition && !enteredToday && hour == openHour) {
            emit(marketBuy(bar));
            inPosition = true;
            enteredToday = true;
        } else if (inPosition && hour == closeHour) {
            emit(marketSellClose(bar));
            inPosition = false;
        }
    }

    @Override
    protected void onReset() {
        lastDayKey = -1;
        inPosition = false;
        enteredToday = false;
    }
}
