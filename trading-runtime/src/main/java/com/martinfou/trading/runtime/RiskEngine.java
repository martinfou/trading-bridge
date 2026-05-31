package com.martinfou.trading.runtime;

import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Position;

import java.time.Instant;
import java.util.List;

/**
 * Pre-trade risk guards before broker submission (Story 16.8 / PS-GR7).
 * Shared with Epic 17 circuit-breaker extensions.
 */
public final class RiskEngine {

    private final RiskLimits limits;

    public RiskEngine() {
        this(RiskLimits.loadDefault());
    }

    public RiskEngine(RiskLimits limits) {
        this.limits = limits != null ? limits : RiskLimits.DEFAULT;
    }

    public RiskLimits limits() {
        return limits;
    }

    public RiskCheckResult checkPreTrade(Order order, List<Position> openPositions) {
        if (order == null) {
            return RiskCheckResult.fail("order", "Order is required", 0, 0);
        }
        double orderQty = order.quantity();
        if (orderQty <= 0) {
            return RiskCheckResult.fail("order", "Order quantity must be positive", 0, orderQty);
        }

        double currentQty = openPositions.stream()
            .filter(p -> p.symbol().equals(order.symbol()) && p.side() == order.side())
            .mapToDouble(Position::quantity)
            .sum();
        double projectedQty = currentQty + orderQty;
        if (projectedQty > limits.maxPositionSize()) {
            return RiskCheckResult.fail(
                "max_position_size",
                "Projected position " + projectedQty + " exceeds max " + limits.maxPositionSize(),
                limits.maxPositionSize(),
                projectedQty);
        }

        double markPrice = order.price() > 0 ? order.price() : 1.0;
        double orderNotional = Math.abs(orderQty * markPrice);
        double openExposure = openPositions.stream()
            .mapToDouble(p -> Math.abs(p.quantity() * p.entryPrice()))
            .sum();
        double projectedExposure = openExposure + orderNotional;
        if (projectedExposure > limits.maxOpenExposure()) {
            return RiskCheckResult.fail(
                "max_open_exposure",
                "Projected exposure " + projectedExposure + " exceeds max " + limits.maxOpenExposure(),
                limits.maxOpenExposure(),
                projectedExposure);
        }

        return RiskCheckResult.pass();
    }

    /**
     * Intraday peak-to-trough drawdown vs configured {@link RiskLimits#maxDailyDrawdownPct()}.
     * Disabled when limit {@code <= 0}.
     */
    public RiskCheckResult checkDailyDrawdown(
        DailyDrawdownTracker tracker,
        double currentEquity,
        Instant asOf
    ) {
        tracker.update(asOf, currentEquity);
        double drawdownPct = tracker.drawdownPct(currentEquity);
        if (!limits.dailyDrawdownGuardEnabled()) {
            return RiskCheckResult.pass();
        }
        double threshold = limits.maxDailyDrawdownPct();
        if (drawdownPct > threshold) {
            return RiskCheckResult.fail(
                "max_daily_drawdown_pct",
                "DAILY_DD_BREACH: drawdown " + drawdownPct + "% exceeds " + threshold + "%",
                threshold,
                drawdownPct);
        }
        return RiskCheckResult.pass();
    }
}
