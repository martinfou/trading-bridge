package com.martinfou.trading.core.agent;

public record RiskFactors(
    boolean macroEventConflict,
    boolean sentimentDivergence,
    String coreFrictionDetails
) {}
