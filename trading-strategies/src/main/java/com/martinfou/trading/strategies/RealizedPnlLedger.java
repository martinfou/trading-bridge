package com.martinfou.trading.strategies;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single writer of realized P&L for a {@link LiveStrategyRunner}.
 *
 * <p><b>Accounting rule (bugfix 2026-09-29).</b> Realized P&L is <em>account currency</em>,
 * <em>broker-sourced</em>, and recorded <em>once per broker trade id</em>. A locally computed
 * estimate lives in the instrument's <em>quote</em> currency (JPY for GBP_JPY, USD for EUR_USD)
 * and can therefore <em>never</em> reach the total — {@link #recordIgnoredLocalEstimate} only
 * bumps a diagnostic counter. Because every entry is keyed by trade id, the async reconciliation
 * cannot double-count the same trade when it overlaps.
 *
 * <p><b>Bounded storage.</b> The ledger keeps an exact total without growing forever: entries that
 * leave the recent window are rolled up into {@link #confirmedTotal()}, and every id ever recorded
 * is kept in a FIFO dedupe set capped at {@link #MAX_RECORDED_IDS}.
 *
 * <p>These guardrails are enforced by construction: the only way a value enters the total is via
 * {@link #recordBrokerPnl}, which dedupes on trade id.
 */
final class RealizedPnlLedger {

    /** Number of most-recent entries kept individually; the integrity watchdog verifies this window. */
    static final int RECENT_WINDOW = 200;

    /**
     * Cap on the dedupe set. Every broker trade id ever recorded is kept here (FIFO eviction) so a
     * repeated reconciliation is rejected and never double-counted.
     *
     * <p><b>Residual risk.</b> If a trade id is evicted from the dedupe set after
     * {@link #MAX_RECORDED_IDS} subsequent trades, a later reconciliation of that same id would be
     * counted again (double count). This is practically unreachable: reconciliation completes within
     * minutes, not after thousands of further trades, so an id leaves the set only long after its
     * trade has been fully reconciled.
     */
    static final int MAX_RECORDED_IDS = 5000;

    /** Rolled-up sum of every entry that has left the recent window. */
    private double confirmedTotal = 0.0;

    /** The most recent entries, in insertion order, capped at {@link #RECENT_WINDOW}. */
    private final Map<String, Double> recent = new LinkedHashMap<>();

    /** Every trade id ever recorded, in insertion order, capped at {@link #MAX_RECORDED_IDS}. */
    private final Set<String> recordedTradeIds = new LinkedHashSet<>();

    private int ignoredLocalEstimates = 0;

    /**
     * Records a broker-sourced, account-currency realized P&L for a trade. Exactly one entry is
     * kept per broker trade id: a null/blank id or a repeat returns {@code false} and changes
     * nothing (dedupe = no double count). Entries that leave the recent window are rolled into
     * {@link #confirmedTotal()} so the total stays exact while the window stays bounded.
     *
     * @return {@code true} when the amount was recorded (the trade's P&L moved the total),
     *         {@code false} when it was rejected and must not be counted.
     */
    synchronized boolean recordBrokerPnl(String tradeId, double amount) {
        if (tradeId == null || tradeId.isBlank()) {
            return false;
        }
        if (!Double.isFinite(amount)) {
            return false;
        }
        if (recordedTradeIds.contains(tradeId)) {
            return false;
        }
        recordedTradeIds.add(tradeId);
        while (recordedTradeIds.size() > MAX_RECORDED_IDS) {
            Iterator<String> it = recordedTradeIds.iterator();
            it.next();
            it.remove();
        }
        recent.put(tradeId, amount);
        while (recent.size() > RECENT_WINDOW) {
            Map.Entry<String, Double> oldest = recent.entrySet().iterator().next();
            confirmedTotal += oldest.getValue();
            recent.remove(oldest.getKey());
        }
        return true;
    }

    /** Records that a local quote-currency estimate was (correctly) ignored — counter only. */
    synchronized void recordIgnoredLocalEstimate() {
        ignoredLocalEstimates++;
    }

    /** Exact sum of all recorded broker-sourced realized P&L, in the account currency. */
    synchronized double total() {
        double sum = confirmedTotal;
        for (double v : recent.values()) {
            sum += v;
        }
        return sum;
    }

    /** Number of distinct broker trades whose P&L is recorded (dedupe-set size, capped). */
    synchronized int trades() {
        return recordedTradeIds.size();
    }

    /** Number of entries currently held individually in the recent window. */
    synchronized int recentSize() {
        return recent.size();
    }

    /** Number of local quote-currency estimates that were ignored. */
    synchronized int ignoredLocalEstimates() {
        return ignoredLocalEstimates;
    }

    /** Rolled-up sum of entries that have left the recent window (persisted separately). */
    synchronized double confirmedTotal() {
        return confirmedTotal;
    }

    /** Immutable copy of the recent window in insertion order — what the integrity watchdog verifies. */
    synchronized Map<String, Double> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(recent));
    }

    /** Immutable copy of every recorded trade id, in insertion order, for persistence. */
    synchronized List<String> recordedIds() {
        return new ArrayList<>(recordedTradeIds);
    }

    /** Replaces the whole ledger from a previously saved snapshot (restored under the v3 gate). */
    synchronized void restore(double confirmedTotal, Map<String, Double> recentEntries,
                              Collection<String> recordedIds, int ignoredLocalEstimates) {
        this.confirmedTotal = confirmedTotal;
        this.recent.clear();
        if (recentEntries != null) {
            this.recent.putAll(recentEntries);
        }
        this.recordedTradeIds.clear();
        if (recordedIds != null) {
            this.recordedTradeIds.addAll(recordedIds);
        }
        this.ignoredLocalEstimates = ignoredLocalEstimates;
    }
}
