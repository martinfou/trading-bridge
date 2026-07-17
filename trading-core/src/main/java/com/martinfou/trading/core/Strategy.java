package com.martinfou.trading.core;

import java.util.List;

public interface Strategy {
    String name();
    void onBar(Bar bar);
    void onTick(double bid, double ask, long volume);
    List<Order> getPendingOrders();
    void reset();

    default void onSentiment(java.util.Map<String, Object> sentiment) {
        // Default no-op for backward compatibility
    }

    @SuppressWarnings("unchecked")
    default List<Bar> getHistory() {
        try {
            java.lang.reflect.Field field = this.getClass().getDeclaredField("history");
            field.setAccessible(true);
            return (List<Bar>) field.get(this);
        } catch (Exception e) {
            return null;
        }
    }

    default void syncPosition(Order.Side side, double quantity, double sl, double tp) {
        try {
            boolean inTrade = (side != null && quantity > 0);
            setFieldValueOpt("inTrade", inTrade);
            setFieldValueOpt("positionSide", side);
            setFieldValueOpt("tradeDirection", side);
            setFieldValueOpt("positionUnits", quantity);
            setFieldValueOpt("units", quantity);
            setFieldValueOpt("stopLoss", sl);
            setFieldValueOpt("takeProfit", tp);
        } catch (Exception e) {
            // Ignore
        }
    }

    private void setFieldValueOpt(String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = this.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            if (value == null && field.getType().isPrimitive()) return;
            if (field.getType() == double.class && value instanceof Number) {
                field.setDouble(this, ((Number)value).doubleValue());
            } else if (field.getType() == int.class && value instanceof Number) {
                field.setInt(this, ((Number)value).intValue());
            } else if (field.getType() == long.class && value instanceof Number) {
                field.setLong(this, ((Number)value).longValue());
            } else {
                field.set(this, value);
            }
        } catch (Exception e) {
            // Ignore if field doesn't exist
        }
    }
}
