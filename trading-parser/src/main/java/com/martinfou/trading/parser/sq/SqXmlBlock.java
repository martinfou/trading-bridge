package com.martinfou.trading.parser.sq;

import java.util.List;

/** Nested block slot on an {@link SqXmlItem} (e.g. {@code #Indicator#}). */
public record SqXmlBlock(String blockKey, SqXmlItem item) {}
