/* Copyright (C) 2025 Interactive Brokers LLC. All rights reserved. This code is subject to the terms
 * and conditions of the IB API Non-Commercial License or the IB API Commercial License, as applicable. */

package com.ib.client;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.ib.client.protobuf.AccountDataEndProto;
import com.ib.client.protobuf.AccountSummaryEndProto;
import com.ib.client.protobuf.AccountSummaryProto;
import com.ib.client.protobuf.AccountUpdateMultiEndProto;
import com.ib.client.protobuf.AccountUpdateMultiProto;
import com.ib.client.protobuf.AccountUpdateTimeProto;
import com.ib.client.protobuf.AccountValueProto;
import com.ib.client.protobuf.CommissionAndFeesReportProto;
import com.ib.client.protobuf.CompletedOrderProto;
import com.ib.client.protobuf.CompletedOrdersEndProto;
import com.ib.client.protobuf.ConfigResponseProto;
import com.ib.client.protobuf.ContractDataEndProto;
import com.ib.client.protobuf.ContractDataProto;
import com.ib.client.protobuf.CurrentTimeInMillisProto;
import com.ib.client.protobuf.CurrentTimeProto;
import com.ib.client.protobuf.DisplayGroupListProto;
import com.ib.client.protobuf.DisplayGroupUpdatedProto;
import com.ib.client.protobuf.ErrorMessageProto;
import com.ib.client.protobuf.ExecutionDetailsEndProto;
import com.ib.client.protobuf.ExecutionDetailsProto;
import com.ib.client.protobuf.FamilyCodesProto;
import com.ib.client.protobuf.FundamentalsDataProto;
import com.ib.client.protobuf.HeadTimestampProto;
import com.ib.client.protobuf.HistogramDataProto;
import com.ib.client.protobuf.HistoricalDataEndProto;
import com.ib.client.protobuf.HistoricalDataProto;
import com.ib.client.protobuf.HistoricalDataUpdateProto;
import com.ib.client.protobuf.HistoricalNewsEndProto;
import com.ib.client.protobuf.HistoricalNewsProto;
import com.ib.client.protobuf.HistoricalScheduleProto;
import com.ib.client.protobuf.HistoricalTicksBidAskProto;
import com.ib.client.protobuf.HistoricalTicksLastProto;
import com.ib.client.protobuf.HistoricalTicksProto;
import com.ib.client.protobuf.ManagedAccountsProto;
import com.ib.client.protobuf.MarketDataTypeProto;
import com.ib.client.protobuf.MarketDepthExchangesProto;
import com.ib.client.protobuf.MarketDepthL2Proto;
import com.ib.client.protobuf.MarketDepthProto;
import com.ib.client.protobuf.MarketRuleProto;
import com.ib.client.protobuf.NewsArticleProto;
import com.ib.client.protobuf.NewsBulletinProto;
import com.ib.client.protobuf.NewsProvidersProto;
import com.ib.client.protobuf.NextValidIdProto;
import com.ib.client.protobuf.OpenOrderProto;
import com.ib.client.protobuf.OpenOrdersEndProto;
import com.ib.client.protobuf.OrderBoundProto;
import com.ib.client.protobuf.OrderStatusProto;
import com.ib.client.protobuf.PnLProto;
import com.ib.client.protobuf.PnLSingleProto;
import com.ib.client.protobuf.PortfolioValueProto;
import com.ib.client.protobuf.PositionEndProto;
import com.ib.client.protobuf.PositionMultiEndProto;
import com.ib.client.protobuf.PositionMultiProto;
import com.ib.client.protobuf.PositionProto;
import com.ib.client.protobuf.RealTimeBarTickProto;
import com.ib.client.protobuf.ReceiveFAProto;
import com.ib.client.protobuf.ReplaceFAEndProto;
import com.ib.client.protobuf.RerouteMarketDataRequestProto;
import com.ib.client.protobuf.RerouteMarketDepthRequestProto;
import com.ib.client.protobuf.ScannerDataProto;
import com.ib.client.protobuf.ScannerParametersProto;
import com.ib.client.protobuf.SecDefOptParameterEndProto;
import com.ib.client.protobuf.SecDefOptParameterProto;
import com.ib.client.protobuf.SmartComponentsProto;
import com.ib.client.protobuf.SoftDollarTiersProto;
import com.ib.client.protobuf.SymbolSamplesProto;
import com.ib.client.protobuf.TickByTickDataProto;
import com.ib.client.protobuf.TickGenericProto;
import com.ib.client.protobuf.TickNewsProto;
import com.ib.client.protobuf.TickOptionComputationProto;
import com.ib.client.protobuf.TickPriceProto;
import com.ib.client.protobuf.TickReqParamsProto;
import com.ib.client.protobuf.TickSizeProto;
import com.ib.client.protobuf.TickSnapshotEndProto;
import com.ib.client.protobuf.TickStringProto;
import com.ib.client.protobuf.UpdateConfigResponseProto;
import com.ib.client.protobuf.UserInfoProto;
import com.ib.client.protobuf.VerifyCompletedProto;
import com.ib.client.protobuf.VerifyMessageApiProto;
import com.ib.client.protobuf.WshEventDataProto;
import com.ib.client.protobuf.WshMetaDataProto;

