package com.martinfou.trading.parser.indicators;

import com.martinfou.trading.parser.config.StrategyConfig;
import com.martinfou.trading.parser.config.StrategyParameter;
import com.martinfou.trading.parser.sq.SqXmlItem;
import com.martinfou.trading.parser.sq.SqXmlParam;

import java.util.Optional;

/** Extracts numeric SQ indicator params from an {@link SqXmlItem}. */
public record SqIndicatorParams(int period, int shift, SqAppliedPrice appliedPrice) {

    public static SqIndicatorParams from(SqXmlItem item, SqParameterResolver resolver) {
        int period = SqParamReaders.readInt(item, "#Period#", resolver, 14);
        int shift = SqParamReaders.readInt(item, "#Shift#", resolver, 0);
        int priceCode = SqParamReaders.readInt(item, "#ComputedFrom#", resolver, 0);
        return new SqIndicatorParams(period, shift, SqAppliedPrice.fromSqCode(priceCode));
    }

    public int endIndex(int barCount) {
        return SqParamReaders.endIndex(barCount, shift);
    }

    /** Resolves parameter literals or strategy variable names. */
    public interface SqParameterResolver {
        SqParameterResolver NONE = (param, defaultValue) ->
            SqParamLiterals.parseInt(param.textValue(), defaultValue);

        static SqParameterResolver from(StrategyConfig config) {
            if (config == null) {
                return NONE;
            }
            return (param, defaultValue) -> {
                if (param.variableReference() && !param.textValue().isBlank()) {
                    return Optional.of(config.intParameter(param.textValue(), defaultValue));
                }
                return SqParamLiterals.parseInt(param.textValue(), defaultValue);
            };
        }

        Optional<Integer> resolveInt(SqXmlParam param, int defaultValue);

        default Optional<Double> resolveDouble(SqXmlParam param, double defaultValue) {
            if (param.variableReference() && !param.textValue().isBlank()) {
                return Optional.empty();
            }
            return SqParamLiterals.parseDouble(param.textValue(), defaultValue);
        }
    }

    /** Resolves doubles including strategy variable references. */
    static SqParameterResolver doubleResolver(StrategyConfig config) {
        if (config == null) {
            return SqParameterResolver.NONE;
        }
        return new SqParameterResolver() {
            @Override
            public Optional<Integer> resolveInt(SqXmlParam param, int defaultValue) {
                if (param.variableReference() && !param.textValue().isBlank()) {
                    return Optional.of(config.intParameter(param.textValue(), defaultValue));
                }
                return SqParamLiterals.parseInt(param.textValue(), defaultValue);
            }

            @Override
            public Optional<Double> resolveDouble(SqXmlParam param, double defaultValue) {
                if (param.variableReference() && !param.textValue().isBlank()) {
                    Optional<StrategyParameter> p = config.parameter(param.textValue());
                    return p.flatMap(StrategyParameter::doubleValue)
                        .or(() -> p.flatMap(StrategyParameter::intValue).map(Integer::doubleValue));
                }
                return SqParamLiterals.parseDouble(param.textValue(), defaultValue);
            }
        };
    }
}
