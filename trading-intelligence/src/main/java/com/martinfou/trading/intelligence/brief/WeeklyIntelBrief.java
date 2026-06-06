package com.martinfou.trading.intelligence.brief;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Structured weekly intelligence brief — deterministic input for LLM planning (Epic 22).
 */
public record WeeklyIntelBrief(
    Instant generatedAt,
    LocalDate weekStart,
    List<CalendarEventEntry> calendarEvents,
    List<NewsItemEntry> newsItems,
    List<CotSnapshotEntry> cotSnapshots,
    SentimentBlock sentiment,
    List<ContradictionEntry> contradictions,
    IngestStatus ingestStatus
) {
    public record CalendarEventEntry(
        String eventId,
        String name,
        String currency,
        String impact,
        Instant timeUtc,
        String source
    ) {}

    public record NewsItemEntry(
        String headline,
        String url,
        Instant publishedAt,
        String source
    ) {}

    public record CotSnapshotEntry(
        String instrument,
        double longPct,
        double shortPct,
        Double percentile52w,
        LocalDate asOf
    ) {}

    public record OandaRetailEntry(
        String instrument,
        double longPct,
        double shortPct,
        String label
    ) {}

    public record NlpScoreEntry(
        String topic,
        double score,
        Instant asOf
    ) {}

    public record SentimentBlock(
        List<OandaRetailEntry> oandaRetail,
        List<NlpScoreEntry> nlpScores
    ) {
        public static SentimentBlock empty() {
            return new SentimentBlock(List.of(), List.of());
        }
    }

    public record ContradictionEntry(
        String type,
        String description,
        List<String> pairs
    ) {}
}
