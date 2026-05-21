package com.martinfou.trading.strategies.sqimported;

import com.martinfou.trading.strategies.IndicatorUtils;
import com.martinfou.trading.strategies.SQConvertedStrategyBase;
import com.martinfou.trading.core.Bar;

/**
 * Strategy 2.31.177 — Meilleur R/R (3.1)
 * Signal: Open croise au-dessus de LinReg(40, CLOSE)
 *     ── shift 3: Open[2+1] < LinReg(40, CLOSE, 2+1)
 *        shift 2: Open[2]   > LinReg(40, CLOSE, 2+1)
 *        ⚠ Correction: le second LinReg utilisait le mauvais shift (3 au lieu de 2).
 * Entry: BUYSTOP at (Lower Bollinger(10,2) + 1.0 * BBRange(20,2))
 * SL: 95 pips, PT: 290 pips | Trailing: 70 pips (activation à 100) | Exp: 168 bars
 */
public class Strategy_2_31_177_Converted extends SQConvertedStrategyBase {

    private static final String NAME = "2.31.177_LinRegBB";
    private static final String SYMBOL = "GBP_JPY";
    private static final double QTY = 1000;

    public Strategy_2_31_177_Converted() {
        super(SYMBOL, QTY, JPY_PIP);
        this.minBars = 50;
    }

    @Override
    public String name() { return NAME; }

    @Override
    protected void execute(Bar bar) {
        // LongEntrySignal JForex:
        // (Open[2+1] < LinReg(40, CLOSE, 2+1) && Open[2] > LinReg(40, CLOSE, 2))
        double open3   = bar(3).open();
        double linReg3 = IndicatorUtils.linReg(history, 40, "close", 3);
        double open2   = bar(2).open();
        double linReg2 = IndicatorUtils.linReg(history, 40, "close", 2);  // FIXED: was shift 3

        boolean longEntry = (open3 < linReg3) && (open2 > linReg2);

        if (longEntry) {
            double sma     = IndicatorUtils.sma(history, 10, "close", 3);
            double bbRange = IndicatorUtils.bbRange(history, 20, 2.0, "close", 2);
            if (sma > 0 && bbRange > 0) {
                double lowerBand = sma - (bbRange / 2.0);
                double entryPrice = lowerBand + 1.0 * bbRange;
                placeBuyStop(entryPrice, 95, 290, 168);
            }
        }
    }
}
