package com.martinfou.trading.data.ibkr;

import com.martinfou.trading.core.Order;

import java.time.Instant;

/**
 * One execution of the current IB trading day, joined with its commission report ({@code reqExecutions}).
 *
 * <p>Reporting only (hermes-web P&amp;L mirror). {@code realizedPnl} is IB's own realized P&amp;L for a
 * closing execution, net of commissions, in {@code commissionCurrency}; it is {@link Double#NaN} for an
 * opening execution and {@code commissionKnown} is false until the commission report has arrived.</p>
 *
 * @param orderRef the IB {@code orderRef} (trading-bridge order id; bracket legs end with {@code -TP}/{@code -SL})
 * @param currency the contract currency (USD for MES or SPY)
 */
public record IbkrExecutionReport(
    String execId,
    long permId,
    int orderId,
    String orderRef,
    String accountId,
    String symbol,
    String secType,
    String currency,
    Order.Side side,
    double quantity,
    double price,
    Instant time,
    boolean commissionKnown,
    double commission,
    String commissionCurrency,
    double realizedPnl
) {

    /** True when IB reported a realized P&amp;L, i.e. this execution closed (part of) a position. */
    public boolean isClosing() {
        return commissionKnown && !Double.isNaN(realizedPnl);
    }

    public IbkrExecutionReport withCommission(double commissionAmount, String ccy, double realized) {
        return new IbkrExecutionReport(execId, permId, orderId, orderRef, accountId, symbol, secType, currency, side,
            quantity, price, time, true, commissionAmount, ccy, realized);
    }
}
