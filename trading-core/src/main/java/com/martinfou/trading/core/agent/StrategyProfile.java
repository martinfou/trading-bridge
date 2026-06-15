package com.martinfou.trading.core.agent;

/**
 * Profiles de validation pour le Unified Strategy Engine.
 * Chaque profil définit des critères de qualification différents
 * adaptés à un horizon de trading spécifique.
 */
public enum StrategyProfile {
    /** Walk-forward 15+ ans, 4 paires majeures, PF ≥ 1.05 OOS */
    LONG_TERM,
    /** Scoring 15pts, soft signals, HMM, 9 paires */
    PROP_SHOP,
    /** Événement calendrier, 1 semaine, SL large */
    NEWS_WEEKLY
}
