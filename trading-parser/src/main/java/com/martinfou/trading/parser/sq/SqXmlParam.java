package com.martinfou.trading.parser.sq;

import java.util.Optional;

/**
 * Optional formula subtree on a param (e.g. EnterAtStop price from HMA indicator).
 */
public record SqXmlParam(
    String key,
    String type,
    String textValue,
    boolean variableReference,
    SqXmlItem formulaRoot
) {
    public java.util.Optional<SqXmlItem> formulaItem() {
        return java.util.Optional.ofNullable(formulaRoot);
    }
}
