package com.martinfou.trading.parser.config;

/** Global SL/PT defaults from {@code GlobalSLPT}. */
public record GlobalExitConfig(boolean useSameForBothDirections, int globalStopLoss, int globalProfitTarget) {}
