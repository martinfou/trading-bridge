package com.martinfou.trading.backtest;

import com.martinfou.trading.core.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.util.*;

/**
 * Bar-by-bar backtesting engine that drives a {@link Strategy} against
 * historical {@link Bar} data and produces a complete {@link BacktestResult}.
 *
 * <p>Supports MARKET, LIMIT, and STOP order fills, configurable
 * commission and slippage, stop-loss / take-profit exit simulation,
 * and computes advanced performance metrics (Sharpe, Sortino, etc.).</p>
 *
 * <h3>Configuration</h3>
 * <ul>
 *   <li>Commission: per-trade fixed cost and/or percentage of notional</li>
 *   <li>Slippage: fixed per-trade or percentage of fill price</li>
 *   <li>Risk-free rate for Sharpe/Sortino (default 2.5 % p.a.)</li>
 * </ul>
 */
public class BacktestEngine {

    private static final Logger log = LoggerFactory.getLogger(BacktestEngine.class);

    private final Strategy strategy;
    private final List<Bar> bars;
    private final double initialCapital;

    // Internal state
    private double equity;
    private double peakEquity;
    private int totalTrades, winningTrades, losingTrades;
    private final List<Trade> trades = new ArrayList<>();
    private final List<Double> equityCurve = new ArrayList<>();
    private final Map<String, Position> openPositions = new HashMap<>();

    // Cost configuration
    private double commissionFixed = 0.0;        // USD per trade
    private double commissionPct = 0.0;          // % of notional (e.g. 0.0007 for 0.7 pip)
    private double slippageFixed = 0.0;          // USD per trade
    private double slippagePct = 0.0;            // % of fill price (e.g. 0.0001)
    private double totalCommission = 0.0;
    private double totalSlippage = 0.0;
    private double riskFreeRate = PerformanceMetrics.DEFAULT_RISK_FREE_RATE;

    // ---------------------------------------------------------------
    //  Constructors
    // ---------------------------------------------------------------

    /**
     * Creates a backtest engine with default zero-cost configuration.
     *
     * @param strategy       the strategy to drive
     * @param bars           historical bar data
     * @param initialCapital starting account balance
     */
    public BacktestEngine(Strategy strategy, List<Bar> bars, double initialCapital) {
        this.strategy = strategy;
        this.bars = bars;
        this.initialCapital = initialCapital;
        this.equity = initialCapital;
        this.peakEquity = initialCapital;
    }

    // ---------------------------------------------------------------
    //  Configuration setters (fluent)
    // ---------------------------------------------------------------

    /** Fixed commission in USD deducted from equity per filled trade. */
    public BacktestEngine withCommissionFixed(double commissionFixed) {
        this.commissionFixed = commissionFixed;
        return this;
    }

    /** Percentage commission of notional value per filled trade (e.g. 0.0007). */
    public BacktestEngine withCommissionPct(double commissionPct) {
        this.commissionPct = commissionPct;
        return this;
    }

    /** Fixed slippage in USD per filled trade. */
    public BacktestEngine withSlippageFixed(double slippageFixed) {
        this.slippageFixed = slippageFixed;
        return this;
    }

    /** Percentage slippage of fill price per trade. */
    public BacktestEngine withSlippagePct(double slippagePct) {
        this.slippagePct = slippagePct;
        return this;
    }

    /** Annual risk-free rate for Sharpe/Sortino calculation (decimal, e.g. 0.025). */
    public BacktestEngine withRiskFreeRate(double riskFreeRate) {
        this.riskFreeRate = riskFreeRate;
        return this;
    }

    // ---------------------------------------------------------------
    //  Main execution
    // ---------------------------------------------------------------

    /**
     * Runs the backtest over all bars.
     *
     * @return a fully populated {@link BacktestResult}
     */
    public BacktestResult run() {
        log.info("Starting backtest: {} | Bars: {} | Capital: ${}",
            strategy.name(), bars.size(), String.format("%,.2f", initialCapital));
        strategy.reset();
        equityCurve.add(equity);

        // Parse all bars
        for (Bar bar : bars) {
            // Let the strategy analyse this bar first
            strategy.onBar(bar);

            // Check SL/TP on open positions before processing new orders
            checkStopLossesTakeProfits(bar);

            // Process pending orders from the strategy
            processOrders(bar);

            // Update equity with floating P&L from open positions
            updateFloatingEquity(bar);

            // Track equity curve
            equityCurve.add(equity);
            if (equity > peakEquity) peakEquity = equity;
        }

        // Close any remaining open positions at last bar's close
        closeRemainingPositions(bars.getLast());

        return buildResult();
    }

