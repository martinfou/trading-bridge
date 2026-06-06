package com.martinfou.trading.intelligence.deploy;

import java.util.List;

public record WeeklyDeployResult(
    boolean success,
    String weekId,
    String correlationId,
    boolean noTradeWeek,
    List<String> runIds,
    String error
) {
    public static WeeklyDeployResult noTradeWeek(String weekId, String correlationId) {
        return new WeeklyDeployResult(true, weekId, correlationId, true, List.of(), null);
    }

    public static WeeklyDeployResult deployed(String weekId, String correlationId, List<String> runIds) {
        return new WeeklyDeployResult(true, weekId, correlationId, false, List.copyOf(runIds), null);
    }

    public static WeeklyDeployResult failed(String weekId, String correlationId, String error) {
        return new WeeklyDeployResult(false, weekId, correlationId, false, List.of(), error);
    }

    public static WeeklyDeployResult skipped(String message) {
        return new WeeklyDeployResult(false, null, null, false, List.of(), message);
    }
}
