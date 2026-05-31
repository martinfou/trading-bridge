package com.martinfou.trading.runtime;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Canonical styling catalog for all execution labels (Story 17.11). */
public final class ExecutionLabelCatalog {

    private ExecutionLabelCatalog() {}

    public static ExecutionLabelPresentation of(ExecutionLabel label) {
        return switch (label) {
            case BACKTEST -> new ExecutionLabelPresentation(
                label.name(),
                "Backtest",
                "SIMULATION",
                "#64748b",
                "#ffffff",
                false,
                false,
                false);
            case PAPER_STUB -> new ExecutionLabelPresentation(
                label.name(),
                "Paper (stub)",
                "PAPER_STUB",
                "#d97706",
                "#ffffff",
                false,
                false,
                true);
            case PAPER_OANDA -> new ExecutionLabelPresentation(
                label.name(),
                "Paper OANDA",
                "BROKER_PAPER",
                "#2563eb",
                "#ffffff",
                true,
                true,
                false);
            case PAPER_IBKR -> new ExecutionLabelPresentation(
                label.name(),
                "Paper IBKR",
                "BROKER_PAPER",
                "#7c3aed",
                "#ffffff",
                true,
                false,
                false);
            case LIVE_OANDA -> new ExecutionLabelPresentation(
                label.name(),
                "Live OANDA",
                "BROKER_LIVE",
                "#dc2626",
                "#ffffff",
                true,
                false,
                false);
            case LIVE_IBKR -> new ExecutionLabelPresentation(
                label.name(),
                "Live IBKR",
                "BROKER_LIVE",
                "#991b1b",
                "#ffffff",
                true,
                false,
                false);
        };
    }

    public static Map<String, Object> catalogMap() {
        Map<String, Object> catalog = new LinkedHashMap<>();
        for (ExecutionLabel label : ExecutionLabel.values()) {
            catalog.put(label.name(), of(label).toMap());
        }
        return Map.copyOf(catalog);
    }

    public static List<Map<String, Object>> catalogList() {
        return Arrays.stream(ExecutionLabel.values())
            .map(label -> of(label).toMap())
            .toList();
    }
}
