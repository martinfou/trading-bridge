package com.martinfou.trading.strategies;

import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.strategies.builtin.*;
import com.martinfou.trading.strategies.harness.HarnessStrategyCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Unified registry for all compiled and SQLite-backed generated strategy families.
 * Example strategies (e.g. SmaCrossover) register at runtime from {@code trading-examples}.
 */
public final class StrategyCatalog {

    public enum Family {
        PROP, GENERATED, LONG_TERM, LLM_WEEKLY, NEWS_WEEKLY, CREATIVE, HARNESS, EXAMPLE
    }

    public record Entry(
        String id,
        Family family,
        String defaultSymbol,
        String type,
        List<String> indicators,
        String description,
        List<String> assetClasses,
        String tradingStyle,
        List<String> timeframeSuitability,
        List<String> recommendedSymbols,
        String complexity
    ) {}

    private record Registration(
        Family family,
        String defaultSymbol,
        String type,
        List<String> indicators,
        String description,
        List<String> assetClasses,
        String tradingStyle,
        List<String> timeframeSuitability,
        List<String> recommendedSymbols,
        String complexity,
        Function<String, Strategy> factory
    ) {}

    private static final Map<String, Registration> ENTRIES = new LinkedHashMap<>();

    static {
        bootstrapBuiltIn();
    }

    private StrategyCatalog() {}

    private static List<String> resolveAssetClasses(String id, Family family, String defaultSymbol) {
        if (id != null && id.startsWith("Futures")) {
            return List.of("FUTURES");
        }
        if (defaultSymbol != null) {
            String sym = defaultSymbol.toUpperCase();
            if (sym.startsWith("MES") || sym.startsWith("MNQ") || sym.startsWith("MGC") || sym.startsWith("M2K") || sym.startsWith("EMD") || sym.contains("FUT")) {
                return List.of("FUTURES");
            }
            if (sym.equals("IJR") || sym.equals("VB") || sym.equals("SCHA") || sym.equals("PLTR") || sym.equals("TSLA") || sym.equals("SOXL") || sym.equals("AAPL")) {
                return List.of("EQUITY");
            }
            if (sym.contains("XAU") || sym.contains("XAG") || sym.contains("BRENT") || sym.contains("WTICO")) {
                return List.of("COMMODITIES", "FOREX");
            }
        }
        if (family == Family.LONG_TERM) {
            if (id != null) {
                return switch (id) {
                    case "FuturesOpeningRangeBreakout", "FuturesTurnOfMonth" -> List.of("FUTURES");
                    case "LtRangeBreakout", "LtEfficiencyRatio", "LtCrossMomentum", "LtDoubleMA" -> List.of("FUTURES", "FOREX", "EQUITY");
                    default -> List.of("FOREX", "EQUITY");
                };
            }
            return List.of("FOREX", "EQUITY");
        }
        if (family == Family.PROP) {
            return List.of("FOREX", "COMMODITIES");
        }
        return List.of("FOREX");
    }

    private static String resolveTradingStyle(String id, Family family) {
        if (id == null) return "TREND_FOLLOWING";
        if (id.startsWith("FuturesTurnOfMonth") || id.contains("TurnOfMonth") || id.equals("WeeklyOpenGapFade")
                || id.equals("UsdCadAutumnBullish") || id.equals("GbpUsdSpringBullish")
                || id.contains("Seasonal") || id.contains("Month") || id.contains("Friday") || id.contains("Thursday") || id.contains("Weekday")) {
            return "SEASONALITY";
        }
        if (id.contains("Breakout") || id.contains("RangeBreakout") || id.equals("LondonOpenRangeBreakout") || id.equals("OverlapMomentumBurst") || id.equals("FuturesOpeningRangeBreakout")) {
            return "BREAKOUT";
        }
        if (id.contains("MeanRev") || id.contains("Reversion") || id.equals("AsianRangeMeanReversion") || id.equals("ConnorsRsi2") || id.equals("PdhlSweepReversal")) {
            return "MEAN_REVERSION";
        }
        if (id.contains("Momentum") || id.contains("Squeeze") || id.equals("InsideBarBreakout")) {
            return "MOMENTUM";
        }
        if (id.contains("Pullback") || id.contains("Continuation") || id.contains("Cross") || id.contains("DoubleMA") || id.contains("EfficiencyRatio") || id.contains("SmaCrossover")) {
            return "TREND_FOLLOWING";
        }
        return "TREND_FOLLOWING";
    }

