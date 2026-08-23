package com.martinfou.trading.core;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Pre-trade risk filter and small-account margin protection guard (Story 46.5).
 *
 * <p>Enforces strict 1-micro contract unit sizing, a hard ceiling of 2 concurrent open positions,
 * and a 50% maximum account initial margin shield to prevent margin calls and forced liquidations
 * on accounts with $3,000–$10,000 equity.</p>
 */
public final class SmallAccountMarginGuard {

    public static final double DEFAULT_MAX_MARGIN_UTILIZATION = 0.50; // 50% max
    public static final int DEFAULT_MAX_CONCURRENT_POSITIONS = 2;

    public record ValidationResult(
        boolean allowed,
        String reason,
        double approvedQuantity,
        double requiredMarginUsd,
        double totalProjectedMarginUsd,
        double marginUtilizationPct
    ) {
        public static ValidationResult approved(double qty, double reqMargin, double totalProjMargin, double utilPct) {
            return new ValidationResult(true, "APPROVED", qty, reqMargin, totalProjMargin, utilPct);
        }

        public static ValidationResult rejected(String reason, double totalProjMargin, double utilPct) {
            return new ValidationResult(false, reason, 0.0, 0.0, totalProjMargin, utilPct);
        }
    }

    private final double maxMarginUtilization;
    private final int maxConcurrentPositions;
    private final double tierScaleStepUsd;

    public SmallAccountMarginGuard() {
        this(DEFAULT_MAX_MARGIN_UTILIZATION, DEFAULT_MAX_CONCURRENT_POSITIONS, 5000.0);
    }

    public SmallAccountMarginGuard(double maxMarginUtilization, int maxConcurrentPositions, double tierScaleStepUsd) {
        this.maxMarginUtilization = Math.max(0.10, Math.min(1.0, maxMarginUtilization));
        this.maxConcurrentPositions = Math.max(1, maxConcurrentPositions);
        this.tierScaleStepUsd = Math.max(1000.0, tierScaleStepUsd);
    }

    /**
     * Validates an order against account equity and current open positions.
     */
    public ValidationResult validateOrder(Order order, double accountEquity, List<Position> openPositions) {
        Objects.requireNonNull(order, "order cannot be null");
        if (order.isCloseOnly()) {
            // Close orders reduce exposure, always allowed
            return ValidationResult.approved(order.quantity(), 0.0, 0.0, 0.0);
        }

        if (accountEquity <= 0) {
            return ValidationResult.rejected("ACCOUNT_EQUITY_ZERO_OR_NEGATIVE", 0.0, 1.0);
        }

        List<Position> activePositions = openPositions != null ? openPositions : List.of();

        // 1. Concurrency limit check
        if (activePositions.size() >= maxConcurrentPositions) {
            return ValidationResult.rejected("CONCURRENT_POSITION_LIMIT_REACHED (active=" + activePositions.size() + ", max=" + maxConcurrentPositions + ")", 0.0, 0.0);
        }

        // 2. Resolve contract initial margin
        Optional<FuturesContract> futOpt = FuturesRegistry.find(order.symbol());
        double perContractMargin = futOpt.map(FuturesContract::initialMargin).orElse(1200.0);

        // 3. Determine max allowed contract size based on equity tier
        int maxAllowedUnits = Math.max(1, (int) Math.floor(accountEquity / tierScaleStepUsd));
        double targetQuantity = Math.min(order.quantity(), (double) maxAllowedUnits);
        if (targetQuantity < 1.0) {
            targetQuantity = 1.0;
        }

        // 4. Calculate current locked margin
        double currentLockedMargin = 0.0;
        for (Position p : activePositions) {
            double margin = FuturesRegistry.find(p.symbol()).map(FuturesContract::initialMargin).orElse(1200.0);
            currentLockedMargin += margin * Math.max(1.0, p.quantity());
        }

        // 5. Calculate projected margin utilization
        double newPositionMargin = targetQuantity * perContractMargin;
        double totalProjectedMargin = currentLockedMargin + newPositionMargin;
        double utilizationPct = totalProjectedMargin / accountEquity;

        if (utilizationPct > maxMarginUtilization) {
            return ValidationResult.rejected(
                String.format("MARGIN_SHIELD_EXCEEDED: Projected margin $%.2f is %.1f%% of equity $%.2f (limit: %.1f%%)",
                    totalProjectedMargin, utilizationPct * 100.0, accountEquity, maxMarginUtilization * 100.0),
                totalProjectedMargin,
                utilizationPct
            );
        }

        return ValidationResult.approved(targetQuantity, newPositionMargin, totalProjectedMargin, utilizationPct);
    }
}
