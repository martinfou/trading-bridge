package com.martinfou.trading.intelligence.agent;

import com.martinfou.trading.core.agent.MarketDirection;

public record SeasonalityData(
    String asset,
    int weekOfYear,
    MarketDirection directionalBias,
    int averagePips
) {}
