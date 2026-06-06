package com.martinfou.trading.intelligence.research;

import java.time.Instant;

/** Represents a strategy inspiration item to be researched and backtested. */
public record ResearchInspiration(
    String id,
    String title,
    String description,
    String status,         // PENDING, RUNNING, COMPLETED, FAILED
    String resultStatus,   // PASS, FAIL, COMPILE_ERROR
    String strategyId,
    String metricsJson,    // JSON string summarizing backtest metrics
    Instant createdAt,
    Instant updatedAt
) {
    public ResearchInspiration {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("id must not be empty");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title must not be empty");
        if (description == null) throw new IllegalArgumentException("description must not be null");
        if (status == null) throw new IllegalArgumentException("status must not be null");
        if (createdAt == null) throw new IllegalArgumentException("createdAt must not be null");
        if (updatedAt == null) throw new IllegalArgumentException("updatedAt must not be null");
    }
}
