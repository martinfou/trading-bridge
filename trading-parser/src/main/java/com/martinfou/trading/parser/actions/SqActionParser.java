package com.martinfou.trading.parser.actions;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.parser.conditions.SqEvaluationContext;
import com.martinfou.trading.parser.conditions.SqValueEvaluator;
import com.martinfou.trading.parser.config.StrategyConfig;
import com.martinfou.trading.parser.sq.SqXmlItem;
import com.martinfou.trading.parser.sq.SqXmlParam;

import java.util.Optional;

/** Parses SQ action Items into intents (story 2-8). */
public final class SqActionParser {

    private SqActionParser() {}

    public static Optional<SqOrderIntent> parseEnterAtStop(
        SqXmlItem item,
        SqEvaluationContext context
    ) {
        if (item == null || !item.isEntryAction()) {
            return Optional.empty();
        }
        StrategyConfig config = context.config();
        Order.Side side = resolveSide(item);
        double quantity = SqPositionSizing.resolveQuantity(item, config, 0.0);
        Optional<Double> stopPrice = resolveStopPrice(item, context);
        Optional<Integer> stopLossPips = resolveExitPips(item, config, "#StopLoss.StopLoss#", side, true);
        Optional<Integer> profitTargetPips = resolveExitPips(item, config, "#ProfitTarget.ProfitTarget#", side, false);
        Optional<Integer> barsValid = resolveBarsValid(item, config);

        return Optional.of(new SqOrderIntent(
            item.key(),
            side,
            quantity,
            stopPrice,
            stopLossPips,
            profitTargetPips,
            barsValid
        ));
    }

    public static Optional<SqCloseIntent> parseCloseAllPositions(SqXmlItem item) {
        if (item == null || !item.isExitAction()) {
            return Optional.empty();
        }
        int directionCode = paramInt(item, "#Direction#", 0);
        Optional<String> magic = paramText(item, "#MagicNumber#").filter(s -> !s.isBlank());
        return Optional.of(new SqCloseIntent(item.key(), SqCloseDirection.fromSqCode(directionCode), magic));
    }

    private static Order.Side resolveSide(SqXmlItem item) {
        int direction = paramInt(item, "#Direction#", 1);
        return direction < 0 ? Order.Side.SELL : Order.Side.BUY;
    }

    private static Optional<Double> resolveStopPrice(SqXmlItem item, SqEvaluationContext context) {
        return param(item, "#Price#")
            .flatMap(SqXmlParam::formulaItem)
            .flatMap(formulaItem -> SqValueEvaluator.evaluate(formulaItem, context));
    }

    private static Optional<Integer> resolveExitPips(
        SqXmlItem item,
        StrategyConfig config,
        String paramKey,
        Order.Side side,
        boolean stopLoss
    ) {
        Optional<String> variableName = param(item, paramKey).map(SqXmlParam::textValue).filter(s -> !s.isBlank());
        if (variableName.isPresent() && config != null) {
            return Optional.of(config.intParameter(variableName.get(), 0)).filter(v -> v > 0);
        }
        if (config != null) {
            Optional<Integer> fromConfig = stopLoss
                ? (side == Order.Side.BUY ? config.longStopLossPips() : config.shortStopLossPips())
                : (side == Order.Side.BUY ? config.longProfitTargetPips() : config.shortProfitTargetPips());
            if (fromConfig.isPresent() && fromConfig.get() > 0) {
                return fromConfig;
            }
        }
        return Optional.empty();
    }

    private static Optional<Integer> resolveBarsValid(SqXmlItem item, StrategyConfig config) {
        return param(item, "#BarsValid#")
            .flatMap(p -> {
                String text = p.textValue();
                if (p.variableReference() && config != null && text != null && !text.isBlank()) {
                    return Optional.of(config.intParameter(text, 0));
                }
                if (text == null || text.isBlank()) {
                    return Optional.empty();
                }
                try {
                    return Optional.of(Integer.parseInt(text.trim()));
                } catch (NumberFormatException e) {
                    return Optional.empty();
                }
            })
            .filter(v -> v > 0);
    }

    private static Optional<SqXmlParam> param(SqXmlItem item, String key) {
        return item.params().stream().filter(p -> key.equals(p.key())).findFirst();
    }

    private static Optional<String> paramText(SqXmlItem item, String key) {
        return param(item, key).map(SqXmlParam::textValue);
    }

    private static int paramInt(SqXmlItem item, String key, int defaultValue) {
        return paramText(item, key)
            .map(text -> {
                try {
                    return Integer.parseInt(text.trim());
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            })
            .orElse(defaultValue);
    }
}
