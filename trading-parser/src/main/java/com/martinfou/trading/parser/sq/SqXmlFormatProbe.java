package com.martinfou.trading.parser.sq;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Extracts structural metadata from StrategyQuant {@code StrategyFile} XML.
 * Delegates parsing to {@link SqXmlParser}.
 *
 * @see docs/sq-xml-format.md
 */
public final class SqXmlFormatProbe {

    private SqXmlFormatProbe() {}

    public static SqXmlFormatReport analyze(Path xmlPath) throws IOException {
        try {
            return analyze(SqXmlParser.parse(xmlPath));
        } catch (SqXmlParseException e) {
            throw new IOException("Failed to analyze SQ XML: " + xmlPath, e);
        }
    }

    public static SqXmlFormatReport analyze(InputStream xmlStream) throws Exception {
        return analyze(SqXmlParser.parse(xmlStream));
    }

    public static SqXmlFormatReport analyze(SqStrategyDocument document) {
        Set<String> signalIds = new LinkedHashSet<>();
        Set<String> blockKeys = new LinkedHashSet<>();
        List<SqXmlBuildingBlock> blocks = new ArrayList<>();
        List<String> entryActions = new ArrayList<>();

        for (SqXmlItem item : document.allItems()) {
            if (!item.key().isBlank() && blockKeys.add(item.key())) {
                blocks.add(new SqXmlBuildingBlock(item.key(), item.categoryType(), item.returnType()));
            }
            if (item.isEntryAction()) {
                entryActions.add(item.key());
            }
        }

        for (SqXmlEvent event : document.events()) {
            for (SqXmlRule rule : event.rules()) {
                for (SqXmlSignal signal : rule.signals()) {
                    signalIds.add(signal.variableId());
                }
            }
        }

        return new SqXmlFormatReport(
            document.strategyFileVersion(),
            document.engine(),
            document.variables(),
            List.copyOf(signalIds),
            List.copyOf(blocks),
            List.copyOf(entryActions)
        );
    }
}
