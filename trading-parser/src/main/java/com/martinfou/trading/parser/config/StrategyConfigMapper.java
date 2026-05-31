package com.martinfou.trading.parser.config;

import com.martinfou.trading.parser.sq.SqStrategyDocument;
import com.martinfou.trading.parser.sq.SqXmlEvent;
import com.martinfou.trading.parser.sq.SqXmlItem;
import com.martinfou.trading.parser.sq.SqXmlRule;
import com.martinfou.trading.parser.sq.SqXmlSignal;
import com.martinfou.trading.parser.sq.SqXmlVariable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class StrategyConfigMapper {

    private static final List<String> SIGNAL_SLOT_NAMES = List.of(
        "LongEntrySignal", "ShortEntrySignal", "LongExitSignal", "ShortExitSignal"
    );

    private StrategyConfigMapper() {}

    static StrategyConfig from(SqStrategyDocument document) {
        Map<String, StrategyParameter> parameters = mapParameters(document);
        Map<String, String> signalRootById = signalRootKeys(document);

        return new StrategyConfig(
            document.strategyFileVersion(),
            document.strategyName(),
            document.engine(),
            mapPositionSizing(document),
            mapGlobalExit(document),
            parameters,
            mapSignalSlots(document, signalRootById),
            mapRules(document),
            mapIndicatorKeys(document),
            mapEntryActions(document)
        );
    }

    private static Map<String, StrategyParameter> mapParameters(SqStrategyDocument document) {
        Map<String, StrategyParameter> out = new LinkedHashMap<>();
        for (SqXmlVariable variable : document.variables()) {
            if (variable.name().isBlank()) {
                continue;
            }
            out.put(variable.name(), new StrategyParameter(
                variable.id(),
                variable.name(),
                variable.type(),
                variable.value(),
                variable.paramType()
            ));
        }
        return Map.copyOf(out);
    }

    private static PositionSizingConfig mapPositionSizing(SqStrategyDocument document) {
        var mm = document.moneyManagement();
        return new PositionSizingConfig(mm.type(), Map.copyOf(mm.params()));
    }

    private static GlobalExitConfig mapGlobalExit(SqStrategyDocument document) {
        var g = document.globalSlPt();
        return new GlobalExitConfig(g.useSameForBothDirections(), g.globalStopLoss(), g.globalProfitTarget());
    }

    private static Map<String, String> signalRootKeys(SqStrategyDocument document) {
        Map<String, String> out = new LinkedHashMap<>();
        for (SqXmlEvent event : document.events()) {
            for (SqXmlRule rule : event.rules()) {
                for (SqXmlSignal signal : rule.signals()) {
                    out.put(signal.variableId(), signal.rootItem().key());
                }
            }
        }
        return out;
    }

    private static List<SignalSlotConfig> mapSignalSlots(
        SqStrategyDocument document,
        Map<String, String> signalRootById
    ) {
        List<SignalSlotConfig> out = new ArrayList<>();
        for (String standardName : SIGNAL_SLOT_NAMES) {
            document.variableByName(standardName).ifPresent(v -> out.add(new SignalSlotConfig(
                v.name(),
                v.id(),
                signalRootById.getOrDefault(v.id(), "")
            )));
        }
        return List.copyOf(out);
    }

    private static List<RuleConfig> mapRules(SqStrategyDocument document) {
        List<RuleConfig> out = new ArrayList<>();
        for (SqXmlEvent event : document.events()) {
            for (SqXmlRule rule : event.rules()) {
                List<String> signalIds = rule.signals().stream().map(SqXmlSignal::variableId).toList();
                String conditionKey = rule.condition().map(SqXmlItem::key).orElse("");
                List<String> actionKeys = rule.actions().stream().map(SqXmlItem::key).toList();
                out.add(new RuleConfig(
                    event.key(),
                    rule.name(),
                    rule.type(),
                    signalIds,
                    conditionKey,
                    actionKeys
                ));
            }
        }
        return List.copyOf(out);
    }

    private static List<String> mapIndicatorKeys(SqStrategyDocument document) {
        Set<String> keys = new LinkedHashSet<>();
        for (SqXmlItem item : uniqueItemsByKey(document)) {
            if (item.isIndicator()) {
                keys.add(item.key());
            }
        }
        return keys.stream().sorted().toList();
    }

    private static List<String> mapEntryActions(SqStrategyDocument document) {
        Set<String> keys = new LinkedHashSet<>();
        for (SqXmlItem item : uniqueItemsByKey(document)) {
            if (item.isEntryAction()) {
                keys.add(item.key());
            }
        }
        return List.copyOf(keys);
    }

    private static List<SqXmlItem> uniqueItemsByKey(SqStrategyDocument document) {
        Map<String, SqXmlItem> byKey = new LinkedHashMap<>();
        for (SqXmlItem item : document.allItems()) {
            if (!item.key().isBlank()) {
                byKey.putIfAbsent(item.key(), item);
            }
        }
        return List.copyOf(byKey.values());
    }
}
