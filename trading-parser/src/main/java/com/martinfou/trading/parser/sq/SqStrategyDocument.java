package com.martinfou.trading.parser.sq;

import java.util.List;
import java.util.Optional;

/**
 * Parsed StrategyQuant {@code StrategyFile} document (story 2-2).
 * Story 2-3 may introduce {@code StrategyConfig} as a higher-level view.
 */
public record SqStrategyDocument(
    String strategyFileVersion,
    String strategyName,
    String engine,
    SqMoneyManagement moneyManagement,
    SqGlobalSlPt globalSlPt,
    List<SqXmlVariable> variables,
    List<SqXmlEvent> events
) {
    public Optional<SqXmlVariable> variableByName(String name) {
        return variables.stream().filter(v -> name.equals(v.name())).findFirst();
    }

    public Optional<SqXmlVariable> variableById(String id) {
        return variables.stream().filter(v -> id.equals(v.id())).findFirst();
    }

    public List<SqXmlItem> allItems() {
        return events.stream()
            .flatMap(e -> e.rules().stream())
            .flatMap(r -> {
                var stream = r.signals().stream().map(SqXmlSignal::rootItem);
                var cond = r.condition().stream();
                var acts = r.actions().stream();
                return java.util.stream.Stream.concat(
                    java.util.stream.Stream.concat(stream, cond),
                    acts
                );
            })
            .flatMap(SqStrategyDocument::flattenItems)
            .toList();
    }

    private static java.util.stream.Stream<SqXmlItem> flattenItems(SqXmlItem item) {
        var nested = item.blocks().stream().map(SqXmlBlock::item).flatMap(SqStrategyDocument::flattenItems);
        var formulas = item.params().stream()
            .flatMap(p -> p.formulaItem().stream())
            .flatMap(SqStrategyDocument::flattenItems);
        return java.util.stream.Stream.concat(
            java.util.stream.Stream.of(item),
            java.util.stream.Stream.concat(nested, formulas)
        );
    }
}
