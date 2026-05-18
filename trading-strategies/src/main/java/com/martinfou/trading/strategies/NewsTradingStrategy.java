package com.martinfou.trading.strategies;

import com.martinfou.trading.core.*;
import com.martinfou.trading.data.OandaPriceClient;
import java.time.LocalDateTime;
import java.util.*;

public class NewsTradingStrategy {
    private final String name;
    private final String pair;
    private final String oandaSymbol;
    private final TradeSide direction;  // Expected move direction
    private final String newsEvent;     // Catalyst
    private final LocalDateTime newsTime;
    private final double slPips;        // Stop loss in pips
    private final double tpRatio;       // Risk:Reward ratio
    private final double quantity;      // Micro lots
    
    public enum TradeSide { LONG, SHORT }
    
    private double entryPrice = 0;
    private Order order = null;
    private boolean executed = false;

    public NewsTradingStrategy(String name, String pair, String oandaSymbol, 
            TradeSide direction, String newsEvent, LocalDateTime newsTime,
            double slPips, double tpRatio, double quantity) {
        this.name = name; this.pair = pair; this.oandaSymbol = oandaSymbol;
        this.direction = direction; this.newsEvent = newsEvent;
        this.newsTime = newsTime; this.slPips = slPips;
        this.tpRatio = tpRatio; this.quantity = quantity;
    }

    public TradeSignal evaluate(List<Bar> bars, boolean newsExpectedHigher) {
        if (executed || bars.isEmpty()) return null;
        
        double currentPrice = bars.get(bars.size()-1).close();
        double rsi = MarketAnalyzer.rsi(bars, 14);
        String trend = MarketAnalyzer.trend(bars, 20, 50);
        double atr = MarketAnalyzer.atr(bars, 14);
        double[] levels = MarketAnalyzer.findKeyLevels(bars, 48);
        
        // Score calculation (0-100)
        int score = 50; // Base
        
        // News alignment
        if (newsExpectedHigher && direction == TradeSide.LONG) score += 20;
        else if (!newsExpectedHigher && direction == TradeSide.SHORT) score += 20;
        else score -= 10; // Against the news
        
        // Technical alignment
        if (direction == TradeSide.LONG && trend.equals("BULLISH")) score += 15;
        else if (direction == TradeSide.SHORT && trend.equals("BEARISH")) score += 15;
        else score -= 5;
        
        // RSI confirmation
        if (direction == TradeSide.LONG && rsi < 40) score += 10; // Oversold buy
        else if (direction == TradeSide.SHORT && rsi > 60) score += 10; // Overbought sell
        else score += 5;
        
        // Price near support/resistance
        if (direction == TradeSide.LONG && currentPrice <= levels[0] * 1.002) score += 10;
        else if (direction == TradeSide.SHORT && currentPrice >= levels[2] * 0.998) score += 10;
        
        // Calculate entry, SL, TP
        double slDistance = slPips * getPipValue();
        this.entryPrice = currentPrice;
        
        double sl, tp;
        if (direction == TradeSide.LONG) {
            sl = currentPrice - slDistance;
            tp = currentPrice + slDistance * tpRatio;
        } else {
            sl = currentPrice + slDistance;
            tp = currentPrice - slDistance * tpRatio;
        }
        
        Order.Side orderSide = direction == TradeSide.LONG ? Order.Side.BUY : Order.Side.SELL;
        
        return new TradeSignal(oandaSymbol, orderSide, entryPrice, sl, tp, 
            quantity, score, name + " | RSI:" + (int)rsi + " Trend:" + trend + " Score:" + score,
            newsEvent);
    }

    public void markExecuted() { this.executed = true; }
    public boolean isExecuted() { return executed; }
    public String name() { return name; }
    public LocalDateTime getNewsTime() { return newsTime; }
    public String getPair() { return pair; }
    public double getSlPips() { return slPips; }
    public double getTpRatio() { return tpRatio; }
    public double getQuantity() { return quantity; }
    public TradeSide getDirection() { return direction; }

    private double getPipValue() {
        if (oandaSymbol.contains("JPY")) return 0.01;
        return 0.0001;
    }

    public void run() {
        System.out.println("\n═══════════════════════════════════════");
        System.out.println("  " + name);
        System.out.println("  Pair: " + pair + " | Direction: " + direction);
        System.out.println("  News: " + newsEvent + " @ " + newsTime);
        System.out.println("  SL: " + slPips + " pips | R:R 1:" + tpRatio);
        System.out.println("  Size: " + quantity + " lots");
        System.out.println("═══════════════════════════════════════");
    }
}
