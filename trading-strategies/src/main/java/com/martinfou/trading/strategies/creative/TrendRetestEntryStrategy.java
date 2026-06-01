package com.martinfou.trading.strategies.creative;

import com.martinfou.trading.core.*;
import java.util.*;

public class TrendRetestEntryStrategy implements Strategy {
    private final String name;
    private final String symbol;
    private final List<Order> pending = new ArrayList<>();
    private final List<Bar> history = new ArrayList<>();
    private boolean inTrade = false;
    private Order.Side tradeDirection = Order.Side.BUY;
    private double entryPrice = 0;
    private int barsHeld = 0;
    private double positionSize = 1000;

    private boolean waitingForRetest = false;
    private Order.Side trendDirection = Order.Side.BUY;
    private double strongBarOpen = 0;
    private double strongBarClose = 0;
    private double strongBarHigh = 0;
    private double strongBarLow = 0;
    private int barsSinceStrongBar = 0;

    public TrendRetestEntryStrategy() {
        this("TrendRetestEntry", "GBP/JPY");
    }

    public TrendRetestEntryStrategy(String name) {
        this(name, "GBP/JPY");
    }

    public TrendRetestEntryStrategy(String name, String symbol) {
        this.name = name;
        this.symbol = symbol;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void onBar(Bar bar) {
        history.add(bar);
        if (history.size() < 25) return;
        double atr = calculateATR(14);

        // --- Manage open position ---
        if (inTrade) {
            barsHeld++;
            if (barsHeld >= 8) {
                closePosition(bar.close());
                return;
            }
            if (tradeDirection == Order.Side.BUY) {
                if (bar.low() <= entryPrice - atr * 1.0) {
                    closePosition(bar.close());
                    return;
                }
                if (bar.high() >= entryPrice + atr * 2.0) {
                    closePosition(bar.close());
                    return;
                }
            } else {
                if (bar.high() >= entryPrice + atr * 1.0) {
                    closePosition(bar.close());
                    return;
                }
                if (bar.low() <= entryPrice - atr * 2.0) {
                    closePosition(bar.close());
                    return;
                }
            }
            return;
        }

        // --- Waiting for retest ---
        if (waitingForRetest) {
            barsSinceStrongBar++;
            if (barsSinceStrongBar > 3) {
                waitingForRetest = false;
                return;
            }

            double strongBarRange = strongBarHigh - strongBarLow;
            double retestZoneLow = strongBarLow + strongBarRange * 0.38;
            double retestZoneHigh = strongBarLow + strongBarRange * 0.62;

            if (trendDirection == Order.Side.BUY) {
                // Price retraced into the 38-62% zone
                if (bar.low() <= retestZoneHigh && bar.high() >= retestZoneLow) {
                    // Reversal candle: close > open (bullish rejection)
                    if (bar.close() > bar.open()) {
                        pending.add(new Order(symbol, Order.Side.BUY, Order.Type.MARKET, positionSize, bar.close()));
                        inTrade = true;
                        tradeDirection = Order.Side.BUY;
                        entryPrice = bar.close();
                        barsHeld = 0;
                        waitingForRetest = false;
                    }
                } else if (bar.low() < strongBarLow) {
                    // Retest failed – strong bar invalidated
                    waitingForRetest = false;
                }
            } else {
                // Price retraced into the 38-62% zone
                if (bar.low() <= retestZoneHigh && bar.high() >= retestZoneLow) {
                    // Reversal candle: close < open (bearish rejection)
                    if (bar.close() < bar.open()) {
                        pending.add(new Order(symbol, Order.Side.SELL, Order.Type.MARKET, positionSize, bar.close()));
                        inTrade = true;
                        tradeDirection = Order.Side.SELL;
                        entryPrice = bar.close();
                        barsHeld = 0;
                        waitingForRetest = false;
                    }
                } else if (bar.high() > strongBarHigh) {
                    waitingForRetest = false;
                }
            }
            return;
        }

        // --- Detect strong directional bar ---
        double range = bar.high() - bar.low();
        double body = Math.abs(bar.close() - bar.open());
        if (range > atr * 1.2 && body > range * 0.5) {
            if (bar.close() > bar.open() && bar.close() >= bar.low() + range * 0.7) {
                // Strong bullish bar
                waitingForRetest = true;
                trendDirection = Order.Side.BUY;
                strongBarOpen = bar.open();
                strongBarClose = bar.close();
                strongBarHigh = bar.high();
                strongBarLow = bar.low();
                barsSinceStrongBar = 0;
            } else if (bar.close() < bar.open() && bar.close() <= bar.low() + range * 0.3) {
                // Strong bearish bar
                waitingForRetest = true;
                trendDirection = Order.Side.SELL;
                strongBarOpen = bar.open();
                strongBarClose = bar.close();
                strongBarHigh = bar.high();
                strongBarLow = bar.low();
                barsSinceStrongBar = 0;
            }
        }
    }

    private void closePosition(double price) {
        pending.add(new Order(
            symbol,
            tradeDirection == Order.Side.BUY ? Order.Side.SELL : Order.Side.BUY,
            Order.Type.MARKET,
            positionSize,
            price
        ).closeOnly());
        inTrade = false;
    }

    private double calculateATR(int period) {
        int size = history.size();
        double sum = 0;
        int count = 0;
        for (int i = Math.max(1, size - period); i < size; i++) {
            Bar prev = history.get(i - 1);
            Bar curr = history.get(i);
            double tr = Math.max(
                curr.high() - curr.low(),
                Math.max(
                    Math.abs(curr.high() - prev.close()),
                    Math.abs(curr.low() - prev.close())
                )
            );
            sum += tr;
            count++;
        }
        return count > 0 ? sum / count : 1.0;
    }

    @Override
    public void onTick(double bid, double ask, long volume) {
        // Tick data not used
    }

    @Override
    public List<Order> getPendingOrders() {
        List<Order> copy = new ArrayList<>(pending);
        pending.clear();
        return copy;
    }

    @Override
    public void reset() {
        history.clear();
        pending.clear();
        inTrade = false;
        tradeDirection = Order.Side.BUY;
        entryPrice = 0;
        barsHeld = 0;
        waitingForRetest = false;
        trendDirection = Order.Side.BUY;
        strongBarOpen = 0;
        strongBarClose = 0;
        strongBarHigh = 0;
        strongBarLow = 0;
        barsSinceStrongBar = 0;
    }
}
