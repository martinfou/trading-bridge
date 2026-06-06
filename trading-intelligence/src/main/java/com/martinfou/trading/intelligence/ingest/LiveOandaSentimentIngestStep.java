package com.martinfou.trading.intelligence.ingest;

import com.martinfou.trading.data.OandaPositionAnalyzer;
import com.martinfou.trading.intelligence.brief.WeeklyIntelBrief;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class LiveOandaSentimentIngestStep implements OandaSentimentIngestStep {

    private static final Logger log = LoggerFactory.getLogger(LiveOandaSentimentIngestStep.class);

    private final String apiKey;
    private final String accountId;
    private final boolean practice;

    public LiveOandaSentimentIngestStep() {
        this(
            System.getenv("OANDA_API_KEY"),
            System.getenv("OANDA_ACCOUNT_ID"),
            true
        );
    }

    LiveOandaSentimentIngestStep(String apiKey, String accountId, boolean practice) {
        this.apiKey = apiKey;
        this.accountId = accountId;
        this.practice = practice;
    }

    @Override
    public Optional<List<WeeklyIntelBrief.OandaRetailEntry>> fetchOptional() {
        if (apiKey == null || apiKey.isBlank() || accountId == null || accountId.isBlank()) {
            log.warn("OANDA credentials not set — sentiment ingest skipped (PARTIAL brief allowed)");
            return Optional.empty();
        }
        try {
            var analyzer = new OandaPositionAnalyzer(apiKey, accountId, practice);
            OandaPositionAnalyzer.MarketSentiment sentiment = analyzer.fetchSentiment();
            List<WeeklyIntelBrief.OandaRetailEntry> entries = new ArrayList<>();
            for (OandaPositionAnalyzer.PositionSentiment pos : sentiment.positions()) {
                entries.add(new WeeklyIntelBrief.OandaRetailEntry(
                    pos.instrument(),
                    pos.longRatio(),
                    pos.shortRatio(),
                    pos.sentimentLabel()
                ));
            }
            log.info("OANDA sentiment ingest: {} instruments", entries.size());
            return Optional.of(entries);
        } catch (Exception ex) {
            log.warn("OANDA sentiment ingest failed — continuing with PARTIAL brief: {}", ex.getMessage());
            return Optional.empty();
        }
    }
}
