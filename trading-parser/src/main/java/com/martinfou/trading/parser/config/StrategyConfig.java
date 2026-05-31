package com.martinfou.trading.parser.config;

import com.martinfou.trading.parser.sq.SqXmlParser;
import com.martinfou.trading.parser.sq.SqStrategyDocument;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * High-level view of a StrategyQuant strategy (story 2-3).
 * Built from {@link SqStrategyDocument} — mirrors StrategyFile sections for codegen.
 *
 * @see docs/sq-xml-format.md
 */
public record StrategyConfig(
    String strategyFileVersion,
    String strategyName,
    String engine,
    PositionSizingConfig positionSizing,
    GlobalExitConfig globalExit,
    Map<String, StrategyParameter> parameters,
    List<SignalSlotConfig> signalSlots,
    List<RuleConfig> rules,
    List<String> indicatorKeys,
    List<String> entryActionKeys
) {
    public static StrategyConfig from(SqStrategyDocument document) {
        return StrategyConfigMapper.from(document);
    }

    public static StrategyConfig parse(InputStream xmlStream) {
        return from(SqXmlParser.parse(xmlStream));
    }

    public static StrategyConfig parse(Path xmlPath) throws IOException {
        return from(SqXmlParser.parse(xmlPath));
    }

    public Optional<StrategyParameter> parameter(String name) {
        return Optional.ofNullable(parameters.get(name));
    }

    public int intParameter(String name, int defaultValue) {
        return parameter(name).flatMap(StrategyParameter::intValue).orElse(defaultValue);
    }

    public Optional<Integer> longStopLossPips() {
        return parameter("LongStopLoss").flatMap(StrategyParameter::intValue);
    }

    public Optional<Integer> shortStopLossPips() {
        return parameter("ShortStopLoss").flatMap(StrategyParameter::intValue);
    }

    public Optional<Integer> longProfitTargetPips() {
        return parameter("LongProfitTarget").flatMap(StrategyParameter::intValue);
    }

    public Optional<Integer> shortProfitTargetPips() {
        return parameter("ShortProfitTarget").flatMap(StrategyParameter::intValue);
    }

    public Map<String, StrategyParameter> exitParameters() {
        Map<String, StrategyParameter> out = new LinkedHashMap<>();
        for (var entry : parameters.entrySet()) {
            String name = entry.getKey();
            if (name.endsWith("StopLoss")
                || name.endsWith("ProfitTarget")
                || name.endsWith("TrailingStop")
                || name.endsWith("ExitAfterBars")) {
                out.put(name, entry.getValue());
            }
        }
        return Map.copyOf(out);
    }
}
