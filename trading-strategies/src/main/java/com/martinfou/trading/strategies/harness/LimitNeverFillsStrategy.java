package com.martinfou.trading.strategies.harness;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;

/** LIMIT BUY far below market — expects zero fills. */
public final class LimitNeverFillsStrategy extends HarnessScriptedStrategy {

    private boolean sent;

    public LimitNeverFillsStrategy(String symbol) {
        super(symbol);
    }

    @Override
    public String name() {
        return "Harness_LimitNeverFills";
    }

    @Override
    public void onBar(Bar bar) {
        clearPending();
        if (!symbolMatches(bar) || sent) {
            return;
        }
        double limitPrice = bar.low() - 1.0;
        emit(new Order(symbol, Order.Side.BUY, Order.Type.LIMIT, HarnessQuantities.DEFAULT, limitPrice));
        sent = true;
    }

    @Override
    protected void onReset() {
        sent = false;
    }
}
