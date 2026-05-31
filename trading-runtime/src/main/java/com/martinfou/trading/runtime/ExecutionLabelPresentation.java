package com.martinfou.trading.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * UI presentation metadata for {@link ExecutionLabel} (Story 17.11 / PS-GR11).
 * Shared by control summary, run detail, evidence export, and HTML reports.
 */
public record ExecutionLabelPresentation(
    String id,
    String displayName,
    String category,
    String badgeBackgroundColor,
    String badgeTextColor,
    boolean brokerBacked,
    boolean countsTowardPaperPeriod,
    boolean stubWarning
) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("displayName", displayName);
        map.put("category", category);
        map.put("badgeBackgroundColor", badgeBackgroundColor);
        map.put("badgeTextColor", badgeTextColor);
        map.put("brokerBacked", brokerBacked);
        map.put("countsTowardPaperPeriod", countsTowardPaperPeriod);
        if (stubWarning) {
            map.put("stubWarning", true);
        }
        return Map.copyOf(map);
    }
}