    private static List<String> resolveTimeframes(String id, Family family) {
        if (id != null && (id.startsWith("FuturesOpeningRangeBreakout") || id.equals("LondonOpenRangeBreakout") || id.equals("AsianRangeMeanReversion"))) {
            return List.of("M1", "M5", "M15", "H1");
        }
        if (id != null) {
            if (id.contains("M5")) return List.of("M5", "M15");
            if (id.contains("M15")) return List.of("M15", "M30", "H1");
            if (id.contains("M30")) return List.of("M30", "H1");
        }
        return List.of("H1", "H4", "D1");
    }

    private static List<String> resolveRecommendedSymbols(String id, Family family, String defaultSymbol, List<String> assetClasses) {
        if (assetClasses != null && assetClasses.contains("FUTURES")) {
            List<String> recs = new ArrayList<>(List.of("MGC", "MNQ", "MES", "M2K", "EMD"));
            if (defaultSymbol != null && !defaultSymbol.isBlank()) {
                recs.remove(defaultSymbol);
                recs.addFirst(defaultSymbol);
            }
            return List.copyOf(recs);
        }
        if (assetClasses != null && assetClasses.contains("EQUITY") && !assetClasses.contains("FOREX")) {
            return List.of("IJR", "VB", "SCHA", "PLTR", "TSLA");
        }
        if (family == Family.PROP) {
            return List.of("EUR_USD", "GBP_USD", "USD_JPY", "USD_CAD", "XAU_USD");
        }
        return List.of("EUR_USD", "GBP_USD", "USD_JPY", "AUD_USD", "USD_CAD");
    }

    private static String resolveComplexity(String id, Family family) {
        if (id == null) return "INTERMEDIATE";
        if (id.equals("SmaCrossover") || id.equals("LtDoubleMA") || id.equals("LtCrossMomentum")) {
            return "BEGINNER";
        }
        if (id.contains("VolRegime") || id.contains("EfficiencyRatio") || id.contains("SupplyDemandZone") || id.contains("TurnOfMonth")) {
            return "ADVANCED";
        }
        return "INTERMEDIATE";
    }

    private static String resolveType(String id, Family family) {
        if (family == Family.PROP) {
            return switch (id) {
                case "LondonOpenRangeBreakout", "OverlapMomentumBurst" -> "Session Breakout";
                case "AsianRangeMeanReversion", "ConnorsRsi2", "WeeklyOpenGapFade" -> "Mean Reversion";
                case "SupplyDemandZone", "PdhlSweepReversal" -> "Price Action";
                case "EmaPullbackContinuation", "NyContinuation" -> "Trend Following";
                case "InsideBarBreakout" -> "Momentum";
                default -> "Trend Following";
            };
        }
        if (family == Family.GENERATED) {
            return "Rule-Based";
        }
        if (family == Family.LONG_TERM) {
            return switch (id) {
                case "FuturesOpeningRangeBreakout" -> "Session Breakout";
                case "FuturesTurnOfMonth" -> "Seasonality / Anomaly";
                case "LtCrossMomentum", "LtDoubleMA", "LtPullbackEntry", "LtEfficiencyRatio" -> "Trend Following";
                case "LtRSIMeanRev" -> "Mean Reversion";
                case "LtRSI3Momentum", "LtSqueezeMomentum" -> "Momentum";
                case "LtRangeBreakout" -> "Breakout";
                case "LtVolRegime" -> "Adaptive";
                case "LtBollingerSqueeze" -> "Volatility Breakout";
                default -> "Trend Following";
            };
        }
        if (id.equals("SmaCrossover")) {
            return "Trend Following";
        }
        return "Trend Following";
    }

    private static List<String> resolveIndicators(String id, Family family) {
        if (family == Family.PROP) {
            return switch (id) {
                case "LondonOpenRangeBreakout" -> List.of("EMA", "ATR");
                case "AsianRangeMeanReversion" -> List.of("RSI", "ATR");
                case "SupplyDemandZone", "PdhlSweepReversal", "WeeklyOpenGapFade" -> List.of("ATR");
                case "EmaPullbackContinuation" -> List.of("EMA", "RSI", "ATR");
                case "ConnorsRsi2" -> List.of("RSI", "SMA");
                case "NyContinuation" -> List.of("EMA", "ATR");
                case "InsideBarBreakout" -> List.of("Bollinger Bands", "ATR");
                case "OverlapMomentumBurst" -> List.of("Consolidation Range");
                default -> List.of();
            };
        }
        if (family == Family.GENERATED) {
            return List.of("EMA", "RSI", "Bollinger Bands");
        }
        if (family == Family.LONG_TERM) {
            return switch (id) {
                case "FuturesOpeningRangeBreakout" -> List.of("ATR", "EMA");
                case "FuturesTurnOfMonth" -> List.of("EMA", "ATR", "Calendar Window");
                case "LtCrossMomentum" -> List.of("SMA", "ATR");
                case "LtRSIMeanRev" -> List.of("RSI", "SMA", "ATR");
                case "LtRSI3Momentum" -> List.of("RSI", "EMA", "ATR");
                case "LtRangeBreakout" -> List.of("Donchian Channel", "ATR");
                case "LtVolRegime" -> List.of("ATR", "EMA", "RSI");
                case "LtBollingerSqueeze" -> List.of("Bollinger Bands", "ATR");
                case "LtSqueezeMomentum" -> List.of("Bollinger Bands", "RSI", "ATR");
                case "LtPullbackEntry" -> List.of("EMA", "SMA", "RSI", "ATR");
                case "LtDoubleMA" -> List.of("EMA", "SMA", "ATR");
                case "LtEfficiencyRatio" -> List.of("Efficiency Ratio", "EMA", "ATR");
                default -> List.of("ATR");
            };
        }
        if (id.equals("SmaCrossover")) {
            return List.of("SMA");
        }
        return List.of();
    }

