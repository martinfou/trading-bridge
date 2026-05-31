package com.martinfou.trading.parser.sq;

import java.util.List;
import java.util.Optional;

/** One rule under an event (Signal, IfThen, …). */
public record SqXmlRule(
    String name,
    String type,
    List<SqXmlSignal> signals,
    Optional<SqXmlItem> condition,
    List<SqXmlItem> actions
) {}
