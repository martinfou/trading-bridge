package com.martinfou.trading.core.agent;

import java.time.Instant;
import java.util.List;

/**
 * Résultat complet d'une itération du pipeline de génération.
 * Contient le spec testé, les résultats par paire, le verdict,
 * et les leçons apprises pour le feedback LLM.
 */
public record PipelineResult(
    StrategySpec spec,
    StrategyProfile profile,
    boolean qualified,
    List<PairResult> pairResults,
    String failureReason,
    List<String> lessonsLearned,
    long durationMs,
    Instant timestamp
) {
    public PipelineResult {
        timestamp = timestamp != null ? timestamp : Instant.now();
        lessonsLearned = lessonsLearned != null ? lessonsLearned : List.of();
        pairResults = pairResults != null ? pairResults : List.of();
    }

    /** Nombre de paires qualifiées selon les critères du profil */
    public int qualifiedPairCount(double minPf, double maxDd, int minTrades) {
        return (int) pairResults.stream()
            .filter(r -> r.qualified(minPf, maxDd, minTrades))
            .count();
    }

    /** PF moyen sur toutes les paires testées */
    public double averagePf() {
        return pairResults.stream()
            .mapToDouble(PairResult::pf)
            .average()
            .orElse(0.0);
    }

    /** Drawdown max sur toutes les paires */
    public double maxDrawdown() {
        return pairResults.stream()
            .mapToDouble(PairResult::dd)
            .max()
            .orElse(100.0);
    }
}
