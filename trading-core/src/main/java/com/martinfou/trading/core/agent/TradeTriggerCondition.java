package com.martinfou.trading.core.agent;

import com.martinfou.trading.core.Order;
import java.util.Map;

public record TradeTriggerCondition(
    String setupName,
    Order.Side side,
    Order.Type type,
    double targetedPriceZone,
    int invalidationPips,
    Map<String, String> executionContextRules
) {}
