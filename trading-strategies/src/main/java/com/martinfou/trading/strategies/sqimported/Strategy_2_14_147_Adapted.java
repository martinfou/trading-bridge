package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.strategies.IndicatorUtils;
import com.martinfou.trading.strategies.SQConvertedStrategyBase;
import com.martinfou.trading.core.Bar;

/**
 * Strategy 2.14.147 — ADX(14) rising + HighestInRange/WC(50) crossover
 * Entry: High[2] + 0.6 * BBRange(50, 1.3)[2]
 * SL: 150 | PT: 365 | H1 timeframe (GBP_JPY)
 */
public class Strategy_2_14_147_Adapted extends SQConvertedStrategyBase {

    private static final String NAME = "2.14.147_ADX_BB";
    private static final String SYMBOL = "GBP_JPY";
    private static final double QTY = 1000;

    public Strategy_2_14_147_Adapted() {
        super(SYMBOL, QTY, JPY_PIP);
        this.minBars = 60;
    }

    @Override
    public String name() { return NAME; }

    @Override
    protected void execute(Bar bar) {
        // ADX(14) rising check at shift 3
        double adxCurrent = adx(14, 3);
        double adxPrev    = adx(14, 4);
        // HighestInRange(50,WC) crossover — using typical price
        boolean highestCrossover = bar(3).high() > bar(4).high() && bar(2).high() > bar(3).high();

        if (adxCurrent > adxPrev && adxCurrent > 25 && highestCrossover) {
            double high2 = bar(2).high();
            double bbRange = IndicatorUtils.bbRange(history, 50, 1.3, "close", 2);
            if (bbRange > 0) {
                placeBuyStop(high2 + 0.6 * bbRange, 150, 365, 0);
            }
        }
    }

    private double adx(int period, int shift) {
        int end = history.size() - 1 - shift;
        int start = end - period + 1;
        if (start < 1) return 25;
        double sumPlusDM = 0, sumMinusDM = 0, sumTr = 0;
        for (int i = start; i <= end; i++) {
            double upMove = history.get(i).high() - history.get(i - 1).high();
            double downMove = history.get(i - 1).low() - history.get(i).low();
            double plusDM = (upMove > downMove && upMove > 0) ? upMove : 0;
            double minusDM = (downMove > upMove && downMove > 0) ? downMove : 0;
            double tr = Math.max(history.get(i).high() - history.get(i).low(),
                Math.max(Math.abs(history.get(i).high() - history.get(i - 1).close()),
                    Math.abs(history.get(i).low() - history.get(i - 1).close())));
            sumPlusDM += plusDM;
            sumMinusDM += minusDM;
            sumTr += tr;
        }
        if (sumTr == 0) return 25;
        double pdi = 100 * sumPlusDM / sumTr;
        double mdi = 100 * sumMinusDM / sumTr;
        return Math.abs(pdi - mdi) / (pdi + mdi) * 100;
    }
}
