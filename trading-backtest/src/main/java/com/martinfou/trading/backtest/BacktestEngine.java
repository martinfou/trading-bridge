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
 * <h3>Hedging (Edging) Support</h3>
 * <p>This engine mirrors OANDA's {@code hedgingEnabled=true} behavior:
 * opposite-side MARKET/STOP/LIMIT orders create new independent positions
 * (hedges) instead of closing existing ones. Use {@link Order#closeOnly()}
 * to <em>reduce</em> an existing opposite-side position — the backtest equivalent
 * of OANDA's {@code positionFill: REDUCE_ONLY}.</p>
 *
 * <p>For OANDA-style symbols ({@code EUR_USD}), Saturday and Sunday UTC bars are
 * skipped (spot FX closed); see {@link ForexMarketCalendar}.</p>
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
    /**
     * Hedging-enabled position store. Each symbol can hold multiple independent
     * positions (one per side, plus additional entries). Use {@link Order#isCloseOnly()}
     * to reduce an opposite-side position (REDUCE_ONLY); non-closeOnly opposite-side
     * orders add new hedge positions, matching OANDA hedging semantics.
     */
    private final Map<String, List<Position>> openPositionsBySymbol = new HashMap<>();

    // Cost configuration
    private double commissionFixed = 0.0;        // USD per trade
    private double commissionPct = 0.0;          // % of notional (e.g. 0.0007 for 0.7 pip)
    private double slippageFixed = 0.0;          // USD per trade
    private double slippagePct = 0.0;            // % of fill price (e.g. 0.0001)
    private double stopSlippagePct = 0.0;         // % added to SL fill price (e.g. 0.0001 = ~1 pip)
    private double totalCommission = 0.0;
    private double totalSlippage = 0.0;
    private double riskFreeRate = PerformanceMetrics.DEFAULT_RISK_FREE_RATE;
    private double usdJpyRate = ForexPnL.DEFAULT_USD_JPY;
    private String dataTimeframe = "h1";
    private String strategyTimeframe = "h1";

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

    /** Returns the detected periods per year from bar timestamps. */
    public double getPeriodsPerYear() {
        return PerformanceMetrics.detectPeriodsPerYear(bars);
    }

    /** Percentage slippage applied to stop-loss fills (e.g. 0.0001 for ~1 pip). */
    public BacktestEngine withStopSlippagePct(double stopSlippagePct) {
        this.stopSlippagePct = stopSlippagePct;
        return this;
    }

    /** USD/JPY rate used to convert JPY-quoted pair P&amp;L into USD (default 150). */
    public BacktestEngine withUsdJpyRate(double usdJpyRate) {
        if (usdJpyRate > 0) {
            this.usdJpyRate = usdJpyRate;
        }
        return this;
    }

    public BacktestEngine withDataTimeframe(String dataTimeframe) {
        if (dataTimeframe != null) {
            this.dataTimeframe = dataTimeframe;
        }
        return this;
    }

    public BacktestEngine withStrategyTimeframe(String strategyTimeframe) {
        if (strategyTimeframe != null) {
            this.strategyTimeframe = strategyTimeframe;
        }
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

        // Parse all bars (spot FX: skip Saturday/Sunday UTC — market closed)
        boolean isMultiTimeframe = !strategyTimeframe.equalsIgnoreCase(dataTimeframe);
        Map<String, BarAggregator> aggregators = new HashMap<>();

        for (int i = 0; i < bars.size(); i++) {
            Bar bar = bars.get(i);
            if (!ForexMarketCalendar.isTradingBar(bar)) {
                continue;
            }

            if (isMultiTimeframe) {
                BarAggregator aggregator = aggregators.computeIfAbsent(bar.symbol(), s -> new BarAggregator(s, strategyTimeframe));
                boolean isNew = aggregator.isNewPeriod(bar);
                boolean hasInProgress = aggregator.getInProgressBar() != null;

                aggregator.add(bar);

                if (isNew && hasInProgress) {
                    Bar completedBar = aggregator.getLastCompletedBar();
                    if (completedBar != null) {
                        strategy.onBar(completedBar);
                    }
                }

                if (i == bars.size() - 1) {
                    aggregator.completePeriod();
                    Bar completedBar = aggregator.getLastCompletedBar();
                    if (completedBar != null) {
                        strategy.onBar(completedBar);
                    }
                }
            } else {
                strategy.onBar(bar);
            }

            // Check SL/TP on open positions before processing new orders
            checkStopLossesTakeProfits(bar);

            // Process pending orders from the strategy
            processOrders(bar);

            // Recompute equity: cash + floating P&L from open positions
            recomputeEquity(bar);

            // Track equity curve (once per bar, after processing)
            equityCurve.add(equity);
            if (equity > peakEquity) peakEquity = equity;
        }

        // Close any remaining open positions at last trading bar's close
        Bar lastTradingBar = lastTradingBar(bars);
        if (lastTradingBar != null) {
            closeRemainingPositions(lastTradingBar);
            recomputeEquity(lastTradingBar);
        }

        return buildResult();
    }

    private static Bar lastTradingBar(List<Bar> bars) {
        Bar last = null;
        for (Bar bar : bars) {
            if (ForexMarketCalendar.isTradingBar(bar)) {
                last = bar;
            }
        }
        return last;
    }

    // ---------------------------------------------------------------
    //  Position helpers
    // ---------------------------------------------------------------

    /** Returns the mutable position list for a symbol, creating it if absent. */
    private List<Position> positions(String symbol) {
        return openPositionsBySymbol.computeIfAbsent(symbol, k -> new ArrayList<>());
    }

    /** Finds the first same-side position for a symbol, or null. */
    private Position findSameSide(String symbol, Order.Side side) {
        for (Position p : positions(symbol)) {
            if (p.side() == side) return p;
        }
        return null;
    }

    /** Finds the first opposite-side position for a symbol, or null. */
    private Position findOppositeSide(String symbol, Order.Side side) {
        for (Position p : positions(symbol)) {
            if (p.side() != side) return p;
        }
        return null;
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
                        fillPrice = Math.max(bar.open(), order.price());
                    } else if (order.side() == Order.Side.SELL && bar.low() <= order.price()) {
                        filled = true;
                        fillPrice = Math.min(bar.open(), order.price());
                    }
                }
            }

            if (filled) {
                // Apply slippage to fill price
                double adjustedPrice = applySlippage(fillPrice, order.side(), order.quantity());

                // Calculate commission
                double commission = calcCommission(adjustedPrice, order.quantity());

                order.fill();

                totalCommission += commission;

                if (order.isCloseOnly()) {
                    // REDUCE_ONLY: find opposite-side position, reduce or close it
                    // NEVER open a new position
                    reduceOppositeSide(order, adjustedPrice, bar.timestamp());
                } else {
                    // Normal (non-closeOnly) order — OANDA hedging semantics:
                    //   same-side → scale in
                    //   opposite-side → create new hedge position
                    //   no position → create new
                    handleEntryOrder(order, adjustedPrice, bar.timestamp());
                }

                log.debug("FILLED: {} {} @ {:.5f} (cost: ${:.2f})",
                    order.side(), order.quantity(), adjustedPrice, commission);
            }
        }
    }

    /**
     * REDUCE_ONLY: reduces the first opposite-side position by the order's quantity.
     * If the position is fully reduced, a Trade is recorded and the position is removed.
     * If no opposite-side position exists, the order is silently dropped (no-op),
     * matching OANDA REDUCE_ONLY when no opposite position exists.
     */
    private void reduceOppositeSide(Order order, double adjustedPrice, Instant timestamp) {
        Position opposite = findOppositeSide(order.symbol(), order.side());
        if (opposite == null) return; // nothing to reduce — silent no-op

        double qty = Math.min(order.quantity(), opposite.quantity());
        double exitPrice = adjustedPrice;
        double entryValue = opposite.entryPrice() * qty;
        double exitValue = exitPrice * qty;
        double pnl = opposite.side() == Order.Side.BUY
            ? exitValue - entryValue
            : entryValue - exitValue;
        totalTrades++;
        if (pnl > 0) winningTrades++;
        else if (pnl < 0) losingTrades++;

        trades.add(new Trade(order.symbol(), opposite.side(), opposite.entryPrice(), exitPrice,
            qty, opposite.entryTime(), timestamp, usdJpyRate, opposite.stopLoss(), opposite.takeProfit()));

        opposite.reduceQuantity(qty);

        // Remove the position if fully reduced
        if (opposite.quantity() <= 0) {
            positions(order.symbol()).remove(opposite);
        }

        log.debug("REDUCE_ONLY: {} {} PnL:${:.2f}", order.symbol(), opposite.side(), pnl);
    }

    /**
     * Handles a non-closeOnly entry order with OANDA hedging semantics:
     * <ul>
     *   <li>Same-side position exists → addQuantity (scale in)</li>
     *   <li>No same-side position → create a new independent position (hedge if opposite already exists)</li>
     * </ul>
     */
    private void handleEntryOrder(Order order, double adjustedPrice, Instant timestamp) {
        Position sameSide = findSameSide(order.symbol(), order.side());
        if (sameSide != null) {
            // Same-side exists — scale in (SL/TP from the first entry remain)
            sameSide.addQuantity(order.quantity(), adjustedPrice);
        } else {
            // No same-side position — create new position (may be a hedge)
            Position pos = new Position(order.symbol(), order.side(),
                order.quantity(), adjustedPrice, timestamp);
            if (order.stopLoss() != 0) pos.withStopLoss(order.stopLoss());
            if (order.takeProfit() != 0) pos.withTakeProfit(order.takeProfit());
            positions(order.symbol()).add(pos);
        }
    }

    // ---------------------------------------------------------------
    //  Stop-loss / take-profit checking
    // ---------------------------------------------------------------

    private void checkStopLossesTakeProfits(Bar bar) {
        List<Position> toClose = new ArrayList<>();
        for (List<Position> posList : openPositionsBySymbol.values()) {
            for (Position pos : posList) {
                double sl = pos.stopLoss();
                double tp = pos.takeProfit();

                if (pos.side() == Order.Side.BUY) {
                    // SL hit
                    if (sl > 0 && bar.low() <= sl) {
                        toClose.add(pos);
                        continue;
                    }
                    // TP hit
                    if (tp > 0 && bar.high() >= tp) {
                        toClose.add(pos);
                    }
                } else {
                    // SELL position
                    if (sl > 0 && bar.high() >= sl) {
                        toClose.add(pos);
                        continue;
                    }
                    if (tp > 0 && bar.low() <= tp) {
                        toClose.add(pos);
                    }
                }
            }
        }

        for (Position pos : toClose) {
            double exitPrice = pos.stopLoss() > 0 && hitStopLoss(pos, bar)
                ? pos.stopLoss() : pos.takeProfit();
            // Apply stop slippage (only on SL fills, not TP — TPs are limit orders)
            if (pos.stopLoss() > 0 && hitStopLoss(pos, bar) && stopSlippagePct > 0) {
                double slipAmount = exitPrice * stopSlippagePct;
                exitPrice = pos.side() == Order.Side.BUY
                    ? exitPrice - slipAmount   // LONG: SL sell, slippage pushes exit lower
                    : exitPrice + slipAmount;  // SHORT: SL buy back, slippage pushes exit higher
                totalSlippage += Math.abs(slipAmount * pos.quantity());
            }
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
        totalTrades++;

        if (pnl > 0) winningTrades++;
        else if (pnl < 0) losingTrades++;

        trades.add(new Trade(pos.symbol(), pos.side(), pos.entryPrice(), exitPrice,
            pos.quantity(), pos.entryTime(), timestamp, usdJpyRate, pos.stopLoss(), pos.takeProfit()));

        // Remove from the symbol's position list
        List<Position> posList = openPositionsBySymbol.get(pos.symbol());
        if (posList != null) {
            posList.remove(pos);
            if (posList.isEmpty()) {
                openPositionsBySymbol.remove(pos.symbol());
            }
        }

        log.debug("CLOSED: {} {} PnL:${:.2f}", pos.symbol(), pos.side(), pnl);
    }

    private void closeRemainingPositions(Bar lastBar) {
        // Collect all positions first (avoid ConcurrentModification)
        List<Position> allOpen = new ArrayList<>();
        for (List<Position> posList : openPositionsBySymbol.values()) {
            allOpen.addAll(posList);
        }
        for (Position pos : allOpen) {
            closePosition(pos, lastBar.close(), lastBar.timestamp());
        }
    }

    /**
     * Recomputes equity correctly: cash (initial + closed P&L - costs) + current floating P&L.
     * Prevents the double-counting bug from incrementally adding floating P&L each bar.
     */
    private void recomputeEquity(Bar bar) {
        double realizedPnl = trades.stream().mapToDouble(Trade::pnl).sum();
        double floatingPnl = 0;
        for (List<Position> posList : openPositionsBySymbol.values()) {
            for (Position pos : posList) {
                floatingPnl += pos.currentPnl(bar.close());
            }
        }
        equity = initialCapital - totalCommission + realizedPnl + floatingPnl;
    }

    // ---------------------------------------------------------------
    //  Cost helpers
    // ---------------------------------------------------------------

    private double applySlippage(double price, Order.Side side, double quantity) {
        double slipAmount = slippageFixed > 0 ? slippageFixed : price * slippagePct;
        totalSlippage += slipAmount * quantity;
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
        double totalPnl = trades.stream().mapToDouble(Trade::pnl).sum() - totalCommission;
        double totalReturnPct = initialCapital > 0 ? (totalPnl / initialCapital) * 100 : 0;
        double maxDd = calcMaxDrawdown();
        double winRate = totalTrades > 0 ? (double) winningTrades / totalTrades * 100 : 0;
        double avgTradePnl = totalTrades > 0 ? totalPnl / totalTrades : 0;

        // Compute advanced metrics from equity curve returns and trade P&Ls
        List<Double> periodReturns = computePeriodReturns();
        List<Double> tradePnlList = trades.stream().map(Trade::pnl).toList();
        double ppy = getPeriodsPerYear();

        double sharpe = PerformanceMetrics.sharpeRatio(periodReturns, riskFreeRate, ppy);
        double sortino = PerformanceMetrics.sortinoRatio(periodReturns, riskFreeRate, ppy);
        double profitFactor = PerformanceMetrics.profitFactor(tradePnlList);
        double calmar = PerformanceMetrics.calmarRatio(equityCurve);

        return new BacktestResult(
            strategy.name(), initialCapital, equity, totalPnl, totalReturnPct,
            totalTrades, winningTrades, losingTrades, winRate, maxDd,
            avgTradePnl, sharpe, sortino, profitFactor, calmar,
            totalCommission, totalSlippage,
            List.copyOf(equityCurve), List.copyOf(trades),
            bars.isEmpty() ? null : bars.getFirst().timestamp(),
            bars.isEmpty() ? null : bars.getLast().timestamp(),
            ppy
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
    int getOpenPositionCount() {
        return openPositionsBySymbol.values().stream()
            .mapToInt(List::size)
            .sum();
    }
}
