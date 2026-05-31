package com.martinfou.trading.parser.indicators;

import com.martinfou.trading.core.Bar;

/** StrategyQuant applied price codes (matches {@code #ComputedFrom#} combo int). */
public enum SqAppliedPrice {
    CLOSE(0),
    OPEN(1),
    HIGH(2),
    LOW(3);

    private final int sqCode;

    SqAppliedPrice(int sqCode) {
        this.sqCode = sqCode;
    }

    public static SqAppliedPrice fromSqCode(int code) {
        return switch (code) {
            case 1 -> OPEN;
            case 2 -> HIGH;
            case 3 -> LOW;
            default -> CLOSE;
        };
    }

    public double value(Bar bar) {
        return switch (this) {
            case OPEN -> bar.open();
            case HIGH -> bar.high();
            case LOW -> bar.low();
            case CLOSE -> bar.close();
        };
    }
}
