package com.martinfou.trading.parser.conditions;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.parser.config.StrategyConfig;
import com.martinfou.trading.parser.sq.SqStrategyDocument;
import com.martinfou.trading.parser.sq.SqXmlEvent;
import com.martinfou.trading.parser.sq.SqXmlRule;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Evaluates IfThen exit rules using signal results and position context (story 2-7). */
public final class SqExitEvaluator {

    public record ExitResult(
        boolean longExitActive,
        boolean shortExitActive,
        Map<String, Boolean> signalResults
    ) {}

    public record PositionState(boolean longPositionOpen, boolean shortPositionOpen) {
        public static PositionState flat() {
            return new PositionState(false, false);
        }
    }

    private SqExitEvaluator() {}

    public static ExitResult evaluate(
        SqStrategyDocument document,
        List<Bar> bars,
        StrategyConfig config,
        PositionState position
    ) {
        PositionState pos = position == null ? PositionState.flat() : position;
        Map<String, Boolean> signals = SqSignalEvaluator.evaluate(document, bars, config);
        SqEvaluationContext context = SqEvaluationContext.of(
            bars, config, signals, pos.longPositionOpen(), pos.shortPositionOpen()
        );

        boolean longExit = false;
        boolean shortExit = false;

        if (document != null) {
            for (SqXmlEvent event : document.events()) {
                if (!"OnBarUpdate".equals(event.key())) {
                    continue;
                }
                for (SqXmlRule rule : event.rules()) {
                    if (!"IfThen".equals(rule.type()) || !isExitRule(rule)) {
                        continue;
                    }
                    boolean active = rule.condition()
                        .flatMap(c -> SqConditionEvaluator.evaluate(c, context))
                        .orElse(false);
                    if (!active) {
                        continue;
                    }
                    if (isLongExitRule(rule)) {
                        longExit = true;
                    }
                    if (isShortExitRule(rule)) {
                        shortExit = true;
                    }
                }
            }
        }

        return new ExitResult(longExit, shortExit, signals);
    }

    public static ExitResult evaluate(SqStrategyDocument document, List<Bar> bars, StrategyConfig config) {
        return evaluate(document, bars, config, PositionState.flat());
    }

    public static boolean longExitActive(ExitResult result) {
        return result != null && result.longExitActive();
    }

    public static boolean shortExitActive(ExitResult result) {
        return result != null && result.shortExitActive();
    }

    private static boolean isExitRule(SqXmlRule rule) {
        if (rule.actions().stream().anyMatch(a -> a.isExitAction())) {
            return true;
        }
        String name = rule.name().toLowerCase(Locale.ROOT);
        return name.contains("exit");
    }

    private static boolean isLongExitRule(SqXmlRule rule) {
        String name = rule.name().toLowerCase(Locale.ROOT);
        if (name.contains("long")) {
            return true;
        }
        return rule.actions().stream()
            .filter(a -> a.isExitAction())
            .flatMap(a -> a.params().stream())
            .anyMatch(p -> "#Direction#".equals(p.key()) && "1".equals(p.textValue()));
    }

    private static boolean isShortExitRule(SqXmlRule rule) {
        String name = rule.name().toLowerCase(Locale.ROOT);
        if (name.contains("short")) {
            return true;
        }
        return rule.actions().stream()
            .filter(a -> a.isExitAction())
            .flatMap(a -> a.params().stream())
            .anyMatch(p -> "#Direction#".equals(p.key()) && "-1".equals(p.textValue()));
    }
}
