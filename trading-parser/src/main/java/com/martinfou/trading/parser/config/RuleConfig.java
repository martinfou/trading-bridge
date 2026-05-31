package com.martinfou.trading.parser.config;

import java.util.List;

/** Summary of one rule under an event hook. */
public record RuleConfig(
    String eventKey,
    String name,
    String type,
    List<String> signalVariableIds,
    String conditionRootKey,
    List<String> actionKeys
) {}
