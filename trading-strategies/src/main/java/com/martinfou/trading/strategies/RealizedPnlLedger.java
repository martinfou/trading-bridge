package com.martinfou.trading.strategies;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

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
 * <p>These guardrails are enforced by construction: the only way a value enters the total is via
 * {@link #recordBrokerPnl}, which dedupes on trade id.
 */
final class RealizedPnlLedger {

    private final Map<String, Double> entries = new LinkedHashMap<>();
    private int ignoredLocalEstimates = 0;

    /**
     * Records a broker-sourced, account-currency realized P&L for a trade. Exactly one entry is
     * kept per broker trade id: a null/blank id or a repeat returns {@code false} and changes
     * nothing (dedupe = no double count).
     *
     * @return {@code true} when the amount was recorded (the trade's P&L moved the total),
     *         {@code false} when it was rejected and must not be counted.
     */
    synchronized boolean recordBrokerPnl(String tradeId, double amount) {
        if (tradeId == null || tradeId.isBlank()) {
            return false;
        }
        if (entries.containsKey(tradeId)) {
            return false;
        }
        entries.put(tradeId, amount);
        return true;
    }

    /**
     * Records a local quote-currency estimate that must NOT move the total. Only the counter is
     * bumped, so we can observe how often an estimate was (correctly) ignored.
     */
    synchronized void recordIgnoredLocalEstimate(String tradeId, double quoteCcyAmount) {
        ignoredLocalEstimates++;
    }

    /** Sum of all recorded broker-sourced realized P&L, in the account currency. */
    synchronized double total() {
        double sum = 0.0;
        for (double v : entries.values()) {
            sum += v;
        }
        return sum;
    }

    /** Number of trades whose broker P&L is recorded. */
    synchronized int trades() {
        return entries.size();
    }

    /** Number of local quote-currency estimates that were ignored. */
    synchronized int ignoredLocalEstimates() {
        return ignoredLocalEstimates;
    }

    /** Immutable copy of the ledger in insertion order, for persistence. */
    synchronized Map<String, Double> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    /** Replaces the ledger contents with a previously saved snapshot. */
    synchronized void restore(Map<String, Double> saved) {
        entries.clear();
        if (saved != null) {
            entries.putAll(saved);
        }
    }
}
