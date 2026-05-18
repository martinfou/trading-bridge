package com.martinfou.trading.core;

import java.util.List;

public interface Strategy {
    String name();
    void onBar(Bar bar);
    void onTick(double bid, double ask, long volume);
    List<Order> getPendingOrders();
    void reset();
}
