package com.martinfou.trading.broker;

import com.martinfou.trading.core.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Idempotent order lifecycle and partial-fill execution manager (Story 46.3).
 *
 * <p>Prevents duplicate order submissions across network timeouts, aggregates partial fills
 * into volume-weighted average prices, and cleanly pauses strategy emission on margin rejections.</p>
 */
public final class IbkrOrderExecutionManager {

    private static final Logger log = LoggerFactory.getLogger(IbkrOrderExecutionManager.class);

    public record ExecutionTracker(
        String orderId,
        String orderTag,
        double requestedQuantity,
        double cumulativeFilledQuantity,
        double volumeWeightedPrice,
        Order.Status status,
        String lastError
    ) {
        public boolean isComplete() {
            return cumulativeFilledQuantity >= requestedQuantity;
        }
    }

    private final Set<String> processedOrderTags = ConcurrentHashMap.newKeySet();
    private final Map<String, ExecutionTracker> activeTrackers = new ConcurrentHashMap<>();
    private final Map<String, Boolean> strategyMarginPaused = new ConcurrentHashMap<>();

    /**
     * Checks if an order can be submitted (idempotency validation and margin pause check).
     */
    public boolean canSubmitOrder(Order order, String orderTag) {
        Objects.requireNonNull(order, "order is required");
        if (orderTag == null || orderTag.isBlank()) {
            return true;
        }

        if (order.strategyId() != null && strategyMarginPaused.getOrDefault(order.strategyId(), false)) {
            log.warn("Submission blocked: Strategy '{}' is currently MARGIN_PAUSED due to prior margin rejection.",
                order.strategyId());
            return false;
        }

        if (!processedOrderTags.add(orderTag)) {
            log.warn("Duplicate order submission blocked for tag: '{}'", orderTag);
            return false;
        }

        activeTrackers.put(order.id(), new ExecutionTracker(
            order.id(), orderTag, order.quantity(), 0.0, 0.0, Order.Status.PENDING, null
        ));
        return true;
    }

    /**
     * Records a partial or complete fill execution.
     */
    public ExecutionTracker recordFill(String orderId, double fillQuantity, double fillPrice) {
        ExecutionTracker current = activeTrackers.get(orderId);
        if (current == null) {
            log.warn("Received fill for untracked order ID: {}", orderId);
            return null;
        }

        double newCumulative = current.cumulativeFilledQuantity() + fillQuantity;
        double newVwap = (current.cumulativeFilledQuantity() == 0)
            ? fillPrice
            : ((current.volumeWeightedPrice() * current.cumulativeFilledQuantity()) + (fillPrice * fillQuantity)) / newCumulative;

        Order.Status newStatus = (newCumulative >= current.requestedQuantity())
            ? Order.Status.FILLED
            : Order.Status.PARTIAL;

        ExecutionTracker updated = new ExecutionTracker(
            current.orderId(),
            current.orderTag(),
            current.requestedQuantity(),
            newCumulative,
            newVwap,
            newStatus,
            null
        );

        activeTrackers.put(orderId, updated);
        log.info("Recorded fill for order {}: +{} @ {} (Cum: {}/{}, VWAP: {}, Status: {})",
            orderId, fillQuantity, fillPrice, newCumulative, current.requestedQuantity(), newVwap, newStatus);

        return updated;
    }

    /**
     * Handles an execution error or rejection from IBKR.
     */
    public void recordError(String orderId, int errorCode, String errorMsg, String strategyId) {
        log.error("IBKR execution error (code: {}): {} [orderId: {}, strategy: {}]",
            errorCode, errorMsg, orderId, strategyId);

        if (errorCode == 201) { // Insufficient margin
            if (strategyId != null) {
                strategyMarginPaused.put(strategyId, true);
                log.warn("Strategy '{}' is now paused due to insufficient margin rejection (Error 201).", strategyId);
            }
        }

        if (orderId != null && activeTrackers.containsKey(orderId)) {
            ExecutionTracker t = activeTrackers.get(orderId);
            activeTrackers.put(orderId, new ExecutionTracker(
                t.orderId(), t.orderTag(), t.requestedQuantity(), t.cumulativeFilledQuantity(),
                t.volumeWeightedPrice(), Order.Status.REJECTED, errorMsg
            ));
        }
    }

    /**
     * Clears the margin pause status for a strategy on the next evaluation cycle.
     */
    public void clearMarginPause(String strategyId) {
        if (strategyId != null) {
            strategyMarginPaused.remove(strategyId);
        }
    }

    public boolean isStrategyMarginPaused(String strategyId) {
        return strategyMarginPaused.getOrDefault(strategyId, false);
    }

    public ExecutionTracker getTracker(String orderId) {
        return activeTrackers.get(orderId);
    }
}
