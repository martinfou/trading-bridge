package com.martinfou.trading.parser.sq;

/** Global stop-loss / profit-target defaults from strategy XML. */
public record SqGlobalSlPt(boolean useSameForBothDirections, int globalStopLoss, int globalProfitTarget) {}