    private static String resolveDescription(String id, Family family) {
        if (family == Family.PROP) {
            return switch (id) {
                case "LondonOpenRangeBreakout" -> "Enters on breakout of the 7:00–8:00 AM London range, filtered by H1 trend.";
                case "AsianRangeMeanReversion" -> "Fades Asian session highs/lows at London Open using candlestick reversals.";
                case "SupplyDemandZone" -> "Enters on price rejection of the base zone from the last impulsive H4-equivalent leg.";
                case "EmaPullbackContinuation" -> "Enters on pullbacks to the 20-EMA within a strong daily trend.";
                case "ConnorsRsi2" -> "Classic short-term mean reversion entry when RSI(2) is oversold/overbought.";
                case "PdhlSweepReversal" -> "Fades sweeps of the Previous Day High/Low (PDHL) liquidity levels.";
                case "NyContinuation" -> "Enters continuation trades during NY session after a strong London morning trend.";
                case "WeeklyOpenGapFade" -> "Fades the weekly market opening gap back toward the Friday close.";
                case "InsideBarBreakout" -> "Breakout of inside bars filtered by a Bollinger Bandwidth volatility squeeze.";
                case "OverlapMomentumBurst" -> "Breakout of the London-NY overlap range (10:00–12:00 UTC).";
                default -> "Built-in prop mechanical strategy.";
            };
        }
        if (family == Family.GENERATED) {
            return "Evolutionary strategy discovered by the Native Strategy Generator.";
        }
        if (family == Family.LONG_TERM) {
            return switch (id) {
                case "FuturesOpeningRangeBreakout" -> "Toby Crabel Opening Range Breakout (ORB) for electronic index futures with ATR risk management.";
                case "FuturesTurnOfMonth" -> "Turn-of-the-Month (TOTM) calendar anomaly capturing passive pension/401(k) monthly inflows on US futures.";
                case "LtCrossMomentum" -> "Golden cross / death cross on SMA(20)/SMA(100) with ATR risk management.";
                case "LtRSIMeanRev" -> "Mean reversion on RSI(14) extremes with SMA(100) trend filter.";
                case "LtRSI3Momentum" -> "Momentum on RSI(3) with EMA(200) trend filter and ATR stops.";
                case "LtRangeBreakout" -> "Donchian channel (20) breakout with trailing ATR stop.";
                case "LtVolRegime" -> "Adaptive: trend-follow in high vol, mean-reversion in low vol via ATR ratio.";
                case "LtBollingerSqueeze" -> "Breakout from Bollinger Bandwidth squeeze with ATR SL/TP.";
                case "LtSqueezeMomentum" -> "Bollinger squeeze breakout confirmed by RSI momentum.";
                case "LtPullbackEntry" -> "Pullback to EMA(50) in trend direction with RSI confirmation.";
                case "LtDoubleMA" -> "EMA(50)/SMA(200) golden cross / death cross with ATR risk management.";
                case "LtEfficiencyRatio" -> "Kaufman Efficiency Ratio trend filter with EMA(50) direction.";
                default -> "Long-term systematic strategy (H1, 15+ year horizon).";
            };
        }
        if (id.equals("SmaCrossover")) {
            return "Simple SMA crossover strategy (20/50 fast/slow SMA crossover).";
        }
        return "Custom or weekly compiled strategy.";
    }

