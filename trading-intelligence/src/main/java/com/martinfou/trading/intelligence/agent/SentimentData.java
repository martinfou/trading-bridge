package com.martinfou.trading.intelligence.agent;

import java.util.List;

public record SentimentData(
    String asset,
    double sentimentScore,
    String retailRatioString,
    List<String> newsHeadlines
) {}
