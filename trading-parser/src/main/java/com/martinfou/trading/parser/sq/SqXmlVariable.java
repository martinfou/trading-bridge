package com.martinfou.trading.parser.sq;

/**
 * A {@code Strategy → Variables → variable} entry from StrategyQuant strategy XML.
 */
public record SqXmlVariable(
    String id,
    String name,
    String type,
    String value,
    String paramType
) {}
