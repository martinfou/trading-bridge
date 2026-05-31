package com.martinfou.trading.parser.sq;

/** Signal rule output wired to a {@link SqXmlVariable} id. */
public record SqXmlSignal(String variableId, SqXmlItem rootItem) {}
