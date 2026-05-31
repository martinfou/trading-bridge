package com.martinfou.trading.parser.sq;

/**
 * An {@code Item} building block discovered under {@code Rules/Events}.
 */
public record SqXmlBuildingBlock(
    String key,
    String categoryType,
    String returnType
) {}
