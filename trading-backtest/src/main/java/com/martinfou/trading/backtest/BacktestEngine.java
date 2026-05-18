package com.martinfou.trading.backtest;

import com.martinfou.trading.core.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.LocalDateTime;
import java.util.*;

public class BacktestEngine {
    private static final Logger log = LoggerFactory.getLogger(BacktestEngine.class);

    private final Strategy strategy;
    private final double initialCapital;
    private double equity;
    private double peakEquity;
    private int totalTrades = 0;
    private int winningTrades = 0;
    private int losingTrades = 0;
    private final List<Trade> trades = new ArrayList<>();
    private final List<Bar> bars;
    private final List<Double> equityCurve = new ArrayList<>();

    public BacktestEngine(Strategy strategy, List<Bar> bars, double initialCapital) {
        this.strategy = strategy;
        this.bars = bars;
        this.initialCapital = initialCapital;
        this.equity = initialCapital;
        this.peakEquity = initialCapital;
    }

    public BacktestResult run() {
        log.info("Starting backtest: {} | Bars: {} | Capital: ${}", strategy.name(), bars.size(), initialCapital);
        strategy.reset();
        equityCurve.add(equity);

        for (Bar bar : bars) {
            strategy.onBar(bar);
            processOrders(bar);
            equityCurve.add(equity);
            if (equity > peakEquity) peakEquity = equity;
        }

        return buildResult();
    }

    private void processOrders(Bar bar) {
        List<Order> pending = new ArrayList<>(strategy.getPendingOrders());
        for (Order order : pending) {
            boolean filled = false;
            double fillPrice = 0;

            switch (order.type()) {
                case MARKET:
                    filled = true;
                    fillPrice = bar.open();
                    break;
                case LIMIT:
                    if (order.side() == Order.Side.BUY && bar.low() <= order.price()) {
                        filled = true; fillPrice = order.price();
                    } else if (order.side() == Order.Side.SELL && bar.high() >= order.price()) {
                        filled = true; fillPrice = order.price();
                    }
                    break;
                case STOP:
                    if (order.side() == Order.Side.BUY && bar.high() >= order.price()) {
                        filled = true; fillPrice = Math.min(bar.close(), order.price());
                    } else if (order.side() == Order.Side.SELL && bar.low() <= order.price()) {
                        filled = true; fillPrice = Math.max(bar.close(), order.price());
                    }
                    break;
            }

            if (filled) {
                order.fill();
                totalTrades++;
                trades.add(new Trade(order.symbol(), order.side(), fillPrice, 0, order.quantity(), bar.timestamp(), null));
                log.debug("FILLED: {} {} @ {:.5f}", order.side(), order.quantity(), fillPrice);
            }
        }
    }

    private BacktestResult buildResult() {
        // Simplified: in real version, track all trade exits too
        double totalPnl = equity - initialCapital;
        double drawdown = calcMaxDrawdown();
        double winRate = totalTrades > 0 ? (double) winningTrades / totalTrades * 100 : 0;

        return new BacktestResult(
            strategy.name(), initialCapital, equity, totalPnl,
            (totalPnl / initialCapital) * 100, totalTrades, winRate, drawdown,
            equityCurve, trades, bars.getFirst().timestamp(), bars.getLast().timestamp()
        );
    }

    private double calcMaxDrawdown() {
        double peak = initialCapital;
        double maxDd = 0;
        for (double e : equityCurve) {
            if (e > peak) peak = e;
            double dd = (peak - e) / peak * 100;
            if (dd > maxDd) maxDd = dd;
        }
        return maxDd;
    }
}
