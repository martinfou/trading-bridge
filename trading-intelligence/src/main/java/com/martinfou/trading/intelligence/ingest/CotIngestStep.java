package com.martinfou.trading.intelligence.ingest;

import com.martinfou.trading.intelligence.brief.WeeklyIntelBrief;

import java.util.List;

@FunctionalInterface
public interface CotIngestStep {
    List<WeeklyIntelBrief.CotSnapshotEntry> fetch() throws Exception;
}