    private static void bootstrapBuiltIn() {
        // 1. Prop Mechanical Strategies
        put("LondonOpenRangeBreakout", Family.PROP, "EUR_USD", LondonOpenRangeBreakoutStrategy::new);
        put("AsianRangeMeanReversion", Family.PROP, "USD_JPY", AsianRangeMeanReversionStrategy::new);
        put("SupplyDemandZone", Family.PROP, "EUR_USD", SupplyDemandZoneStrategy::new);
        put("ConnorsRsi2", Family.PROP, "EUR_USD", ConnorsRsi2Strategy::new);
        put("PdhlSweepReversal", Family.PROP, "EUR_USD", PdhlSweepReversalStrategy::new);
        put("NyContinuation", Family.PROP, "GBP_USD", NyContinuationStrategy::new);
        put("WeeklyOpenGapFade", Family.PROP, "USD_JPY", WeeklyOpenGapFadeStrategy::new);
        put("InsideBarBreakout", Family.PROP, "EUR_USD", InsideBarBreakoutStrategy::new);
        put("OverlapMomentumBurst", Family.PROP, "GBP_USD", OverlapMomentumBurstStrategy::new);
        put("BollingerBreakoutMomentum", Family.PROP, "EUR_USD", sym -> new BollingerBreakoutMomentum("BollingerBreakoutMomentum_" + sym, sym));
        put("EmaCloudTrendFilter", Family.PROP, "EUR_USD", sym -> new EmaCloudTrendFilter("EmaCloudTrendFilter_" + sym, sym));
        put("RSIPulseMomentum", Family.PROP, "EUR_USD", sym -> new RSIPulseMomentumStrategy("RSIPulseMomentum_" + sym, sym));
        put("RSI3MeanReversion", Family.PROP, "EUR_USD", sym -> new RSI3MeanReversion("RSI3MeanReversion_" + sym, sym));
        put("DlrMeanReversion", Family.PROP, "EUR_USD", sym -> new DlrMeanReversion("DlrMeanReversion_" + sym, sym));
        put("EngulfingReversal", Family.PROP, "EUR_USD", sym -> new EngulfingReversalStrategy("EngulfingReversal_" + sym, sym));

        // 2. LLM Weekly Strategies
        LlmWeeklyStrategyCatalog.all().keySet().forEach(id ->
            put(id, Family.LLM_WEEKLY, LlmWeeklyStrategyCatalog.defaultSymbol(id),
                sym -> LlmWeeklyStrategyCatalog.create(id, sym)));

        // 3. Built-in Research / Creative Strategies
        put("ADXTrendFilter", Family.CREATIVE, "EUR_USD", sym -> new ADXTrendFilterStrategy("ADXTrendFilter", sym));
        put("ATRChannelTrail", Family.CREATIVE, "EUR_USD", sym -> new ATRChannelTrailStrategy("ATRChannelTrail", sym));
        put("ATRExpansionMomentum", Family.CREATIVE, "EUR_USD", sym -> new ATRExpansionMomentumStrategy("ATRExpansionMomentum", sym));
        put("ChaikinMoneyFlow", Family.CREATIVE, "EUR_USD", sym -> new ChaikinMoneyFlowStrategy("ChaikinMoneyFlow", sym));
        put("ChandelierExitTrend", Family.CREATIVE, "EUR_USD", sym -> new ChandelierExitTrendStrategy("ChandelierExitTrend", sym));
        put("CompositeMomentumRanking", Family.CREATIVE, "EUR_USD", sym -> new CompositeMomentumRankingStrategy("CompositeMomentumRanking", sym));
        put("DualEmaMomentum", Family.CREATIVE, "EUR_USD", sym -> new DualEmaMomentumStrategy("DualEmaMomentum", sym));
        put("DualTimeframeConviction", Family.CREATIVE, "EUR_USD", sym -> new DualTimeframeConvictionStrategy("DualTimeframeConviction", sym));
        put("EMAPullback", Family.CREATIVE, "EUR_USD", sym -> new EMAPullbackStrategy("EMAPullback", sym));
        put("FisherTransformRSI", Family.CREATIVE, "EUR_USD", sym -> new FisherTransformRSIStrategy("FisherTransformRSI", sym));
        put("HeikinAshiTrend", Family.CREATIVE, "EUR_USD", sym -> new HeikinAshiTrendStrategy("HeikinAshiTrend", sym));
        put("IchimokuCloud", Family.CREATIVE, "EUR_USD", sym -> new IchimokuCloudStrategy("IchimokuCloud", sym));
        put("MomentumAcceleration", Family.CREATIVE, "EUR_USD", sym -> new MomentumAccelerationStrategy("MomentumAcceleration", sym));
        put("MomentumDivergence", Family.CREATIVE, "EUR_USD", sym -> new MomentumDivergenceStrategy("MomentumDivergence", sym));
        put("ParabolicSAR", Family.CREATIVE, "EUR_USD", sym -> new ParabolicSARStrategy("ParabolicSAR", sym));
        put("TripleEMACrossover", Family.CREATIVE, "EUR_USD", sym -> new TripleEMACrossoverStrategy("TripleEMACrossover", sym));
        put("TrueRangeMomentum", Family.CREATIVE, "EUR_USD", sym -> new TrueRangeMomentumStrategy("TrueRangeMomentum", sym));
        put("VWAPMomentum", Family.CREATIVE, "EUR_USD", sym -> new VWAPMomentumStrategy("VWAPMomentum", sym));
        put("BollingerMeanReversion", Family.CREATIVE, "EUR_USD", sym -> new BollingerMeanReversion("BollingerMeanReversion", sym));
        put("BollingerSqueezeBreakout", Family.CREATIVE, "EUR_USD", sym -> new BollingerSqueezeBreakoutStrategy("BollingerSqueezeBreakout", sym));
        put("ConsecutiveBarExhaustion", Family.CREATIVE, "EUR_USD", sym -> new ConsecutiveBarExhaustionStrategy("ConsecutiveBarExhaustion", sym));
        put("DonchianChannelBreakout", Family.CREATIVE, "EUR_USD", sym -> new DonchianChannelBreakoutStrategy("DonchianChannelBreakout", sym));
        put("FalseBreakoutReversal", Family.CREATIVE, "EUR_USD", sym -> new FalseBreakoutReversalStrategy("FalseBreakoutReversal", sym));
        put("FractalPattern", Family.CREATIVE, "EUR_USD", sym -> new FractalPatternStrategy("FractalPattern", sym));
        put("IBSMeanReversion", Family.CREATIVE, "EUR_USD", sym -> new IBSMeanReversionStrategy("IBSMeanReversion", sym));
        put("KeltnerChannelReversal", Family.CREATIVE, "EUR_USD", sym -> new KeltnerChannelReversalStrategy("KeltnerChannelReversal", sym));
        put("PriceDeviationMeanReversion", Family.CREATIVE, "EUR_USD", sym -> new PriceDeviationMeanReversion("PriceDeviationMeanReversion", sym));
        put("RenkoFilteredMomentum", Family.CREATIVE, "EUR_USD", sym -> new RenkoFilteredMomentumStrategy("RenkoFilteredMomentum", sym));
        put("RSIDivergence", Family.CREATIVE, "EUR_USD", sym -> new RSIDivergenceStrategy("RSIDivergence", sym));
        put("SeasonalMeanReversion", Family.CREATIVE, "EUR_USD", sym -> new SeasonalMeanReversion("SeasonalMeanReversion", sym));
        put("SeasonalPullback", Family.CREATIVE, "EUR_USD", sym -> new SeasonalPullbackStrategy("SeasonalPullback", sym));
        put("StochasticMeanReversion", Family.CREATIVE, "EUR_USD", sym -> new StochasticMeanReversion("StochasticMeanReversion", sym));
        put("SwingLevelReversal", Family.CREATIVE, "EUR_USD", sym -> new SwingLevelReversalStrategy("SwingLevelReversal", sym));
        put("VolatilitySpikeFade", Family.CREATIVE, "EUR_USD", sym -> new VolatilitySpikeFadeStrategy("VolatilitySpikeFade", sym));
        put("VolumeProfileVA", Family.CREATIVE, "EUR_USD", sym -> new VolumeProfileVAStrategy("VolumeProfileVA", sym));
        put("VwapPremiumReversion", Family.CREATIVE, "EUR_USD", sym -> new VwapPremiumReversionStrategy("VwapPremiumReversion", sym));
        put("VWPReversion", Family.CREATIVE, "EUR_USD", sym -> new VWPReversionStrategy("VWPReversion", sym));
        put("WickReversal", Family.CREATIVE, "EUR_USD", sym -> new WickReversalStrategy("WickReversal", sym));
        put("ZScoreReversion", Family.CREATIVE, "EUR_USD", sym -> new ZScoreReversionStrategy("ZScoreReversion", sym));
        put("CreativeInsideBarBreakout", Family.CREATIVE, "EUR_USD", sym -> new CreativeInsideBarBreakoutStrategy("CreativeInsideBarBreakout", sym));
        put("LondonSessionEMAPullback", Family.CREATIVE, "EUR_USD", sym -> new LondonSessionEMAPullbackStrategy("LondonSessionEMAPullback", sym));
        put("MidMonthExhaustion", Family.CREATIVE, "EUR_USD", sym -> new MidMonthExhaustionStrategy("MidMonthExhaustion", sym));
        put("MonthPhaseMomentum", Family.CREATIVE, "EUR_USD", sym -> new MonthPhaseMomentumStrategy("MonthPhaseMomentum", sym));
        put("MonthWeekPhase", Family.CREATIVE, "EUR_USD", sym -> new MonthWeekPhaseStrategy("MonthWeekPhase", sym));
        put("OpeningRangeBreakout", Family.CREATIVE, "EUR_USD", sym -> new OpeningRangeBreakoutStrategy("OpeningRangeBreakout", sym));
        put("OpeningRangeContinuation", Family.CREATIVE, "EUR_USD", sym -> new OpeningRangeContinuationStrategy("OpeningRangeContinuation", sym));
        put("PivotPointReversal", Family.CREATIVE, "EUR_USD", sym -> new PivotPointReversalStrategy("PivotPointReversal", sym));
        put("PostNewsAbsorption", Family.CREATIVE, "EUR_USD", sym -> new PostNewsAbsorptionStrategy("PostNewsAbsorption", sym));
        put("PostNewsContinuation", Family.CREATIVE, "EUR_USD", sym -> new PostNewsContinuationStrategy("PostNewsContinuation", sym));
        put("SessionBreakoutMomentum", Family.CREATIVE, "EUR_USD", sym -> new SessionBreakoutMomentumStrategy("SessionBreakoutMomentum", sym));
        put("SessionMomentumFlow", Family.CREATIVE, "EUR_USD", sym -> new SessionMomentumFlowStrategy("SessionMomentumFlow", sym));
        put("SessionOverlapBreakout", Family.CREATIVE, "EUR_USD", sym -> new SessionOverlapBreakoutStrategy("SessionOverlapBreakout", sym));
        put("TrendRetestEntry", Family.CREATIVE, "EUR_USD", sym -> new TrendRetestEntryStrategy("TrendRetestEntry", sym));
        put("WeekdaySession", Family.CREATIVE, "EUR_USD", sym -> new WeekdaySessionStrategy("WeekdaySession", sym));
        put("WeekendContinuation", Family.CREATIVE, "EUR_USD", sym -> new WeekendContinuationStrategy("WeekendContinuation", sym));
        put("COTSentimentContrarian", Family.CREATIVE, "EUR_USD", sym -> new COTSentimentContrarianStrategy("COTSentimentContrarian", sym));
        put("JPYSpecMomentum", Family.CREATIVE, "EUR_USD", sym -> new JPYSpecMomentumStrategy("JPYSpecMomentum", sym));
        put("MayExhaustionFade", Family.CREATIVE, "EUR_USD", sym -> new MayExhaustionFadeStrategy("MayExhaustionFade", sym));
        put("MACDHistogramDivergence", Family.CREATIVE, "EUR_USD", sym -> new MACDHistogramDivergenceStrategy("MACDHistogramDivergence", sym));
        put("FridayBear", Family.CREATIVE, "GBP_JPY", sym -> new FridayBearStrategy("FridayBear"));
        put("GapFader", Family.CREATIVE, "GBP_JPY", sym -> new GapFaderStrategy());
        put("LondonOpenVol", Family.CREATIVE, "GBP_JPY", sym -> new LondonOpenVolStrategy("LondonOpenVol"));
        put("MonthlyRotation", Family.CREATIVE, "GBP_JPY", sym -> new MonthlyRotationStrategy("MonthlyRotation"));
        put("NYMidSessionMomentum", Family.CREATIVE, "GBP_JPY", sym -> new NYMidSessionMomentumStrategy("NYMidSessionMomentum"));
        put("SessionCloseReversal", Family.CREATIVE, "GBP_JPY", sym -> new SessionCloseReversalStrategy("SessionCloseReversal"));
        put("SessionTransition", Family.CREATIVE, "GBP_JPY", sym -> new SessionTransitionStrategy("SessionTransition"));
        put("ThursdayRangeExpansion", Family.CREATIVE, "GBP_JPY", sym -> new ThursdayRangeExpansionStrategy("ThursdayRangeExpansion"));
        put("VolClusterMomentum", Family.CREATIVE, "GBP_JPY", sym -> new VolClusterMomentumStrategy("VolClusterMomentum"));
        put("VolContractionBreakout", Family.CREATIVE, "GBP_JPY", sym -> new VolContractionBreakoutStrategy("VolContractionBreakout"));
        put("UsdCadAutumnBullish", Family.CREATIVE, "USD_CAD", UsdCadAutumnBullish::new);
        put("GbpUsdSpringBullish", Family.CREATIVE, "GBP_USD", GbpUsdSpringBullish::new);

        // 4. Long-Term Systematic Strategies
        put("LtCrossMomentum", Family.LONG_TERM, "EUR_USD", sym -> new LtCrossMomentum("LtCrossMomentum", sym));
        put("LtRSIMeanRev", Family.LONG_TERM, "EUR_USD", sym -> new LtRSIMeanRev("LtRSIMeanRev", sym));
        put("LtRSI3Momentum", Family.LONG_TERM, "EUR_USD", sym -> new LtRSI3Momentum("LtRSI3Momentum", sym));
        put("LtRangeBreakout", Family.LONG_TERM, "EUR_USD", sym -> new LtRangeBreakout("LtRangeBreakout", sym));
        put("LtVolRegime", Family.LONG_TERM, "EUR_USD", sym -> new LtVolRegime("LtVolRegime", sym));
        put("LtBollingerSqueeze", Family.LONG_TERM, "EUR_USD", sym -> new LtBollingerSqueeze("LtBollingerSqueeze", sym));
        put("LtSqueezeMomentum", Family.LONG_TERM, "EUR_USD", sym -> new LtSqueezeMomentum("LtSqueezeMomentum", sym));
        put("LtPullbackEntry", Family.LONG_TERM, "EUR_USD", sym -> new LtPullbackEntry("LtPullbackEntry", sym));
        put("LtDoubleMA", Family.LONG_TERM, "EUR_USD", sym -> new LtDoubleMA("LtDoubleMA", sym));
        put("LtEfficiencyRatio", Family.LONG_TERM, "EUR_USD", sym -> new LtEfficiencyRatio("LtEfficiencyRatio", sym));

        // 5. Harness Strategies
        HarnessStrategyCatalog.all().keySet().forEach(id ->
            put(id, Family.HARNESS, HarnessStrategyCatalog.defaultSymbol(id),
                sym -> HarnessStrategyCatalog.create(id, sym)));
    }

