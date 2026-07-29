package com.martinfou.trading.strategies.creative;

/**
 * Registers all creative/market-research strategies with the
 * {@link CreativeStrategyCatalog}.
 *
 * <p>Strategies with a {@code (String name, String symbol)} constructor
 * accept the symbol from the caller. Strategies with a hardcoded
 * {@code SYMBOL} constant always trade their designated pair, and
 * the supplied symbol parameter is ignored.
 */
public final class CreativeStrategyCatalogRegistrar {

    private CreativeStrategyCatalogRegistrar() {}

    public static void registerAll() {

        // =========================================================
        // 1. Strategies with (String name, String symbol) constructor
        //    — symbol is respected
        // =========================================================

        // — Trend Following —
        CreativeStrategyCatalog.register("ADXTrendFilter", sym -> new ADXTrendFilterStrategy("ADXTrendFilter", sym));
        CreativeStrategyCatalog.register("ATRChannelTrail", sym -> new ATRChannelTrailStrategy("ATRChannelTrail", sym));
        CreativeStrategyCatalog.register("ATRExpansionMomentum", sym -> new ATRExpansionMomentumStrategy("ATRExpansionMomentum", sym));
        CreativeStrategyCatalog.register("ChaikinMoneyFlow", sym -> new ChaikinMoneyFlowStrategy("ChaikinMoneyFlow", sym));
        CreativeStrategyCatalog.register("ChandelierExitTrend", sym -> new ChandelierExitTrendStrategy("ChandelierExitTrend", sym));
        CreativeStrategyCatalog.register("CompositeMomentumRanking", sym -> new CompositeMomentumRankingStrategy("CompositeMomentumRanking", sym));
        CreativeStrategyCatalog.register("DualEmaMomentum", sym -> new DualEmaMomentumStrategy("DualEmaMomentum", sym));
        CreativeStrategyCatalog.register("DualTimeframeConviction", sym -> new DualTimeframeConvictionStrategy("DualTimeframeConviction", sym));
        CreativeStrategyCatalog.register("EMAPullback", sym -> new EMAPullbackStrategy("EMAPullback", sym));
        CreativeStrategyCatalog.register("FisherTransformRSI", sym -> new FisherTransformRSIStrategy("FisherTransformRSI", sym));
        CreativeStrategyCatalog.register("HeikinAshiTrend", sym -> new HeikinAshiTrendStrategy("HeikinAshiTrend", sym));
        CreativeStrategyCatalog.register("HmmRegimeMomentum", sym -> new HmmRegimeMomentumStrategy("HmmRegimeMomentum", sym));
        CreativeStrategyCatalog.register("HMMRegimeMomentum", sym -> new HMMRegimeMomentumStrategy("HMMRegimeMomentum", sym));
        CreativeStrategyCatalog.register("IchimokuCloud", sym -> new IchimokuCloudStrategy("IchimokuCloud", sym));
        CreativeStrategyCatalog.register("MomentumAcceleration", sym -> new MomentumAccelerationStrategy("MomentumAcceleration", sym));
        CreativeStrategyCatalog.register("MomentumDivergence", sym -> new MomentumDivergenceStrategy("MomentumDivergence", sym));
        CreativeStrategyCatalog.register("ParabolicSAR", sym -> new ParabolicSARStrategy("ParabolicSAR", sym));
        CreativeStrategyCatalog.register("TripleEMACrossover", sym -> new TripleEMACrossoverStrategy("TripleEMACrossover", sym));
        CreativeStrategyCatalog.register("TrueRangeMomentum", sym -> new TrueRangeMomentumStrategy("TrueRangeMomentum", sym));
        CreativeStrategyCatalog.register("VWAPMomentum", sym -> new VWAPMomentumStrategy("VWAPMomentum", sym));

        // — Mean Reversion —
        CreativeStrategyCatalog.register("BollingerMeanReversion", sym -> new BollingerMeanReversion("BollingerMeanReversion", sym));
        CreativeStrategyCatalog.register("BollingerSqueezeBreakout", sym -> new BollingerSqueezeBreakoutStrategy("BollingerSqueezeBreakout", sym));
        CreativeStrategyCatalog.register("ConsecutiveBarExhaustion", sym -> new ConsecutiveBarExhaustionStrategy("ConsecutiveBarExhaustion", sym));
        CreativeStrategyCatalog.register("DonchianChannelBreakout", sym -> new DonchianChannelBreakoutStrategy("DonchianChannelBreakout", sym));
        CreativeStrategyCatalog.register("FalseBreakoutReversal", sym -> new FalseBreakoutReversalStrategy("FalseBreakoutReversal", sym));
        CreativeStrategyCatalog.register("FractalPattern", sym -> new FractalPatternStrategy("FractalPattern", sym));
        CreativeStrategyCatalog.register("IBSMeanReversion", sym -> new IBSMeanReversionStrategy("IBSMeanReversion", sym));
        CreativeStrategyCatalog.register("KeltnerChannelReversal", sym -> new KeltnerChannelReversalStrategy("KeltnerChannelReversal", sym));
        CreativeStrategyCatalog.register("PriceDeviationMeanReversion", sym -> new PriceDeviationMeanReversion("PriceDeviationMeanReversion", sym));
        CreativeStrategyCatalog.register("RenkoFilteredMomentum", sym -> new RenkoFilteredMomentumStrategy("RenkoFilteredMomentum", sym));
        CreativeStrategyCatalog.register("RSIDivergence", sym -> new RSIDivergenceStrategy("RSIDivergence", sym));
        CreativeStrategyCatalog.register("SeasonalMeanReversion", sym -> new SeasonalMeanReversion("SeasonalMeanReversion", sym));
        CreativeStrategyCatalog.register("SeasonalPullback", sym -> new SeasonalPullbackStrategy("SeasonalPullback", sym));
        CreativeStrategyCatalog.register("StochasticMeanReversion", sym -> new StochasticMeanReversion("StochasticMeanReversion", sym));
        CreativeStrategyCatalog.register("SwingLevelReversal", sym -> new SwingLevelReversalStrategy("SwingLevelReversal", sym));
        CreativeStrategyCatalog.register("VolatilitySpikeFade", sym -> new VolatilitySpikeFadeStrategy("VolatilitySpikeFade", sym));
        CreativeStrategyCatalog.register("VolumeProfileVA", sym -> new VolumeProfileVAStrategy("VolumeProfileVA", sym));
        CreativeStrategyCatalog.register("VwapPremiumReversion", sym -> new VwapPremiumReversionStrategy("VwapPremiumReversion", sym));
        CreativeStrategyCatalog.register("VWPReversion", sym -> new VWPReversionStrategy("VWPReversion", sym));
        CreativeStrategyCatalog.register("WickReversal", sym -> new WickReversalStrategy("WickReversal", sym));
        CreativeStrategyCatalog.register("ZScoreReversion", sym -> new ZScoreReversionStrategy("ZScoreReversion", sym));

        // — Session / Time-Based —
        CreativeStrategyCatalog.register("InsideBarBreakout", sym -> new InsideBarBreakoutStrategy("InsideBarBreakout", sym));
        CreativeStrategyCatalog.register("LondonSessionEMAPullback", sym -> new LondonSessionEMAPullbackStrategy("LondonSessionEMAPullback", sym));
        CreativeStrategyCatalog.register("MidMonthExhaustion", sym -> new MidMonthExhaustionStrategy("MidMonthExhaustion", sym));
        CreativeStrategyCatalog.register("MonthPhaseMomentum", sym -> new MonthPhaseMomentumStrategy("MonthPhaseMomentum", sym));
        CreativeStrategyCatalog.register("MonthWeekPhase", sym -> new MonthWeekPhaseStrategy("MonthWeekPhase", sym));
        CreativeStrategyCatalog.register("OpeningRangeBreakout", sym -> new OpeningRangeBreakoutStrategy("OpeningRangeBreakout", sym));
        CreativeStrategyCatalog.register("OpeningRangeContinuation", sym -> new OpeningRangeContinuationStrategy("OpeningRangeContinuation", sym));
        CreativeStrategyCatalog.register("PivotPointReversal", sym -> new PivotPointReversalStrategy("PivotPointReversal", sym));
        CreativeStrategyCatalog.register("PostNewsAbsorption", sym -> new PostNewsAbsorptionStrategy("PostNewsAbsorption", sym));
        CreativeStrategyCatalog.register("PostNewsContinuation", sym -> new PostNewsContinuationStrategy("PostNewsContinuation", sym));
        CreativeStrategyCatalog.register("SessionBreakoutMomentum", sym -> new SessionBreakoutMomentumStrategy("SessionBreakoutMomentum", sym));
        CreativeStrategyCatalog.register("SessionMomentumFlow", sym -> new SessionMomentumFlowStrategy("SessionMomentumFlow", sym));
        CreativeStrategyCatalog.register("SessionOverlapBreakout", sym -> new SessionOverlapBreakoutStrategy("SessionOverlapBreakout", sym));
        CreativeStrategyCatalog.register("TrendRetestEntry", sym -> new TrendRetestEntryStrategy("TrendRetestEntry", sym));
        CreativeStrategyCatalog.register("TurnOfMonthFlow", sym -> new TurnOfMonthFlowStrategy("TurnOfMonthFlow", sym));
        CreativeStrategyCatalog.register("WeekdaySession", sym -> new WeekdaySessionStrategy("WeekdaySession", sym));
        CreativeStrategyCatalog.register("WeekendContinuation", sym -> new WeekendContinuationStrategy("WeekendContinuation", sym));

        // — Sentiment / News-Based —
        CreativeStrategyCatalog.register("COTSentimentContrarian", sym -> new COTSentimentContrarianStrategy("COTSentimentContrarian", sym));
        CreativeStrategyCatalog.register("JPYSpecMomentum", sym -> new JPYSpecMomentumStrategy("JPYSpecMomentum", sym));
        CreativeStrategyCatalog.register("MayExhaustionFade", sym -> new MayExhaustionFadeStrategy("MayExhaustionFade", sym));

        // — Multiple Timeframe —
        CreativeStrategyCatalog.register("MACDHistogramDivergence", sym -> new MACDHistogramDivergenceStrategy("MACDHistogramDivergence", sym));

        // =========================================================
        // 2. Strategies with hardcoded SYMBOL
        //    — symbol param is ignored; they always trade designated pair
        // =========================================================

        CreativeStrategyCatalog.register("FridayBear", sym -> new FridayBearStrategy("FridayBear"));
        CreativeStrategyCatalog.register("GapFader", sym -> new GapFaderStrategy());
        CreativeStrategyCatalog.register("LondonOpenVol", sym -> new LondonOpenVolStrategy("LondonOpenVol"));
        CreativeStrategyCatalog.register("MonthlyRotation", sym -> new MonthlyRotationStrategy("MonthlyRotation"));
        CreativeStrategyCatalog.register("NYMidSessionMomentum", sym -> new NYMidSessionMomentumStrategy("NYMidSessionMomentum"));
        CreativeStrategyCatalog.register("SessionCloseReversal", sym -> new SessionCloseReversalStrategy("SessionCloseReversal"));
        CreativeStrategyCatalog.register("SessionTransition", sym -> new SessionTransitionStrategy("SessionTransition"));
        CreativeStrategyCatalog.register("ThursdayRangeExpansion", sym -> new ThursdayRangeExpansionStrategy("ThursdayRangeExpansion"));
        CreativeStrategyCatalog.register("VolClusterMomentum", sym -> new VolClusterMomentumStrategy("VolClusterMomentum"));
        CreativeStrategyCatalog.register("VolContractionBreakout", sym -> new VolContractionBreakoutStrategy("VolContractionBreakout"));

        // =========================================================
        // NOTE — Not registered (incompatible constructors):
        //   CasinoStrategy     — joke strategy (uses Random)
        //   GoBigStrategy      — joke strategy
        //   NfpWeekStrategy    — requires (name, symbol, oandaSymbol, quantity)
        //   StreakReversalStrategy — requires (name, minStreakLength)
        // =========================================================
    }
}