    // ---------------------------------------------------------------
    //  Order processing
    // ---------------------------------------------------------------

    private void processOrders(Bar bar) {
        List<Order> pending = new ArrayList<>(strategy.getPendingOrders());
        for (Order order : pending) {
            boolean filled = false;
            double fillPrice = 0;

            switch (order.type()) {
                case MARKET -> {
                    filled = true;
                    fillPrice = bar.open();
                }
                case LIMIT -> {
                    if (order.side() == Order.Side.BUY && bar.low() <= order.price()) {
                        filled = true;
                        fillPrice = order.price();
                    } else if (order.side() == Order.Side.SELL && bar.high() >= order.price()) {
                        filled = true;
                        fillPrice = order.price();
                    }
                }
                case STOP -> {
                    if (order.side() == Order.Side.BUY && bar.high() >= order.price()) {
                        filled = true;
                        fillPrice = Math.min(bar.close(), order.price());
                    } else if (order.side() == Order.Side.SELL && bar.low() <= order.price()) {
                        filled = true;
                        fillPrice = Math.max(bar.close(), order.price());
                    }
                }
            }

            if (filled) {
                // Apply slippage to fill price
                double adjustedPrice = applySlippage(fillPrice, order.side());

                // Calculate commission
                double commission = calcCommission(adjustedPrice, order.quantity());

                order.fill();

                // Deduct costs from equity immediately
                equity -= commission;
                totalCommission += commission;

                // Open or add to position
                Position existing = openPositions.get(order.symbol());
                if (existing != null && existing.side() == order.side()) {
                    existing.addQuantity(order.quantity(), adjustedPrice);
                } else {
                    // Close existing opposite position if exists
                    if (existing != null) {
                        closePosition(existing, adjustedPrice, bar.timestamp());
                    }
                    Position pos = new Position(order.symbol(), order.side(),
                        order.quantity(), adjustedPrice);
                    if (order.stopLoss() != 0) pos.withStopLoss(order.stopLoss());
                    if (order.takeProfit() != 0) pos.withTakeProfit(order.takeProfit());
                    openPositions.put(order.symbol(), pos);
                }

                log.debug("FILLED: {} {} @ {:.5f} (cost: ${:.2f})",
                    order.side(), order.quantity(), adjustedPrice, commission);
            }
        }
    }

    // ---------------------------------------------------------------
    //  Stop-loss / take-profit checking
    // ---------------------------------------------------------------

    private void checkStopLossesTakeProfits(Bar bar) {
        List<Map.Entry<String, Position>> toClose = new ArrayList<>();
        for (Position pos : openPositions.values()) {
            double sl = pos.stopLoss();
            double tp = pos.takeProfit();

            if (pos.side() == Order.Side.BUY) {
                // SL hit
                if (sl > 0 && bar.low() <= sl) {
                    toClose.add(Map.entry(pos.symbol(), pos));
                    continue;
                }
                // TP hit
                if (tp > 0 && bar.high() >= tp) {
                    toClose.add(Map.entry(pos.symbol(), pos));
                }
            } else {
                // SELL position
                if (sl > 0 && bar.high() >= sl) {
                    toClose.add(Map.entry(pos.symbol(), pos));
                    continue;
                }
                if (tp > 0 && bar.low() <= tp) {
                    toClose.add(Map.entry(pos.symbol(), pos));
                }
            }
        }

        for (var entry : toClose) {
            Position pos = entry.getValue();
            double exitPrice = pos.stopLoss() > 0 && hitStopLoss(pos, bar)
                ? pos.stopLoss() : pos.takeProfit();
            closePosition(pos, exitPrice, bar.timestamp());
        }
    }