import java.util.Set;

public class DefaultEWrapper implements EWrapper {

	@Override
	public void tickPrice(int tickerId, int field, double price,
			TickAttrib attribs) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void tickSize(int tickerId, int field, Decimal size) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void tickOptionComputation(int tickerId, int field, int tickAttrib,
			double impliedVol, double delta, double optPrice,
			double pvDividend, double gamma, double vega, double theta,
			double undPrice) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void tickGeneric(int tickerId, int tickType, double value) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void tickString(int tickerId, int tickType, String value) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void tickEFP(int tickerId, int tickType, double basisPoints,
			String formattedBasisPoints, double impliedFuture, int holdDays,
			String futureLastTradeDate, double dividendImpact, double dividendsToLastTradeDate) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void orderStatus(int orderId, String status, Decimal filled,
			Decimal remaining, double avgFillPrice, long permId, int parentId,
			double lastFillPrice, int clientId, String whyHeld, double mktCapPrice) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void openOrder(int orderId, Contract contract, Order order,
			OrderState orderState) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void openOrderEnd() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateAccountValue(String key, String value, String currency,
			String accountName) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updatePortfolio(Contract contract, Decimal position,
			double marketPrice, double marketValue, double averageCost,
			double unrealizedPNL, double realizedPNL, String accountName) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateAccountTime(String timeStamp) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void accountDownloadEnd(String accountName) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void nextValidId(int orderId) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void contractDetails(int reqId, ContractDetails contractDetails) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void bondContractDetails(int reqId, ContractDetails contractDetails) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void contractDetailsEnd(int reqId) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void execDetails(int reqId, Contract contract, Execution execution) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void execDetailsEnd(int reqId) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateMktDepth(int tickerId, int position, int operation,
			int side, double price, Decimal size) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateMktDepthL2(int tickerId, int position,
			String marketMaker, int operation, int side, double price, Decimal size, boolean isSmartDepth) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void updateNewsBulletin(int msgId, int msgType, String message,
			String origExchange) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void managedAccounts(String accountsList) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void receiveFA(int faDataType, String xml) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void historicalData(int reqId, Bar bar) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void scannerParameters(String xml) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void scannerData(int reqId, int rank,
			ContractDetails contractDetails, String distance, String benchmark,
			String projection, String legsStr) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void scannerDataEnd(int reqId) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void realtimeBar(int reqId, long time, double open, double high,
			double low, double close, Decimal volume, Decimal wap, int count) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void currentTime(long time) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void fundamentalData(int reqId, String data) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void deltaNeutralValidation(int reqId, DeltaNeutralContract deltaNeutralContract) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void tickSnapshotEnd(int reqId) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void marketDataType(int reqId, int marketDataType) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void commissionAndFeesReport(CommissionAndFeesReport commissionAndFeesReport) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void position(String account, Contract contract, Decimal pos,
			double avgCost) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void positionEnd() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void accountSummary(int reqId, String account, String tag,
			String value, String currency) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void accountSummaryEnd(int reqId) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void verifyMessageAPI(String apiData) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void verifyCompleted(boolean isSuccessful, String errorText) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void verifyAndAuthMessageAPI(String apiData, String xyzChallenge) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void verifyAndAuthCompleted(boolean isSuccessful, String errorText) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void displayGroupList(int reqId, String groups) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void displayGroupUpdated(int reqId, String contractInfo) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void error(Exception e) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void error(String str) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void error(int id, long errorTime, int errorCode, String errorMsg, String advancedOrderRejectJson) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void connectionClosed() {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void connectAck() {
		// TODO Auto-generated method stub
		
	}
	
	@Override
	public void positionMulti( int reqId, String account, String modelCode, Contract contract, Decimal pos, double avgCost) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void positionMultiEnd( int reqId) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void accountUpdateMulti( int reqId, String account, String modelCode, String key, String value, String currency) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void accountUpdateMultiEnd( int reqId) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void securityDefinitionOptionalParameter(int reqId, String exchange, int underlyingConId, String tradingClass,
			String multiplier, Set<String> expirations, Set<Double> strikes) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void securityDefinitionOptionalParameterEnd(int reqId) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void softDollarTiers(int reqId, SoftDollarTier[] tiers) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void familyCodes(FamilyCode[] familyCodes) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void historicalDataEnd(int reqId, String startDateStr, String endDateStr) {
		// TODO Auto-generated method stub
		
	}
	
	@Override
	public void symbolSamples(int reqId, ContractDescription[] contractDescriptions) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void mktDepthExchanges(DepthMktDataDescription[] depthMktDataDescriptions) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void tickNews(int tickerId, long timeStamp, String providerCode, String articleId, String headline, String extraData) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void smartComponents(int reqId, Map<Integer, Entry<String, Character>> theMap) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void tickReqParams(int tickerId, double minTick, String bboExchange, int snapshotPermissions) {
		// TODO Auto-generated method stub
		
	}
	
	
	@Override
	public void newsProviders(NewsProvider[] newsProviders) {
		// TODO Auto-generated method stub
		
	}
	
	@Override
	public void newsArticle(int requestId, int articleType, String articleText) {
		// TODO Auto-generated method stub
		
	}
	
	@Override
	public void historicalNews(int requestId, String time, String providerCode, String articleId, String headline) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void historicalNewsEnd(int requestId, boolean hasMore) {
		// TODO Auto-generated method stub
		
	}
	
	@Override
	public void headTimestamp(int reqId, String headTimestamp) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void histogramData(int reqId, List<HistogramEntry> items) {
		// TODO Auto-generated method stub
		
	}
	
    @Override
    public void historicalDataUpdate(int reqId, Bar bar) {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void pnl(int reqId, double dailyPnL, double unrealizedPnL, double realizedPnL) {
        // TODO Auto-generated method stub
        
    }

	@Override
	public void rerouteMktDataReq(int reqId, int conId, String exchange) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void rerouteMktDepthReq(int reqId, int conId, String exchange) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void marketRule(int marketRuleId, PriceIncrement[] priceIncrements) {
		// TODO Auto-generated method stub
		
	}

    @Override
    public void pnlSingle(int reqId, Decimal pos, double dailyPnL, double unrealizedPnL, double realizedPnL, double value) {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void historicalTicks(int reqId, List<HistoricalTick> ticks, boolean last) {
        // TODO Auto-generated method stub
        
    }


    @Override
    public void historicalTicksBidAsk(int reqId, List<HistoricalTickBidAsk> ticks, boolean done) {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void historicalTicksLast(int reqId, List<HistoricalTickLast> ticks, boolean done) {
        // TODO Auto-generated method stub
        
    }

    @Override
    public void tickByTickAllLast(int reqId, int tickType, long time, double price, Decimal size, TickAttribLast tickAttribLast,
             String exchange, String specialConditions) {
        // TODO Auto-generated method stub
    }

    @Override
    public void tickByTickBidAsk(int reqId, long time, double bidPrice, double askPrice, Decimal bidSize, Decimal askSize,
            TickAttribBidAsk tickAttribBidAsk) {
        // TODO Auto-generated method stub
	}

    @Override
    public void tickByTickMidPoint(int reqId, long time, double midPoint) {
        // TODO Auto-generated method stub
    }

    @Override
    public void orderBound(long permId, int clientId, int orderId) {
        // TODO Auto-generated method stub
    }

    @Override
    public void completedOrder(Contract contract, Order order, OrderState orderState) {
        // TODO Auto-generated method stub
    }

    @Override
    public void completedOrdersEnd() {
        // TODO Auto-generated method stub
    }

    @Override
    public void replaceFAEnd(int reqId, String text) {
        // TODO Auto-generated method stub
    }

	@Override
	public void wshMetaData(int reqId, String dataJson) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void wshEventData(int reqId, String dataJson) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void historicalSchedule(int reqId, String startDateTime, String endDateTime, String timeZone,
			List<HistoricalSession> sessions) {
		// TODO Auto-generated method stub
		
	}
	
	@Override
	public void userInfo(int reqId, String whiteBrandingId) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public void currentTimeInMillis(long timeInMillis) {
		// TODO Auto-generated method stub
		
	}
	
    // ---------------------------------------------- Protobuf ---------------------------------------------
    @Override public void orderStatusProtoBuf(OrderStatusProto.OrderStatus orderStatusProto) { }
    @Override public void openOrderProtoBuf(OpenOrderProto.OpenOrder openOrderProto) { }
    @Override public void openOrdersEndProtoBuf(OpenOrdersEndProto.OpenOrdersEnd openOrdersEnd) { }
    @Override public void errorProtoBuf(ErrorMessageProto.ErrorMessage errorMessageProto) { }
    @Override public void execDetailsProtoBuf(ExecutionDetailsProto.ExecutionDetails executionDetailsProto) { }
    @Override public void execDetailsEndProtoBuf(ExecutionDetailsEndProto.ExecutionDetailsEnd executionDetailsEndProto) { }
    @Override public void completedOrderProtoBuf(CompletedOrderProto.CompletedOrder completedOrderProto) { }
    @Override public void completedOrdersEndProtoBuf(CompletedOrdersEndProto.CompletedOrdersEnd completedOrdersEndProto) { }
    @Override public void orderBoundProtoBuf(OrderBoundProto.OrderBound orderBoundProto) { }
    @Override public void contractDataProtoBuf(ContractDataProto.ContractData contractDataProto) { }
    @Override public void bondContractDataProtoBuf(ContractDataProto.ContractData contractDataProto) { }
    @Override public void contractDataEndProtoBuf(ContractDataEndProto.ContractDataEnd contractDataEndProto) { }
    @Override public void tickPriceProtoBuf(TickPriceProto.TickPrice tickPriceProto) { }
    @Override public void tickSizeProtoBuf(TickSizeProto.TickSize tickSizeProto) { }
    @Override public void tickOptionComputationProtoBuf(TickOptionComputationProto.TickOptionComputation tickOptionComputationProto) { }
    @Override public void tickGenericProtoBuf(TickGenericProto.TickGeneric tickGenericProto) { }
    @Override public void tickStringProtoBuf(TickStringProto.TickString tickStringProto) { }
    @Override public void tickSnapshotEndProtoBuf(TickSnapshotEndProto.TickSnapshotEnd tickSnapshotEndProto) { }
    @Override public void updateMarketDepthProtoBuf(MarketDepthProto.MarketDepth marketDepthProto) { }
    @Override public void updateMarketDepthL2ProtoBuf(MarketDepthL2Proto.MarketDepthL2 marketDepthL2Proto) { }
    @Override public void marketDataTypeProtoBuf(MarketDataTypeProto.MarketDataType marketDataTypeProto) { }
    @Override public void tickReqParamsProtoBuf(TickReqParamsProto.TickReqParams tickReqParamsProto) { }
    @Override public void updateAccountValueProtoBuf(AccountValueProto.AccountValue accounValueProto) { }
    @Override public void updatePortfolioProtoBuf(PortfolioValueProto.PortfolioValue portfolioValueProto) { }
    @Override public void updateAccountTimeProtoBuf(AccountUpdateTimeProto.AccountUpdateTime accountUpdateTimeProto) { }
    @Override public void accountDataEndProtoBuf(AccountDataEndProto.AccountDataEnd accountDataEndProto) { }
    @Override public void managedAccountsProtoBuf(ManagedAccountsProto.ManagedAccounts managedAccountsProto) { }
    @Override public void positionProtoBuf(PositionProto.Position positionProto) { }
    @Override public void positionEndProtoBuf(PositionEndProto.PositionEnd positionEndProto) { }
    @Override public void accountSummaryProtoBuf(AccountSummaryProto.AccountSummary accountSummaryProto) { }
    @Override public void accountSummaryEndProtoBuf(AccountSummaryEndProto.AccountSummaryEnd accountSummaryEndProto) { }
    @Override public void positionMultiProtoBuf(PositionMultiProto.PositionMulti positionMultiProto) { }
    @Override public void positionMultiEndProtoBuf(PositionMultiEndProto.PositionMultiEnd positionMultiEndProto) { }
    @Override public void accountUpdateMultiProtoBuf(AccountUpdateMultiProto.AccountUpdateMulti accountUpdateMultiProto) { }
    @Override public void accountUpdateMultiEndProtoBuf(AccountUpdateMultiEndProto.AccountUpdateMultiEnd accountUpdateMultiEndProto) { }
    @Override public void historicalDataProtoBuf(HistoricalDataProto.HistoricalData historicalDataProto) { }
    @Override public void historicalDataUpdateProtoBuf(HistoricalDataUpdateProto.HistoricalDataUpdate historicalDataUpdateProto) { }
    @Override public void historicalDataEndProtoBuf(HistoricalDataEndProto.HistoricalDataEnd historicalDataEndProto) { }
    @Override public void realTimeBarTickProtoBuf(RealTimeBarTickProto.RealTimeBarTick realTimeBarTickProto) { }
    @Override public void headTimestampProtoBuf(HeadTimestampProto.HeadTimestamp headTimestampProto) { }
    @Override public void histogramDataProtoBuf(HistogramDataProto.HistogramData histogramDataProto) { }
    @Override public void historicalTicksProtoBuf(HistoricalTicksProto.HistoricalTicks historicalTicksProto) { }
    @Override public void historicalTicksBidAskProtoBuf(HistoricalTicksBidAskProto.HistoricalTicksBidAsk historicalTicksBidAskProto) { }
    @Override public void historicalTicksLastProtoBuf(HistoricalTicksLastProto.HistoricalTicksLast historicalTicksLastProto) { }
    @Override public void tickByTickDataProtoBuf(TickByTickDataProto.TickByTickData tickByTickDataProto) { }
    @Override public void updateNewsBulletinProtoBuf(NewsBulletinProto.NewsBulletin newsBulletinProto) { }
    @Override public void newsArticleProtoBuf(NewsArticleProto.NewsArticle newsArticleProto) { }
    @Override public void newsProvidersProtoBuf(NewsProvidersProto.NewsProviders newsProvidersProto) { }
    @Override public void historicalNewsProtoBuf(HistoricalNewsProto.HistoricalNews historicalNewsProto) { }
    @Override public void historicalNewsEndProtoBuf(HistoricalNewsEndProto.HistoricalNewsEnd historicalNewsEndProto) { }
    @Override public void wshMetaDataProtoBuf(WshMetaDataProto.WshMetaData wshMetaDataProto) { }
    @Override public void wshEventDataProtoBuf(WshEventDataProto.WshEventData wshEventDataProto) { }
    @Override public void tickNewsProtoBuf(TickNewsProto.TickNews tickNewsProto) { }
    @Override public void scannerParametersProtoBuf(ScannerParametersProto.ScannerParameters scannerParametersProto) { }
    @Override public void scannerDataProtoBuf(ScannerDataProto.ScannerData scannerDataProto) { }
    @Override public void fundamentalsDataProtoBuf(FundamentalsDataProto.FundamentalsData fundamentalsDataProto) { }
    @Override public void pnlProtoBuf(PnLProto.PnL pnlProto) { }
    @Override public void pnlSingleProtoBuf(PnLSingleProto.PnLSingle pnlSingleProto) { }
    @Override public void receiveFAProtoBuf(ReceiveFAProto.ReceiveFA receiveFAProto) { }
    @Override public void replaceFAEndProtoBuf(ReplaceFAEndProto.ReplaceFAEnd replaceFAEndProto) { }
    @Override public void commissionAndFeesReportProtoBuf(CommissionAndFeesReportProto.CommissionAndFeesReport commissionAndFeesReportProto) { }
    @Override public void historicalScheduleProtoBuf(HistoricalScheduleProto.HistoricalSchedule historicalScheduleProto) { }
    @Override public void rerouteMarketDataRequestProtoBuf(RerouteMarketDataRequestProto.RerouteMarketDataRequest rerouteMarketDataRequestProto) { }
    @Override public void rerouteMarketDepthRequestProtoBuf(RerouteMarketDepthRequestProto.RerouteMarketDepthRequest rerouteMarketDepthRequestProto) { }
    @Override public void secDefOptParameterProtoBuf(SecDefOptParameterProto.SecDefOptParameter secDefOptParameterProto) { }
    @Override public void secDefOptParameterEndProtoBuf(SecDefOptParameterEndProto.SecDefOptParameterEnd secDefOptParameterEndProto) { }
    @Override public void softDollarTiersProtoBuf(SoftDollarTiersProto.SoftDollarTiers softDollarTiersProto) { }
    @Override public void familyCodesProtoBuf(FamilyCodesProto.FamilyCodes familyCodesProto) { }
    @Override public void symbolSamplesProtoBuf(SymbolSamplesProto.SymbolSamples symbolSamplesProto) { }
    @Override public void smartComponentsProtoBuf(SmartComponentsProto.SmartComponents smartComponentsProto) { }
    @Override public void marketRuleProtoBuf(MarketRuleProto.MarketRule marketRuleProto) { }
    @Override public void userInfoProtoBuf(UserInfoProto.UserInfo userInfoProto) { }
    @Override public void nextValidIdProtoBuf(NextValidIdProto.NextValidId nextValidIdProto) { }
    @Override public void currentTimeProtoBuf(CurrentTimeProto.CurrentTime currentTimeProto) { }
    @Override public void currentTimeInMillisProtoBuf(CurrentTimeInMillisProto.CurrentTimeInMillis currentTimeInMillisProto) { }
    @Override public void verifyMessageApiProtoBuf(VerifyMessageApiProto.VerifyMessageApi verifyMessageApiProto) { }
    @Override public void verifyCompletedProtoBuf(VerifyCompletedProto.VerifyCompleted verifyCompletedProto) { }
    @Override public void displayGroupListProtoBuf(DisplayGroupListProto.DisplayGroupList displayGroupListProto) { }
    @Override public void displayGroupUpdatedProtoBuf(DisplayGroupUpdatedProto.DisplayGroupUpdated displayGroupUpdatedProto) { }
    @Override public void marketDepthExchangesProtoBuf(MarketDepthExchangesProto.MarketDepthExchanges marketDepthExchangesProto) { }
    @Override public void configResponseProtoBuf(ConfigResponseProto.ConfigResponse configResponseProto) { }
    @Override public void updateConfigResponseProtoBuf(UpdateConfigResponseProto.UpdateConfigResponse updateConfigResponseProto) { }
}
