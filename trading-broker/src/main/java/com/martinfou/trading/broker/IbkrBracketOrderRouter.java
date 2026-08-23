package com.martinfou.trading.broker;

import com.martinfou.trading.core.FuturesRegistry;
import com.martinfou.trading.core.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * CME Native OCA (One-Cancels-All) Bracket Order Router (Story 46.2).
 *
 * <p>Constructs linked parent-child brackets (Entry + Stop-Market + Take-Profit) that execute
 * natively on CME matching engines via Interactive Brokers. Supports dynamic trailing stop
 * price updates at bar closes.</p>
 */
public final class IbkrBracketOrderRouter {

    private static final Logger log = LoggerFactory.getLogger(IbkrBracketOrderRouter.class);

    public record BracketBundle(
        Order parentEntryOrder,
        Order stopLossOrder,
        Order takeProfitOrder,
        String ocaGroup
    ) {
        public List<Order> allOrders() {
            List<Order> list = new ArrayList<>();
            list.add(parentEntryOrder);
            if (stopLossOrder != null) list.add(stopLossOrder);
            if (takeProfitOrder != null) list.add(takeProfitOrder);
            return List.copyOf(list);
        }
    }

    /**
     * Builds a native CME bracket order bundle from an entry order with stop and target prices.
     */
    public static BracketBundle createBracket(Order entryOrder, double stopLossPrice, double takeProfitPrice) {
        Objects.requireNonNull(entryOrder, "entryOrder is required");
        String symbol = entryOrder.symbol();
        String ocaGroup = "TB-OCA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        int ocaType = 1; // Cancel with block

        Order.Side exitSide = (entryOrder.side() == Order.Side.BUY) ? Order.Side.SELL : Order.Side.BUY;

        Order parent = entryOrder
            .withOcaGroup(ocaGroup, ocaType);

        Order stopOrder = null;
        if (stopLossPrice > 0) {
            double quantizedSl = FuturesRegistry.quantizePrice(symbol, stopLossPrice);
            stopOrder = new Order(
                symbol,
                exitSide,
                Order.Type.STOP,
                entryOrder.quantity(),
                quantizedSl
            )
            .asCloseOnly()
            .withParentId(entryOrder.id())
            .withOcaGroup(ocaGroup, ocaType)
            .withStrategyId(entryOrder.strategyId());
        }

        Order tpOrder = null;
        if (takeProfitPrice > 0) {
            double quantizedTp = FuturesRegistry.quantizePrice(symbol, takeProfitPrice);
            tpOrder = new Order(
                symbol,
                exitSide,
                Order.Type.LIMIT,
                entryOrder.quantity(),
                quantizedTp
            )
            .asCloseOnly()
            .withParentId(entryOrder.id())
            .withOcaGroup(ocaGroup, ocaType)
            .withStrategyId(entryOrder.strategyId());
        }

        log.info("Constructed CME Bracket for {} {}: Entry @ {}, SL @ {}, TP @ {} (OCA: {})",
            entryOrder.side(), symbol, entryOrder.price(), stopLossPrice, takeProfitPrice, ocaGroup);

        return new BracketBundle(parent, stopOrder, tpOrder, ocaGroup);
    }

    /**
     * Updates an existing active stop order with a new trailing stop price.
     */
    public static Order updateTrailingStop(Order existingStopOrder, double newStopPrice) {
        Objects.requireNonNull(existingStopOrder, "existingStopOrder is required");
        double quantizedPrice = FuturesRegistry.quantizePrice(existingStopOrder.symbol(), newStopPrice);
        log.info("Updating trailing stop for {} (id: {}) from {} to {}",
            existingStopOrder.symbol(), existingStopOrder.id(), existingStopOrder.price(), quantizedPrice);
        return existingStopOrder.withPrice(quantizedPrice);
    }
}
