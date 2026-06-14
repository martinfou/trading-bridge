package com.martinfou.trading.intelligence.agent;

import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.agent.MarketDirection;
import com.martinfou.trading.core.agent.MarketRegime;
import com.martinfou.trading.core.agent.RiskFactors;
import com.martinfou.trading.core.agent.TradeTriggerCondition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WeeklyStrategyOutlookRawTest {

    @Test
    void testWeeklyStrategyOutlookRawInstantiation() {
        RiskFactors riskFactors = new RiskFactors(true, false, "Conflict with NFP");
        TradeTriggerCondition setup = new TradeTriggerCondition(
            "Breakout",
            Order.Side.SELL,
            Order.Type.STOP,
            1.0500,
            30,
            Map.of("level", "support")
        );

        WeeklyStrategyOutlookRaw raw = new WeeklyStrategyOutlookRaw(
            "EUR_USD",
            MarketDirection.BEARISH,
            MarketRegime.HIGH_VOL_TREND,
            -0.8,
            35.0,
            "Bearish breakout bias",
            List.of(setup),
            riskFactors,
            "High impact news release"
        );

        assertNotNull(raw);
        assertEquals("EUR_USD", raw.targetAsset());
        assertEquals(MarketDirection.BEARISH, raw.bias());
        assertEquals(MarketRegime.HIGH_VOL_TREND, raw.identifiedRegime());
        assertEquals(-0.8, raw.rawSentimentScore());
        assertEquals(35.0, raw.seasonalityWinRate());
        assertEquals(1, raw.setups().size());
        assertEquals(Order.Side.SELL, raw.setups().get(0).side());
        assertEquals("Conflict with NFP", raw.riskFactors().coreFrictionDetails());
    }

    @Test
    void testSentimentDataInstantiation() {
        SentimentData sentiment = new SentimentData(
            "EUR_USD",
            0.45,
            "60% Long / 40% Short",
            List.of("EUR strengthens against USD", "ECB rate decision expectations")
        );

        assertNotNull(sentiment);
        assertEquals("EUR_USD", sentiment.asset());
        assertEquals(0.45, sentiment.sentimentScore());
        assertEquals("60% Long / 40% Short", sentiment.retailRatioString());
        assertEquals(2, sentiment.newsHeadlines().size());
    }

    @Test
    void testSeasonalityDataInstantiation() {
        SeasonalityData seasonality = new SeasonalityData(
            "GBP_USD",
            24,
            MarketDirection.NEUTRAL,
            15
        );

        assertNotNull(seasonality);
        assertEquals("GBP_USD", seasonality.asset());
        assertEquals(24, seasonality.weekOfYear());
        assertEquals(MarketDirection.NEUTRAL, seasonality.directionalBias());
        assertEquals(15, seasonality.averagePips());
    }

    @Test
    void testJacksonDeserializationCaseInsensitive() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = com.fasterxml.jackson.databind.json.JsonMapper.builder()
                .configure(com.fasterxml.jackson.databind.MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, true)
                .build();

        String jsonBullishUpper = "{\"targetAsset\":\"EUR_USD\",\"bias\":\"BULLISH\",\"identifiedRegime\":\"HIGH_VOL_TREND\",\"rawSentimentScore\":0.2,\"seasonalityWinRate\":65.0,\"strategyRationale\":\"test\",\"setups\":[],\"riskFactors\":{\"sentimentDivergence\":false,\"macroEventConflict\":false,\"coreFrictionDetails\":\"\"},\"alphaKillSwitchCondition\":\"test\"}";
        WeeklyStrategyOutlookRaw raw1 = mapper.readValue(jsonBullishUpper, WeeklyStrategyOutlookRaw.class);
        assertEquals(MarketDirection.BULLISH, raw1.bias());

        String jsonBullishMixed = "{\"targetAsset\":\"EUR_USD\",\"bias\":\"Bullish\",\"identifiedRegime\":\"HIGH_VOL_TREND\",\"rawSentimentScore\":0.2,\"seasonalityWinRate\":65.0,\"strategyRationale\":\"test\",\"setups\":[],\"riskFactors\":{\"sentimentDivergence\":false,\"macroEventConflict\":false,\"coreFrictionDetails\":\"\"},\"alphaKillSwitchCondition\":\"test\"}";
        WeeklyStrategyOutlookRaw raw2 = mapper.readValue(jsonBullishMixed, WeeklyStrategyOutlookRaw.class);
        assertEquals(MarketDirection.BULLISH, raw2.bias());

        String jsonBullishLower = "{\"targetAsset\":\"EUR_USD\",\"bias\":\"bullish\",\"identifiedRegime\":\"HIGH_VOL_TREND\",\"rawSentimentScore\":0.2,\"seasonalityWinRate\":65.0,\"strategyRationale\":\"test\",\"setups\":[],\"riskFactors\":{\"sentimentDivergence\":false,\"macroEventConflict\":false,\"coreFrictionDetails\":\"\"},\"alphaKillSwitchCondition\":\"test\"}";
        WeeklyStrategyOutlookRaw raw3 = mapper.readValue(jsonBullishLower, WeeklyStrategyOutlookRaw.class);
        assertEquals(MarketDirection.BULLISH, raw3.bias());
    }
}
