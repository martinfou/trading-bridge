package com.martinfou.trading.intelligence.pipeline;

import com.martinfou.trading.core.agent.PairResult;
import com.martinfou.trading.core.agent.PipelineResult;
import com.martinfou.trading.core.agent.ValidationProfile;

/**
 * Validation profile for NEWS_WEEKLY strategies.
 *
 * These are event-driven (NFP, CPI, central banks) with no historical
 * backtest — they trade the event itself. Validation checks:
 * - Strategy has a clear event trigger
 * - SL is wide enough for the expected volatility
 * - Direction is clearly defined
 *
 * Backtest metrics are NOT used for qualification; this is a structural
 * validation of the strategy concept.
 */
public class NewsWeeklyValidator implements ValidationProfile {

    private static final String[] PAIRS = {"EUR_USD"};

    @Override
    public String name() {
        return "NEWS_WEEKLY";
    }

    @Override
    public boolean qualifies(PipelineResult result) {
        var spec = result.spec();
        if (spec == null) return false;

        // Must have a clear entry condition referencing an event or news
        boolean hasLongEntry = spec.longEntry() != null && !spec.longEntry().isBlank()
            && !spec.longEntry().equals("false");
        boolean hasShortEntry = spec.shortEntry() != null && !spec.shortEntry().isBlank()
            && !spec.shortEntry().equals("false");

        if (!hasLongEntry && !hasShortEntry) return false;

        // Must have a reasonable SL (news events need room)
        if (spec.slMultiplier() < 2.0) return false;

        // Must have an inspiration/event reference
        if (spec.inspiration() == null || spec.inspiration().isBlank()
            || spec.inspiration().equals("LLM-generated")) return false;

        return true;
    }

    @Override
    public String whyRejected(PipelineResult result) {
        var spec = result.spec();
        if (spec == null) return "No strategy spec provided";

        var sb = new StringBuilder();
        boolean hasLongEntry = spec.longEntry() != null && !spec.longEntry().isBlank()
            && !spec.longEntry().equals("false");
        boolean hasShortEntry = spec.shortEntry() != null && !spec.shortEntry().isBlank()
            && !spec.shortEntry().equals("false");

        if (!hasLongEntry && !hasShortEntry) {
            sb.append("No entry condition defined\n");
        }
        if (spec.slMultiplier() < 2.0) {
            sb.append(String.format("SL multiplier %.1f too tight for news events (min: 2.0)\n", spec.slMultiplier()));
        }
        if (spec.inspiration() == null || spec.inspiration().isBlank() || spec.inspiration().equals("LLM-generated")) {
            sb.append("Missing event reference in inspiration field\n");
        }
        return sb.toString();
    }

    @Override
    public String[] requiredPairs() {
        return PAIRS;
    }

    @Override
    public double referenceCapital() {
        return 10_000;
    }
}
