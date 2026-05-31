package com.martinfou.trading.parser.actions;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.parser.conditions.SqConditionEvaluator;
import com.martinfou.trading.parser.conditions.SqEvaluationContext;
import com.martinfou.trading.parser.conditions.SqExitEvaluator;
import com.martinfou.trading.parser.conditions.SqSignalEvaluator;
import com.martinfou.trading.parser.config.StrategyConfig;
import com.martinfou.trading.parser.sq.SqStrategyDocument;
import com.martinfou.trading.parser.sq.SqXmlEvent;
import com.martinfou.trading.parser.sq.SqXmlItem;
import com.martinfou.trading.parser.sq.SqXmlRule;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Combines entry/exit gates with action parsing (story 2-8). */
public final class SqStrategyActionsEvaluator {

    private SqStrategyActionsEvaluator() {}

    public static SqBarActions evaluateOnBar(
        SqStrategyDocument document,
        List<Bar> bars,
        StrategyConfig config,
        SqExitEvaluator.PositionState position
    ) {
        if (document == null || bars == null || bars.isEmpty()) {
            return SqBarActions.empty();
        }
        Map<String, Boolean> signals = SqSignalEvaluator.evaluate(document, bars, config);
        SqEvaluationContext context = SqEvaluationContext.of(
            bars,
            config,
            signals,
            position.longPositionOpen(),
            position.shortPositionOpen()
        );

        List<SqOrderIntent> entries = new ArrayList<>();
        List<SqCloseIntent> closes = new ArrayList<>();

        for (SqXmlEvent event : document.events()) {
            if (!"OnBarUpdate".equals(event.key())) {
                continue;
            }
            for (SqXmlRule rule : event.rules()) {
                if (!"IfThen".equals(rule.type())) {
                    continue;
                }
                boolean active = rule.condition()
                    .flatMap(c -> SqConditionEvaluator.evaluate(c, context))
                    .orElse(false);
                if (!active) {
                    continue;
                }
                if (isEntryRule(rule)) {
                    for (SqXmlItem action : rule.actions()) {
                        SqActionParser.parseEnterAtStop(action, context).ifPresent(entries::add);
                    }
                }
                if (isExitRule(rule)) {
                    for (SqXmlItem action : rule.actions()) {
                        SqActionParser.parseCloseAllPositions(action).ifPresent(closes::add);
                    }
                }
            }
        }

        return new SqBarActions(List.copyOf(entries), List.copyOf(closes));
    }

    public static SqBarActions evaluateOnBar(
        SqStrategyDocument document,
        List<Bar> bars,
        StrategyConfig config
    ) {
        return evaluateOnBar(document, bars, config, SqExitEvaluator.PositionState.flat());
    }

    private static boolean isEntryRule(SqXmlRule rule) {
        if (rule.actions().stream().anyMatch(SqXmlItem::isEntryAction)) {
            return true;
        }
        return rule.name().toLowerCase(Locale.ROOT).contains("entry");
    }

    private static boolean isExitRule(SqXmlRule rule) {
        if (rule.actions().stream().anyMatch(SqXmlItem::isExitAction)) {
            return true;
        }
        return rule.name().toLowerCase(Locale.ROOT).contains("exit");
    }
}
