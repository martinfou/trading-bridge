package com.martinfou.trading.parser.conditions;

import com.martinfou.trading.parser.sq.SqXmlBlock;
import com.martinfou.trading.parser.sq.SqXmlItem;

import java.util.List;
import java.util.Optional;

final class SqBlockUtils {

    private SqBlockUtils() {}

    static List<SqXmlItem> childItems(SqXmlItem item) {
        return item.blocks().stream().map(SqXmlBlock::item).toList();
    }

    static Optional<SqXmlItem> blockItem(SqXmlItem item, String blockKey) {
        return item.blocks().stream()
            .filter(b -> blockKey.equals(b.blockKey()))
            .map(SqXmlBlock::item)
            .findFirst();
    }

    static Optional<SqXmlItem> blockItem(SqXmlItem item, String firstKey, String secondKey) {
        return blockItem(item, firstKey).or(() -> blockItem(item, secondKey));
    }

    static Optional<String> paramText(SqXmlItem item, String paramKey) {
        return item.params().stream()
            .filter(p -> paramKey.equals(p.key()))
            .map(p -> p.textValue() == null ? "" : p.textValue().trim())
            .filter(s -> !s.isBlank())
            .findFirst();
    }
}