    /** Runtime registration for example strategies (called from RunBacktest). */
    public static synchronized void register(String id, Family family, Function<String, Strategy> factory, String defaultSymbol) {
        if (family != Family.EXAMPLE) {
            throw new IllegalArgumentException("register() is for EXAMPLE family only; built-ins use sub-catalogs");
        }
        put(id, family, defaultSymbol, factory);
    }

    /**
     * Runtime registration for strategies discovered and promoted via the Native Strategy Generator.
     * Overwrites any existing dynamic registration with the same id.
     */
    public static synchronized void registerGenerated(
        String id,
        Family family,
        String defaultSymbol,
        String type,
        List<String> indicators,
        String description,
        String tradingStyle,
        List<String> timeframes,
        Function<String, Strategy> factory
    ) {
        Family resolvedFamily = family != null ? family : Family.GENERATED;
        String resolvedSymbol = (defaultSymbol != null && !defaultSymbol.isBlank()) ? defaultSymbol : "EUR_USD";
        List<String> assetClasses = resolveAssetClasses(id, resolvedFamily, resolvedSymbol);
        List<String> recommendedSymbols = new ArrayList<>(resolveRecommendedSymbols(id, resolvedFamily, resolvedSymbol, assetClasses));
        recommendedSymbols.remove(resolvedSymbol);
        recommendedSymbols.addFirst(resolvedSymbol);
        ENTRIES.put(id, new Registration(
            resolvedFamily,
            resolvedSymbol,
            (type != null && !type.isBlank()) ? type : resolveType(id, resolvedFamily),
            (indicators != null && !indicators.isEmpty()) ? List.copyOf(indicators) : resolveIndicators(id, resolvedFamily),
            (description != null && !description.isBlank()) ? description : resolveDescription(id, resolvedFamily),
            assetClasses,
            (tradingStyle != null && !tradingStyle.isBlank()) ? tradingStyle : resolveTradingStyle(id, resolvedFamily),
            (timeframes != null && !timeframes.isEmpty()) ? List.copyOf(timeframes) : resolveTimeframes(id, resolvedFamily),
            List.copyOf(recommendedSymbols),
            "INTERMEDIATE",
            factory
        ));
    }

