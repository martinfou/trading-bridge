package com.martinfou.trading.intelligence.template;

import java.util.List;

/** Weekly risk limits injected into planner prompts and snapshotted on plans (Epic 22). */
public record RiskBudgetEnvelope(
    int maxPicks,
    double maxLotSize,
    double maxWeeklyDrawdownPct,
    List<String> whitelistPairs
) {
    public static RiskBudgetEnvelope defaults(List<String> whitelistPairs) {
        return new RiskBudgetEnvelope(3, 0.01, 5.0, whitelistPairs == null ? List.of() : List.copyOf(whitelistPairs));
    }
}
