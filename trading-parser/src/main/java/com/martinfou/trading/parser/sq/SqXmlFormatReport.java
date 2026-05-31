package com.martinfou.trading.parser.sq;

import java.util.List;

/**
 * Read-only summary of a StrategyQuant {@code StrategyFile} XML document.
 */
public record SqXmlFormatReport(
    String strategyFileVersion,
    String engine,
    List<SqXmlVariable> variables,
    List<String> signalVariableIds,
    List<SqXmlBuildingBlock> buildingBlocks,
    List<String> entryActionKeys
) {
    public boolean hasStandardSignalVariables() {
        return variables.stream().anyMatch(v -> "LongEntrySignal".equals(v.name()))
            && variables.stream().anyMatch(v -> "ShortEntrySignal".equals(v.name()));
    }

    public List<SqXmlVariable> exitParameters() {
        return variables.stream()
            .filter(v -> v.name().endsWith("StopLoss")
                || v.name().endsWith("ProfitTarget")
                || v.name().endsWith("TrailingStop")
                || v.name().endsWith("ExitAfterBars"))
            .toList();
    }
}
