package com.martinfou.trading.parser.indicators;

import com.martinfou.trading.parser.sq.SqXmlItem;
import com.martinfou.trading.parser.sq.SqXmlParam;

import java.util.Optional;

/** Shared SQ Item param extraction for indicator evaluators. */
final class SqParamReaders {

    private SqParamReaders() {}

    static int readInt(
        SqXmlItem item,
        String paramKey,
        SqIndicatorParams.SqParameterResolver resolver,
        int defaultValue
    ) {
        return findParam(item, paramKey)
            .flatMap(p -> resolver.resolveInt(p, defaultValue))
            .orElse(defaultValue);
    }

    static double readDouble(
        SqXmlItem item,
        String paramKey,
        SqIndicatorParams.SqParameterResolver resolver,
        double defaultValue
    ) {
        return findParam(item, paramKey)
            .flatMap(p -> resolver.resolveDouble(p, defaultValue))
            .orElse(defaultValue);
    }

    static int endIndex(int barCount, int shift) {
        return barCount - 1 - shift;
    }

    private static Optional<SqXmlParam> findParam(SqXmlItem item, String key) {
        return item.params().stream().filter(p -> key.equals(p.key())).findFirst();
    }
}
