package com.martinfou.trading.core.agent;

import com.martinfou.trading.core.Order;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WeeklyStrategyOutlookTest {

    @Test
    void testWeeklyStrategyOutlookInstantiation() {
        RiskFactors riskFactors = new RiskFactors(false, true, "No major conflicts");
        
        TradeTriggerCondition setup = new TradeTriggerCondition(
            "EMA Crossover",
            Order.Side.BUY,
            Order.Type.LIMIT,
            1.1234,
            50,
            Map.of("period", "20")
        );
        
        WeeklyStrategyOutlook outlook = new WeeklyStrategyOutlook(
            "EUR_USD",
            MarketDirection.BULLISH,
            MarketRegime.HIGH_VOL_TREND,
            ComfortLevel.HIGH,
            0.65,
            62.5,
            "Bullish momentum on daily charts",
            List.of(setup),
            riskFactors,
            "Price falls below 1.1100"
        );
        
        assertNotNull(outlook);
        assertEquals("EUR_USD", outlook.targetAsset());
        assertEquals(MarketDirection.BULLISH, outlook.bias());
        assertEquals(MarketRegime.HIGH_VOL_TREND, outlook.identifiedRegime());
        assertEquals(ComfortLevel.HIGH, outlook.comfortLevel());
        assertEquals(0.65, outlook.rawSentimentScore());
        assertEquals(62.5, outlook.seasonalityWinRate());
        assertEquals("Bullish momentum on daily charts", outlook.strategyRationale());
        assertEquals(1, outlook.setups().size());
        assertEquals("EMA Crossover", outlook.setups().get(0).setupName());
        assertEquals(Order.Side.BUY, outlook.setups().get(0).side());
        assertEquals(Order.Type.LIMIT, outlook.setups().get(0).type());
        assertEquals(1.1234, outlook.setups().get(0).targetedPriceZone());
        assertEquals(50, outlook.setups().get(0).invalidationPips());
        assertEquals("20", outlook.setups().get(0).executionContextRules().get("period"));
        assertFalse(outlook.riskFactors().macroEventConflict());
        assertTrue(outlook.riskFactors().sentimentDivergence());
        assertEquals("No major conflicts", outlook.riskFactors().coreFrictionDetails());
        assertEquals("Price falls below 1.1100", outlook.alphaKillSwitchCondition());
    }
}
