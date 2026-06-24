package com.martinfou.trading.backtest.reconciliation;

import com.martinfou.trading.core.Order;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

public final class TradeReconciler {

    public List<ReconciliationAnomaly> reconcile(
        List<Order> backtestOrders,
        List<Order> liveOrders,
        ReconciliationConfig config
    ) {
        List<ReconciliationAnomaly> anomalies = new ArrayList<>();
        
        // Lists of unmatched orders
        List<Order> unmatchedBt = new ArrayList<>(backtestOrders);
        List<Order> unmatchedLive = new ArrayList<>(liveOrders);
        
        // Step 1: Match by correlationId
        Iterator<Order> btIter = unmatchedBt.iterator();
        while (btIter.hasNext()) {
            Order bt = btIter.next();
            if (bt.correlationId() != null && !bt.correlationId().isBlank()) {
                Optional<Order> matchedLive = unmatchedLive.stream()
                    .filter(l -> bt.correlationId().equals(l.correlationId()))
                    .findFirst();
                if (matchedLive.isPresent()) {
                    Order live = matchedLive.get();
                    unmatchedLive.remove(live);
                    btIter.remove();
                    checkDrift(bt, live, config, anomalies);
                }
            }
        }
        
        // Step 2: Match by proximity fallback (same symbol, same side, closest time within 5 minutes)
        class CandidatePair implements Comparable<CandidatePair> {
            final Order bt;
            final Order live;
            final long diffMs;
            
            CandidatePair(Order bt, Order live, long diffMs) {
                this.bt = bt;
                this.live = live;
                this.diffMs = diffMs;
            }
            
            @Override
            public int compareTo(CandidatePair o) {
                return Long.compare(this.diffMs, o.diffMs);
            }
        }
        
        List<CandidatePair> candidates = new ArrayList<>();
        for (Order bt : unmatchedBt) {
            for (Order live : unmatchedLive) {
                if (Objects.equals(bt.symbol(), live.symbol()) && bt.side() == live.side()) {
                    Instant btTime = bt.filledAt() != null ? bt.filledAt() : bt.createdAt();
                    Instant liveTime = live.filledAt() != null ? live.filledAt() : live.createdAt();
                    long diffMs = Math.abs(Duration.between(btTime, liveTime).toMillis());
                    if (diffMs < 300_000) { // 5 minutes window
                        candidates.add(new CandidatePair(bt, live, diffMs));
                    }
                }
            }
        }
        
        Collections.sort(candidates);
        
        Set<String> matchedBtIds = new HashSet<>();
        Set<String> matchedLiveIds = new HashSet<>();
        for (CandidatePair pair : candidates) {
            if (!matchedBtIds.contains(pair.bt.id()) && !matchedLiveIds.contains(pair.live.id())) {
                matchedBtIds.add(pair.bt.id());
                matchedLiveIds.add(pair.live.id());
                unmatchedBt.remove(pair.bt);
                unmatchedLive.remove(pair.live);
                checkDrift(pair.bt, pair.live, config, anomalies);
            }
        }
        
        // Step 3: Any remaining unmatched BT are MISSING_LIVE
        for (Order bt : unmatchedBt) {
            anomalies.add(new ReconciliationAnomaly(
                ReconciliationAnomaly.AnomalyType.MISSING_LIVE,
                bt.id(),
                "Order present in Backtest but missing in Live execution",
                0.0,
                0L
            ));
        }
        
        // Step 4: Any remaining unmatched Live are GHOST_LIVE
        for (Order live : unmatchedLive) {
            anomalies.add(new ReconciliationAnomaly(
                ReconciliationAnomaly.AnomalyType.GHOST_LIVE,
                live.id(),
                "Ghost trade: Order executed in Live but missing in Backtest strategy logic",
                0.0,
                0L
            ));
        }
        
        return anomalies;
    }
    
    private void checkDrift(Order bt, Order live, ReconciliationConfig config, List<ReconciliationAnomaly> anomalies) {
        Instant btTime = bt.filledAt() != null ? bt.filledAt() : bt.createdAt();
        Instant liveTime = live.filledAt() != null ? live.filledAt() : live.createdAt();
        
        long diffSec = Math.abs(Duration.between(btTime, liveTime).toSeconds());
        long diffMs = Math.abs(Duration.between(btTime, liveTime).toMillis());
        double priceDiff = Math.abs(bt.price() - live.price());
        
        if (diffSec > config.maxTimeDriftSeconds()) {
            anomalies.add(new ReconciliationAnomaly(
                ReconciliationAnomaly.AnomalyType.TIME_DRIFT,
                live.id(),
                String.format("Time drift: execution delay of %d seconds (max allowed: %d)", diffSec, config.maxTimeDriftSeconds()),
                priceDiff,
                diffMs
            ));
        }
        
        double allowedDrift = bt.priceDriftLimit() > 0.0 ? bt.priceDriftLimit() : config.maxPriceDrift();
        if (priceDiff > allowedDrift) {
            anomalies.add(new ReconciliationAnomaly(
                ReconciliationAnomaly.AnomalyType.PRICE_DRIFT,
                live.id(),
                String.format("Price drift: execution price delta of %.5f (max allowed: %.5f)", priceDiff, allowedDrift),
                priceDiff,
                diffMs
            ));
        }
    }
}