    public static Strategy create(String id, String symbol) {
        return create(id, symbol, null);
    }

    /** Creates the strategy directly from its registered factory without wrapping in {@link FixedQuantityStrategy}. */
    public static Strategy createRaw(String id, String symbol) {
        Registration reg = ENTRIES.get(id);
        if (reg == null) {
            throw new IllegalArgumentException("Unknown strategy: " + id);
        }
        return reg.factory().apply(symbol);
    }

    public static Strategy create(String id, String symbol, Double quantityUnits) {
        Registration reg = ENTRIES.get(id);
        if (reg == null) {
            throw new IllegalArgumentException("Unknown strategy: " + id);
        }
        Strategy strategy = reg.factory().apply(symbol);
        if (quantityUnits == null && reg.family() == Family.GENERATED) {
            return strategy;
        }
        return new FixedQuantityStrategy(strategy, com.martinfou.trading.core.LotSizing.resolveQuantityUnits(quantityUnits, symbol));
    }

    public static synchronized void unregister(String id) {
        if (id != null) {
            ENTRIES.remove(id);
        }
    }

    public static synchronized void unregisterAllGenerated() {
        ENTRIES.entrySet().removeIf(e -> e.getValue().family() == Family.GENERATED);
    }

    public static String defaultSymbol(String id) {
        Registration reg = ENTRIES.get(id);
        if (reg == null) {
            throw new IllegalArgumentException("Unknown strategy: " + id);
        }
        return reg.defaultSymbol();
    }

