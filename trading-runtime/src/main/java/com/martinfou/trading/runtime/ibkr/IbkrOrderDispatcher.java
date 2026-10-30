package com.martinfou.trading.runtime.ibkr;

import com.martinfou.trading.broker.Broker;
import com.martinfou.trading.broker.IbkrBracketOrderRouter;
import com.martinfou.trading.broker.OrderSubmitResult;
import com.martinfou.trading.core.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Dispatches strategy entry orders and mandatory venue-side protective stops to IBKR (Story 46.10).
 *
 * <p>Enforces the non-negotiable futures safety contract:</p>
 * <ul>
 *   <li>Every entry order must carry {@code stopLoss > 0} ({@link Order#hasProtectiveStop()}).
 *       A stopless entry is refused locally at {@code ERROR} and never reaches {@link Broker#submitOrder}.</li>
 *   <li>Every entry is tagged with {@code strategyId = <strategy>_<symbol>}.</li>
 *   <li>Immediately after the entry is accepted, the protective stop built by
 *       {@link IbkrBracketOrderRouter#createBracket(Order, double, double)} is submitted to the venue.</li>
 *   <li>If the protective stop is rejected by the venue: logs {@code ERROR}, cancels open orders,
 *       flattens all positions via {@link Broker#flattenAllPositions()}, and halts the lane.
 *       No naked position ever survives a cycle.</li>
 * </ul>
 */
public final class IbkrOrderDispatcher implements Consumer<Order> {

    private static final Logger log = LoggerFactory.getLogger(IbkrOrderDispatcher.class);

    private final Broker broker;
    private final String strategyName;
    private final String symbol;
    private final Runnable haltLaneAction;
    private final boolean dryRun;
    private final AtomicBoolean halted = new AtomicBoolean(false);
    private final Set<String> dispatchedIdempotencyKeys = ConcurrentHashMap.newKeySet();

    public IbkrOrderDispatcher(
        Broker broker,
        String strategyName,
        String symbol,
        Runnable haltLaneAction,
        boolean dryRun
    ) {
        this.broker = Objects.requireNonNull(broker, "broker is required");
        this.strategyName = Objects.requireNonNull(strategyName, "strategyName is required").trim();
        this.symbol = Objects.requireNonNull(symbol, "symbol is required").trim().toUpperCase();
        this.haltLaneAction = Objects.requireNonNull(haltLaneAction, "haltLaneAction is required");
        this.dryRun = dryRun;
    }

    public boolean isHalted() {
        return halted.get();
    }

    @Override
    public void accept(Order rawOrder) {
        if (rawOrder == null) {
            return;
        }
        if (halted.get()) {
            log.error("Lane is HALTED — refusing order dispatch for {}/{}", strategyName, symbol);
            return;
        }

        String canonicalStrategyId = strategyName + "_" + symbol;
        Order order = rawOrder.withStrategyId(canonicalStrategyId);

        // 1. Refuse stopless entries locally at ERROR
        if (!order.isCloseOnly() && !order.hasProtectiveStop()) {
            log.error("REFUSED STOPLESS ENTRY for {} [{}]: side={} qty={} price={} stopLoss={}. "
                + "Every live futures entry requires a positive protective stop.",
                symbol, canonicalStrategyId, order.side(), order.quantity(), order.price(), order.stopLoss());
            return;
        }

        // 2. Idempotency guard per (barTimestamp/correlation or order id, strategy, symbol)
        String idempotencyKey = (order.correlationId() != null ? order.correlationId() : order.id())
            + "|" + canonicalStrategyId;
        if (!dispatchedIdempotencyKeys.add(idempotencyKey)) {
            log.warn("Duplicate dispatch suppressed for key={}", idempotencyKey);
            return;
        }

        if (dryRun) {
            log.info("DRY_RUN=true: would dispatch strategy={} symbol={} side={} qty={} price={} stop={} tp={}",
                canonicalStrategyId, symbol, order.side(), order.quantity(),
                order.price(), order.stopLoss(), order.takeProfit());
            return;
        }

        // 3. Build bracket (parent entry + venue STOP + optional venue LIMIT TP) via IbkrBracketOrderRouter
        IbkrBracketOrderRouter.BracketBundle bracket = IbkrBracketOrderRouter.createBracket(
            order, order.stopLoss(), order.takeProfit()
        );
        Order entryOrder = bracket.parentEntryOrder().withStrategyId(canonicalStrategyId);
        Order stopOrder = bracket.stopLossOrder() != null
            ? bracket.stopLossOrder().withStrategyId(canonicalStrategyId)
            : null;
        Order tpOrder = bracket.takeProfitOrder() != null
            ? bracket.takeProfitOrder().withStrategyId(canonicalStrategyId)
            : null;

        if (stopOrder == null) {
            log.error("REFUSED ENTRY for {}: IbkrBracketOrderRouter produced no protective stop order", canonicalStrategyId);
            return;
        }

        // 4. Submit parent entry
        OrderSubmitResult entryResult = broker.submitOrder(entryOrder);
        if (!entryResult.accepted()) {
            log.error("Entry order rejected by IBKR for {}: reason={}",
                canonicalStrategyId, entryResult.rejectReason());
            return;
        }

        log.info("IBKR DISPATCH [ENTRY] strategy={} side={} qty={} price={} stop={} tp={} brokerOrderId={}",
            canonicalStrategyId, entryOrder.side(), entryOrder.quantity(), entryOrder.price(),
            entryOrder.stopLoss(), entryOrder.takeProfit(), entryResult.brokerOrderId());

        // 5. Immediately submit protective stop to the venue
        OrderSubmitResult stopResult = broker.submitOrder(stopOrder);
        if (!stopResult.accepted()) {
            log.error("CRITICAL: Protective STOP order REJECTED by IBKR after entry acceptance "
                + "(strategy={}, entryOrderId={}, rejectReason={}). Flattening position and HALTING lane immediately!",
                canonicalStrategyId, entryResult.brokerOrderId(), stopResult.rejectReason());
            halted.set(true);
            try {
                broker.cancelAllOrders();
            } catch (Exception e) {
                log.error("Error cancelling orders during emergency halt: {}", e.getMessage(), e);
            }
            try {
                int flattened = broker.flattenAllPositions();
                log.error("Emergency flatten after rejected stop completed: {} position(s) flattened", flattened);
            } catch (Exception e) {
                log.error("Error flattening positions during emergency halt: {}", e.getMessage(), e);
            }
            haltLaneAction.run();
            return;
        }

        log.info("IBKR DISPATCH [PROTECTIVE_STOP] strategy={} side={} qty={} stopPrice={} brokerOrderId={} ocaGroup={}",
            canonicalStrategyId, stopOrder.side(), stopOrder.quantity(), stopOrder.price(),
            stopResult.brokerOrderId(), bracket.ocaGroup());

        // 6. Submit optional take-profit LIMIT sibling in the same OCA group
        if (tpOrder != null) {
            OrderSubmitResult tpResult = broker.submitOrder(tpOrder);
            if (tpResult.accepted()) {
                log.info("IBKR DISPATCH [TAKE_PROFIT] strategy={} side={} qty={} tpPrice={} brokerOrderId={} ocaGroup={}",
                    canonicalStrategyId, tpOrder.side(), tpOrder.quantity(), tpOrder.price(),
                    tpResult.brokerOrderId(), bracket.ocaGroup());
            } else {
                log.warn("Take-profit limit order rejected for {} (position remains protected by venue stop {}): {}",
                    canonicalStrategyId, stopResult.brokerOrderId(), tpResult.rejectReason());
            }
        }
    }
}
