package com.martinfou.trading.core.agent;

/**
 * Interface polymorphique pour les profils de validation.
 * Chaque profil (LONG_TERM, PROP_SHOP, NEWS_WEEKLY) implémente
 * ses propres règles de qualification.
 */
public interface ValidationProfile {

    /** Nom lisible du profil (ex: "LONG_TERM", "PROP_SHOP") */
    String name();

    /** Évalue si un résultat de backtest passe les critères du profil */
    boolean qualifies(PipelineResult result);

    /**
     * Message d'échec clair, utilisable comme feedback LLM.
     * Ex: "PF 0.89 < 1.05 minimum, trades 45 < 100 minimum"
     */
    String whyRejected(PipelineResult result);

    /** Paires à tester pour ce profil */
    String[] requiredPairs();

    /** Capital de référence pour le backtest */
    default double referenceCapital() { return 10_000; }

    /** Commission en devise par trade */
    default double commissionPerTrade() { return 0.07; }

    /** Slippage en devise par trade */
    default double slippagePerTrade() { return 0.0; }
}
