package com.martinfou.trading.data.ibkr;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe cache for normalized Interactive Brokers account and margin metrics.
 */
public final class IbkrAccountCache {

    public record AccountSummary(
        String accountId,
        double netLiquidation,
        double totalCashBalance,
        double availableFunds,
        double buyingPower,
        double initialMargin,
        double maintenanceMargin,
        double grossPositionValue,
        int dayTradesRemaining,
        boolean pdtRestricted,
        double marginUtilizationPct,
        Instant updatedAt
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("accountId", accountId);
            m.put("netLiquidation", netLiquidation);
            m.put("totalCashBalance", totalCashBalance);
            m.put("availableFunds", availableFunds);
            m.put("buyingPower", buyingPower);
            m.put("initialMargin", initialMargin);
            m.put("maintenanceMargin", maintenanceMargin);
            m.put("grossPositionValue", grossPositionValue);
            m.put("dayTradesRemaining", dayTradesRemaining);
            m.put("pdtRestricted", pdtRestricted);
            m.put("marginUtilizationPct", marginUtilizationPct);
            m.put("updatedAt", updatedAt != null ? updatedAt.toString() : null);
            return m;
        }
    }

    private final String accountId;
    private final Map<String, Double> values = new ConcurrentHashMap<>();
    private volatile Instant lastUpdatedAt = Instant.EPOCH;

    public IbkrAccountCache(String accountId) {
        this.accountId = accountId != null ? accountId : "DU12345";
    }

    public void updateValue(String key, double value) {
        if (key != null) {
            values.put(key, value);
            lastUpdatedAt = Instant.now();
        }
    }

    public void updateFromString(String key, String valueStr) {
        if (key != null && valueStr != null) {
            try {
                double val = Double.parseDouble(valueStr.trim());
                updateValue(key, val);
            } catch (NumberFormatException ignored) {}
        }
    }

    public AccountSummary snapshot() {
        // Fail-closed defaults: a missing/never-received account metric MUST NOT fabricate a
        // healthy $100k account (the previous behaviour). Sizing/margin guards downstream must
        // see zeros and refuse to trade rather than assume phantom capital.
        double netLiq = values.getOrDefault("NetLiquidation", 0.0);
        double cash = values.getOrDefault("TotalCashBalance", netLiq);
        double initMargin = values.getOrDefault("FullInitMarginReq", values.getOrDefault("InitMarginReq", 0.0));
        double maintMargin = values.getOrDefault("FullMaintMarginReq", values.getOrDefault("MaintMarginReq", 0.0));
        double available = values.getOrDefault("AvailableFunds", 0.0);
        double buyingPower = values.getOrDefault("BuyingPower", 0.0);
        double grossPos = values.getOrDefault("GrossPositionValue", 0.0);
        int dayTrades = values.getOrDefault("DayTradesRemaining", 0.0).intValue();

        boolean pdtRestricted = (netLiq < 25_000.0 && dayTrades <= 0);
        double utilization = netLiq > 0 ? (maintMargin / netLiq) * 100.0 : 0.0;

        return new AccountSummary(
            accountId,
            netLiq,
            cash,
            available,
            buyingPower,
            initMargin,
            maintMargin,
            grossPos,
            dayTrades,
            pdtRestricted,
            utilization,
            lastUpdatedAt
        );
    }

    public boolean isFresh(Duration maxAge) {
        return Duration.between(lastUpdatedAt, Instant.now()).compareTo(maxAge) <= 0;
    }
}
