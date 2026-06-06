package com.martinfou.trading.intelligence.agent;

import com.martinfou.trading.core.agent.MarketDirection;
import com.martinfou.trading.core.agent.MarketRegime;
import com.martinfou.trading.core.agent.RiskFactors;
import com.martinfou.trading.core.agent.TradeTriggerCondition;
import java.util.List;

public record WeeklyStrategyOutlookRaw(
    String targetAsset,
    MarketDirection bias,
    MarketRegime identifiedRegime,
    double rawSentimentScore,
    double seasonalityWinRate,
    String strategyRationale,
    List<TradeTriggerCondition> setups,
    RiskFactors riskFactors,
    String alphaKillSwitchCondition
) {}
