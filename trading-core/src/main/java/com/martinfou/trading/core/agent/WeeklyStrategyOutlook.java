package com.martinfou.trading.core.agent;

import java.util.List;

public record WeeklyStrategyOutlook(
    String targetAsset,
    MarketDirection bias,
    MarketRegime identifiedRegime,
    ComfortLevel comfortLevel,
    double rawSentimentScore,
    double seasonalityWinRate,
    String strategyRationale,
    List<TradeTriggerCondition> setups,
    RiskFactors riskFactors,
    String alphaKillSwitchCondition
) {}
