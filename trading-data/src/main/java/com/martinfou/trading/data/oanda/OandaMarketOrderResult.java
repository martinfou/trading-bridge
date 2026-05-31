package com.martinfou.trading.data.oanda;

/** OANDA v20 REST order placement result. */
public record OandaMarketOrderResult(
    int httpStatus,
    String orderId,
    String tradeId,
    Double fillPrice,
    String errorMessage
) {
    public boolean success() {
        return httpStatus == 201 && errorMessage == null;
    }

    public static OandaMarketOrderResult success(String orderId, String tradeId, double fillPrice) {
        return new OandaMarketOrderResult(201, orderId, tradeId, fillPrice, null);
    }

    public static OandaMarketOrderResult failure(int httpStatus, String errorMessage) {
        return new OandaMarketOrderResult(httpStatus, null, null, null, errorMessage);
    }
}
