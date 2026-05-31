package com.martinfou.trading.parser.conditions;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.parser.config.StrategyConfig;
import com.martinfou.trading.parser.sq.SqStrategyDocument;
import com.martinfou.trading.parser.sq.SqXmlEvent;
import com.martinfou.trading.parser.sq.SqXmlRule;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Evaluates IfThen entry rules using precomputed signal results (story 2-6). */
public final class SqEntryEvaluator {

    public record EntryResult(boolean longEntryActive, boolean shortEntryActive, Map<String, Boolean> signalResults) {}

    private SqEntryEvaluator() {}

    public static EntryResult evaluate(
        SqStrategyDocument document,
        List<Bar> bars,
        StrategyConfig config
    ) {
        Map<String, Boolean> signals = SqSignalEvaluator.evaluate(document, bars, config);
        SqEvaluationContext context = SqEvaluationContext.of(bars, config, signals);

        boolean longEntry = false;
        boolean shortEntry = false;

        if (document != null) {
            for (SqXmlEvent event : document.events()) {
                if (!"OnBarUpdate".equals(event.key())) {
                    continue;
                }
                for (SqXmlRule rule : event.rules()) {
                    if (!"IfThen".equals(rule.type()) || !isEntryRule(rule)) {
                        continue;
                    }
                    boolean active = rule.condition()
                        .flatMap(c -> SqConditionEvaluator.evaluate(c, context))
                        .orElse(false);
                    if (!active) {
                        continue;
                    }
                    if (isLongEntryRule(rule)) {
                        longEntry = true;
                    }
                    if (isShortEntryRule(rule)) {
                        shortEntry = true;
                    }
                }
            }
        }

        return new EntryResult(longEntry, shortEntry, signals);
    }

    public static boolean longEntryActive(EntryResult result) {
        return result != null && result.longEntryActive();
    }

    public static boolean shortEntryActive(EntryResult result) {
        return result != null && result.shortEntryActive();
    }

    private static boolean isEntryRule(SqXmlRule rule) {
        if (rule.actions().stream().anyMatch(a -> a.isEntryAction())) {
            return true;
        }
        String name = rule.name().toLowerCase(Locale.ROOT);
        return name.contains("entry");
    }

    private static boolean isLongEntryRule(SqXmlRule rule) {
        String name = rule.name().toLowerCase(Locale.ROOT);
        return name.contains("long");
    }

    private static boolean isShortEntryRule(SqXmlRule rule) {
        String name = rule.name().toLowerCase(Locale.ROOT);
        return name.contains("short");
    }
}
