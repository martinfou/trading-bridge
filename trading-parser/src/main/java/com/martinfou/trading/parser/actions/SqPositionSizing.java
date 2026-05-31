package com.martinfou.trading.parser.actions;

import com.martinfou.trading.core.Order;
import com.martinfou.trading.parser.config.StrategyConfig;
import com.martinfou.trading.parser.sq.SqXmlItem;
import com.martinfou.trading.parser.sq.SqXmlParam;

import java.util.Optional;

/** Resolves order quantity from SQ {@link EnterAtStop} {@code #Size#} / money management (story 2-8). */
public final class SqPositionSizing {

    private SqPositionSizing() {}

    public static double resolveQuantity(SqXmlItem enterAtStop, StrategyConfig config, double defaultLots) {
        if (config == null) {
            return defaultLots;
        }
        Optional<SqXmlParam> sizeParam = param(enterAtStop, "#Size#");
        if (sizeParam.isEmpty() || sizeParam.get().formulaItem().isPresent()) {
            return config.positionSizing().fixedSizeOr(defaultLots);
        }
        String text = sizeParam.get().textValue();
        if (text == null || text.isBlank()) {
            return config.positionSizing().fixedSizeOr(defaultLots);
        }
        if (sizeParam.get().variableReference()) {
            return config.intParameter(text, (int) defaultLots);
        }
        try {
            return Double.parseDouble(text.trim());
        } catch (NumberFormatException e) {
            return config.positionSizing().fixedSizeOr(defaultLots);
        }
    }

    private static Optional<SqXmlParam> param(SqXmlItem item, String key) {
        return item.params().stream().filter(p -> key.equals(p.key())).findFirst();
    }
}
