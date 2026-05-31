package com.martinfou.trading.parser.codegen;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.parser.actions.SqBarActions;
import com.martinfou.trading.parser.actions.SqCloseDirection;
import com.martinfou.trading.parser.actions.SqCloseIntent;
import com.martinfou.trading.parser.actions.SqOrderIntent;
import com.martinfou.trading.parser.actions.SqStrategyActionsEvaluator;
import com.martinfou.trading.parser.conditions.SqExitEvaluator;
import com.martinfou.trading.parser.config.StrategyConfig;
import com.martinfou.trading.parser.sq.SqStrategyDocument;
import com.martinfou.trading.parser.sq.SqXmlParser;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Runtime {@link Strategy} that evaluates SQ XML via the parser interpreter stack (story 2-9).
 */
public final class SqInterpretedStrategy implements Strategy {

    private final String name;
    private final String symbol;
    private final SqStrategyDocument document;
    private final StrategyConfig config;
    private final double pipSize;
    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pendingOrders = new ArrayList<>();
    private boolean longOpen;
    private boolean shortOpen;
    private double longQuantity;
    private double shortQuantity;

    public SqInterpretedStrategy(
        SqStrategyDocument document,
        StrategyConfig config,
        String name,
        String symbol
    ) {
        this.document = document;
        this.config = config;
        this.name = name;
        this.symbol = symbol;
        this.pipSize = SqPipScale.pipSize(symbol);
    }

    public static SqInterpretedStrategy fromDocument(
        SqStrategyDocument document,
        String name,
        String symbol
    ) {
        return new SqInterpretedStrategy(document, StrategyConfig.from(document), name, symbol);
    }

    public static SqInterpretedStrategy fromXml(InputStream xmlStream, String name, String symbol) {
        return fromDocument(SqXmlParser.parse(xmlStream), name, symbol);
    }

    public static SqInterpretedStrategy fromClasspath(String resourcePath, String name, String symbol) {
        InputStream in = SqInterpretedStrategy.class.getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IllegalArgumentException("Classpath resource not found: " + resourcePath);
        }
        return fromXml(in, name, symbol);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        SqExitEvaluator.PositionState position = new SqExitEvaluator.PositionState(longOpen, shortOpen);
        SqBarActions actions = SqStrategyActionsEvaluator.evaluateOnBar(document, history, config, position);

        boolean closedThisBar = false;
        for (SqCloseIntent close : actions.closeActions()) {
            if (emitClose(close, bar)) {
                closedThisBar = true;
            }
        }
        for (SqOrderIntent entry : actions.entryOrders()) {
            if (closedThisBar) {
                continue;
            }
            if (entry.side() == Order.Side.BUY && longOpen) {
                continue;
            }
            if (entry.side() == Order.Side.SELL && shortOpen) {
                continue;
            }
            entry.toOrder(symbol).ifPresent(order -> {
                enrichWithExitPips(order, entry);
                pendingOrders.add(order);
                if (entry.side() == Order.Side.BUY) {
                    longOpen = true;
                    longQuantity = entry.quantity();
                } else {
                    shortOpen = true;
                    shortQuantity = entry.quantity();
                }
            });
        }
    }

    @Override
    public void onTick(double bid, double ask, long volume) {
        // Bar-based SQ strategies
    }

    @Override
    public List<Order> getPendingOrders() {
        List<Order> copy = new ArrayList<>(pendingOrders);
        pendingOrders.clear();
        return copy;
    }

    @Override
    public void reset() {
        history.clear();
        pendingOrders.clear();
        longOpen = false;
        shortOpen = false;
        longQuantity = 0.0;
        shortQuantity = 0.0;
    }

    /** Test hook: seed open position without a prior entry bar. */
    void seedShortPosition(double quantity) {
        shortOpen = true;
        shortQuantity = quantity;
    }

    /** Test hook: seed open long position without a prior entry bar. */
    void seedLongPosition(double quantity) {
        longOpen = true;
        longQuantity = quantity;
    }

    private boolean emitClose(SqCloseIntent close, Bar bar) {
        return switch (close.direction()) {
            case LONG -> closeLong(bar);
            case SHORT -> closeShort(bar);
            case ANY -> closeLong(bar) | closeShort(bar);
        };
    }

    private boolean closeLong(Bar bar) {
        if (!longOpen || longQuantity <= 0.0) {
            return false;
        }
        pendingOrders.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, longQuantity, bar.close()));
        longOpen = false;
        longQuantity = 0.0;
        return true;
    }

    private boolean closeShort(Bar bar) {
        if (!shortOpen || shortQuantity <= 0.0) {
            return false;
        }
        pendingOrders.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, shortQuantity, bar.close()));
        shortOpen = false;
        shortQuantity = 0.0;
        return true;
    }

    private void enrichWithExitPips(Order order, SqOrderIntent intent) {
        double reference = order.price();
        intent.stopLossPips().ifPresent(pips -> {
            double offset = pips * pipSize;
            if (order.side() == Order.Side.BUY) {
                order.withStopLoss(reference - offset);
            } else {
                order.withStopLoss(reference + offset);
            }
        });
        intent.profitTargetPips().ifPresent(pips -> {
            double offset = pips * pipSize;
            if (order.side() == Order.Side.BUY) {
                order.withTakeProfit(reference + offset);
            } else {
                order.withTakeProfit(reference - offset);
            }
        });
    }
}
