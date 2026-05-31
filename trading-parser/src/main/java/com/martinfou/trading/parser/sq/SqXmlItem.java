package com.martinfou.trading.parser.sq;

import java.util.List;

/**
 * One StrategyQuant building-block node ({@code Item} element) with params and nested blocks.
 */
public record SqXmlItem(
    String key,
    String name,
    String display,
    String categoryType,
    String returnType,
    List<SqXmlParam> params,
    List<SqXmlBlock> blocks
) {
    public boolean isEntryAction() {
        return key.startsWith("EnterAt") && "order".equals(returnType);
    }

    public boolean isExitAction() {
        return "CloseAllPositions".equals(key);
    }

    public boolean isIndicator() {
        return "indicator".equals(categoryType);
    }
}
