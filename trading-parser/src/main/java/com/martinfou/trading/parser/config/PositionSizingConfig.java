package com.martinfou.trading.parser.config;

import java.util.Map;

/** Position sizing from {@code MoneyManagement}. */
public record PositionSizingConfig(String type, Map<String, String> params) {
    public double fixedSizeOr(double defaultLots) {
        String raw = params.get("#Size#");
        if (raw == null || raw.isBlank()) {
            return defaultLots;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return defaultLots;
        }
    }
}
