package com.martinfou.trading.intelligence.ingest;

import com.martinfou.trading.data.COTDataFetcher;
import com.martinfou.trading.intelligence.brief.WeeklyIntelBrief;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public final class LiveCotIngestStep implements CotIngestStep {

    private static final Logger log = LoggerFactory.getLogger(LiveCotIngestStep.class);

    private final COTDataFetcher fetcher;

    public LiveCotIngestStep() {
        this(new COTDataFetcher());
    }

    LiveCotIngestStep(COTDataFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public List<WeeklyIntelBrief.CotSnapshotEntry> fetch() throws Exception {
        List<COTDataFetcher.COTPosition> positions = fetcher.fetchAll();
        List<WeeklyIntelBrief.CotSnapshotEntry> out = new ArrayList<>();
        for (COTDataFetcher.COTPosition pos : positions) {
            double ratio = pos.speculatorRatio();
            double longPct = ratio / (1.0 + ratio) * 100.0;
            double shortPct = 100.0 - longPct;
            out.add(new WeeklyIntelBrief.CotSnapshotEntry(
                pos.pair(),
                longPct,
                shortPct,
                null,
                pos.reportDate()
            ));
        }
        log.info("COT ingest: {} snapshots", out.size());
        return out;
    }
}
