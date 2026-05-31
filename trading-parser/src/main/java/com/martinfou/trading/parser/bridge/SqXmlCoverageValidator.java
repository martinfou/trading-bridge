package com.martinfou.trading.parser.bridge;

import com.martinfou.trading.parser.conditions.SqConditionRegistry;
import com.martinfou.trading.parser.indicators.SqIndicatorRegistry;
import com.martinfou.trading.parser.sq.SqImportedBlockInventory;
import com.martinfou.trading.parser.sq.SqStrategyDocument;
import com.martinfou.trading.parser.sq.SqXmlItem;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Classifies SQ building blocks for inbox DLQ routing (story 21-3). */
public final class SqXmlCoverageValidator {

    private static final Set<String> IGNORED_KEYS = Set.of("", "Param", "Block");

    private SqXmlCoverageValidator() {}

    public static SqXmlCoverageReport analyze(SqStrategyDocument document) {
        Set<String> supported = new LinkedHashSet<>();
        Set<String> deferred = new LinkedHashSet<>();
        Set<String> inline = new LinkedHashSet<>();
        Set<String> gap = new LinkedHashSet<>();
        Set<String> unknown = new LinkedHashSet<>();
        Set<String> badEntries = new LinkedHashSet<>();

        for (SqXmlItem item : document.allItems()) {
            classify(item, supported, deferred, inline, gap, unknown, badEntries);
        }

        return new SqXmlCoverageReport(
            List.copyOf(supported),
            List.copyOf(deferred),
            List.copyOf(inline),
            List.copyOf(gap),
            List.copyOf(unknown),
            List.copyOf(badEntries)
        );
    }

    private static void classify(
        SqXmlItem item,
        Set<String> supported,
        Set<String> deferred,
        Set<String> inline,
        Set<String> gap,
        Set<String> unknown,
        Set<String> badEntries
    ) {
        String key = item.key();
        if (key == null || key.isBlank() || IGNORED_KEYS.contains(key)) {
            return;
        }
        if (item.isEntryAction()) {
            if ("EnterAtStop".equals(key)) {
                supported.add(key);
            } else {
                badEntries.add(key);
            }
            return;
        }
        if (item.isExitAction()) {
            deferred.add(key);
            return;
        }
        if (SqConditionRegistry.isRegistered(key)) {
            if (SqConditionRegistry.isDeferred(key)) {
                deferred.add(key);
            } else {
                supported.add(key);
            }
            return;
        }
        if (item.isIndicator() || "price".equals(item.returnType())) {
            classifyIndicator(key, supported, inline, gap, unknown);
            return;
        }
        if ("boolean".equals(item.returnType()) && "other".equals(item.categoryType())) {
            supported.add(key);
            return;
        }
        unknown.add(key);
    }

    private static void classifyIndicator(
        String key,
        Set<String> supported,
        Set<String> inline,
        Set<String> gap,
        Set<String> unknown
    ) {
        if (SqIndicatorRegistry.supports(key)) {
            supported.add(key);
            return;
        }
        var mapping = SqImportedBlockInventory.bySqItemKey(key);
        if (mapping.isPresent()) {
            switch (mapping.get().support()) {
                case CORE -> supported.add(key);
                case INLINE -> inline.add(key);
                case GAP -> gap.add(key);
            }
            return;
        }
        unknown.add(key);
    }
}
