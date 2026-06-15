package com.martinfou.trading.core.agent;

import java.util.List;
import java.util.Map;

/**
 * Specification d'une stratégie de trading générée par LLM.
 *
 * Utilise un modèle extensible (Map<String, Object> params) plutôt
 * que des champs hardcodés pour permettre au LLM de définir des
 * paramètres custom sans modifier le schema.
 */
public record StrategySpec(
    String name,
    String inspiration,
    String description,
    StrategyProfile profile,
    String category,
    List<String> indicators,
    String longEntry,
    String shortEntry,
    String exitCondition,
    double slMultiplier,
    double tpMultiplier,
    int maxHoldBars,
    Map<String, Object> params
) {
    public StrategySpec {
        name = name != null ? name : "Unnamed";
        profile = profile != null ? profile : StrategyProfile.LONG_TERM;
        category = category != null ? category : "Trend Following";
        indicators = indicators != null ? indicators : List.of();
        params = params != null ? params : Map.of();
        if (slMultiplier <= 0) slMultiplier = 2.0;
        if (tpMultiplier <= 0) tpMultiplier = 4.0;
        if (maxHoldBars <= 0) maxHoldBars = 240;
    }
}
