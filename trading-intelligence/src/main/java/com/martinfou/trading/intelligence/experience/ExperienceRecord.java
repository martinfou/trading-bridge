package com.martinfou.trading.intelligence.experience;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * A single record in the experience store.
 * JSON-serializable (Jackson), one per line in the JSONL file.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExperienceRecord(
    String strategyName,
    String category,
    boolean qualified,
    double avgPf,
    double maxDd,
    int totalPairs,
    int qualifiedPairs,
    String failureReason,
    List<String> lessons,
    Instant timestamp
) {}