    private boolean hitStopLoss(Position pos, Bar bar) {
        if (pos.side() == Order.Side.BUY) {
            return pos.stopLoss() > 0 && bar.low() <= pos.stopLoss();
        } else {
            return pos.stopLoss() > 0 && bar.high() >= pos.stopLoss();
        }
    }

    // ---------------------------------------------------------------
    //  Position management
    // ---------------------------------------------------------------

    private void closePosition(Position pos, double exitPrice, Instant timestamp) {
        double pnl = pos.currentPnl(exitPrice);
        equity += pnl;
        totalTrades++;

        if (pnl > 0) winningTrades++;
        else if (pnl < 0) losingTrades++;

        trades.add(new Trade(pos.symbol(), pos.side(), pos.entryPrice(), exitPrice,
            pos.quantity(), timestamp, timestamp));
        openPositions.remove(pos.symbol());

        log.debug("CLOSED: {} {} PnL:${:.2f}", pos.symbol(), pos.side(), pnl);
    }

    private void closeRemainingPositions(Bar lastBar) {
        for (Position pos : List.copyOf(openPositions.values())) {
            closePosition(pos, lastBar.close(), lastBar.timestamp());
        }
    }

    private void updateFloatingEquity(Bar bar) {
        for (Position pos : openPositions.values()) {
            equity += pos.currentPnl(bar.close());
        }
    }

    // ---------------------------------------------------------------
    //  Cost helpers
    // ---------------------------------------------------------------

    private double applySlippage(double price, Order.Side side) {
        double slipAmount = slippageFixed > 0 ? slippageFixed : price * slippagePct;
        totalSlippage += slipAmount;
        // Slippage always works against the trader
        return side == Order.Side.BUY ? price + slipAmount : price - slipAmount;
    }

    private double calcCommission(double price, double quantity) {
        double notional = price * quantity;
        return commissionFixed + notional * commissionPct;
    }

    // ---------------------------------------------------------------
    //  Result builder
    // ---------------------------------------------------------------

    private BacktestResult buildResult() {
        double totalPnl = equity - initialCapital;
        double totalReturnPct = initialCapital > 0 ? (totalPnl / initialCapital) * 100 : 0;
        double maxDd = calcMaxDrawdown();
        double winRate = totalTrades > 0 ? (double) winningTrades / totalTrades * 100 : 0;
        double avgTradePnl = totalTrades > 0 ? totalPnl / totalTrades : 0;

        // Compute advanced metrics from equity curve returns and trade P&Ls
        List<Double> periodReturns = computePeriodReturns();
        List<Double> tradePnlList = trades.stream().map(Trade::pnl).toList();

        double sharpe = PerformanceMetrics.sharpeRatio(periodReturns, riskFreeRate);
        double sortino = PerformanceMetrics.sortinoRatio(periodReturns, riskFreeRate);
        double profitFactor = PerformanceMetrics.profitFactor(tradePnlList);
        double calmar = PerformanceMetrics.calmarRatio(equityCurve);

        return new BacktestResult(
            strategy.name(), initialCapital, equity, totalPnl, totalReturnPct,
            totalTrades, winningTrades, losingTrades, winRate, maxDd,
            avgTradePnl, sharpe, sortino, profitFactor, calmar,
            totalCommission, totalSlippage,
            List.copyOf(equityCurve), List.copyOf(trades),
            bars.getFirst().timestamp(), bars.getLast().timestamp()
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

    /**
     * Computes consecutive equity-curve percentage changes for Sharpe/Sortino.
     * We use the internal raw equity curve (including floating P&L updates
     * on every bar) to capture realistic return series.
     */
    List<Double> computePeriodReturns() {
        if (equityCurve.size() < 2) return List.of();
        List<Double> returns = new ArrayList<>(equityCurve.size() - 1);
        for (int i = 1; i < equityCurve.size(); i++) {
            double prev = equityCurve.get(i - 1);
            if (prev != 0.0) {
                returns.add((equityCurve.get(i) - prev) / prev);
            }
        }
        return returns;
    }

    // Exposed for testing
    double getEquity() { return equity; }
    int getOpenPositionCount() { return openPositions.size(); }
}
