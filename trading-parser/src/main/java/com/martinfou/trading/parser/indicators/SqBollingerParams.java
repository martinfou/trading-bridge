package com.martinfou.trading.parser.indicators;

import com.martinfou.trading.parser.config.StrategyConfig;
import com.martinfou.trading.parser.sq.SqXmlItem;

/** Bollinger / BBRange params from SQ Item (#Period#, #Deviation#, #Shift#, #ComputedFrom#). */
public record SqBollingerParams(
    int period,
    double deviation,
    int shift,
    SqAppliedPrice appliedPrice
) {
    public static SqBollingerParams from(SqXmlItem item, StrategyConfig config) {
        SqIndicatorParams.SqParameterResolver resolver = config == null
            ? SqIndicatorParams.SqParameterResolver.NONE
            : SqIndicatorParams.doubleResolver(config);
        int period = SqParamReaders.readInt(item, "#Period#", resolver, 20);
        int shift = SqParamReaders.readInt(item, "#Shift#", resolver, 0);
        int priceCode = SqParamReaders.readInt(item, "#ComputedFrom#", resolver, 0);
        double deviation = readDeviation(item, resolver);
        return new SqBollingerParams(period, deviation, shift, SqAppliedPrice.fromSqCode(priceCode));
    }

    private static double readDeviation(SqXmlItem item, SqIndicatorParams.SqParameterResolver resolver) {
        double deviation = SqParamReaders.readDouble(item, "#Deviation#", resolver, Double.NaN);
        if (!Double.isNaN(deviation)) {
            return deviation;
        }
        deviation = SqParamReaders.readDouble(item, "#Multiplier#", resolver, Double.NaN);
        if (!Double.isNaN(deviation)) {
            return deviation;
        }
        return SqParamReaders.readDouble(item, "#StdDev#", resolver, 2.0);
    }

    public int endIndex(int barCount) {
        return SqParamReaders.endIndex(barCount, shift);
    }
}