    public static Family family(String id) {
        Registration reg = ENTRIES.get(id);
        if (reg == null) {
            throw new IllegalArgumentException("Unknown strategy: " + id);
        }
        return reg.family();
    }

    public static boolean requireBacktest(String id) {
        Family f = family(id);
        return f != Family.NEWS_WEEKLY && f != Family.LLM_WEEKLY;
    }

    public static List<String> ids() {
        return List.copyOf(ENTRIES.keySet());
    }

    public static List<Entry> entries() {
        var list = new ArrayList<Entry>(ENTRIES.size());
        ENTRIES.forEach((id, reg) -> list.add(new Entry(
            id,
            reg.family(),
            reg.defaultSymbol(),
            reg.type(),
            reg.indicators(),
            reg.description(),
            reg.assetClasses(),
            reg.tradingStyle(),
            reg.timeframeSuitability(),
            reg.recommendedSymbols(),
            reg.complexity()
        )));
        return List.copyOf(list);
    }

    public static boolean contains(String id) {
        return ENTRIES.containsKey(id);
    }

    /** Test-only: reset to built-in registrations (clears EXAMPLE entries). */
    public static synchronized void resetForTesting() {
        ENTRIES.clear();
        bootstrapBuiltIn();
    }

    /**
     * Query strategies by criteria. All filters are optional — pass null to skip.
     *
     * @param family   filter by family (null = any)
     * @param type     filter by type/category (null = any)
     * @param text     search name, description, indicators (case-insensitive, null = any)
     * @return filtered list of entries
     */
    public static List<Entry> query(Family family, String type, String text) {
        return entries().stream()
            .filter(e -> family == null || e.family() == family)
            .filter(e -> type == null || type.isBlank() || e.type().equalsIgnoreCase(type))
            .filter(e -> {
                if (text == null || text.isBlank()) return true;
                String lower = text.toLowerCase();
                return e.id().toLowerCase().contains(lower)
                    || e.description().toLowerCase().contains(lower)
                    || e.indicators().stream().anyMatch(i -> i.toLowerCase().contains(lower));
            })
            .collect(java.util.stream.Collectors.toList());
    }

