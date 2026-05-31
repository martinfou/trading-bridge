package com.martinfou.trading.parser.indicators;

import com.martinfou.trading.parser.config.StrategyConfig;
import com.martinfou.trading.parser.sq.SqXmlItem;

/** MACD params from SQ Item (#FastPeriod#, #SlowPeriod#, #SignalPeriod#, #Shift#, #ComputedFrom#). */
public record SqMacdParams(
    int fastPeriod,
    int slowPeriod,
    int signalPeriod,
    int shift,
    SqAppliedPrice appliedPrice
) {
    public static SqMacdParams from(SqXmlItem item, StrategyConfig config) {
        SqIndicatorParams.SqParameterResolver resolver = SqIndicatorParams.SqParameterResolver.from(config);
        int fast = SqParamReaders.readInt(item, "#FastPeriod#", resolver, 12);
        int slow = SqParamReaders.readInt(item, "#SlowPeriod#", resolver, 26);
        int signal = SqParamReaders.readInt(item, "#SignalPeriod#", resolver, 9);
        int shift = SqParamReaders.readInt(item, "#Shift#", resolver, 0);
        int priceCode = SqParamReaders.readInt(item, "#ComputedFrom#", resolver, 0);
        return new SqMacdParams(fast, slow, signal, shift, SqAppliedPrice.fromSqCode(priceCode));
    }

    public int endIndex(int barCount) {
        return SqParamReaders.endIndex(barCount, shift);
    }
}
