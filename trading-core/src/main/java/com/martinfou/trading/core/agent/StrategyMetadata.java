package com.martinfou.trading.core.agent;

import java.time.Instant;
import java.util.List;

/**
 * Métadonnées d'une stratégie validée, stockées dans StrategyCatalog
 * pour permettre la recherche par métriques de performance.
 */
public record StrategyMetadata(
    String name,
    StrategyProfile profile,
    String category,
    List<String> indicators,
    String inspiration,
    int pairCount,
    double avgPf,
    double avgSharpe,
    double maxDd,
    double avgWinRate,
    int totalTrades,
    List<PairResult> pairResults,
    Instant createdAt
) {}