    /**
     * Print the catalog to stdout in a structured format.
     */
    public static void printCatalog() {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║  Strategy Catalog — " + ENTRIES.size() + " strategies                          ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        Map<Family, List<Entry>> byFamily = entries().stream()
            .collect(java.util.stream.Collectors.groupingBy(Entry::family));

        for (var family : byFamily.keySet()) {
            System.out.println("── " + family + " (" + byFamily.get(family).size() + ") ──");
            for (var e : byFamily.get(family)) {
                System.out.printf("  %-30s %-20s %s%n",
                    e.id(), e.type(), e.description());
            }
            System.out.println();
        }
    }

    private static synchronized void put(String id, Family family, String defaultSymbol, Function<String, Strategy> factory) {
        if (ENTRIES.containsKey(id)) {
            throw new IllegalStateException("Duplicate strategy id: " + id);
        }
        String type = resolveType(id, family);
        List<String> indicators = resolveIndicators(id, family);
        String description = resolveDescription(id, family);
        List<String> assetClasses = resolveAssetClasses(id, family, defaultSymbol);
        String tradingStyle = resolveTradingStyle(id, family);
        List<String> timeframes = resolveTimeframes(id, family);
        List<String> recommendedSymbols = resolveRecommendedSymbols(id, family, defaultSymbol, assetClasses);
        String complexity = resolveComplexity(id, family);

        ENTRIES.put(id, new Registration(
            family, defaultSymbol, type, indicators, description,
            assetClasses, tradingStyle, timeframes, recommendedSymbols, complexity,
            factory
        ));
    }
}
