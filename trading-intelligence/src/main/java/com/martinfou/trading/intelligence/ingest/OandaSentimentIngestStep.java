package com.martinfou.trading.intelligence.ingest;

import com.martinfou.trading.intelligence.brief.WeeklyIntelBrief;

import java.util.List;
import java.util.Optional;

public interface OandaSentimentIngestStep {
    /** Empty if credentials missing or fetch skipped. */
    Optional<List<WeeklyIntelBrief.OandaRetailEntry>> fetchOptional();
}
