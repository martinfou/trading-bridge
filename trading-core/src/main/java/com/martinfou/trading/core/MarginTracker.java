package com.martinfou.trading.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Tracks margin requirements and enforces Reg-T equity margin, CME futures margin,
 * and PDT (Pattern Day Trader) rules with forced liquidation simulation.
 */
public class MarginTracker {

    public enum MarginHealth {
        HEALTHY,
        MARGIN_CALL,
        LIQUIDATED
    }

    public record MarginMetrics(
        double totalInitialMargin,
        double totalMaintenanceMargin,
        double availableFunds,
        double marginUtilizationPct,
        MarginHealth health,
        boolean pdtRestricted
    ) {}

    private final double futuresSafetyBufferPct; // e.g. 0.05 (+5%)
    private final boolean pdtEnforced;
    private final double pdtMinimumEquity; // $25,000.00
    private final List<Instant> dayTradeTimestamps = new ArrayList<>();
    private MarginHealth currentHealth = MarginHealth.HEALTHY;

    public MarginTracker() {
        this(0.05, true, 25_000.0);
    }

    public MarginTracker(double futuresSafetyBufferPct, boolean pdtEnforced, double pdtMinimumEquity) {
        this.futuresSafetyBufferPct = Math.max(0.0, futuresSafetyBufferPct);
        this.pdtEnforced = pdtEnforced;
        this.pdtMinimumEquity = pdtMinimumEquity;
    }

    public MarginMetrics evaluate(
        double currentEquity,
        Collection<Position> openPositions,
        double currentPrice,
        Instant currentTimestamp
    ) {
        double initialMarginSum = 0.0;
        double maintenanceMarginSum = 0.0;

        for (Position pos : openPositions) {
            String symbol = pos.symbol();
            AssetValuationModel model = AssetValuationRegistry.resolve(symbol);

            if (model instanceof FuturesValuationModel futModel) {
                FuturesContract contract = futModel.contract();
                double contracts = futModel.validateQuantity(pos.quantity());
                initialMarginSum += contract.initialMargin() * contracts;
                double baseMaint = contract.maintenanceMargin() * contracts;
                maintenanceMarginSum += baseMaint * (1.0 + futuresSafetyBufferPct);
            } else if (model instanceof StockValuationModel stockModel) {
                double shares = stockModel.validateQuantity(pos.quantity());
                double notional = currentPrice > 0 ? currentPrice * shares : pos.entryPrice() * shares;
                // Reg-T 50% initial margin, 25% maintenance margin
                initialMarginSum += notional * 0.50;
                maintenanceMarginSum += notional * 0.25;
            } else {
                // Forex default 2% maintenance margin (50:1 leverage)
                double notional = pos.quantity();
                initialMarginSum += notional * 0.033; // ~30:1
                maintenanceMarginSum += notional * 0.020; // ~50:1
            }
        }

        double available = Math.max(0.0, currentEquity - initialMarginSum);
        double utilization = currentEquity > 0 ? (maintenanceMarginSum / currentEquity) * 100.0 : 100.0;

        boolean pdtRestricted = false;
        if (pdtEnforced && currentEquity < pdtMinimumEquity) {
            cleanOldDayTrades(currentTimestamp);
            if (dayTradeTimestamps.size() >= 3) {
                pdtRestricted = true;
            }
        }

        MarginHealth health;
        if (maintenanceMarginSum > 0 && currentEquity < maintenanceMarginSum) {
            health = MarginHealth.MARGIN_CALL;
            currentHealth = MarginHealth.MARGIN_CALL;
        } else if (currentHealth == MarginHealth.MARGIN_CALL && currentEquity >= maintenanceMarginSum) {
            health = MarginHealth.HEALTHY;
            currentHealth = MarginHealth.HEALTHY;
        } else {
            health = currentHealth;
        }

        return new MarginMetrics(
            initialMarginSum,
            maintenanceMarginSum,
            available,
            utilization,
            health,
            pdtRestricted
        );
    }

    public void recordDayTrade(Instant timestamp) {
        if (timestamp != null) {
            dayTradeTimestamps.add(timestamp);
            cleanOldDayTrades(timestamp);
        }
    }

    public void markLiquidated() {
        this.currentHealth = MarginHealth.LIQUIDATED;
    }

    public MarginHealth currentHealth() {
        return currentHealth;
    }

    private void cleanOldDayTrades(Instant currentTimestamp) {
        if (currentTimestamp == null) return;
        Instant fiveDaysAgo = currentTimestamp.minus(5, java.time.temporal.ChronoUnit.DAYS);
        dayTradeTimestamps.removeIf(t -> t.isBefore(fiveDaysAgo));
    }
}
