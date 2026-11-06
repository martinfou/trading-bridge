package com.martinfou.trading.runtime.wfa;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;

import java.util.ArrayList;
import java.util.List;

/**
 * Universal Walk-Forward Strategy Wrapper.
 * <ul>
 *   <li>Exposes {@code delegate} so {@code WfaEngine} reflection mutates native strategy fields first when present.</li>
 *   <li>Provides fallback mutable envelope parameters ({@code slScale}, {@code tpScale}, {@code warmupBars},
 *       plus legacy aliases {@code period1}, {@code period2}, {@code slAtrMult}, {@code tpAtrMult}) so
 *       compiled strategies with inline constants can still be optimized in WFA without reflection errors.</li>
 * </ul>
 */
public final class WfaParamEnvelopeStrategy implements Strategy {

    private final Strategy delegate;

    // Envelope parameters (used when mutated or when inner strategy has inline constants)
    private double slScale = 1.0;
    private double tpScale = 1.0;
    private int warmupBars = 0;

    // Fallback aliases for strategies that do not declare their own mutable fields
    private int period1 = -1;
    private int period2 = -1;
    private int fastPeriod = -1;
    private int slowPeriod = -1;
    private int atrPeriod = -1;
    private double slAtrMult = -1.0;
    private double tpAtrMult = -1.0;

    private int barIndex = 0;
    private double lastClose = 0.0;
    private double prevClose = 0.0;
    private double rollingTrSum = 0.0;
    private int trCount = 0;

    public WfaParamEnvelopeStrategy(Strategy delegate) {
        this.delegate = delegate;
    }

    public Strategy delegate() {
        return delegate;
    }

    @Override
    public String name() {
        return delegate.name();
    }

    @Override
    public void reconcileInstrument(String brokerSymbol) {
        delegate.reconcileInstrument(brokerSymbol);
    }

    @Override
    public void onTick(double bid, double ask, long volume) {
        delegate.onTick(bid, ask, volume);
    }

    @Override
    public void onBar(Bar bar) {
        barIndex++;
        if (lastClose > 0.0) {
            double tr = Math.max(
                bar.high() - bar.low(),
                Math.max(Math.abs(bar.high() - lastClose), Math.abs(bar.low() - lastClose))
            );
            rollingTrSum = (trCount < 14) ? (rollingTrSum + tr) : (rollingTrSum * (13.0 / 14.0) + tr);
            trCount++;
        }
        prevClose = lastClose;
        lastClose = bar.close();

        delegate.onBar(bar);

        int effectiveWarmup = warmupBars > 0 ? warmupBars : (period1 > 0 ? period1 : (fastPeriod > 0 ? fastPeriod : 0));
        if (effectiveWarmup > 0 && barIndex <= effectiveWarmup) {
            // Drain any premature entry orders during the envelope warmup window
            delegate.getPendingOrders();
        }
    }

    @Override
    public List<Order> getPendingOrders() {
        List<Order> raw = delegate.getPendingOrders();
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }

        double effSlScale = slScale != 1.0 ? slScale : (slAtrMult > 0 ? (slAtrMult / 2.0) : 1.0);
        double effTpScale = tpScale != 1.0 ? tpScale : (tpAtrMult > 0 ? (tpAtrMult / 4.0) : 1.0);

        if (Math.abs(effSlScale - 1.0) < 1e-6 && Math.abs(effTpScale - 1.0) < 1e-6) {
            return raw;
        }

        double approxAtr = trCount > 0 ? (rollingTrSum / Math.min(14, trCount)) : (lastClose * 0.005);
        List<Order> adjusted = new ArrayList<>(raw.size());
        for (Order o : raw) {
            if (o.closeOnly()) {
                adjusted.add(o);
                continue;
            }
            double refPrice = o.price() > 0 ? o.price() : lastClose;
            if (refPrice <= 0) {
                adjusted.add(o);
                continue;
            }

            Double newSl = o.stopLoss();
            if (Math.abs(effSlScale - 1.0) >= 1e-6) {
                if (newSl != null && newSl > 0) {
                    double dist = Math.abs(refPrice - newSl);
                    newSl = (o.side() == Order.Side.BUY)
                        ? refPrice - dist * effSlScale
                        : refPrice + dist * effSlScale;
                } else if (approxAtr > 0) {
                    double dist = 2.0 * approxAtr * effSlScale;
                    newSl = (o.side() == Order.Side.BUY) ? refPrice - dist : refPrice + dist;
                }
            }

            Double newTp = o.takeProfit();
            if (Math.abs(effTpScale - 1.0) >= 1e-6) {
                if (newTp != null && newTp > 0) {
                    double dist = Math.abs(newTp - refPrice);
                    newTp = (o.side() == Order.Side.BUY)
                        ? refPrice + dist * effTpScale
                        : refPrice - dist * effTpScale;
                } else if (approxAtr > 0) {
                    double dist = 4.0 * approxAtr * effTpScale;
                    newTp = (o.side() == Order.Side.BUY) ? refPrice + dist : refPrice - dist;
                }
            }

            Order mod = o;
            if (newSl != null && newSl > 0) {
                mod = mod.withStopLoss(newSl);
            }
            if (newTp != null && newTp > 0) {
                mod = mod.withTakeProfit(newTp);
            }
            adjusted.add(mod);
        }
        return adjusted;
    }

    @Override
    public void reset() {
        barIndex = 0;
        lastClose = 0.0;
        prevClose = 0.0;
        rollingTrSum = 0.0;
        trCount = 0;
        delegate.reset();
    }
}
