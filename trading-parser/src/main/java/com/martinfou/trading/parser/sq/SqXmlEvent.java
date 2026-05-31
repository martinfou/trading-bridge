package com.martinfou.trading.parser.sq;

import java.util.List;

/** Event hook such as {@code OnBarUpdate}. */
public record SqXmlEvent(String key, List<SqXmlRule> rules) {}
