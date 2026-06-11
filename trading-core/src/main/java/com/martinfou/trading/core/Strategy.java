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
}
