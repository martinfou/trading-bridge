package com.martinfou.trading.parser.sq;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Crosswalk between StrategyQuant building blocks used in the GBPJPY sqimported catalogue
 * and Trading Bridge implementation targets.
 *
 * @see docs/sqimported/CATALOGUE.md
 * @see docs/sq-xml-format.md
 */
public final class SqImportedBlockInventory {

    public record BlockMapping(
        String catalogueFamily,
        String sqItemKey,
        String bridgeTarget,
        SupportLevel support
    ) {}

    public enum SupportLevel {
        /** Available in {@code com.martinfou.trading.core.indicators.Indicators}. */
        CORE,
        /** Implemented inline in sqimported / JForexConverter helpers. */
        INLINE,
        /** Not yet mapped — parser/codegen gap. */
        GAP
    }

    private static final List<BlockMapping> MAPPINGS = List.of(
        new BlockMapping("A", "ADX", "inline calcADX", SupportLevel.INLINE),
        new BlockMapping("A", "BBRange", "calcBBRange / Indicators.bollinger", SupportLevel.INLINE),
        new BlockMapping("A", "Highest", "calcHighest / inline", SupportLevel.INLINE),
        new BlockMapping("A", "HighestInRange", "LowestInRange/HighestInRange inline", SupportLevel.INLINE),
        new BlockMapping("B", "KeltnerChannel", "Keltner inline (sqimported)", SupportLevel.INLINE),
        new BlockMapping("C", "BiggestRange", "calcBiggestRange", SupportLevel.INLINE),
        new BlockMapping("C", "SmallestRange", "inline smallest range", SupportLevel.GAP),
        new BlockMapping("D", "ADX", "ADX hump pattern inline", SupportLevel.INLINE),
        new BlockMapping("E", "Vortex", "calcVortexPlus/Minus", SupportLevel.INLINE),
        new BlockMapping("F", "LinearRegression", "calcLinReg", SupportLevel.INLINE),
        new BlockMapping("G", "Ichimoku", "not implemented", SupportLevel.GAP),
        new BlockMapping("H", "SuperTrend", "not implemented", SupportLevel.GAP),
        new BlockMapping("I", "LinearRegression", "LinReg cross (Open vs LinReg)", SupportLevel.INLINE),
        new BlockMapping("J", "KeltnerChannel", "Open vs KC upper", SupportLevel.INLINE),
        new BlockMapping("K", "Vortex", "Vortex + trailing exits", SupportLevel.INLINE),
        new BlockMapping("L", "KeltnerChannel", "Daily HIGH + KC", SupportLevel.INLINE),
        new BlockMapping("*", "ATR", "Indicators.atr / calcATR", SupportLevel.CORE),
        new BlockMapping("*", "SMA", "Indicators.sma", SupportLevel.CORE),
        new BlockMapping("*", "EnterAtStop", "Order.Type.STOP BUYSTOP", SupportLevel.INLINE),
        new BlockMapping("*", "LowestInRange", "LowestInRange inline", SupportLevel.INLINE)
    );

    private static final Map<String, BlockMapping> BY_SQ_KEY = indexBySqKey();

    private SqImportedBlockInventory() {}

    public static List<BlockMapping> all() {
        return MAPPINGS;
    }

    public static Optional<BlockMapping> bySqItemKey(String sqItemKey) {
        return Optional.ofNullable(BY_SQ_KEY.get(sqItemKey));
    }

    public static List<BlockMapping> gaps() {
        return MAPPINGS.stream().filter(m -> m.support() == SupportLevel.GAP).toList();
    }

    public static List<BlockMapping> byFamily(String familyCode) {
        return MAPPINGS.stream()
            .filter(m -> m.catalogueFamily().equals(familyCode) || "*".equals(m.catalogueFamily()))
            .toList();
    }

    private static Map<String, BlockMapping> indexBySqKey() {
        Map<String, BlockMapping> map = new LinkedHashMap<>();
        for (BlockMapping mapping : MAPPINGS) {
            map.putIfAbsent(mapping.sqItemKey(), mapping);
        }
        return Map.copyOf(map);
    }
}
