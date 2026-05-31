package com.martinfou.trading.parser.conditions;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.parser.config.StrategyConfig;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Runtime context for SQ condition evaluation (story 2-6, 2-7). */
public final class SqEvaluationContext {

    private final List<Bar> bars;
    private final StrategyConfig config;
    private final Map<String, Boolean> signalResults;
    private final boolean longPositionOpen;
    private final boolean shortPositionOpen;

    private SqEvaluationContext(
        List<Bar> bars,
        StrategyConfig config,
        Map<String, Boolean> signalResults,
        boolean longPositionOpen,
        boolean shortPositionOpen
    ) {
        this.bars = List.copyOf(bars);
        this.config = config;
        this.signalResults = Map.copyOf(signalResults);
        this.longPositionOpen = longPositionOpen;
        this.shortPositionOpen = shortPositionOpen;
    }

    public static SqEvaluationContext of(List<Bar> bars, StrategyConfig config, Map<String, Boolean> signalResults) {
        return of(bars, config, signalResults, false, false);
    }

    public static SqEvaluationContext of(
        List<Bar> bars,
        StrategyConfig config,
        Map<String, Boolean> signalResults,
        boolean longPositionOpen,
        boolean shortPositionOpen
    ) {
        return new SqEvaluationContext(
            bars,
            config,
            signalResults == null ? Map.of() : signalResults,
            longPositionOpen,
            shortPositionOpen
        );
    }

    public List<Bar> bars() {
        return bars;
    }

    public StrategyConfig config() {
        return config;
    }

    public boolean longPositionOpen() {
        return longPositionOpen;
    }

    public boolean shortPositionOpen() {
        return shortPositionOpen;
    }

    public Optional<Boolean> signalResult(String variableId) {
        if (variableId == null || variableId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(signalResults.get(variableId));
    }
}
