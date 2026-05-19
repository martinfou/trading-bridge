package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.core.*;
import com.martinfou.trading.strategies.MarketAnalyzer;
import java.util.*;

/**
 * Adapted from StrategyQuant 2.14.147
 * Backtested: GBPJPY H1, 2003-2024
 * Family: Pure Price Action
 *
 * Signal: Open(shift=3) <= Open(shift=2) — open not making new highs
 * Entry: SMA(20, TypicalPrice) + 1.6 × BiggestRange(25)
 * SL: 110 pips | TP: 310 pips | R:R = 1:2.82
 * Expiration: 176 bars | Long only, BUYSTOP equivalent
 */
public class Strategy_2_14_147_Adapted implements Strategy {

    private static final double PIP_JPY = 0.01;
    private static final String SYMBOL = "GBP_JPY";
    private static final double QUANTITY = 1000; // 0.01 lots mini

    // SQ parameters
    private final double priceEntryMult = 1.6;
    private final int lwmaPeriod = 20;
    private final int biggestRangePeriod = 25;
    private final double stopLossPips = 110;
    private final double takeProfitPips = 310;
    private final int expirationBars = 176;

    private final List<Bar> history = new ArrayList<>();
    private final List<Order> pendingOrders = new ArrayList<>();
    private Order activeOrder = null;
    private int barsSinceEntry = 0;
    private boolean entryTriggered = false;

    @Override
    public String name() {
        return "SQ_2.14.147_PurePA_RR2.82";
    }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < lwmaPeriod + biggestRangePeriod + 5) return;

        // Manage existing order
        if (activeOrder != null) {
            barsSinceEntry++;
            if (barsSinceEntry >= expirationBars) {
                pendingOrders.remove(activeOrder);
                activeOrder = null;
            }
            return;
        }

        // Entry signal: Open(3) <= Open(2)
        Bar bar3 = history.get(history.size() - 1 - 3);
        Bar bar2 = history.get(history.size() - 1 - 2);
        boolean entrySignal = bar3.open() <= bar2.open();

        if (!entrySignal || entryTriggered) return;

        // Calculate entry price: SMA(20, TypicalPrice) + 1.6 × BiggestRange(25)
        double typicalPrice = 0;
        double maxRange = 0;

        // SMA of TypicalPrice over lwmaPeriod, offset 2
        int startIdx = history.size() - 1 - 2;
        for (int i = 0; i < lwmaPeriod; i++) {
            Bar b = history.get(startIdx - i);
            typicalPrice += (b.high() + b.low() + b.close()) / 3.0;
        }
        typicalPrice /= lwmaPeriod;

        // Biggest range (high - low) over biggestRangePeriod, offset 2
        for (int i = 0; i < biggestRangePeriod; i++) {
            Bar b = history.get(startIdx - i);
            double range = b.high() - b.low();
            if (range > maxRange) maxRange = range;
        }

        double entryPrice = typicalPrice + (priceEntryMult * maxRange);
        double sl = entryPrice - (stopLossPips * PIP_JPY);
        double tp = entryPrice + (takeProfitPips * PIP_JPY);

        // Create BUY STOP order
        Order order = new Order(SYMBOL, Order.Side.BUY, Order.Type.STOP, QUANTITY, entryPrice)
                .withStopLoss(sl)
                .withTakeProfit(tp);
        pendingOrders.add(order);
        activeOrder = order;
        entryTriggered = true;
        barsSinceEntry = 0;
    }

    @Override
    public void onTick(double bid, double ask, long volume) {
        // Not used for bar-based strategy
    }

    @Override
    public List<Order> getPendingOrders() {
        // Clean up filled orders
        pendingOrders.removeIf(o -> o.status() != Order.Status.PENDING);
        return pendingOrders;
    }

    @Override
    public void reset() {
        history.clear();
        pendingOrders.clear();
        activeOrder = null;
        barsSinceEntry = 0;
        entryTriggered = false;
    }
}
