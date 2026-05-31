package com.martinfou.trading.parser.bridge;

import java.util.List;

/**
 * Interpreter coverage summary for a parsed SQ document (story 21-3).
 */
public record SqXmlCoverageReport(
    List<String> supportedKeys,
    List<String> deferredKeys,
    List<String> inlineKeys,
    List<String> gapKeys,
    List<String> unknownKeys,
    List<String> unsupportedEntryActions
) {
    public boolean requiresDlq() {
        return !gapKeys.isEmpty() || !unsupportedEntryActions.isEmpty();
    }

    public String dlqReason() {
        if (!gapKeys.isEmpty()) {
            return "GAP blocks: " + String.join(", ", gapKeys);
        }
        if (!unsupportedEntryActions.isEmpty()) {
            return "Unsupported entry actions: " + String.join(", ", unsupportedEntryActions);
        }
        return null;
    }
}
