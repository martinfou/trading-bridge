package com.martinfou.trading.data.ibkr;

/** Market order outcome from IB Gateway (Story 16.10). */
public record IbkrMarketOrderResult(
    boolean success,
    String orderId,
    String executionId,
    Double fillPrice,
    String errorMessage
) {

    public static IbkrMarketOrderResult success(String orderId, String executionId, double fillPrice) {
        return new IbkrMarketOrderResult(true, orderId, executionId, fillPrice, null);
    }

    public static IbkrMarketOrderResult failure(String message) {
        return new IbkrMarketOrderResult(false, null, null, null, message);
    }
}
