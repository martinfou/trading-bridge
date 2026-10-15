/* Copyright (C) 2025 Interactive Brokers LLC. All rights reserved. This code is subject to the terms
 * and conditions of the IB API Non-Commercial License or the IB API Commercial License, as applicable. */

package com.ib.client;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInput;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import com.ib.client.Types.FundAssetType;
import com.ib.client.Types.FundDistributionPolicyIndicator;
import com.ib.client.Types.SecType;
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
import com.ib.client.protobuf.ContractDescriptionProto;
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
import com.ib.client.protobuf.HistogramDataEntryProto;
import com.ib.client.protobuf.HistogramDataProto;
import com.ib.client.protobuf.HistoricalDataBarProto;
import com.ib.client.protobuf.HistoricalDataEndProto;
import com.ib.client.protobuf.HistoricalDataProto;
import com.ib.client.protobuf.HistoricalDataUpdateProto;
import com.ib.client.protobuf.HistoricalNewsEndProto;
import com.ib.client.protobuf.HistoricalNewsProto;
import com.ib.client.protobuf.HistoricalScheduleProto;
import com.ib.client.protobuf.HistoricalSessionProto;
import com.ib.client.protobuf.HistoricalTickBidAskProto;
import com.ib.client.protobuf.HistoricalTickLastProto;
import com.ib.client.protobuf.HistoricalTickProto;
import com.ib.client.protobuf.HistoricalTicksBidAskProto;
import com.ib.client.protobuf.HistoricalTicksLastProto;
import com.ib.client.protobuf.HistoricalTicksProto;
import com.ib.client.protobuf.ManagedAccountsProto;
import com.ib.client.protobuf.MarketDataTypeProto;
import com.ib.client.protobuf.MarketDepthDataProto;
import com.ib.client.protobuf.MarketDepthExchangesProto;
import com.ib.client.protobuf.MarketDepthL2Proto;
import com.ib.client.protobuf.MarketDepthProto;
import com.ib.client.protobuf.MarketRuleProto;
import com.ib.client.protobuf.NewsArticleProto;
import com.ib.client.protobuf.NewsBulletinProto;
import com.ib.client.protobuf.NewsProviderProto;
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
import com.ib.client.protobuf.ScannerDataElementProto;
import com.ib.client.protobuf.ScannerDataProto;
import com.ib.client.protobuf.ScannerParametersProto;
import com.ib.client.protobuf.SecDefOptParameterEndProto;
import com.ib.client.protobuf.SecDefOptParameterProto;
import com.ib.client.protobuf.SmartComponentsProto;
import com.ib.client.protobuf.SoftDollarTierProto;
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

class EDecoder implements ObjectInput {
    // incoming msg id's
    private static final int END_CONN           = -1;
    private static final int TICK_PRICE		= 1;
    private static final int TICK_SIZE		= 2;
    private static final int ORDER_STATUS	= 3;
    private static final int ERR_MSG		= 4;
    private static final int OPEN_ORDER         = 5;
    private static final int ACCT_VALUE         = 6;
    private static final int PORTFOLIO_VALUE    = 7;
    private static final int ACCT_UPDATE_TIME   = 8;
    private static final int NEXT_VALID_ID      = 9;
    private static final int CONTRACT_DATA      = 10;
    private static final int EXECUTION_DATA     = 11;
    private static final int MARKET_DEPTH     	= 12;
    private static final int MARKET_DEPTH_L2    = 13;
    private static final int NEWS_BULLETINS    	= 14;
    private static final int MANAGED_ACCTS    	= 15;
    private static final int RECEIVE_FA    	    = 16;
    private static final int HISTORICAL_DATA    = 17;
    private static final int BOND_CONTRACT_DATA = 18;
    private static final int SCANNER_PARAMETERS = 19;
    private static final int SCANNER_DATA       = 20;
    private static final int TICK_OPTION_COMPUTATION = 21;
    private static final int TICK_GENERIC = 45;
    private static final int TICK_STRING = 46;
    private static final int TICK_EFP = 47;
    private static final int CURRENT_TIME = 49;
    private static final int REAL_TIME_BARS = 50;
    private static final int FUNDAMENTAL_DATA = 51;
    private static final int CONTRACT_DATA_END = 52;
    private static final int OPEN_ORDER_END = 53;
    private static final int ACCT_DOWNLOAD_END = 54;
    private static final int EXECUTION_DATA_END = 55;
    private static final int DELTA_NEUTRAL_VALIDATION = 56;
    private static final int TICK_SNAPSHOT_END = 57;
    private static final int MARKET_DATA_TYPE = 58;
    private static final int COMMISSION_AND_FEES_REPORT = 59;
    private static final int POSITION = 61;
    private static final int POSITION_END = 62;
    private static final int ACCOUNT_SUMMARY = 63;
    private static final int ACCOUNT_SUMMARY_END = 64;
    private static final int VERIFY_MESSAGE_API = 65;
    private static final int VERIFY_COMPLETED = 66;
    private static final int DISPLAY_GROUP_LIST = 67;
    private static final int DISPLAY_GROUP_UPDATED = 68;
    private static final int VERIFY_AND_AUTH_MESSAGE_API = 69;
    private static final int VERIFY_AND_AUTH_COMPLETED = 70;
    private static final int POSITION_MULTI = 71;
    private static final int POSITION_MULTI_END = 72;
    private static final int ACCOUNT_UPDATE_MULTI = 73;
    private static final int ACCOUNT_UPDATE_MULTI_END = 74;
    private static final int SECURITY_DEFINITION_OPTION_PARAMETER = 75;
    private static final int SECURITY_DEFINITION_OPTION_PARAMETER_END = 76;
    private static final int SOFT_DOLLAR_TIERS = 77;
    private static final int FAMILY_CODES = 78;
    private static final int SYMBOL_SAMPLES = 79;
    private static final int MKT_DEPTH_EXCHANGES = 80;
    private static final int TICK_REQ_PARAMS = 81;
    private static final int SMART_COMPONENTS = 82;
    private static final int NEWS_ARTICLE = 83;
    private static final int TICK_NEWS = 84;
    private static final int NEWS_PROVIDERS = 85;
    private static final int HISTORICAL_NEWS = 86;
    private static final int HISTORICAL_NEWS_END = 87;
    private static final int HEAD_TIMESTAMP = 88;
    private static final int HISTOGRAM_DATA = 89;
    private static final int HISTORICAL_DATA_UPDATE = 90;
    private static final int REROUTE_MKT_DATA_REQ = 91;
    private static final int REROUTE_MKT_DEPTH_REQ = 92;
    private static final int MARKET_RULE = 93;
    private static final int PNL = 94;
    private static final int PNL_SINGLE = 95;
    private static final int HISTORICAL_TICKS = 96;
    private static final int HISTORICAL_TICKS_BID_ASK = 97;
    private static final int HISTORICAL_TICKS_LAST = 98;
    private static final int TICK_BY_TICK = 99;
    private static final int ORDER_BOUND = 100;
    private static final int COMPLETED_ORDER = 101;
    private static final int COMPLETED_ORDERS_END = 102;
    private static final int REPLACE_FA_END = 103;
    private static final int WSH_META_DATA = 104;
    private static final int WSH_EVENT_DATA = 105;
    private static final int HISTORICAL_SCHEDULE = 106;
    private static final int USER_INFO = 107;
    private static final int HISTORICAL_DATA_END = 108;
    private static final int CURRENT_TIME_IN_MILLIS = 109;
    private static final int CONFIG_RESPONSE = 110;
    private static final int UPDATE_CONFIG_RESPONSE = 111;

    static final int MAX_MSG_LENGTH = 0xffffff;

    private EClientMsgSink m_clientMsgSink;
    private EWrapper m_EWrapper;
    private int m_serverVersion;
	private IMessageReader m_messageReader;

	public EDecoder(int serverVersion, EWrapper callback) {
		this(serverVersion, callback, null);
	}
	
	public EDecoder(int serverVersion, EWrapper callback, EClientMsgSink sink) {
		m_clientMsgSink = sink;
		m_serverVersion = serverVersion;
		m_EWrapper = callback;
	}
	
    private void processFirstMsg() throws IOException {
        m_serverVersion = readInt();
        if (m_serverVersion >= 20) {
            // currently with Unified both server version and time sent in one
            // message
            String twsTime = readStr();

            if (m_clientMsgSink != null)
                m_clientMsgSink.serverVersion(m_serverVersion, twsTime);
        } else {
            if (m_clientMsgSink != null)
                m_clientMsgSink.serverVersion(m_serverVersion, null);
        }

        m_EWrapper.connectAck();
    } 
    
    private boolean readMessageToInternalBuf(InputStream dis) {
  		m_messageReader = new PreV100MessageReader(dis);
    	return true;
    }
    
    public int processMsg(EMessage msg) throws IOException {
    	if (!readMessageToInternalBuf(msg.getStream())) {
    		return 0;
    	}
    	
    	if (m_serverVersion == 0) {
            try {
                processFirstMsg();
                return m_messageReader.msgLength();
            } catch (Exception ex) {
                m_EWrapper.error(EClientErrors.NO_VALID_ID, Util.currentTimeMillis(), EClientErrors.SOCKET_EXCEPTION.code(),
                        EClientErrors.SOCKET_EXCEPTION.msg() + ex.getMessage(), null);
                return 0;
            }
    	}
    	
        int msgId = m_serverVersion >= EClient.MIN_SERVER_VER_PROTOBUF ? readRawInt() : readInt();
        boolean useProtoBuf = false;
        if (msgId > EClient.PROTOBUF_MSG_ID) {
            useProtoBuf = true;
            msgId -= EClient.PROTOBUF_MSG_ID;
        }
        
        if (useProtoBuf) {
            switch( msgId) {
                case ORDER_STATUS:
                    processOrderStatusMsgProtoBuf();
                    break;
                case ERR_MSG:
                    processErrorMsgProtoBuf();
                    break;
                case OPEN_ORDER:
                    processOpenOrderMsgProtoBuf();
                    break;
                case EXECUTION_DATA:
                    processExecutionDataMsgProtoBuf();
                    break;
                case OPEN_ORDER_END:
                    processOpenOrderEndMsgProtoBuf();
                    break;
                case EXECUTION_DATA_END:
                    processExecutionDataEndMsgProtoBuf();
                    break;
                case COMPLETED_ORDER:
                    processCompletedOrderMsgProtoBuf();
                    break;
                case COMPLETED_ORDERS_END:
                    processCompletedOrdersEndMsgProtoBuf();
                    break;
                case ORDER_BOUND:
                    processOrderBoundMsgProtoBuf();
                    break;
                case CONTRACT_DATA:
                    processContractDataMsgProtoBuf();
                    break;
                case BOND_CONTRACT_DATA:
                    processBondContractDataMsgProtoBuf();
                    break;
                case CONTRACT_DATA_END:
                    processContractDataEndMsgProtoBuf();
                    break;
                case TICK_PRICE:
                    processTickPriceMsgProtoBuf();
                    break;
                case TICK_SIZE:
                    processTickSizeMsgProtoBuf();
                    break;
                case MARKET_DEPTH:
                    processMarketDepthMsgProtoBuf();
                    break;
                case MARKET_DEPTH_L2:
                    processMarketDepthL2MsgProtoBuf();
                    break;
                case TICK_OPTION_COMPUTATION:
                    processTickOptionComputationMsgProtoBuf();
                    break;
                case TICK_GENERIC:
                    processTickGenericMsgProtoBuf();
                    break;
                case TICK_STRING:
                    processTickStringMsgProtoBuf();
                    break;
                case TICK_SNAPSHOT_END:
                    processTickSnapshotEndMsgProtoBuf();
                    break;
                case MARKET_DATA_TYPE:
                    processMarketDataTypeMsgProtoBuf();
                    break;
                case TICK_REQ_PARAMS:
                    processTickReqParamsMsgProtoBuf();
                    break;
                case ACCT_VALUE:
                    processAccountValueMsgProtoBuf();
                    break;
                case PORTFOLIO_VALUE:
                    processPortfolioValueMsgProtoBuf();
                    break;
                case ACCT_UPDATE_TIME:
                    processAcctUpdateTimeMsgProtoBuf();
                    break;
                case ACCT_DOWNLOAD_END:
                    processAccountDataEndMsgProtoBuf();
                    break;
                case MANAGED_ACCTS:
                    processManagedAccountsMsgProtoBuf();
                    break;
                case POSITION:
                    processPositionMsgProtoBuf();
                    break;
                case POSITION_END:
                    processPositionEndMsgProtoBuf();
                    break;
                case ACCOUNT_SUMMARY:
                    processAccountSummaryMsgProtoBuf();
                    break;
                case ACCOUNT_SUMMARY_END:
                    processAccountSummaryEndMsgProtoBuf();
                    break;
                case POSITION_MULTI:
                    processPositionMultiMsgProtoBuf();
                    break;
                case POSITION_MULTI_END:
                    processPositionMultiEndMsgProtoBuf();
                    break;
                case ACCOUNT_UPDATE_MULTI:
                    processAccountUpdateMultiMsgProtoBuf();
                    break;
                case ACCOUNT_UPDATE_MULTI_END:
                    processAccountUpdateMultiEndMsgProtoBuf();
                    break;
                case HISTORICAL_DATA:
                    processHistoricalDataMsgProtoBuf();
                    break;
                case HISTORICAL_DATA_UPDATE:
                    processHistoricalDataUpdateMsgProtoBuf();
                    break;
                case HISTORICAL_DATA_END:
                    processHistoricalDataEndMsgProtoBuf();
                    break;
                case REAL_TIME_BARS:
                    processRealTimeBarMsgProtoBuf();
                    break;
                case HEAD_TIMESTAMP:
                    processHeadTimestampMsgProtoBuf();
                    break;
                case HISTOGRAM_DATA:
                    processHistogramDataMsgProtoBuf();
                    break;
                case HISTORICAL_TICKS:
                    processHistoricalTicksMsgProtoBuf();
                    break;
                case HISTORICAL_TICKS_BID_ASK:
                    processHistoricalTicksBidAskMsgProtoBuf();
                    break;
                case HISTORICAL_TICKS_LAST:
                    processHistoricalTicksLastMsgProtoBuf();
                    break;
                case TICK_BY_TICK:
                    processTickByTickMsgProtoBuf();
                    break;
                case NEWS_BULLETINS:
                    processNewsBulletinMsgProtoBuf();
                    break;
                case NEWS_ARTICLE:
                    processNewsArticleMsgProtoBuf();
                    break;
                case NEWS_PROVIDERS:
                    processNewsProvidersMsgProtoBuf();
                    break;
                case HISTORICAL_NEWS:
                    processHistoricalNewsMsgProtoBuf();
                    break;
                case HISTORICAL_NEWS_END:
                    processHistoricalNewsEndMsgProtoBuf();
                    break;
                case WSH_META_DATA:
                    processWshMetaDataMsgProtoBuf();
                    break;
                case WSH_EVENT_DATA:
                    processWshEventDataMsgProtoBuf();
                    break;
                case TICK_NEWS:
                    processTickNewsMsgProtoBuf();
                    break;
                case SCANNER_PARAMETERS:
                    processScannerParametersMsgProtoBuf();
                    break;
                case SCANNER_DATA:
                    processScannerDataMsgProtoBuf();
                    break;
                case FUNDAMENTAL_DATA:
                    processFundamentalsDataMsgProtoBuf();
                    break;
                case PNL:
                    processPnLMsgProtoBuf();
                    break;
                case PNL_SINGLE:
                    processPnLSingleMsgProtoBuf();
                    break;
                case RECEIVE_FA:
                    processReceiveFAMsgProtoBuf();
                    break;
                case REPLACE_FA_END:
                    processReplaceFAEndMsgProtoBuf();
                    break;
                case COMMISSION_AND_FEES_REPORT:
                    processCommissionAndFeesReportMsgProtoBuf();
                    break;
                case HISTORICAL_SCHEDULE:
                    processHistoricalScheduleMsgProtoBuf();
                    break;
                case REROUTE_MKT_DATA_REQ:
                    processRerouteMktDataReqMsgProtoBuf();
                    break;
                case REROUTE_MKT_DEPTH_REQ:
                    processRerouteMktDepthReqMsgProtoBuf();
                    break;
                case SECURITY_DEFINITION_OPTION_PARAMETER:
                    processSecurityDefinitionOptionParameterMsgProtoBuf();
                    break;
                case SECURITY_DEFINITION_OPTION_PARAMETER_END:
                    processSecurityDefinitionOptionParameterEndMsgProtoBuf();
                    break;
                case SOFT_DOLLAR_TIERS:
                    processSoftDollarTiersMsgProtoBuf();
                    break;
                case FAMILY_CODES:
                    processFamilyCodesMsgProtoBuf();
                    break;
                case SYMBOL_SAMPLES:
                    processSymbolSamplesMsgProtoBuf();
                    break;
                case SMART_COMPONENTS:
                    processSmartComponentsMsgProtoBuf();
                    break;
                case MARKET_RULE:
                    processMarketRuleMsgProtoBuf();
                    break;
                case USER_INFO:
                    processUserInfoMsgProtoBuf();
                    break;
                case NEXT_VALID_ID:
                    processNextValidIdMsgProtoBuf();
                    break;
                case CURRENT_TIME:
                    processCurrentTimeMsgProtoBuf();
                    break;
                case CURRENT_TIME_IN_MILLIS:
                    processCurrentTimeInMillisMsgProtoBuf();
                    break;
                case VERIFY_MESSAGE_API:
                    processVerifyMessageApiMsgProtoBuf();
                    break;
                case VERIFY_COMPLETED:
                    processVerifyCompletedMsgProtoBuf();
                    break;
                case DISPLAY_GROUP_LIST:
                    processDisplayGroupListMsgProtoBuf();
                    break;
                case DISPLAY_GROUP_UPDATED:
                    processDisplayGroupUpdatedMsgProtoBuf();
                    break;
                case MKT_DEPTH_EXCHANGES:
                    processMktDepthExchangesMsgProtoBuf();
                    break;
                case CONFIG_RESPONSE:
                    processConfigMsgProtoBuf();
                    break;
                case UPDATE_CONFIG_RESPONSE:
                    processUpdateConfigMsgProtoBuf();
                    break;
                    
                default: {
                    m_EWrapper.error( EClientErrors.NO_VALID_ID, Util.currentTimeMillis(), EClientErrors.UNKNOWN_ID.code(), EClientErrors.UNKNOWN_ID.msg(), null);
                    return 0;
                }
            }
        } else {
            switch( msgId) {
                case END_CONN:
                    return 0;

                case TICK_PRICE:
                    processTickPriceMsg();
                    break;

                case TICK_SIZE:
                    processTickSizeMsg();
                    break;

                case POSITION:
                    processPositionMsg();
                    break;

                case POSITION_END:
                    processPositionEndMsg();
                    break;

                case ACCOUNT_SUMMARY:
                    processAccountSummaryMsg();
                    break;

                case ACCOUNT_SUMMARY_END:
                    processAccountSummaryEndMsg();
                    break;

                case TICK_OPTION_COMPUTATION:
                    processTickOptionComputationMsg();
                    break;

                case TICK_GENERIC:
                    processTickGenericMsg();
                    break;

                case TICK_STRING:
                    processTickStringMsg();
                    break;

                case TICK_EFP:
                    processTickEFPMsg();
                    break;

                case ORDER_STATUS:
                    processOrderStatusMsg();
                    break;

                case ACCT_VALUE:
                    processAcctValueMsg();
                    break;

                case PORTFOLIO_VALUE:
                    processPortfolioValueMsg();
                    break;

                case ACCT_UPDATE_TIME:
                    processAcctUpdateTimeMsg();
                    break;

                case ERR_MSG:
                    processErrorMsg();
                    break;

                case OPEN_ORDER:
                    processOpenOrderMsg();
                    break;

                case NEXT_VALID_ID:
                    processNextValidIdMsg();
                    break;

                case SCANNER_DATA:
                    processScannerDataMsg();
                    break;

                case CONTRACT_DATA:
                    processContractDataMsg();
                    break;

                case BOND_CONTRACT_DATA:
                    processBondContractDataMsg();
                    break;

                case EXECUTION_DATA:
                    processExecutionDataMsg();
                    break;

                case MARKET_DEPTH:
                    processMarketDepthMsg();
                    break;

                case MARKET_DEPTH_L2:
                    processMarketDepthL2Msg();
                    break;

                case NEWS_BULLETINS:
                    processNewsBulletinsMsg();
                    break;

                case MANAGED_ACCTS:
                    processManagedAcctsMsg();
                    break;

                case RECEIVE_FA:
                    processReceiveFaMsg();
                    break;

                case HISTORICAL_DATA:
                    processHistoricalDataMsg();
                    break;

                case SCANNER_PARAMETERS:
                    processScannerParametersMsg();
                    break;

                case CURRENT_TIME:
                    processCurrentTimeMsg();
                    break;

                case REAL_TIME_BARS:
                    processRealTimeBarsMsg();
                    break;

                case FUNDAMENTAL_DATA:
                    processFundamentalDataMsg();
                    break;

                case CONTRACT_DATA_END:
                    processContractDataEndMsg();
                    break;

                case OPEN_ORDER_END:
                    processOpenOrderEndMsg();
                    break;

                case ACCT_DOWNLOAD_END:
                    processAcctDownloadEndMsg();
                    break;

                case EXECUTION_DATA_END:
                    processExecutionDataEndMsg();
                    break;

                case DELTA_NEUTRAL_VALIDATION:
                    processDeltaNeutralValidationMsg();
                    break;

                case TICK_SNAPSHOT_END:
                    processTickSnapshotEndMsg();
                    break;

                case MARKET_DATA_TYPE:
                    processMarketDataTypeMsg();
                    break;

                case COMMISSION_AND_FEES_REPORT:
                    processCommissionAndFeesReportMsg();
                    break;

                case VERIFY_MESSAGE_API:
                    processVerifyMessageApiMsg();
                    break;

                case VERIFY_COMPLETED:
                    processVerifyCompletedMsg();
                    break;

                case DISPLAY_GROUP_LIST:
                    processDisplayGroupListMsg();
                    break;

                case DISPLAY_GROUP_UPDATED:
                    processDisplayGroupUpdatedMsg();
                    break;

                case VERIFY_AND_AUTH_MESSAGE_API:
                    processVerifyAndAuthMessageMsg();
                    break;

                case VERIFY_AND_AUTH_COMPLETED:
                    processVerifyAndAuthCompletedMsg();
                    break;

                case POSITION_MULTI:
                    processPositionMultiMsg();
                    break;

                case POSITION_MULTI_END:
                    processPositionMultiEndMsg();
                    break;

                case ACCOUNT_UPDATE_MULTI:
                    processAccountUpdateMultiMsg();
                    break;

                case ACCOUNT_UPDATE_MULTI_END:
                    processAccountUpdateMultiEndMsg();
                    break;

                case SECURITY_DEFINITION_OPTION_PARAMETER:
                    processSecurityDefinitionOptionalParameterMsg();
                    break;

                case SECURITY_DEFINITION_OPTION_PARAMETER_END:
                    processSecurityDefinitionOptionalParameterEndMsg();
                    break;

                case SOFT_DOLLAR_TIERS:
                    processSoftDollarTiersMsg();
                    break;

                case FAMILY_CODES:
                    processFamilyCodesMsg();
                    break;

                case SMART_COMPONENTS:
                    processSmartComponentsMsg();
                    break;

                case TICK_REQ_PARAMS:
                    processTickReqParamsMsg();
                    break;

                case SYMBOL_SAMPLES:
                    processSymbolSamplesMsg();
                    break;

                case MKT_DEPTH_EXCHANGES:
                    processMktDepthExchangesMsg();
                    break;

                case HEAD_TIMESTAMP:
                    processHeadTimestampMsg();
                    break;

                case TICK_NEWS:
                    processTickNewsMsg();
                    break;

                case NEWS_PROVIDERS:
                    processNewsProvidersMsg();
                    break;

                case NEWS_ARTICLE:
                    processNewsArticleMsg();
                    break;

                case HISTORICAL_NEWS:
                    processHistoricalNewsMsg();
                    break;

                case HISTORICAL_NEWS_END:
                    processHistoricalNewsEndMsg();
                    break;

                case HISTOGRAM_DATA:
                    processHistogramDataMsg();
                    break;

                case HISTORICAL_DATA_UPDATE:
                    processHistoricalDataUpdateMsg();
                    break;

                case REROUTE_MKT_DATA_REQ:
                    processRerouteMktDataReq();
                    break;

                case REROUTE_MKT_DEPTH_REQ:
                    processRerouteMktDepthReq();
                    break;

                case MARKET_RULE:
                    processMarketRuleMsg();
                    break;

                case PNL:
                    processPnLMsg();
                    break;

                case PNL_SINGLE:
                    processPnLSingleMsg();
                    break;

                case HISTORICAL_TICKS:
                    processHistoricalTicks();
                    break;

                case HISTORICAL_TICKS_BID_ASK:
                    processHistoricalTicksBidAsk();
                    break;

                case HISTORICAL_TICKS_LAST:
                    processHistoricalTicksLast();
                    break;

                case TICK_BY_TICK:
                    processTickByTickMsg();
                    break;

                case ORDER_BOUND:
                    processOrderBoundMsg();
                    break;

                case COMPLETED_ORDER:
                    processCompletedOrderMsg();
                    break;

                case COMPLETED_ORDERS_END:
                    processCompletedOrdersEndMsg();
                    break;

                case REPLACE_FA_END:
                    processReplaceFAEndMsg();
                    break;

                case WSH_META_DATA:
                    processWshMetaData();
                    break;

                case WSH_EVENT_DATA:
                    processWshEventData();
                    break;

                case HISTORICAL_SCHEDULE:
                    processHistoricalSchedule();
                    break;

                case USER_INFO:
                    processUserInfo();
                    break;

                case HISTORICAL_DATA_END:
                    processHistoricalDataEndMsg();
                    break;

                case CURRENT_TIME_IN_MILLIS:
                    processCurrentTimeInMillisMsg();
                    break;

                default: {
                    m_EWrapper.error( EClientErrors.NO_VALID_ID, Util.currentTimeMillis(), EClientErrors.UNKNOWN_ID.code(), EClientErrors.UNKNOWN_ID.msg(), null);
                    return 0;
                }
            }
        }
        
        m_messageReader.close();
        return m_messageReader.msgLength();
    }

    private void processHistoricalTicksLast() throws IOException {
        int reqId = readInt(),
            tickCount = readInt();
                
        List<HistoricalTickLast> ticks = new ArrayList<>();
        
        for (int i = 0; i < tickCount; i++) {
            long time = readLong();
            BitMask mask = new BitMask(readInt());
            TickAttribLast tickAttribLast = new TickAttribLast();
            tickAttribLast.pastLimit(mask.get(0));
            tickAttribLast.unreported(mask.get(1));
            
            double price = readDouble();
            Decimal size = readDecimal();
            String exchange = readStr(),
                   specialConditions = readStr();

            ticks.add(new HistoricalTickLast(time, tickAttribLast, price, size, exchange, specialConditions));
        }

        boolean done = readBoolean();

        m_EWrapper.historicalTicksLast(reqId, ticks, done);
    }

    private void processHistoricalTicksLastMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        HistoricalTicksLastProto.HistoricalTicksLast historicalTicksLastProto = HistoricalTicksLastProto.HistoricalTicksLast.parseFrom(byteArray);
        m_EWrapper.historicalTicksLastProtoBuf(historicalTicksLastProto);

        int reqId = historicalTicksLastProto.hasReqId() ? historicalTicksLastProto.getReqId() : EClientErrors.NO_VALID_ID;
        boolean done = historicalTicksLastProto.hasIsDone() ? historicalTicksLastProto.getIsDone() : false;

        List<HistoricalTickLast> historicalTicksLast = new ArrayList<>();
        if (historicalTicksLastProto.getHistoricalTicksLastCount() > 0) {
            for (HistoricalTickLastProto.HistoricalTickLast historicalTickLast : historicalTicksLastProto.getHistoricalTicksLastList()) {
                historicalTicksLast.add(EDecoderUtils.decodeHistoricalTickLast(historicalTickLast));
            }
        }

        m_EWrapper.historicalTicksLast(reqId, historicalTicksLast, done);
    }

    private void processHistoricalTicksBidAsk() throws IOException {
        int reqId = readInt(),
            tickCount = readInt();
            
        List<HistoricalTickBidAsk> ticks = new ArrayList<>();
        
        for (int i = 0; i < tickCount; i++) {
            long time = readLong();
            BitMask mask = new BitMask(readInt());
            TickAttribBidAsk tickAttribBidAsk = new TickAttribBidAsk();
            tickAttribBidAsk.askPastHigh(mask.get(0));
            tickAttribBidAsk.bidPastLow(mask.get(1));
            
            double priceBid = readDouble(),
                   priceAsk = readDouble();
            Decimal sizeBid = readDecimal(),
                    sizeAsk = readDecimal();

            ticks.add(new HistoricalTickBidAsk(time, tickAttribBidAsk, priceBid, priceAsk, sizeBid, sizeAsk));
        }

        boolean done = readBoolean();

        m_EWrapper.historicalTicksBidAsk(reqId, ticks, done);       
    }
    
    private void processHistoricalTicksBidAskMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        HistoricalTicksBidAskProto.HistoricalTicksBidAsk historicalTicksBidAskProto = HistoricalTicksBidAskProto.HistoricalTicksBidAsk.parseFrom(byteArray);
        m_EWrapper.historicalTicksBidAskProtoBuf(historicalTicksBidAskProto);

        int reqId = historicalTicksBidAskProto.hasReqId() ? historicalTicksBidAskProto.getReqId() : EClientErrors.NO_VALID_ID;
        boolean done = historicalTicksBidAskProto.hasIsDone() ? historicalTicksBidAskProto.getIsDone() : false;

        List<HistoricalTickBidAsk> historicalTicksBidAsk = new ArrayList<>();
        if (historicalTicksBidAskProto.getHistoricalTicksBidAskCount() > 0) {
            for (HistoricalTickBidAskProto.HistoricalTickBidAsk historicalTickBidAsk : historicalTicksBidAskProto.getHistoricalTicksBidAskList()) {
                historicalTicksBidAsk.add(EDecoderUtils.decodeHistoricalTickBidAsk(historicalTickBidAsk));
            }
        }

        m_EWrapper.historicalTicksBidAsk(reqId, historicalTicksBidAsk, done);
    }

    private void processHistoricalTicks() throws IOException {
        int reqId = readInt(),
            tickCount = readInt();
        
        List<HistoricalTick> ticks = new ArrayList<>();
        
        for (int i = 0; i < tickCount; i++) {
            long time = readLong();
            readInt();//for consistency
            double price = readDouble();
            Decimal size = readDecimal();
            
            ticks.add(new HistoricalTick(time, price, size));
        }
        
        boolean done = readBoolean();

        m_EWrapper.historicalTicks(reqId, ticks, done);       
    }

    private void processHistoricalTicksMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        HistoricalTicksProto.HistoricalTicks historicalTicksProto = HistoricalTicksProto.HistoricalTicks.parseFrom(byteArray);
        m_EWrapper.historicalTicksProtoBuf(historicalTicksProto);

        int reqId = historicalTicksProto.hasReqId() ? historicalTicksProto.getReqId() : EClientErrors.NO_VALID_ID;
        boolean isDone = historicalTicksProto.hasIsDone() ? historicalTicksProto.getIsDone() : false;

        List<HistoricalTick> historicalTicks = new ArrayList<>();
        if (historicalTicksProto.getHistoricalTicksCount() > 0) {
            for (HistoricalTickProto.HistoricalTick historicalTick : historicalTicksProto.getHistoricalTicksList()) {
                historicalTicks.add(EDecoderUtils.decodeHistoricalTick(historicalTick));
            }
        }

        m_EWrapper.historicalTicks(reqId, historicalTicks, isDone);
    }

    private void processMarketRuleMsg() throws IOException {
        int marketRuleId = readInt();

        PriceIncrement[] priceIncrements;
        int nPriceIncrements = readInt();
        if (nPriceIncrements > 0) {
            priceIncrements = new PriceIncrement[nPriceIncrements];
            for (int i = 0; i < nPriceIncrements; i++){
                priceIncrements[i] = new PriceIncrement(readDouble(), readDouble());
            }
        } else {
            priceIncrements = new PriceIncrement[0];
        }

        m_EWrapper.marketRule(marketRuleId, priceIncrements);
    }

    private void processMarketRuleMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        MarketRuleProto.MarketRule marketRuleProto = MarketRuleProto.MarketRule.parseFrom(byteArray);

        m_EWrapper.marketRuleProtoBuf(marketRuleProto);

        int marketRuleId = marketRuleProto.hasMarketRuleId() ? marketRuleProto.getMarketRuleId() : 0;

        PriceIncrement[] priceIncrements = new PriceIncrement[0];
        if (marketRuleProto.getPriceIncrementsCount() > 0) {
            priceIncrements = new PriceIncrement[marketRuleProto.getPriceIncrementsCount()];
            for (int i = 0; i < marketRuleProto.getPriceIncrementsCount(); i++) {
                priceIncrements[i] = EDecoderUtils.decodePriceIncrement(marketRuleProto.getPriceIncrements(i));
            }
        }

        m_EWrapper.marketRule(marketRuleId, priceIncrements);
    }

    private void processRerouteMktDepthReq() throws IOException {
        int reqId = readInt();
        int conId = readInt();
        String exchange = readStr();

        m_EWrapper.rerouteMktDepthReq(reqId, conId, exchange);
    }

    private void processRerouteMktDepthReqMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        RerouteMarketDepthRequestProto.RerouteMarketDepthRequest rerouteMarketDepthRequestProto = RerouteMarketDepthRequestProto.RerouteMarketDepthRequest.parseFrom(byteArray);

        m_EWrapper.rerouteMarketDepthRequestProtoBuf(rerouteMarketDepthRequestProto);

        int reqId = rerouteMarketDepthRequestProto.hasReqId() ? rerouteMarketDepthRequestProto.getReqId() : EClientErrors.NO_VALID_ID;
        int conId = rerouteMarketDepthRequestProto.hasConId() ? rerouteMarketDepthRequestProto.getConId() : 0;
        String exchange = rerouteMarketDepthRequestProto.hasExchange() ? rerouteMarketDepthRequestProto.getExchange() : "";

        m_EWrapper.rerouteMktDepthReq(reqId, conId, exchange);
    }

    private void processRerouteMktDataReq() throws IOException {
        int reqId = readInt();
        int conId = readInt();
        String exchange = readStr();

        m_EWrapper.rerouteMktDataReq(reqId, conId, exchange);
    }

    private void processRerouteMktDataReqMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        RerouteMarketDataRequestProto.RerouteMarketDataRequest rerouteMarketDataRequestProto = RerouteMarketDataRequestProto.RerouteMarketDataRequest.parseFrom(byteArray);

        m_EWrapper.rerouteMarketDataRequestProtoBuf(rerouteMarketDataRequestProto);

        int reqId = rerouteMarketDataRequestProto.hasReqId() ? rerouteMarketDataRequestProto.getReqId() : EClientErrors.NO_VALID_ID;
        int conId = rerouteMarketDataRequestProto.hasConId() ? rerouteMarketDataRequestProto.getConId() : 0;
        String exchange = rerouteMarketDataRequestProto.hasExchange() ? rerouteMarketDataRequestProto.getExchange() : "";

        m_EWrapper.rerouteMktDataReq(reqId, conId, exchange);
    }

    private void processHistoricalDataUpdateMsg() throws IOException {
        int reqId = readInt();
        int barCount = readInt();
        String date = readStr();
        double open = readDouble();
        double close = readDouble();
        double high = readDouble();
        double low = readDouble();
        Decimal WAP = readDecimal();
        Decimal volume = readDecimal();

        m_EWrapper.historicalDataUpdate(reqId, new Bar(date, open, high, low, close, volume, barCount, WAP));
    }

    private void processHistoricalDataUpdateMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        HistoricalDataUpdateProto.HistoricalDataUpdate historicalDataUpdateProto = HistoricalDataUpdateProto.HistoricalDataUpdate.parseFrom(byteArray);
        m_EWrapper.historicalDataUpdateProtoBuf(historicalDataUpdateProto);

        int reqId = historicalDataUpdateProto.hasReqId() ? historicalDataUpdateProto.getReqId() : EClientErrors.NO_VALID_ID;

        if (!historicalDataUpdateProto.hasHistoricalDataBar()) {
            return;
        }
        Bar bar = EDecoderUtils.decodeHistoricalDataBar(historicalDataUpdateProto.getHistoricalDataBar());

        m_EWrapper.historicalDataUpdate(reqId, bar);
    }

    private void processPnLSingleMsg() throws IOException {
    	int reqId = readInt();
    	Decimal pos = readDecimal();
    	double dailyPnL = readDouble();
    	double unrealizedPnL = Double.MAX_VALUE;
        double realizedPnL = Double.MAX_VALUE;
    	
        if (m_serverVersion >= EClient.MIN_SERVER_VER_UNREALIZED_PNL) {
            unrealizedPnL = readDouble();
        }

        if (m_serverVersion >= EClient.MIN_SERVER_VER_REALIZED_PNL) {
            realizedPnL = readDouble();
        }

    	double value = readDouble();
    	
        m_EWrapper.pnlSingle(reqId, pos, dailyPnL, unrealizedPnL, realizedPnL, value);
	}


    private void processPnLSingleMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        PnLSingleProto.PnLSingle pnlSingleProto = PnLSingleProto.PnLSingle.parseFrom(byteArray);
        m_EWrapper.pnlSingleProtoBuf(pnlSingleProto);

        int reqId = pnlSingleProto.hasReqId() ? pnlSingleProto.getReqId() : EClientErrors.NO_VALID_ID;
        Decimal pos = pnlSingleProto.hasPosition() ? Util.stringToDecimal(pnlSingleProto.getPosition()) : Decimal.INVALID;
        double dailyPnL = pnlSingleProto.hasDailyPnL() ? pnlSingleProto.getDailyPnL() : Double.MAX_VALUE;
        double unrealizedPnL = pnlSingleProto.hasUnrealizedPnL() ? pnlSingleProto.getUnrealizedPnL() : Double.MAX_VALUE;
        double realizedPnL = pnlSingleProto.hasRealizedPnL() ? pnlSingleProto.getRealizedPnL() : Double.MAX_VALUE;
        double value = pnlSingleProto.hasValue() ? pnlSingleProto.getValue() : Double.MAX_VALUE;

        m_EWrapper.pnlSingle(reqId, pos, dailyPnL, unrealizedPnL, realizedPnL, value);
    }

	private void processPnLMsg() throws IOException {
		int reqId = readInt();
		double dailyPnL = readDouble();
		double unrealizedPnL = Double.MAX_VALUE;
		double realizedPnL = Double.MAX_VALUE;
		
		if (m_serverVersion >= EClient.MIN_SERVER_VER_UNREALIZED_PNL) {
		    unrealizedPnL = readDouble();
		}
		
		if (m_serverVersion >= EClient.MIN_SERVER_VER_REALIZED_PNL) {
		    realizedPnL = readDouble();
		}
		
		m_EWrapper.pnl(reqId, dailyPnL, unrealizedPnL, realizedPnL);
	}


    private void processPnLMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        PnLProto.PnL pnlProto = PnLProto.PnL.parseFrom(byteArray);
        m_EWrapper.pnlProtoBuf(pnlProto);

        int reqId = pnlProto.hasReqId() ? pnlProto.getReqId() : EClientErrors.NO_VALID_ID;
        double dailyPnL = pnlProto.hasDailyPnL() ? pnlProto.getDailyPnL() : Double.MAX_VALUE;
        double unrealizedPnL = pnlProto.hasUnrealizedPnL() ? pnlProto.getUnrealizedPnL() : Double.MAX_VALUE;
        double realizedPnL = pnlProto.hasRealizedPnL() ? pnlProto.getRealizedPnL() : Double.MAX_VALUE;

        m_EWrapper.pnl(reqId, dailyPnL, unrealizedPnL, realizedPnL);
    }

    private void processHistogramDataMsg() throws IOException {
    	int reqId = readInt();
    	int n = readInt();
    	List<HistogramEntry> items = new ArrayList<>(n);
    	
    	for (int i = 0; i < n; i++) {
    		items.add(new HistogramEntry(readDouble(), readDecimal()));
    	}
    	
    	m_EWrapper.histogramData(reqId, items);
	}
    
    private void processHistogramDataMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        HistogramDataProto.HistogramData histogramDataProto = HistogramDataProto.HistogramData.parseFrom(byteArray);
        m_EWrapper.histogramDataProtoBuf(histogramDataProto);

        int reqId = histogramDataProto.hasReqId() ? histogramDataProto.getReqId() : EClientErrors.NO_VALID_ID;

        List<HistogramEntry> histogramEntries = new ArrayList<>();
        if (histogramDataProto.getHistogramDataEntriesCount() > 0) {
            for (HistogramDataEntryProto.HistogramDataEntry histogramDataEntry : histogramDataProto.getHistogramDataEntriesList()) {
                histogramEntries.add(EDecoderUtils.decodeHistogramDataEntry(histogramDataEntry));
            }
        }

        m_EWrapper.histogramData(reqId, histogramEntries);
    }

	private void processHistoricalNewsEndMsg() throws IOException {
        int requestId = readInt();
        boolean hasMore = readBoolFromInt();

        m_EWrapper.historicalNewsEnd(requestId, hasMore);
    }

    private void processHistoricalNewsEndMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        HistoricalNewsEndProto.HistoricalNewsEnd historicalNewsEndProto = HistoricalNewsEndProto.HistoricalNewsEnd.parseFrom(byteArray);
        m_EWrapper.historicalNewsEndProtoBuf(historicalNewsEndProto);

        int requestId = historicalNewsEndProto.hasReqId() ? historicalNewsEndProto.getReqId() : EClientErrors.NO_VALID_ID;
        boolean hasMore = historicalNewsEndProto.hasHasMore() ? historicalNewsEndProto.getHasMore() : false;

        m_EWrapper.historicalNewsEnd(requestId, hasMore);
    }

    private void processHistoricalNewsMsg() throws IOException {
        int requestId = readInt();
        String time = readStr();
        String providerCode = readStr();
        String articleId = readStr();
        String headline = readStr();

        m_EWrapper.historicalNews(requestId, time, providerCode, articleId, headline);
    }

    private void processHistoricalNewsMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        HistoricalNewsProto.HistoricalNews historicalNewsProto = HistoricalNewsProto.HistoricalNews.parseFrom(byteArray);
        m_EWrapper.historicalNewsProtoBuf(historicalNewsProto);

        int requestId = historicalNewsProto.hasReqId() ? historicalNewsProto.getReqId() : EClientErrors.NO_VALID_ID;
        String time = historicalNewsProto.hasTime() ? historicalNewsProto.getTime() : "";
        String providerCode = historicalNewsProto.hasProviderCode() ? historicalNewsProto.getProviderCode() : "";
        String articleId = historicalNewsProto.hasArticleId() ? historicalNewsProto.getArticleId() : "";
        String headline = historicalNewsProto.hasHeadline() ? historicalNewsProto.getHeadline() : "";

        m_EWrapper.historicalNews(requestId, time, providerCode, articleId, headline);
    }

    private void processNewsArticleMsg() throws IOException {
        int requestId = readInt();
        int articleType = readInt();
        String articleText = readStr();

        m_EWrapper.newsArticle(requestId, articleType, articleText);
    }

    private void processNewsArticleMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        NewsArticleProto.NewsArticle newsArticleProto = NewsArticleProto.NewsArticle.parseFrom(byteArray);
        m_EWrapper.newsArticleProtoBuf(newsArticleProto);

        int requestId = newsArticleProto.hasReqId() ? newsArticleProto.getReqId() : EClientErrors.NO_VALID_ID;
        int articleType = newsArticleProto.hasArticleType() ? newsArticleProto.getArticleType() : 0;
        String articleText = newsArticleProto.hasArticleText() ? newsArticleProto.getArticleText() : "";

        m_EWrapper.newsArticle(requestId, articleType, articleText);
    }

    private void processNewsProvidersMsg() throws IOException {
        NewsProvider[] newsProviders = new NewsProvider[0];
        int nNewsProviders = readInt();

        if (nNewsProviders > 0) {
            newsProviders = new NewsProvider[nNewsProviders];

            for (int i = 0; i < nNewsProviders; i++)
            {
                newsProviders[i] = new NewsProvider(readStr(), readStr());
            }
        }

        m_EWrapper.newsProviders(newsProviders);
    }

    private void processNewsProvidersMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        NewsProvidersProto.NewsProviders newsProvidersProto = NewsProvidersProto.NewsProviders.parseFrom(byteArray);
        m_EWrapper.newsProvidersProtoBuf(newsProvidersProto);

        NewsProvider[] newsProviders = new NewsProvider[0];
        if (newsProvidersProto.getNewsProvidersCount() > 0) {
            newsProviders = new NewsProvider[newsProvidersProto.getNewsProvidersCount()];
            for (int i = 0; i < newsProvidersProto.getNewsProvidersCount(); i++) {
                NewsProviderProto.NewsProvider providerProto = newsProvidersProto.getNewsProviders(i);
                String code = providerProto.hasProviderCode() ? providerProto.getProviderCode() : "";
                String name = providerProto.hasProviderName() ? providerProto.getProviderName() : "";
                newsProviders[i] = new NewsProvider(code, name);
            }
        }

        m_EWrapper.newsProviders(newsProviders);
    }

    private void processTickNewsMsg() throws IOException {
        int tickerId = readInt();
        long timeStamp = readLong();
        String providerCode = readStr();
        String articleId = readStr();
        String headline = readStr();
        String extraData = readStr();

        m_EWrapper.tickNews(tickerId, timeStamp, providerCode, articleId, headline, extraData);
    }

    private void processTickNewsMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        TickNewsProto.TickNews tickNewsProto = TickNewsProto.TickNews.parseFrom(byteArray);
        m_EWrapper.tickNewsProtoBuf(tickNewsProto);

        int reqId = tickNewsProto.hasReqId() ? tickNewsProto.getReqId() : EClientErrors.NO_VALID_ID;
        long timestamp = tickNewsProto.hasTimestamp() ? tickNewsProto.getTimestamp() : 0;
        String providerCode = tickNewsProto.hasProviderCode() ? tickNewsProto.getProviderCode() : "";
        String articleId = tickNewsProto.hasArticleId() ? tickNewsProto.getArticleId() : "";
        String headline = tickNewsProto.hasHeadline() ? tickNewsProto.getHeadline() : "";
        String extraData = tickNewsProto.hasExtraData() ? tickNewsProto.getExtraData() : "";

        m_EWrapper.tickNews(reqId, timestamp, providerCode, articleId, headline, extraData);
    }

    private void processHeadTimestampMsg() throws IOException {
		int reqId = readInt();
		String headTimestamp = readStr();
		
		m_EWrapper.headTimestamp(reqId, headTimestamp);
	}

    private void processHeadTimestampMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        HeadTimestampProto.HeadTimestamp headTimestampProto = HeadTimestampProto.HeadTimestamp.parseFrom(byteArray);
        m_EWrapper.headTimestampProtoBuf(headTimestampProto);

        int reqId = headTimestampProto.hasReqId() ? headTimestampProto.getReqId() : EClientErrors.NO_VALID_ID;
        String headTimestamp = headTimestampProto.hasHeadTimestamp() ? headTimestampProto.getHeadTimestamp() : "";
        
        m_EWrapper.headTimestamp(reqId, headTimestamp);
    }

    private void processMktDepthExchangesMsg() throws IOException {
        DepthMktDataDescription[] depthMktDataDescriptions = new DepthMktDataDescription[0];
        int nDepthMktDataDescriptions = readInt();

        if (nDepthMktDataDescriptions > 0) {
            depthMktDataDescriptions = new DepthMktDataDescription[nDepthMktDataDescriptions];

            for (int i = 0; i < nDepthMktDataDescriptions; i++)
            {
                if (m_serverVersion >= EClient.MIN_SERVER_VER_SERVICE_DATA_TYPE) {
                    depthMktDataDescriptions[i] = new DepthMktDataDescription(readStr(), readStr(), readStr(), readStr(), readIntMax());
                } else {
                    depthMktDataDescriptions[i] = new DepthMktDataDescription(readStr(), readStr(), "", readBoolFromInt() ? "Deep2" : "Deep", Integer.MAX_VALUE);
                }
            }
        }

        m_EWrapper.mktDepthExchanges(depthMktDataDescriptions);
    }

    private void processMktDepthExchangesMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        MarketDepthExchangesProto.MarketDepthExchanges marketDepthExchangesProto = MarketDepthExchangesProto.MarketDepthExchanges.parseFrom(byteArray);

        m_EWrapper.marketDepthExchangesProtoBuf(marketDepthExchangesProto);

        DepthMktDataDescription[] depthMktDataDescriptions = new DepthMktDataDescription[0];
        if (marketDepthExchangesProto.getDepthMarketDataDescriptionsCount() > 0) {
            depthMktDataDescriptions = new DepthMktDataDescription[marketDepthExchangesProto.getDepthMarketDataDescriptionsCount()];
            for (int i = 0; i < marketDepthExchangesProto.getDepthMarketDataDescriptionsCount(); i++) {
                depthMktDataDescriptions[i] = EDecoderUtils.decodeDepthMarketDataDescription(marketDepthExchangesProto.getDepthMarketDataDescriptions(i));
            }
        }

        m_EWrapper.mktDepthExchanges(depthMktDataDescriptions);
    }

    private void processSymbolSamplesMsg() throws IOException {
        int reqId = readInt();
        ContractDescription[] contractDescriptions = new ContractDescription[0];
        int nContractDescriptions = readInt();

        if (nContractDescriptions > 0){
            contractDescriptions = new ContractDescription[nContractDescriptions];

            for (int i = 0; i < nContractDescriptions; i++)
            {
                // read contract fields
                Contract contract = new Contract();
                contract.conid(readInt());
                contract.symbol(readStr());
                contract.secType(readStr());
                contract.primaryExch(readStr());
                contract.currency(readStr());

                // read derivative sec types list
                String[] derivativeSecTypes = new String[0];
                int nDerivativeSecTypes = readInt();

                if (nDerivativeSecTypes > 0){
                    derivativeSecTypes = new String[nDerivativeSecTypes];
                    for (int j = 0; j < nDerivativeSecTypes; j++)
                    {
                        derivativeSecTypes[j] = readStr();
                    }
                }

                if (m_serverVersion >= EClient.MIN_SERVER_VER_BOND_ISSUERID) {
                    contract.description(readStr());
                    contract.issuerId(readStr());
                }

                ContractDescription contractDescription = new ContractDescription(contract, derivativeSecTypes);
                contractDescriptions[i] = contractDescription;
            }
        }

        m_EWrapper.symbolSamples(reqId, contractDescriptions);
    }

    private void processSymbolSamplesMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        SymbolSamplesProto.SymbolSamples symbolSamplesProto = SymbolSamplesProto.SymbolSamples.parseFrom(byteArray);

        m_EWrapper.symbolSamplesProtoBuf(symbolSamplesProto);

        int reqId = symbolSamplesProto.hasReqId() ? symbolSamplesProto.getReqId() : EClientErrors.NO_VALID_ID;
        
        ContractDescription[] contractDescriptions = new ContractDescription[0];
        if (symbolSamplesProto.getContractDescriptionsCount() > 0) {
            contractDescriptions = new ContractDescription[symbolSamplesProto.getContractDescriptionsCount()];
            for (int i = 0; i < symbolSamplesProto.getContractDescriptionsCount(); i++) {
                ContractDescriptionProto.ContractDescription contractDescriptionProto = symbolSamplesProto.getContractDescriptions(i);

                if (!contractDescriptionProto.hasContract()) {
                    continue;
                }
                Contract contract = EDecoderUtils.decodeContract(contractDescriptionProto.getContract());

                String[] derivativeSecTypes = new String[0];
                if (contractDescriptionProto.getDerivativeSecTypesCount() > 0) {
                    derivativeSecTypes = contractDescriptionProto.getDerivativeSecTypesList().toArray(new String[0]);
                }

                contractDescriptions[i] = new ContractDescription(contract, derivativeSecTypes);
            }
        }

        m_EWrapper.symbolSamples(reqId, contractDescriptions);
    }

    private void processFamilyCodesMsg() throws IOException {
        FamilyCode[] familyCodes = new FamilyCode[0];
        int nFamilyCodes = readInt();

        if (nFamilyCodes > 0) {
            familyCodes = new FamilyCode[nFamilyCodes];

            for (int i = 0; i < nFamilyCodes; i++)
            {
                familyCodes[i] = new FamilyCode(readStr(), readStr());
            }
        }

        m_EWrapper.familyCodes(familyCodes);
    }

    private void processFamilyCodesMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        FamilyCodesProto.FamilyCodes familyCodesProto = FamilyCodesProto.FamilyCodes.parseFrom(byteArray);

        m_EWrapper.familyCodesProtoBuf(familyCodesProto);

        FamilyCode[] familyCodes = new FamilyCode[0];
        if (familyCodesProto.getFamilyCodesCount() > 0) {
            familyCodes = new FamilyCode[familyCodesProto.getFamilyCodesCount()];
            for (int i = 0; i < familyCodesProto.getFamilyCodesCount(); i++) {
                familyCodes[i] = EDecoderUtils.decodeFamilyCode(familyCodesProto.getFamilyCodes(i));
            }
        }

        m_EWrapper.familyCodes(familyCodes);
    }

	private void processSoftDollarTiersMsg() throws IOException {
		int reqId = readInt();
		int nTiers = readInt();
		SoftDollarTier[] tiers = new SoftDollarTier[nTiers]; 
		
		for (int i = 0; i < nTiers; i++) {
			tiers[i] = new SoftDollarTier(readStr(), readStr(), readStr()); 
		}
		
		m_EWrapper.softDollarTiers(reqId, tiers);
	}

    private void processSoftDollarTiersMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        SoftDollarTiersProto.SoftDollarTiers softDollarTiersProto = SoftDollarTiersProto.SoftDollarTiers.parseFrom(byteArray);

        m_EWrapper.softDollarTiersProtoBuf(softDollarTiersProto);

        int reqId = softDollarTiersProto.hasReqId() ? softDollarTiersProto.getReqId() : EClientErrors.NO_VALID_ID;

        SoftDollarTier[] tiers = new SoftDollarTier[0];
        if (softDollarTiersProto.getSoftDollarTiersCount() > 0) {
            tiers = new SoftDollarTier[softDollarTiersProto.getSoftDollarTiersCount()];
            for (int i = 0; i < softDollarTiersProto.getSoftDollarTiersCount(); i++) {
                SoftDollarTierProto.SoftDollarTier softDollarTierProto = softDollarTiersProto.getSoftDollarTiers(i);
                if (softDollarTierProto != null) tiers[i] = EDecoderUtils.decodeSoftDollarTier(softDollarTierProto);
            }
        }

        m_EWrapper.softDollarTiers(reqId, tiers);
    }

	private void processSecurityDefinitionOptionalParameterEndMsg() throws IOException {
		int reqId = readInt();
		
		m_EWrapper.securityDefinitionOptionalParameterEnd(reqId);
	}

    private void processSecurityDefinitionOptionParameterEndMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        SecDefOptParameterEndProto.SecDefOptParameterEnd secDefOptParameterEndProto = SecDefOptParameterEndProto.SecDefOptParameterEnd.parseFrom(byteArray);

        m_EWrapper.secDefOptParameterEndProtoBuf(secDefOptParameterEndProto);

        int reqId = secDefOptParameterEndProto.hasReqId() ? secDefOptParameterEndProto.getReqId() : EClientErrors.NO_VALID_ID;

        m_EWrapper.securityDefinitionOptionalParameterEnd(reqId);
    }

	private void processSecurityDefinitionOptionalParameterMsg() throws IOException {
		int reqId = readInt();	
		String exchange = readStr();
		int underlyingConId = readInt();
		String tradingClass = readStr();
		String multiplier = readStr();
		int expirationsSize = readInt();
		Set<String> expirations = new HashSet<>();
		Set<Double> strikes = new HashSet<>();
		
		for (int i = 0; i < expirationsSize; i++) {
			expirations.add(readStr());
		}
		
		int strikesSize = readInt();
		
		for (int i = 0; i < strikesSize; i++) {
			strikes.add(readDouble());
		}
		
		m_EWrapper.securityDefinitionOptionalParameter(reqId, exchange, underlyingConId, tradingClass, multiplier, expirations, strikes);
	}

    private void processSecurityDefinitionOptionParameterMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        SecDefOptParameterProto.SecDefOptParameter secDefOptParameterProto = SecDefOptParameterProto.SecDefOptParameter.parseFrom(byteArray);

        m_EWrapper.secDefOptParameterProtoBuf(secDefOptParameterProto);

        int reqId = secDefOptParameterProto.hasReqId() ? secDefOptParameterProto.getReqId() : EClientErrors.NO_VALID_ID;
        String exchange = secDefOptParameterProto.hasExchange() ? secDefOptParameterProto.getExchange() : "";
        int underlyingConId = secDefOptParameterProto.hasUnderlyingConId() ? secDefOptParameterProto.getUnderlyingConId() : 0;
        String tradingClass = secDefOptParameterProto.hasTradingClass() ? secDefOptParameterProto.getTradingClass() : "";
        String multiplier = secDefOptParameterProto.hasMultiplier() ? secDefOptParameterProto.getMultiplier() : "";

        Set<String> expirations = new HashSet<>();
        if (secDefOptParameterProto.getExpirationsCount() > 0) {
            for (String expiration : secDefOptParameterProto.getExpirationsList()) {
                expirations.add(expiration);
            }
        }

        Set<Double> strikes = new HashSet<>();
        if (secDefOptParameterProto.getStrikesCount() > 0) {
            for (double strike : secDefOptParameterProto.getStrikesList()) {
                strikes.add(strike);
            }
        }

        m_EWrapper.securityDefinitionOptionalParameter(reqId, exchange, underlyingConId, tradingClass, multiplier, expirations, strikes);
    }

	private void processVerifyAndAuthCompletedMsg() throws IOException {
		/*int version =*/ readInt();
		String isSuccessfulStr = readStr();
		boolean isSuccessful = "true".equals(isSuccessfulStr);
		String errorText = readStr();

		m_EWrapper.verifyAndAuthCompleted(isSuccessful, errorText);
	}

	private void processVerifyAndAuthMessageMsg() throws IOException {
		/*int version =*/ readInt();
		String apiData = readStr();
		String xyzChallenge = readStr();

		m_EWrapper.verifyAndAuthMessageAPI(apiData, xyzChallenge);
	}

	private void processDisplayGroupUpdatedMsg() throws IOException {
		/*int version =*/ readInt();
		int reqId = readInt();
		String contractInfo = readStr();

		m_EWrapper.displayGroupUpdated(reqId, contractInfo);
	}

    private void processDisplayGroupUpdatedMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        DisplayGroupUpdatedProto.DisplayGroupUpdated displayGroupUpdatedProto = DisplayGroupUpdatedProto.DisplayGroupUpdated.parseFrom(byteArray);

        m_EWrapper.displayGroupUpdatedProtoBuf(displayGroupUpdatedProto);

        int reqId = displayGroupUpdatedProto.hasReqId() ? displayGroupUpdatedProto.getReqId() : EClientErrors.NO_VALID_ID;
        String contractInfo = displayGroupUpdatedProto.hasContractInfo() ? displayGroupUpdatedProto.getContractInfo() : "";

        m_EWrapper.displayGroupUpdated(reqId, contractInfo);
    }

	private void processDisplayGroupListMsg() throws IOException {
		/*int version =*/ readInt();
		int reqId = readInt();
		String groups = readStr();

		m_EWrapper.displayGroupList(reqId, groups);
	}

    private void processDisplayGroupListMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        DisplayGroupListProto.DisplayGroupList displayGroupListProto = DisplayGroupListProto.DisplayGroupList.parseFrom(byteArray);

        m_EWrapper.displayGroupListProtoBuf(displayGroupListProto);

        int reqId = displayGroupListProto.hasReqId() ? displayGroupListProto.getReqId() : EClientErrors.NO_VALID_ID;
        String groups = displayGroupListProto.hasGroups() ? displayGroupListProto.getGroups() : "";

        m_EWrapper.displayGroupList(reqId, groups);
    }

	private void processVerifyCompletedMsg() throws IOException {
		/*int version =*/ readInt();
		String isSuccessfulStr = readStr();
		boolean isSuccessful = "true".equals(isSuccessfulStr);
		String errorText = readStr();

		m_EWrapper.verifyCompleted(isSuccessful, errorText);
	}

    private void processVerifyCompletedMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        VerifyCompletedProto.VerifyCompleted verifyCompletedProto = VerifyCompletedProto.VerifyCompleted.parseFrom(byteArray);

        m_EWrapper.verifyCompletedProtoBuf(verifyCompletedProto);

        boolean isSuccessful = verifyCompletedProto.hasIsSuccessful() ? verifyCompletedProto.getIsSuccessful() : false;
        String errorText = verifyCompletedProto.hasErrorText() ? verifyCompletedProto.getErrorText() : "";

        m_EWrapper.verifyCompleted(isSuccessful, errorText);
    }

	private void processVerifyMessageApiMsg() throws IOException {
		/*int version =*/ readInt();
		String apiData = readStr();

		m_EWrapper.verifyMessageAPI(apiData);
	}

    private void processVerifyMessageApiMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        VerifyMessageApiProto.VerifyMessageApi verifyMessageApiProto = VerifyMessageApiProto.VerifyMessageApi.parseFrom(byteArray);

        m_EWrapper.verifyMessageApiProtoBuf(verifyMessageApiProto);

        String apiData = verifyMessageApiProto.hasApiData() ? verifyMessageApiProto.getApiData() : "";

        m_EWrapper.verifyMessageAPI(apiData);
    }

	private void processCommissionAndFeesReportMsg() throws IOException {
		/*int version =*/ readInt();

		CommissionAndFeesReport commissionAndFeesReport = new CommissionAndFeesReport();
		commissionAndFeesReport.execId(readStr());
		commissionAndFeesReport.commissionAndFees(readDouble());
		commissionAndFeesReport.currency(readStr());
		commissionAndFeesReport.realizedPNL(readDouble());
		commissionAndFeesReport.yield(readDouble());
		commissionAndFeesReport.yieldRedemptionDate(readInt());

		m_EWrapper.commissionAndFeesReport( commissionAndFeesReport);
	}

    private void processCommissionAndFeesReportMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        CommissionAndFeesReportProto.CommissionAndFeesReport commissionAndFeesReportProto = CommissionAndFeesReportProto.CommissionAndFeesReport.parseFrom(byteArray);

        m_EWrapper.commissionAndFeesReportProtoBuf(commissionAndFeesReportProto);

        CommissionAndFeesReport commissionAndFeesReport = new CommissionAndFeesReport();
        commissionAndFeesReport.execId(commissionAndFeesReportProto.hasExecId() ? commissionAndFeesReportProto.getExecId() : "");
        commissionAndFeesReport.commissionAndFees(commissionAndFeesReportProto.hasCommissionAndFees() ? commissionAndFeesReportProto.getCommissionAndFees() : 0.0);
        commissionAndFeesReport.currency(commissionAndFeesReportProto.hasCurrency() ? commissionAndFeesReportProto.getCurrency() : "");
        commissionAndFeesReport.realizedPNL(commissionAndFeesReportProto.hasRealizedPNL() ? commissionAndFeesReportProto.getRealizedPNL() : 0.0);
        commissionAndFeesReport.yield(commissionAndFeesReportProto.hasBondYield() ? commissionAndFeesReportProto.getBondYield() : 0.0);
        commissionAndFeesReport.yieldRedemptionDate(commissionAndFeesReportProto.hasYieldRedemptionDate() ? Integer.parseInt(commissionAndFeesReportProto.getYieldRedemptionDate()) : 0);
        m_EWrapper.commissionAndFeesReport(commissionAndFeesReport);
    }

	private void processMarketDataTypeMsg() throws IOException {
		/*int version =*/ readInt();
		int reqId = readInt();
		int marketDataType = readInt();

		m_EWrapper.marketDataType( reqId, marketDataType);
	}
	
    private void processMarketDataTypeMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        MarketDataTypeProto.MarketDataType marketDataTypeProto = MarketDataTypeProto.MarketDataType.parseFrom(byteArray);
        m_EWrapper.marketDataTypeProtoBuf(marketDataTypeProto);

        int reqId = marketDataTypeProto.hasReqId() ? marketDataTypeProto.getReqId() : EClientErrors.NO_VALID_ID;
        int marketDataType = marketDataTypeProto.hasMarketDataType() ? marketDataTypeProto.getMarketDataType() : 0;

        m_EWrapper.marketDataType(reqId, marketDataType);
    }

	private void processTickSnapshotEndMsg() throws IOException {
		/*int version =*/ readInt();
		int reqId = readInt();

		m_EWrapper.tickSnapshotEnd( reqId);
	}

    private void processTickSnapshotEndMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        TickSnapshotEndProto.TickSnapshotEnd tickSnapshotEndProto = TickSnapshotEndProto.TickSnapshotEnd.parseFrom(byteArray);
        m_EWrapper.tickSnapshotEndProtoBuf(tickSnapshotEndProto);

        int reqId = tickSnapshotEndProto.hasReqId() ? tickSnapshotEndProto.getReqId() : EClientErrors.NO_VALID_ID;

        m_EWrapper.tickSnapshotEnd(reqId);
    }

	private void processDeltaNeutralValidationMsg() throws IOException {
		/*int version =*/ readInt();
		int reqId = readInt();

		DeltaNeutralContract deltaNeutralContract = new DeltaNeutralContract(readInt(), readDouble(), readDouble());
		m_EWrapper.deltaNeutralValidation( reqId, deltaNeutralContract);
	}

	private void processExecutionDataEndMsg() throws IOException {
		/*int version =*/ readInt();
		int reqId = readInt();
		m_EWrapper.execDetailsEnd( reqId);
	}

    private void processExecutionDataEndMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        ExecutionDetailsEndProto.ExecutionDetailsEnd executionDetailsEndProto = ExecutionDetailsEndProto.ExecutionDetailsEnd.parseFrom(byteArray);
        m_EWrapper.execDetailsEndProtoBuf(executionDetailsEndProto);
        int reqId = executionDetailsEndProto.hasReqId() ? executionDetailsEndProto.getReqId() : EClientErrors.NO_VALID_ID;
        m_EWrapper.execDetailsEnd(reqId);
    }

	private void processAcctDownloadEndMsg() throws IOException {
		/*int version =*/ readInt();
		String accountName = readStr();
		m_EWrapper.accountDownloadEnd( accountName);
	}

    private void processAccountDataEndMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        AccountDataEndProto.AccountDataEnd accountDataEndProto = AccountDataEndProto.AccountDataEnd.parseFrom(byteArray);
        m_EWrapper.accountDataEndProtoBuf(accountDataEndProto);

        String accountName = accountDataEndProto.hasAccountName() ? accountDataEndProto.getAccountName() : "";

        m_EWrapper.accountDownloadEnd(accountName);
    }

    private void processOpenOrderEndMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        OpenOrdersEndProto.OpenOrdersEnd openOrdersEndProto = OpenOrdersEndProto.OpenOrdersEnd.parseFrom(byteArray);
        m_EWrapper.openOrdersEndProtoBuf(openOrdersEndProto);
        m_EWrapper.openOrderEnd();
    }
	
	private void processOpenOrderEndMsg() throws IOException {
		/*int version =*/ readInt();
		m_EWrapper.openOrderEnd();
	}

	private void processContractDataEndMsg() throws IOException {
		/*int version =*/ readInt();
		int reqId = readInt();
		m_EWrapper.contractDetailsEnd(reqId);
	}
	
    private void processContractDataEndMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        ContractDataEndProto.ContractDataEnd contractDataEndProto = ContractDataEndProto.ContractDataEnd.parseFrom(byteArray);
        m_EWrapper.contractDataEndProtoBuf(contractDataEndProto);
        int reqId = contractDataEndProto.hasReqId() ? contractDataEndProto.getReqId() : EClientErrors.NO_VALID_ID;
        m_EWrapper.contractDetailsEnd(reqId);
    }

	private void processFundamentalDataMsg() throws IOException {
		/*int version =*/ readInt();
		int reqId = readInt();
		String data = readStr();
		m_EWrapper.fundamentalData(reqId, data);
	}

    private void processFundamentalsDataMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        FundamentalsDataProto.FundamentalsData fundamentalsDataProto = FundamentalsDataProto.FundamentalsData.parseFrom(byteArray);
        m_EWrapper.fundamentalsDataProtoBuf(fundamentalsDataProto);

        int reqId = fundamentalsDataProto.hasReqId() ? fundamentalsDataProto.getReqId() : EClientErrors.NO_VALID_ID;
        String data = fundamentalsDataProto.hasData() ? fundamentalsDataProto.getData() : "";

        m_EWrapper.fundamentalData(reqId, data);
    }

	private void processRealTimeBarsMsg() throws IOException {
		/*int version =*/ readInt();
		int reqId = readInt();
		long time = readLong();
		double open = readDouble();
		double high = readDouble();
		double low = readDouble();
		double close = readDouble();
		Decimal volume = readDecimal();
		Decimal wap = readDecimal();
		int count = readInt();
		m_EWrapper.realtimeBar(reqId, time, open, high, low, close, volume, wap, count);
	}
	
    private void processRealTimeBarMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        RealTimeBarTickProto.RealTimeBarTick realTimeBarTickProto = RealTimeBarTickProto.RealTimeBarTick.parseFrom(byteArray);
        m_EWrapper.realTimeBarTickProtoBuf(realTimeBarTickProto);

        int reqId = realTimeBarTickProto.hasReqId() ? realTimeBarTickProto.getReqId() : EClientErrors.NO_VALID_ID;
        long time = realTimeBarTickProto.hasTime() ? realTimeBarTickProto.getTime() : 0;
        double open = realTimeBarTickProto.hasOpen() ? realTimeBarTickProto.getOpen() : 0;
        double high = realTimeBarTickProto.hasHigh() ? realTimeBarTickProto.getHigh() : 0;
        double low = realTimeBarTickProto.hasLow() ? realTimeBarTickProto.getLow() : 0;
        double close = realTimeBarTickProto.hasClose() ? realTimeBarTickProto.getClose() : 0;
        Decimal volume = realTimeBarTickProto.hasVolume() ? Util.stringToDecimal(realTimeBarTickProto.getVolume()) : Decimal.INVALID;
        Decimal wap = realTimeBarTickProto.hasWAP() ? Util.stringToDecimal(realTimeBarTickProto.getWAP()) : Decimal.INVALID;
        int count = realTimeBarTickProto.hasCount() ? realTimeBarTickProto.getCount() : 0;
        
        m_EWrapper.realtimeBar(reqId, time, open, high, low, close, volume, wap, count);
    }

	private void processCurrentTimeMsg() throws IOException {
		/*int version =*/ readInt();
		long time = readLong();
		m_EWrapper.currentTime(time);
	}

    private void processCurrentTimeMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        CurrentTimeProto.CurrentTime currentTimeProto = CurrentTimeProto.CurrentTime.parseFrom(byteArray);

        m_EWrapper.currentTimeProtoBuf(currentTimeProto);

        long currentTime = currentTimeProto.hasCurrentTime() ? currentTimeProto.getCurrentTime() : 0;

        m_EWrapper.currentTime(currentTime);
    }

	private void processScannerParametersMsg() throws IOException {
		/*int version =*/ readInt();
		String xml = readStr();
		m_EWrapper.scannerParameters(xml);
	}

    private void processScannerParametersMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        ScannerParametersProto.ScannerParameters scannerParametersProto = ScannerParametersProto.ScannerParameters.parseFrom(byteArray);
        m_EWrapper.scannerParametersProtoBuf(scannerParametersProto);

        String xml = scannerParametersProto.hasXml() ? scannerParametersProto.getXml() : "";

        m_EWrapper.scannerParameters(xml);
    }

	private void processHistoricalDataMsg() throws IOException {
	    int version = Integer.MAX_VALUE;
	    
	    if (m_serverVersion < EClient.MIN_SERVER_VER_SYNT_REALTIME_BARS) {
	        version = readInt();
	    }
	    
	    int reqId = readInt();
	    String startDateStr = "";
	    String endDateStr = "";

	    if (version >= 2 && m_serverVersion < EClient.MIN_SERVER_VER_HISTORICAL_DATA_END) {
	        startDateStr = readStr();
	        endDateStr = readStr();
	    }
	    int itemCount = readInt();
	    for (int ctr = 0; ctr < itemCount; ctr++) {
	        String date = readStr();
	        double open = readDouble();
	        double high = readDouble();
	        double low = readDouble();
	        double close = readDouble();
            Decimal volume = readDecimal();
	        Decimal WAP = readDecimal();
	        
	        if (m_serverVersion < EClient.MIN_SERVER_VER_SYNT_REALTIME_BARS) {	        
	            /*String hasGaps = */readStr();
	        }
	        
	        int barCount = -1;
	        
	        if (version >= 3) {
	            barCount = readInt();
	        }
	        
	        m_EWrapper.historicalData(reqId, new Bar(date, open, high, low, close, volume, barCount, WAP));
	    }
        if (m_serverVersion < EClient.MIN_SERVER_VER_HISTORICAL_DATA_END) {
            // send end of dataset marker
            m_EWrapper.historicalDataEnd(reqId, startDateStr, endDateStr);
        }
	}
	
    private void processHistoricalDataMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        HistoricalDataProto.HistoricalData historicalDataProto = HistoricalDataProto.HistoricalData.parseFrom(byteArray);
        m_EWrapper.historicalDataProtoBuf(historicalDataProto);

        int reqId = historicalDataProto.hasReqId() ? historicalDataProto.getReqId() : EClientErrors.NO_VALID_ID;

        if (historicalDataProto.getHistoricalDataBarsCount() > 0) {
            for (HistoricalDataBarProto.HistoricalDataBar historicalDataBarProto : historicalDataProto.getHistoricalDataBarsList()) {
                m_EWrapper.historicalData(reqId, EDecoderUtils.decodeHistoricalDataBar(historicalDataBarProto));
            }
        }
    }

    private void processHistoricalDataEndMsg() throws IOException {
        int reqId = readInt();
        String startDateStr = readStr();
        String endDateStr = readStr();
        m_EWrapper.historicalDataEnd(reqId, startDateStr, endDateStr);
    }

    private void processHistoricalDataEndMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        HistoricalDataEndProto.HistoricalDataEnd historicalDataEndProto = HistoricalDataEndProto.HistoricalDataEnd.parseFrom(byteArray);
        m_EWrapper.historicalDataEndProtoBuf(historicalDataEndProto);

        int reqId = historicalDataEndProto.hasReqId() ? historicalDataEndProto.getReqId() : EClientErrors.NO_VALID_ID;
        String startDateStr = historicalDataEndProto.hasStartDateStr() ? historicalDataEndProto.getStartDateStr() : "";
        String endDateStr = historicalDataEndProto.hasEndDateStr() ? historicalDataEndProto.getEndDateStr() : "";
        
        m_EWrapper.historicalDataEnd(reqId, startDateStr, endDateStr);
    }

	private void processReceiveFaMsg() throws IOException {
	    /*int version =*/ readInt();
	    int faDataType = readInt();
	    String xml = readStr();

	    m_EWrapper.receiveFA(faDataType, xml);
	}
	
    private void processReceiveFAMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        ReceiveFAProto.ReceiveFA receiveFAProto = ReceiveFAProto.ReceiveFA.parseFrom(byteArray);
        m_EWrapper.receiveFAProtoBuf(receiveFAProto);

        int faDataType = receiveFAProto.hasFaDataType() ? receiveFAProto.getFaDataType() : 0;
        String xml = receiveFAProto.hasXml() ? receiveFAProto.getXml() : "";

        m_EWrapper.receiveFA(faDataType, xml);
    }

	private void processManagedAcctsMsg() throws IOException {
		/*int version =*/ readInt();
		String accountsList = readStr();

		m_EWrapper.managedAccounts( accountsList);
	}

    private void processManagedAccountsMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        ManagedAccountsProto.ManagedAccounts managedAccountsProto = ManagedAccountsProto.ManagedAccounts.parseFrom(byteArray);
        m_EWrapper.managedAccountsProtoBuf(managedAccountsProto);

        String accountsList = managedAccountsProto.hasAccountsList() ? managedAccountsProto.getAccountsList() : "";

        m_EWrapper.managedAccounts(accountsList);
    }

	private void processNewsBulletinsMsg() throws IOException {
		/*int version =*/ readInt();
		int newsMsgId = readInt();
		int newsMsgType = readInt();
		String newsMessage = readStr();
		String originatingExch = readStr();

		m_EWrapper.updateNewsBulletin( newsMsgId, newsMsgType, newsMessage, originatingExch);
	}

    private void processNewsBulletinMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        NewsBulletinProto.NewsBulletin newsBulletinProto = NewsBulletinProto.NewsBulletin.parseFrom(byteArray);
        m_EWrapper.updateNewsBulletinProtoBuf(newsBulletinProto);

        int msgId = newsBulletinProto.hasNewsMsgId() ? newsBulletinProto.getNewsMsgId() : 0;
        int msgType = newsBulletinProto.hasNewsMsgType() ? newsBulletinProto.getNewsMsgType() : 0;
        String message = newsBulletinProto.hasNewsMessage() ? newsBulletinProto.getNewsMessage() : "";
        String originExch = newsBulletinProto.hasOriginatingExch() ? newsBulletinProto.getOriginatingExch() : "";

        m_EWrapper.updateNewsBulletin(msgId, msgType, message, originExch);
    }

    private void processMarketDepthL2Msg() throws IOException {
		/*int version =*/ readInt();
		int id = readInt();

		int position = readInt();
		String marketMaker = readStr();
		int operation = readInt();
		int side = readInt();
		double price = readDouble();
		Decimal size = readDecimal();
		
		boolean isSmartDepth = false;
		if (m_serverVersion >= EClient.MIN_SERVER_VER_SMART_DEPTH) {
			isSmartDepth = readBoolFromInt();
		}

		m_EWrapper.updateMktDepthL2(id, position, marketMaker,
		                operation, side, price, size, isSmartDepth);
	}

    private void processMarketDepthL2MsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        MarketDepthL2Proto.MarketDepthL2 marketDepthL2Proto = MarketDepthL2Proto.MarketDepthL2.parseFrom(byteArray);
        m_EWrapper.updateMarketDepthL2ProtoBuf(marketDepthL2Proto);

        int reqId = marketDepthL2Proto.hasReqId() ? marketDepthL2Proto.getReqId() : EClientErrors.NO_VALID_ID;

        if (!marketDepthL2Proto.hasMarketDepthData()) {
            return;
        }
        MarketDepthDataProto.MarketDepthData marketDepthDataProto = marketDepthL2Proto.getMarketDepthData();
        int position = marketDepthDataProto.hasPosition() ? marketDepthDataProto.getPosition() : 0;
        String marketMaker = marketDepthDataProto.hasMarketMaker() ? marketDepthDataProto.getMarketMaker() : "";
        int operation = marketDepthDataProto.hasOperation() ? marketDepthDataProto.getOperation() : 0;
        int side = marketDepthDataProto.hasSide() ? marketDepthDataProto.getSide() : 0;
        double price = marketDepthDataProto.hasPrice() ? marketDepthDataProto.getPrice() : 0;
        Decimal size = marketDepthDataProto.hasSize() ? Util.stringToDecimal(marketDepthDataProto.getSize()) : Decimal.INVALID;
        boolean isSmartDepth = marketDepthDataProto.hasIsSmartDepth() ? marketDepthDataProto.getIsSmartDepth() : false;

        m_EWrapper.updateMktDepthL2(reqId, position, marketMaker, operation, side, price, size, isSmartDepth);
    }

	private void processMarketDepthMsg() throws IOException {
		/*int version =*/ readInt();
		int id = readInt();

		int position = readInt();
		int operation = readInt();
		int side = readInt();
		double price = readDouble();
		Decimal size = readDecimal();

		m_EWrapper.updateMktDepth(id, position, operation,
		                side, price, size);
	}

    private void processMarketDepthMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        MarketDepthProto.MarketDepth marketDepthProto = MarketDepthProto.MarketDepth.parseFrom(byteArray);
        m_EWrapper.updateMarketDepthProtoBuf(marketDepthProto);

        int reqId = marketDepthProto.hasReqId() ? marketDepthProto.getReqId() : EClientErrors.NO_VALID_ID;

        if (!marketDepthProto.hasMarketDepthData()) {
            return;
        }
        MarketDepthDataProto.MarketDepthData marketDepthDataProto = marketDepthProto.getMarketDepthData();
        int position = marketDepthDataProto.hasPosition() ? marketDepthDataProto.getPosition() : 0;
        int operation = marketDepthDataProto.hasOperation() ? marketDepthDataProto.getOperation() : 0;
        int side = marketDepthDataProto.hasSide() ? marketDepthDataProto.getSide() : 0;
        double price = marketDepthDataProto.hasPrice() ? marketDepthDataProto.getPrice() : 0;
        Decimal size = marketDepthDataProto.hasSize() ? Util.stringToDecimal(marketDepthDataProto.getSize()) : Decimal.INVALID;

        m_EWrapper.updateMktDepth(reqId, position, operation, side, price, size);
    }

    private void processExecutionDataMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        ExecutionDetailsProto.ExecutionDetails executionDetailsProto = ExecutionDetailsProto.ExecutionDetails.parseFrom(byteArray);

        m_EWrapper.execDetailsProtoBuf(executionDetailsProto);

        int reqId = executionDetailsProto.hasReqId() ? executionDetailsProto.getReqId() : EClientErrors.NO_VALID_ID;

        // set contract fields
        if (!executionDetailsProto.hasContract()) {
            return;
        }
        Contract contract = EDecoderUtils.decodeContract(executionDetailsProto.getContract());

        // set execution fields
        if (!executionDetailsProto.hasExecution()) {
            return;
        }
        Execution execution = EDecoderUtils.decodeExecution(executionDetailsProto.getExecution());

        m_EWrapper.execDetails( reqId, contract, execution);
    }

	private void processExecutionDataMsg() throws IOException {
		int version = m_serverVersion;
		
		if (m_serverVersion < EClient.MIN_SERVER_VER_LAST_LIQUIDITY) {
		    version = readInt();
		}

		int reqId = -1;
		if (version >= 7) {
			reqId = readInt();
		}

		int orderId = readInt();

		// read contract fields
		Contract contract = new Contract();
		if (version >= 5) {
			contract.conid(readInt());
		}
		contract.symbol(readStr());
		contract.secType(readStr());
		contract.lastTradeDateOrContractMonth(readStr());
		contract.strike(readDouble());
		contract.right(readStr());
		if (version >= 9) {
		    contract.multiplier(readStr());
		}
		contract.exchange(readStr());
		contract.currency(readStr());
		contract.localSymbol(readStr());
		if (version >= 10) {
		    contract.tradingClass(readStr());
		}

		Execution exec = new Execution();
		exec.orderId(orderId);
		exec.execId(readStr());
		exec.time(readStr());
		exec.acctNumber(readStr());
		exec.exchange(readStr());
		exec.side(readStr());
		exec.shares(readDecimal());
		exec.price(readDouble());
		if ( version >= 2 ) {
		    exec.permId(readLong());
		}
		if ( version >= 3) {
		    exec.clientId(readInt());
		}
		if ( version >= 4) {
		    exec.liquidation(readInt());
		}
		if (version >= 6) {
			exec.cumQty(readDecimal());
			exec.avgPrice(readDouble());
		}
		if (version >= 8) {
		    exec.orderRef(readStr());
		}
		if (version >= 9) {
		    exec.evRule(readStr());
		    exec.evMultiplier(readDouble());
		}
		if (m_serverVersion >= EClient.MIN_SERVER_VER_MODELS_SUPPORT) {
			exec.modelCode(readStr());
		}
		
        if (m_serverVersion >= EClient.MIN_SERVER_VER_LAST_LIQUIDITY) {
            exec.lastLiquidity(readInt());
        }
        
        if (m_serverVersion >= EClient.MIN_SERVER_VER_PENDING_PRICE_REVISION) {
        	exec.pendingPriceRevision(readBoolFromInt());
        }

        if (m_serverVersion >= EClient.MIN_SERVER_VER_SUBMITTER) {
            exec.submitter(readStr());
        }

		m_EWrapper.execDetails( reqId, contract, exec);
	}

	private void processBondContractDataMsg() throws IOException {
		int version = 6;
		if (m_serverVersion < EClient.MIN_SERVER_VER_SIZE_RULES) {
			version = readInt();
		}

		int reqId = -1;
		if (version >= 3) {
			reqId = readInt();
		}

		ContractDetails contract = new ContractDetails();

		contract.contract().symbol(readStr());
		contract.contract().secType(readStr());
		contract.cusip(readStr());
		contract.coupon(readDouble());
		readLastTradeDate(contract, true);
		contract.issueDate(readStr());
		contract.ratings(readStr());
		contract.bondType(readStr());
		contract.couponType(readStr());
		contract.convertible(readBoolFromInt());
		contract.callable(readBoolFromInt());
		contract.putable(readBoolFromInt());
		contract.descAppend(readStr());
		contract.contract().exchange(readStr());
		contract.contract().currency(readStr());
		contract.marketName(readStr());
		contract.contract().tradingClass(readStr());
		contract.contract().conid(readInt());
		contract.minTick(readDouble());
		if (m_serverVersion >= EClient.MIN_SERVER_VER_MD_SIZE_MULTIPLIER && m_serverVersion < EClient.MIN_SERVER_VER_SIZE_RULES) {
			readInt(); // mdSizeMultiplier - not used anymore
		}
		contract.orderTypes(readStr());
		contract.validExchanges(readStr());
		if (version >= 2) {
			contract.nextOptionDate(readStr());
			contract.nextOptionType(readStr());
			contract.nextOptionPartial(readBoolFromInt());
			contract.notes(readStr());
		}
		if( version >= 4) {
		   contract.longName(readStr());
		}
		if (m_serverVersion >= EClient.MIN_SERVER_VER_BOND_TRADING_HOURS) {
		    contract.timeZoneId(readStr());
		    contract.tradingHours(readStr());
		    contract.liquidHours(readStr());
		}
		if ( version >= 6) {
		    contract.evRule(readStr());
		    contract.evMultiplier(readDouble());
		}
		if (version >= 5) {
		    int secIdListCount = readInt();
		    if (secIdListCount  > 0) {
		        contract.secIdList(new ArrayList<>(secIdListCount));
		        for (int i = 0; i < secIdListCount; ++i) {
		            TagValue tagValue = new TagValue();
		            tagValue.m_tag = readStr();
		            tagValue.m_value = readStr();
		            contract.secIdList().add(tagValue);
		        }
		    }
		}
		if (m_serverVersion >= EClient.MIN_SERVER_VER_AGG_GROUP) {
			contract.aggGroup(readInt());
		}
		if (m_serverVersion >= EClient.MIN_SERVER_VER_MARKET_RULES) {
			contract.marketRuleIds(readStr());
		}
		if (m_serverVersion >= EClient.MIN_SERVER_VER_SIZE_RULES) {
		    contract.minSize(readDecimal());
		    contract.sizeIncrement(readDecimal());
		    contract.suggestedSizeIncrement(readDecimal());
		}
		
		m_EWrapper.bondContractDetails( reqId, contract);
	}
	
    private void processBondContractDataMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        ContractDataProto.ContractData contractDataProto = ContractDataProto.ContractData.parseFrom(byteArray);
        m_EWrapper.bondContractDataProtoBuf(contractDataProto);

        int reqId = contractDataProto.hasReqId() ? contractDataProto.getReqId() : EClientErrors.NO_VALID_ID;

        if (!contractDataProto.hasContract() || !contractDataProto.hasContractDetails()) {
            return;
        }
        // set contract details fields
        ContractDetails contractDetails = EDecoderUtils.decodeContractDetails(contractDataProto.getContract(), contractDataProto.getContractDetails(), true);
        
        m_EWrapper.bondContractDetails(reqId, contractDetails);
    }
	

	private void processContractDataMsg() throws IOException {
		int version = 8;
		if (m_serverVersion < EClient.MIN_SERVER_VER_SIZE_RULES) {
			version = readInt();
		}

		int reqId = -1;
		if (version >= 3) {
			reqId = readInt();
		}

		ContractDetails contract = new ContractDetails();
		contract.contract().symbol(readStr());
		contract.contract().secType(readStr());
		readLastTradeDate(contract, false);
		if (m_serverVersion >= EClient.MIN_SERVER_VER_LAST_TRADE_DATE) {
			contract.contract().lastTradeDate(readStr());
		}
		contract.contract().strike(readDouble());
		contract.contract().right(readStr());
		contract.contract().exchange(readStr());
		contract.contract().currency(readStr());
		contract.contract().localSymbol(readStr());
		contract.marketName(readStr());
		contract.contract().tradingClass(readStr());
		contract.contract().conid(readInt());
		contract.minTick(readDouble());
		if (m_serverVersion >= EClient.MIN_SERVER_VER_MD_SIZE_MULTIPLIER && m_serverVersion < EClient.MIN_SERVER_VER_SIZE_RULES) {
			readInt(); // mdSizeMultiplier - not used anymore
		}
		contract.contract().multiplier(readStr());
		contract.orderTypes(readStr());
		contract.validExchanges(readStr());
		if (version >= 2) {
		    contract.priceMagnifier(readInt());
		}
		if (version >= 4) {
			contract.underConid(readInt());
		}
		if( version >= 5) {
		   contract.longName(m_serverVersion >= EClient.MIN_SERVER_VER_ENCODE_MSG_ASCII7 ? decodeUnicodeEscapedString(readStr()) : readStr());
		   contract.contract().primaryExch(readStr());
		}
		if( version >= 6) {
		    contract.contractMonth(readStr());
		    contract.industry(readStr());
		    contract.category(readStr());
		    contract.subcategory(readStr());
		    contract.timeZoneId(readStr());
		    contract.tradingHours(readStr());
		    contract.liquidHours(readStr());
		 }
		if (version >= 8) {
		    contract.evRule(readStr());
		    contract.evMultiplier(readDouble());
		}
		if (version >= 7) {
		    int secIdListCount = readInt();
		        if (secIdListCount  > 0) {
		            contract.secIdList(new ArrayList<>(secIdListCount));
		            for (int i = 0; i < secIdListCount; ++i) {
		                TagValue tagValue = new TagValue();
		                tagValue.m_tag = readStr();
		                tagValue.m_value = readStr();
		                contract.secIdList().add(tagValue);
		            }
		        }
		}
		if (m_serverVersion >= EClient.MIN_SERVER_VER_AGG_GROUP) {
			contract.aggGroup(readInt());
		}
		if (m_serverVersion >= EClient.MIN_SERVER_VER_UNDERLYING_INFO) {
			contract.underSymbol(readStr());
			contract.underSecType(readStr());
		}
		if (m_serverVersion >= EClient.MIN_SERVER_VER_MARKET_RULES) {
			contract.marketRuleIds(readStr());
		}
		if (m_serverVersion >= EClient.MIN_SERVER_VER_REAL_EXPIRATION_DATE) {
			contract.realExpirationDate(readStr());
		}
		if (m_serverVersion >= EClient.MIN_SERVER_VER_STOCK_TYPE) {
		    contract.stockType(readStr());
		}
		if (m_serverVersion >= EClient.MIN_SERVER_VER_FRACTIONAL_SIZE_SUPPORT && m_serverVersion < EClient.MIN_SERVER_VER_SIZE_RULES) {
		    readDecimal(); // sizeMinTick - not used anymore
		}
		if (m_serverVersion >= EClient.MIN_SERVER_VER_SIZE_RULES) {
		    contract.minSize(readDecimal());
		    contract.sizeIncrement(readDecimal());
		    contract.suggestedSizeIncrement(readDecimal());
		}
        if (m_serverVersion >= EClient.MIN_SERVER_VER_FUND_DATA_FIELDS && contract.contract().secType() == SecType.FUND) {
            contract.fundName(readStr());
            contract.fundFamily(readStr());
            contract.fundType(readStr());
            contract.fundFrontLoad(readStr());
            contract.fundBackLoad(readStr());
            contract.fundBackLoadTimeInterval(readStr());
            contract.fundManagementFee(readStr());
            contract.fundClosed(readBoolFromInt());
            contract.fundClosedForNewInvestors(readBoolFromInt());
            contract.fundClosedForNewMoney(readBoolFromInt());
            contract.fundNotifyAmount(readStr());
            contract.fundMinimumInitialPurchase(readStr());
            contract.fundSubsequentMinimumPurchase(readStr());
            contract.fundBlueSkyStates(readStr());
            contract.fundBlueSkyTerritories(readStr());
            contract.fundDistributionPolicyIndicator(FundDistributionPolicyIndicator.get(readStr()));
            contract.fundAssetType(FundAssetType.get(readStr()));
        }
        
        if (m_serverVersion >= EClient.MIN_SERVER_VER_INELIGIBILITY_REASONS) {
            int ineligibilityReasonCount = readInt();
            List<IneligibilityReason> ineligibilityReasonList = new ArrayList<>();

            for (int i = 0; i < ineligibilityReasonCount; i++) {
                String id = readStr();
                String description = readStr();
                ineligibilityReasonList.add(new IneligibilityReason(id, description));
            }
            contract.ineligibilityReasonList(ineligibilityReasonList);
        }

		m_EWrapper.contractDetails( reqId, contract);
	}

    private void processContractDataMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        ContractDataProto.ContractData contractDataProto = ContractDataProto.ContractData.parseFrom(byteArray);
        m_EWrapper.contractDataProtoBuf(contractDataProto);

        int reqId = contractDataProto.hasReqId() ? contractDataProto.getReqId() : EClientErrors.NO_VALID_ID;

        if (!contractDataProto.hasContract() || !contractDataProto.hasContractDetails()) {
            return;
        }
        // set contract details fields
        ContractDetails contractDetails = EDecoderUtils.decodeContractDetails(contractDataProto.getContract(), contractDataProto.getContractDetails(), false);
        
        m_EWrapper.contractDetails(reqId, contractDetails);
    }
	
	private void processScannerDataMsg() throws IOException {
		int version = readInt();
		int tickerId = readInt();
		int numberOfElements = readInt();
		for (int ctr=0; ctr < numberOfElements; ctr++) {
		    int rank = readInt();
		    ContractDetails contract = new ContractDetails();
		    if (version >= 3) {
		    	contract.contract().conid(readInt());
		    }
		    contract.contract().symbol(readStr());
		    contract.contract().secType(readStr());
		    contract.contract().lastTradeDateOrContractMonth(readStr());
		    contract.contract().strike(readDouble());
		    contract.contract().right(readStr());
		    contract.contract().exchange(readStr());
		    contract.contract().currency(readStr());
		    contract.contract().localSymbol(readStr());
		    contract.marketName(readStr());
		    contract.contract().tradingClass(readStr());
		    String distance = readStr();
		    String benchmark = readStr();
		    String projection = readStr();
		    String legsStr = null;
		    if (version >= 2) {
		    	legsStr = readStr();
		    }
		    m_EWrapper.scannerData(tickerId, rank, contract, distance,
		        benchmark, projection, legsStr);
		}
		m_EWrapper.scannerDataEnd(tickerId);
	}

    private void processScannerDataMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        ScannerDataProto.ScannerData scannerDataProto = ScannerDataProto.ScannerData.parseFrom(byteArray);
        m_EWrapper.scannerDataProtoBuf(scannerDataProto);

        int reqId = scannerDataProto.hasReqId() ? scannerDataProto.getReqId() : EClientErrors.NO_VALID_ID;

        if (scannerDataProto.getScannerDataElementCount() > 0) {
            for (ScannerDataElementProto.ScannerDataElement element : scannerDataProto.getScannerDataElementList()) {
                int rank = element.hasRank() ? element.getRank() : 0;

                // Set contract details
                ContractDetails contractDetails = new ContractDetails();
                if (element.hasContract()) {
                    Contract contract = EDecoderUtils.decodeContract(element.getContract());
                    contractDetails.contract(contract);
                    contractDetails.marketName(element.hasMarketName() ? element.getMarketName() : "");
                }

                String distance = element.hasDistance() ? element.getDistance() : "";
                String benchmark = element.hasBenchmark() ? element.getBenchmark() : "";
                String projection = element.hasProjection() ? element.getProjection() : "";
                String comboKey = element.hasComboKey() ? element.getComboKey() : "";

                m_EWrapper.scannerData(reqId, rank, contractDetails, distance, benchmark, projection, comboKey);
            }
        }

        m_EWrapper.scannerDataEnd(reqId);
    }

	private void processNextValidIdMsg() throws IOException {
		/*int version =*/ readInt();
		int orderId = readInt();
		m_EWrapper.nextValidId( orderId);
	}

    private void processNextValidIdMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        NextValidIdProto.NextValidId nextValidIdProto = NextValidIdProto.NextValidId.parseFrom(byteArray);

        m_EWrapper.nextValidIdProtoBuf(nextValidIdProto);

        int orderId = nextValidIdProto.hasOrderId() ? nextValidIdProto.getOrderId() : 0;

        m_EWrapper.nextValidId(orderId);
    }

    private void processOpenOrderMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        OpenOrderProto.OpenOrder openOrderProto = OpenOrderProto.OpenOrder.parseFrom(byteArray);
        m_EWrapper.openOrderProtoBuf(openOrderProto);

        int orderId = openOrderProto.hasOrderId() ? openOrderProto.getOrderId() : 0;

        // set contract fields
        if (!openOrderProto.hasContract()) {
            return;
        }
        Contract contract = EDecoderUtils.decodeContract(openOrderProto.getContract());

        // set order fields
        if (!openOrderProto.hasOrder()) {
            return;
        }
        Order order = EDecoderUtils.decodeOrder(orderId, openOrderProto.getContract(), openOrderProto.getOrder());
        
        // set order state fields
        if (!openOrderProto.hasOrderState()) {
            return;
        }
        OrderState orderState  = EDecoderUtils.decodeOrderState(openOrderProto.getOrderState());
        m_EWrapper.openOrder(orderId, contract, order, orderState);
    }
	
    private void processOpenOrderMsg() throws IOException {

        // read version
        int version = m_serverVersion < EClient.MIN_SERVER_VER_ORDER_CONTAINER ? readInt() : m_serverVersion;

        Contract contract = new Contract();
        Order order = new Order();
        OrderState orderState = new OrderState();
        EOrderDecoder eOrderDecoder = new EOrderDecoder(this, contract, order, orderState, version, m_serverVersion);

        // read order id
        eOrderDecoder.readOrderId();

        // read contract fields
        eOrderDecoder.readContractFields();

        // read order fields
        eOrderDecoder.readAction();
        eOrderDecoder.readTotalQuantity();
        eOrderDecoder.readOrderType();
        eOrderDecoder.readLmtPrice();
        eOrderDecoder.readAuxPrice();
        eOrderDecoder.readTIF();
        eOrderDecoder.readOcaGroup();
        eOrderDecoder.readAccount();
        eOrderDecoder.readOpenClose();
        eOrderDecoder.readOrigin();
        eOrderDecoder.readOrderRef();
        eOrderDecoder.readClientId();
        eOrderDecoder.readPermId();
        eOrderDecoder.readOutsideRth();
        eOrderDecoder.readHidden();
        eOrderDecoder.readDiscretionaryAmount();
        eOrderDecoder.readGoodAfterTime();
        eOrderDecoder.skipSharesAllocation();
        eOrderDecoder.readFAParams();
        eOrderDecoder.readModelCode();
        eOrderDecoder.readGoodTillDate();
        eOrderDecoder.readRule80A();
        eOrderDecoder.readPercentOffset();
        eOrderDecoder.readSettlingFirm();
        eOrderDecoder.readShortSaleParams();
        eOrderDecoder.readAuctionStrategy();
        eOrderDecoder.readBoxOrderParams();
        eOrderDecoder.readPegToStkOrVolOrderParams();
        eOrderDecoder.readDisplaySize();
        eOrderDecoder.readOldStyleOutsideRth();
        eOrderDecoder.readBlockOrder();
        eOrderDecoder.readSweepToFill();
        eOrderDecoder.readAllOrNone();
        eOrderDecoder.readMinQty();
        eOrderDecoder.readOcaType();
        eOrderDecoder.readETradeOnly();
        eOrderDecoder.readFirmQuoteOnly();
        eOrderDecoder.readNbboPriceCap();
        eOrderDecoder.readParentId();
        eOrderDecoder.readTriggerMethod();
        eOrderDecoder.readVolOrderParams(true);
        eOrderDecoder.readTrailParams();
        eOrderDecoder.readBasisPoints();
        eOrderDecoder.readComboLegs();
        eOrderDecoder.readSmartComboRoutingParams();
        eOrderDecoder.readScaleOrderParams();
        eOrderDecoder.readHedgeParams();
        eOrderDecoder.readOptOutSmartRouting();
        eOrderDecoder.readClearingParams();
        eOrderDecoder.readNotHeld();
        eOrderDecoder.readDeltaNeutral();
        eOrderDecoder.readAlgoParams();
        eOrderDecoder.readSolicited();
        eOrderDecoder.readWhatIfInfoAndCommissionAndFees();
        eOrderDecoder.readVolRandomizeFlags();
        eOrderDecoder.readPegToBenchParams();
        eOrderDecoder.readConditions();
        eOrderDecoder.readAdjustedOrderParams();
        eOrderDecoder.readSoftDollarTier();
        eOrderDecoder.readCashQty();
        eOrderDecoder.readDontUseAutoPriceForHedge();
        eOrderDecoder.readIsOmsContainer();
        eOrderDecoder.readDiscretionaryUpToLimitPrice();
        eOrderDecoder.readUsePriceMgmtAlgo();
        eOrderDecoder.readDuration();
        eOrderDecoder.readPostToAts();
        eOrderDecoder.readAutoCancelParent(EClient.MIN_SERVER_VER_AUTO_CANCEL_PARENT);
        eOrderDecoder.readPegBestPegMidOrderAttributes();
        eOrderDecoder.readCustomerAccount();
        eOrderDecoder.readProfessionalCustomer();
        eOrderDecoder.readBondAccruedInterest();
        eOrderDecoder.readIncludeOvernight();
        eOrderDecoder.readCMETaggingFields();
        eOrderDecoder.readSubmitter();
        eOrderDecoder.readImbalanceOnly(EClient.MIN_SERVER_VER_IMBALANCE_ONLY);

        m_EWrapper.openOrder(order.orderId(), contract, order, orderState);
    }

    private void processErrorMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        ErrorMessageProto.ErrorMessage errorMessageProto = ErrorMessageProto.ErrorMessage.parseFrom(byteArray);
        m_EWrapper.errorProtoBuf(errorMessageProto);

        int id = errorMessageProto.hasId() ? errorMessageProto.getId() : 0;
        int errorCode = errorMessageProto.hasErrorCode() ? errorMessageProto.getErrorCode() : 0;
        String errorMsg = errorMessageProto.hasErrorMsg() ? errorMessageProto.getErrorMsg() : "";
        String advancedOrderRejectJson = errorMessageProto.hasAdvancedOrderRejectJson() ? errorMessageProto.getAdvancedOrderRejectJson() : "";
        long errorTime = errorMessageProto.hasErrorTime() ? errorMessageProto.getErrorTime() : 0;
        m_EWrapper.error(id, errorTime, errorCode, errorMsg, advancedOrderRejectJson);
    }

    private void processErrorMsg() throws IOException {
        if (m_serverVersion < EClient.MIN_SERVER_VER_ERROR_TIME) {
            /*int version =*/ readInt();
        }
        int id = readInt();
        int errorCode   = readInt();
        String errorMsg = m_serverVersion >= EClient.MIN_SERVER_VER_ENCODE_MSG_ASCII7 ? decodeUnicodeEscapedString(readStr()) : readStr();
        String advancedOrderRejectJson = null;
        if (m_serverVersion >= EClient.MIN_SERVER_VER_ADVANCED_ORDER_REJECT) {
            advancedOrderRejectJson = decodeUnicodeEscapedString(readStr());
        }
        long errorTime = 0;
        if (m_serverVersion >= EClient.MIN_SERVER_VER_ERROR_TIME) {
            errorTime = readLong();
        }
        m_EWrapper.error(id, errorTime, errorCode, errorMsg, advancedOrderRejectJson);
    }

	private void processAcctUpdateTimeMsg() throws IOException {
		/*int version =*/ readInt();
		String timeStamp = readStr();
		m_EWrapper.updateAccountTime(timeStamp);
	}

    private void processAcctUpdateTimeMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        AccountUpdateTimeProto.AccountUpdateTime accountUpdateTimeProto = AccountUpdateTimeProto.AccountUpdateTime.parseFrom(byteArray);
        m_EWrapper.updateAccountTimeProtoBuf(accountUpdateTimeProto);

        String timeStamp = accountUpdateTimeProto.hasTimeStamp() ? accountUpdateTimeProto.getTimeStamp() : "";

        m_EWrapper.updateAccountTime(timeStamp);
    }

	private void processPortfolioValueMsg() throws IOException {
		int version = readInt();
		Contract contract = new Contract();
		if (version >= 6) {
			contract.conid(readInt());
		}
		contract.symbol(readStr());
		contract.secType(readStr());
		contract.lastTradeDateOrContractMonth(readStr());
		contract.strike(readDouble());
		contract.right(readStr());
		if (version >= 7) {
			contract.multiplier(readStr());
			contract.primaryExch(readStr());
		}
		contract.currency(readStr());
		if ( version >= 2 ) {
		    contract.localSymbol(readStr());
		}
		if (version >= 8) {
		    contract.tradingClass(readStr());
		}

		Decimal position = readDecimal();
		double marketPrice = readDouble();
		double marketValue = readDouble();
		double  averageCost = 0.0;
		double  unrealizedPNL = 0.0;
		double  realizedPNL = 0.0;
		if (version >=3 ) {
		    averageCost = readDouble();
		    unrealizedPNL = readDouble();
		    realizedPNL = readDouble();
		}

		String accountName = null ;
		if( version >= 4) {
		    accountName = readStr();
		}

		if(version == 6 && m_serverVersion == 39) {
			contract.primaryExch(readStr());
		}

		m_EWrapper.updatePortfolio(contract, position, marketPrice, marketValue,
		                averageCost, unrealizedPNL, realizedPNL, accountName);
	}

    private void processPortfolioValueMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        PortfolioValueProto.PortfolioValue portfolioValueProto = PortfolioValueProto.PortfolioValue.parseFrom(byteArray);
        m_EWrapper.updatePortfolioProtoBuf(portfolioValueProto);

        // set contract fields
        if (!portfolioValueProto.hasContract()) {
            return;
        }
        Contract contract = EDecoderUtils.decodeContract(portfolioValueProto.getContract());

        Decimal position = portfolioValueProto.hasPosition() ? Util.stringToDecimal(portfolioValueProto.getPosition()) : Decimal.INVALID;
        double marketPrice = portfolioValueProto.hasMarketPrice() ? portfolioValueProto.getMarketPrice() : 0.0;
        double marketValue = portfolioValueProto.hasMarketValue() ? portfolioValueProto.getMarketValue() : 0.0;
        double averageCost = portfolioValueProto.hasAverageCost() ? portfolioValueProto.getAverageCost() : 0.0;
        double unrealizedPNL = portfolioValueProto.hasUnrealizedPNL() ? portfolioValueProto.getUnrealizedPNL() : 0.0;
        double realizedPNL = portfolioValueProto.hasRealizedPNL() ? portfolioValueProto.getRealizedPNL() : 0.0;
        String accountName = portfolioValueProto.hasAccountName() ? portfolioValueProto.getAccountName() : "";

        m_EWrapper.updatePortfolio(contract, position, marketPrice, marketValue, averageCost, unrealizedPNL, realizedPNL, accountName);
    }

	private void processAcctValueMsg() throws IOException {
		int version = readInt();
		String key = readStr();
		String val  = readStr();
		String cur = readStr();
		String accountName = null ;
		if( version >= 2) {
		    accountName = readStr();
		}
		m_EWrapper.updateAccountValue(key, val, cur, accountName);
	}
	
    private void processAccountValueMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        AccountValueProto.AccountValue accountValueProto = AccountValueProto.AccountValue.parseFrom(byteArray);
        m_EWrapper.updateAccountValueProtoBuf(accountValueProto);

        String key = accountValueProto.hasKey() ? accountValueProto.getKey() : "";
        String value = accountValueProto.hasValue() ? accountValueProto.getValue() : "";
        String currency = accountValueProto.hasCurrency() ? accountValueProto.getCurrency() : "";
        String accountName = accountValueProto.hasAccountName() ? accountValueProto.getAccountName() : "";

        m_EWrapper.updateAccountValue(key, value, currency, accountName);
    }

	private void processOrderStatusMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        OrderStatusProto.OrderStatus orderStatusProto = OrderStatusProto.OrderStatus.parseFrom(byteArray);
        m_EWrapper.orderStatusProtoBuf(orderStatusProto);

        int orderId = orderStatusProto.hasOrderId() ? orderStatusProto.getOrderId() : Integer.MAX_VALUE;
        String status = orderStatusProto.hasStatus() ? orderStatusProto.getStatus() : "";
        Decimal filled = orderStatusProto.hasFilled() ? Util.stringToDecimal(orderStatusProto.getFilled()) : Decimal.INVALID;
        Decimal remaining = orderStatusProto.hasRemaining() ? Util.stringToDecimal(orderStatusProto.getRemaining()) : Decimal.INVALID;
        double avgFillPrice = orderStatusProto.hasAvgFillPrice() ? orderStatusProto.getAvgFillPrice() : Double.MAX_VALUE;
        long permId = orderStatusProto.hasPermId() ? orderStatusProto.getPermId() : Long.MAX_VALUE;
        int parentId = orderStatusProto.hasParentId() ? orderStatusProto.getParentId() : Integer.MAX_VALUE;
        double lastFillPrice = orderStatusProto.hasLastFillPrice() ? orderStatusProto.getLastFillPrice() : Double.MAX_VALUE;
        int clientId = orderStatusProto.hasClientId() ? orderStatusProto.getClientId() : Integer.MAX_VALUE;
        String whyHeld = orderStatusProto.hasWhyHeld() ? orderStatusProto.getWhyHeld() : "";
        double mktCapPrice = orderStatusProto.hasMktCapPrice() ? orderStatusProto.getMktCapPrice() : Double.MAX_VALUE;
        m_EWrapper.orderStatus(orderId, status, filled, remaining, avgFillPrice, permId, parentId, lastFillPrice, clientId, whyHeld, mktCapPrice);
    }

	private void processOrderStatusMsg() throws IOException {
		int version = m_serverVersion >= EClient.MIN_SERVER_VER_MARKET_CAP_PRICE ? Integer.MAX_VALUE : readInt();
		int id = readInt();
		String status = readStr();
		Decimal filled = readDecimal();
		Decimal remaining = readDecimal();
		double avgFillPrice = readDouble();

		long permId = 0;
		if( version >= 2) {
		    permId = readLong();
		}

		int parentId = 0;
		if( version >= 3) {
		    parentId = readInt();
		}

		double lastFillPrice = 0;
		if( version >= 4) {
		    lastFillPrice = readDouble();
		}

		int clientId = 0;
		if( version >= 5) {
		    clientId = readInt();
		}

		String whyHeld = null;
		if( version >= 6) {
			whyHeld = readStr();
		}
		
		double mktCapPrice = Double.MAX_VALUE;
		
		if (m_serverVersion >= EClient.MIN_SERVER_VER_MARKET_CAP_PRICE) {
		    mktCapPrice = readDouble();
		}

		m_EWrapper.orderStatus( id, status, filled, remaining, avgFillPrice,
		                permId, parentId, lastFillPrice, clientId, whyHeld, mktCapPrice);
	}

	private void processTickEFPMsg() throws IOException {
		/*int version =*/ readInt();
		int tickerId = readInt();
		int tickType = readInt();
		double basisPoints = readDouble();
		String formattedBasisPoints = readStr();
		double impliedFuturesPrice = readDouble();
		int holdDays = readInt();
		String futureLastTradeDate = readStr();
		double dividendImpact = readDouble();
		double dividendsToLastTradeDate = readDouble();
		m_EWrapper.tickEFP( tickerId, tickType, basisPoints, formattedBasisPoints,
							impliedFuturesPrice, holdDays, futureLastTradeDate, dividendImpact, dividendsToLastTradeDate);
	}

	private void processTickStringMsg() throws IOException {
		/*int version =*/ readInt();
		int tickerId = readInt();
		int tickType = readInt();
		String value = readStr();

		m_EWrapper.tickString( tickerId, tickType, value);
	}

    private void processTickStringMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        TickStringProto.TickString tickStringProto = TickStringProto.TickString.parseFrom(byteArray);
        m_EWrapper.tickStringProtoBuf(tickStringProto);

        int reqId = tickStringProto.hasReqId() ? tickStringProto.getReqId() : EClientErrors.NO_VALID_ID;
        int tickType = tickStringProto.hasTickType() ? tickStringProto.getTickType() : 0;
        String value = tickStringProto.hasValue() ? tickStringProto.getValue() : "";

        m_EWrapper.tickString(reqId, tickType, value);
    }

	private void processTickGenericMsg() throws IOException {
		/*int version =*/ readInt();
		int tickerId = readInt();
		int tickType = readInt();
		double value = readDouble();

		m_EWrapper.tickGeneric( tickerId, tickType, value);
	}

    private void processTickGenericMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        TickGenericProto.TickGeneric tickGenericProto = TickGenericProto.TickGeneric.parseFrom(byteArray);
        m_EWrapper.tickGenericProtoBuf(tickGenericProto);

        int reqId = tickGenericProto.hasReqId() ? tickGenericProto.getReqId() : EClientErrors.NO_VALID_ID;
        int tickType = tickGenericProto.hasTickType() ? tickGenericProto.getTickType() : 0;
        double value = tickGenericProto.hasValue() ? tickGenericProto.getValue() : 0;

        m_EWrapper.tickGeneric(reqId, tickType, value);
    }

	private void processTickOptionComputationMsg() throws IOException {
		int version = m_serverVersion >= EClient.MIN_SERVER_VER_PRICE_BASED_VOLATILITY ? Integer.MAX_VALUE : readInt();
		int tickerId = readInt();
		int tickType = readInt();
		int tickAttrib = Integer.MAX_VALUE;
		if (m_serverVersion >= EClient.MIN_SERVER_VER_PRICE_BASED_VOLATILITY) {
			tickAttrib = readInt();
		}
		double impliedVol = readDouble();
		if (Double.compare(impliedVol, -1) == 0) { // -1 is the "not yet computed" indicator
			impliedVol = Double.MAX_VALUE;
		}
		
		double delta = readDouble();
		if (Double.compare(delta, -2) == 0) { // -2 is the "not yet computed" indicator
			delta = Double.MAX_VALUE;
		}
		double optPrice = Double.MAX_VALUE;
		double pvDividend = Double.MAX_VALUE;
		double gamma = Double.MAX_VALUE;
		double vega = Double.MAX_VALUE;
		double theta = Double.MAX_VALUE;
		double undPrice = Double.MAX_VALUE;
		if (version >= 6 || tickType == TickType.MODEL_OPTION.index()
				|| tickType == TickType.DELAYED_MODEL_OPTION.index()) { // introduced in version == 5
			optPrice = readDouble();
			if (Double.compare(optPrice, -1) == 0) { // -1 is the "not yet computed" indicator
				optPrice = Double.MAX_VALUE;
			}
			pvDividend = readDouble();
			if (Double.compare(pvDividend, -1) == 0) { // -1 is the "not yet computed" indicator
				pvDividend = Double.MAX_VALUE;
			}
		}
		if (version >= 6) {
			gamma = readDouble();
			if (Double.compare(gamma, -2) == 0) { // -2 is the "not yet computed" indicator
				gamma = Double.MAX_VALUE;
			}
			vega = readDouble();
			if (Double.compare(vega,  -2) == 0) { // -2 is the "not yet computed" indicator
				vega = Double.MAX_VALUE;
			}
			theta = readDouble();
			if (Double.compare(theta, -2) == 0) { // -2 is the "not yet computed" indicator
				theta = Double.MAX_VALUE;
			}
			undPrice = readDouble();
			if (Double.compare(undPrice, -1) == 0) { // -1 is the "not yet computed" indicator
				undPrice = Double.MAX_VALUE;
			}
		}

		m_EWrapper.tickOptionComputation( tickerId, tickType, tickAttrib, impliedVol, delta, optPrice, pvDividend, gamma, vega, theta, undPrice);
	}

    private void processTickOptionComputationMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        TickOptionComputationProto.TickOptionComputation tickOptionComputationProto = TickOptionComputationProto.TickOptionComputation.parseFrom(byteArray);
        m_EWrapper.tickOptionComputationProtoBuf(tickOptionComputationProto);

        int reqId = tickOptionComputationProto.hasReqId() ? tickOptionComputationProto.getReqId() : EClientErrors.NO_VALID_ID;
        int tickType = tickOptionComputationProto.hasTickType() ? tickOptionComputationProto.getTickType() : 0;
        int tickAttrib = tickOptionComputationProto.hasTickAttrib() ? tickOptionComputationProto.getTickAttrib() : 0;
        double impliedVol = tickOptionComputationProto.hasImpliedVol() ? tickOptionComputationProto.getImpliedVol() : Double.MAX_VALUE;
        if (Double.compare(impliedVol, -1) == 0) { // -1 is the "not yet computed" indicator
            impliedVol = Double.MAX_VALUE;
        }
        double delta = tickOptionComputationProto.hasDelta() ? tickOptionComputationProto.getDelta() : Double.MAX_VALUE;
        if (Double.compare(delta, -2) == 0) { // -2 is the "not yet computed" indicator
            delta = Double.MAX_VALUE;
        }
        double optPrice = tickOptionComputationProto.hasOptPrice() ? tickOptionComputationProto.getOptPrice() : Double.MAX_VALUE;
        if (Double.compare(optPrice, -1) == 0) { // -1 is the "not yet computed" indicator
            optPrice = Double.MAX_VALUE;
        }
        double pvDividend = tickOptionComputationProto.hasPvDividend() ? tickOptionComputationProto.getPvDividend() : Double.MAX_VALUE;
        if (Double.compare(pvDividend, -1) == 0) { // -1 is the "not yet computed" indicator
            pvDividend = Double.MAX_VALUE;
        }
        double gamma = tickOptionComputationProto.hasGamma() ? tickOptionComputationProto.getGamma() : Double.MAX_VALUE;
        if (Double.compare(gamma, -2) == 0) { // -2 is the "not yet computed" indicator
            gamma = Double.MAX_VALUE;
        }
        double vega = tickOptionComputationProto.hasVega() ? tickOptionComputationProto.getVega() : Double.MAX_VALUE;
        if (Double.compare(vega,  -2) == 0) { // -2 is the "not yet computed" indicator
            vega = Double.MAX_VALUE;
        }
        double theta = tickOptionComputationProto.hasTheta() ? tickOptionComputationProto.getTheta() : Double.MAX_VALUE;
        if (Double.compare(theta, -2) == 0) { // -2 is the "not yet computed" indicator
            theta = Double.MAX_VALUE;
        }
        double undPrice = tickOptionComputationProto.hasUndPrice() ? tickOptionComputationProto.getUndPrice() : Double.MAX_VALUE;
        if (Double.compare(undPrice, -1) == 0) { // -1 is the "not yet computed" indicator
            undPrice = Double.MAX_VALUE;
        }

        m_EWrapper.tickOptionComputation(reqId, tickType, tickAttrib, impliedVol, delta, optPrice, pvDividend, gamma, vega, theta, undPrice);
    }

	private void processAccountSummaryEndMsg() throws IOException {
		/*int version =*/ readInt();
		int reqId = readInt();
		m_EWrapper.accountSummaryEnd(reqId);
	}

    private void processAccountSummaryEndMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        AccountSummaryEndProto.AccountSummaryEnd accountSummaryEndProto = AccountSummaryEndProto.AccountSummaryEnd.parseFrom(byteArray);
        m_EWrapper.accountSummaryEndProtoBuf(accountSummaryEndProto);

        int reqId = accountSummaryEndProto.hasReqId() ? accountSummaryEndProto.getReqId() : EClientErrors.NO_VALID_ID;

        m_EWrapper.accountSummaryEnd(reqId);
    }

	private void processAccountSummaryMsg() throws IOException {
		/*int version =*/ readInt();
		int reqId = readInt();
		String account = readStr();
		String tag = readStr();
		String value = readStr();
		String currency = readStr();
		m_EWrapper.accountSummary(reqId, account, tag, value, currency);
	}

    private void processAccountSummaryMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        AccountSummaryProto.AccountSummary accountSummaryProto = AccountSummaryProto.AccountSummary.parseFrom(byteArray);
        m_EWrapper.accountSummaryProtoBuf(accountSummaryProto);

        int reqId = accountSummaryProto.hasReqId() ? accountSummaryProto.getReqId() : EClientErrors.NO_VALID_ID;
        String account = accountSummaryProto.hasAccount() ? accountSummaryProto.getAccount() : "";
        String tag = accountSummaryProto.hasTag() ? accountSummaryProto.getTag() : "";
        String value = accountSummaryProto.hasValue() ? accountSummaryProto.getValue() : "";
        String currency = accountSummaryProto.hasCurrency() ? accountSummaryProto.getCurrency() : "";

        m_EWrapper.accountSummary(reqId, account, tag, value, currency);
    }

	private void processPositionEndMsg() throws IOException {
		/*int version =*/ readInt();
		m_EWrapper.positionEnd();
	}

    private void processPositionEndMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        PositionEndProto.PositionEnd positionEndProto = PositionEndProto.PositionEnd.parseFrom(byteArray);
        m_EWrapper.positionEndProtoBuf(positionEndProto);

        m_EWrapper.positionEnd();
    }

	private void processPositionMsg() throws IOException {
		int version = readInt();
		String account = readStr();

		Contract contract = new Contract();
		contract.conid(readInt());
		contract.symbol(readStr());
		contract.secType(readStr());
		contract.lastTradeDateOrContractMonth(readStr());
		contract.strike(readDouble());
		contract.right(readStr());
		contract.multiplier(readStr());
		contract.exchange(readStr());
		contract.currency(readStr());
		contract.localSymbol(readStr());
		if (version >= 2) {
			contract.tradingClass(readStr());
		}

		Decimal pos = readDecimal();
		double avgCost = 0;
		if (version >= 3) {
			avgCost = readDouble();
		}

		m_EWrapper.position( account, contract, pos, avgCost);
	}

    private void processPositionMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        PositionProto.Position positionProto = PositionProto.Position.parseFrom(byteArray);
        m_EWrapper.positionProtoBuf(positionProto);

        String account = positionProto.hasAccount() ? positionProto.getAccount() : "";

        // set contract fields
        if (!positionProto.hasContract()) {
            return;
        }
        Contract contract = EDecoderUtils.decodeContract(positionProto.getContract());

        Decimal position = positionProto.hasPosition() ? Util.stringToDecimal(positionProto.getPosition()) : Decimal.INVALID;
        double avgCost = positionProto.hasAvgCost() ? positionProto.getAvgCost() : 0.0;

        m_EWrapper.position(account, contract, position, avgCost);
    }

	private void processTickSizeMsg() throws IOException {
		/*int version =*/ readInt();
		int tickerId = readInt();
		int tickType = readInt();
		Decimal size = readDecimal();

		m_EWrapper.tickSize( tickerId, tickType, size);
	}

	private void processTickSizeMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        TickSizeProto.TickSize tickSizeProto = TickSizeProto.TickSize.parseFrom(byteArray);
        m_EWrapper.tickSizeProtoBuf(tickSizeProto);

        int reqId = tickSizeProto.hasReqId() ? tickSizeProto.getReqId() : EClientErrors.NO_VALID_ID;
        int tickType = tickSizeProto.hasTickType() ? tickSizeProto.getTickType() : 0;
        Decimal size = tickSizeProto.hasSize() ? Util.stringToDecimal(tickSizeProto.getSize()) : Decimal.INVALID;

        m_EWrapper.tickSize(reqId, tickType, size);
    }

	private void processTickPriceMsg() throws IOException {
		int version = readInt();
		int tickerId = readInt();
		int tickType = readInt();
		double price = readDouble();
		Decimal size = Decimal.INVALID;
		TickAttrib attribs = new TickAttrib();
		
		if( version >= 2) {
		    size = readDecimal();
		}
		
		if (version >= 3) {		
			int attrMask = readInt();			

			attribs.canAutoExecute(attrMask == 1);
			
			if (m_serverVersion >= EClient.MIN_SERVER_VER_PAST_LIMIT) {
				BitMask mask = new BitMask(attrMask);
				
				attribs.canAutoExecute(mask.get(0));
				attribs.pastLimit(mask.get(1));
				if (m_serverVersion >= EClient.MIN_SERVER_VER_PRE_OPEN_BID_ASK) {
					attribs.preOpen(mask.get(2));
				}
			}
		}

		
		m_EWrapper.tickPrice( tickerId, tickType, price, attribs);

		if( version >= 2) {
		    final int sizeTickType;
		    switch (tickType) {
		        case 1: // BID
		            sizeTickType = 0 ; // BID_SIZE
		            break ;
		        case 2: // ASK
		            sizeTickType = 3 ; // ASK_SIZE
		            break ;
		        case 4: // LAST
		            sizeTickType = 5 ; // LAST_SIZE
		            break ;
		        case 66: // DELAYED_BID
		            sizeTickType = 69 ; // DELAYED_BID_SIZE
		            break ;
		        case 67: // DELAYED_ASK
		            sizeTickType = 70 ; // DELAYED_ASK_SIZE
		            break ;
		        case 68: // DELAYED_LAST
		            sizeTickType = 71 ; // DELAYED_LAST_SIZE
		            break ;
                default:
                    sizeTickType = -1; // not a tick
		    }
		    if (sizeTickType != -1) {
		        m_EWrapper.tickSize( tickerId, sizeTickType, size);
		    }
		}
	}

    private void processTickPriceMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        TickPriceProto.TickPrice tickPriceProto = TickPriceProto.TickPrice.parseFrom(byteArray);
        m_EWrapper.tickPriceProtoBuf(tickPriceProto);

        int reqId = tickPriceProto.hasReqId() ? tickPriceProto.getReqId() : EClientErrors.NO_VALID_ID;
        int tickType = tickPriceProto.hasTickType() ? tickPriceProto.getTickType() : 0;
        double price = tickPriceProto.hasPrice() ? tickPriceProto.getPrice() : 0;
        Decimal size = tickPriceProto.hasSize() ? Util.stringToDecimal(tickPriceProto.getSize()) : Decimal.INVALID;
        TickAttrib attribs = new TickAttrib();
        int attrMask = tickPriceProto.hasAttrMask() ? tickPriceProto.getAttrMask() : 0;
        BitMask mask = new BitMask(attrMask);
        attribs.canAutoExecute(mask.get(0));
        attribs.pastLimit(mask.get(1));
        attribs.preOpen(mask.get(2));

        m_EWrapper.tickPrice(reqId, tickType, price, attribs);

        final int sizeTickType;
        switch (tickType) {
            case 1: // BID
                sizeTickType = 0 ; // BID_SIZE
                break ;
            case 2: // ASK
                sizeTickType = 3 ; // ASK_SIZE
                break ;
            case 4: // LAST
                sizeTickType = 5 ; // LAST_SIZE
                break ;
            case 66: // DELAYED_BID
                sizeTickType = 69 ; // DELAYED_BID_SIZE
                break ;
            case 67: // DELAYED_ASK
                sizeTickType = 70 ; // DELAYED_ASK_SIZE
                break ;
            case 68: // DELAYED_LAST
                sizeTickType = 71 ; // DELAYED_LAST_SIZE
                break ;
            default:
                sizeTickType = -1; // not a tick
        }
        if (sizeTickType != -1) {
            m_EWrapper.tickSize(reqId, sizeTickType, size);
        }
    }

    private void processPositionMultiMsg() throws IOException {
        /*int version =*/ readInt();
        int reqId = readInt();
        String account = readStr();

        Contract contract = new Contract();
        contract.conid(readInt());
        contract.symbol(readStr());
        contract.secType(readStr());
        contract.lastTradeDateOrContractMonth(readStr());
        contract.strike(readDouble());
        contract.right(readStr());
        contract.multiplier(readStr());
        contract.exchange(readStr());
        contract.currency(readStr());
        contract.localSymbol(readStr());
        contract.tradingClass(readStr());
        Decimal pos = readDecimal();
        double avgCost = readDouble();
        String modelCode = readStr();

        m_EWrapper.positionMulti( reqId, account, modelCode, contract, pos, avgCost);
    }

    private void processPositionMultiMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        PositionMultiProto.PositionMulti positionMultiProto = PositionMultiProto.PositionMulti.parseFrom(byteArray);
        m_EWrapper.positionMultiProtoBuf(positionMultiProto);

        int reqId = positionMultiProto.hasReqId() ? positionMultiProto.getReqId() : EClientErrors.NO_VALID_ID;
        String account = positionMultiProto.hasAccount() ? positionMultiProto.getAccount() : "";
        String modelCode = positionMultiProto.hasModelCode() ? positionMultiProto.getModelCode() : "";

        // set contract fields
        if (!positionMultiProto.hasContract()) {
            return;
        }
        Contract contract = EDecoderUtils.decodeContract(positionMultiProto.getContract());

        Decimal position = positionMultiProto.hasPosition() ? Util.stringToDecimal(positionMultiProto.getPosition()) : Decimal.INVALID;
        double avgCost = positionMultiProto.hasAvgCost() ? positionMultiProto.getAvgCost() : 0.0;

        m_EWrapper.positionMulti(reqId, account, modelCode, contract, position, avgCost);
    }

    private void processPositionMultiEndMsg() throws IOException {
        /*int version =*/ readInt();
        int reqId = readInt();

        m_EWrapper.positionMultiEnd( reqId);
    }

    private void processPositionMultiEndMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        PositionMultiEndProto.PositionMultiEnd positionMultiEndProto = PositionMultiEndProto.PositionMultiEnd.parseFrom(byteArray);
        m_EWrapper.positionMultiEndProtoBuf(positionMultiEndProto);

        int reqId = positionMultiEndProto.hasReqId() ? positionMultiEndProto.getReqId() : EClientErrors.NO_VALID_ID;
        
        m_EWrapper.positionMultiEnd(reqId);
    }

    private void processAccountUpdateMultiMsg() throws IOException {
        /*int version =*/ readInt();
        int reqId = readInt();
        String account = readStr();
        String modelCode = readStr();
        String key = readStr();
        String value = readStr();
        String currency = readStr();

        m_EWrapper.accountUpdateMulti( reqId, account, modelCode, key, value, currency);
    }

    private void processAccountUpdateMultiMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        AccountUpdateMultiProto.AccountUpdateMulti accountUpdateMultiProto = AccountUpdateMultiProto.AccountUpdateMulti.parseFrom(byteArray);
        m_EWrapper.accountUpdateMultiProtoBuf(accountUpdateMultiProto);

        int reqId = accountUpdateMultiProto.hasReqId() ? accountUpdateMultiProto.getReqId() : EClientErrors.NO_VALID_ID;
        String account = accountUpdateMultiProto.hasAccount() ? accountUpdateMultiProto.getAccount() : "";
        String modelCode = accountUpdateMultiProto.hasModelCode() ? accountUpdateMultiProto.getModelCode() : "";
        String key = accountUpdateMultiProto.hasKey() ? accountUpdateMultiProto.getKey() : "";
        String value = accountUpdateMultiProto.hasValue() ? accountUpdateMultiProto.getValue() : "";
        String currency = accountUpdateMultiProto.hasCurrency() ? accountUpdateMultiProto.getCurrency() : "";

        m_EWrapper.accountUpdateMulti(reqId, account, modelCode, key, value, currency);
    }

    private void processAccountUpdateMultiEndMsg() throws IOException {
        /*int version =*/ readInt();
        int reqId = readInt();

        m_EWrapper.accountUpdateMultiEnd( reqId);
    }  

    private void processAccountUpdateMultiEndMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        AccountUpdateMultiEndProto.AccountUpdateMultiEnd accountUpdateMultiEndProto = AccountUpdateMultiEndProto.AccountUpdateMultiEnd.parseFrom(byteArray);
        m_EWrapper.accountUpdateMultiEndProtoBuf(accountUpdateMultiEndProto);

        int reqId = accountUpdateMultiEndProto.hasReqId() ? accountUpdateMultiEndProto.getReqId() : EClientErrors.NO_VALID_ID;
        
        m_EWrapper.accountUpdateMultiEnd(reqId);
    }

    private void processSmartComponentsMsg() throws IOException {
    	int reqId = readInt();
    	int n = readInt();    	
    	Map<Integer, Entry<String, Character>> theMap = new HashMap<>();
    	
    	for (int i = 0; i < n; i++) {
    		int bitNumber = readInt();
    		String exchange = readStr();
    		char exchangeLetter = readChar();
    		
    		theMap.put(bitNumber, new SimpleEntry<>(exchange, exchangeLetter));
    }
    
    	m_EWrapper.smartComponents(reqId, theMap);
    }

    private void processSmartComponentsMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        SmartComponentsProto.SmartComponents smartComponentsProto = SmartComponentsProto.SmartComponents.parseFrom(byteArray);

        m_EWrapper.smartComponentsProtoBuf(smartComponentsProto);

        int reqId = smartComponentsProto.hasReqId() ? smartComponentsProto.getReqId() : EClientErrors.NO_VALID_ID;
        Map<Integer, Entry<String, Character>> theMap = EDecoderUtils.decodeSmartComponents(smartComponentsProto); 

        m_EWrapper.smartComponents(reqId, theMap);
    }

    private void processTickReqParamsMsg() throws IOException {
    	int tickerId = readInt();
    	double minTick = readDouble();
    	String bboExchange = readStr();
    	int snapshotPermissions = readInt();
    	
    	m_EWrapper.tickReqParams(tickerId, minTick, bboExchange, snapshotPermissions);
    }

    private void processTickReqParamsMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        TickReqParamsProto.TickReqParams tickReqParamsProto = TickReqParamsProto.TickReqParams.parseFrom(byteArray);
        m_EWrapper.tickReqParamsProtoBuf(tickReqParamsProto);

        int reqId = tickReqParamsProto.hasReqId() ? tickReqParamsProto.getReqId() : EClientErrors.NO_VALID_ID;
        double minTick = tickReqParamsProto.hasMinTick() ? Double.parseDouble(tickReqParamsProto.getMinTick()) : Double.MAX_VALUE;
        String bboExchange = tickReqParamsProto.hasBboExchange() ? tickReqParamsProto.getBboExchange() : "";
        int snapshotPermissions = tickReqParamsProto.hasSnapshotPermissions() ? tickReqParamsProto.getSnapshotPermissions() : Integer.MAX_VALUE;

        m_EWrapper.tickReqParams(reqId, minTick, bboExchange, snapshotPermissions);
    }
    
    private void processTickByTickMsg() throws IOException {
        int reqId = readInt();
        int tickType = readInt();
        long time = readLong();

        BitMask mask;
        switch(tickType){
            case 0: // None
                break;
            case 1: // Last
            case 2: // AllLast
                double price = readDouble();
                Decimal size = readDecimal();
                mask = new BitMask(readInt());
                TickAttribLast tickAttribLast = new TickAttribLast();
                tickAttribLast.pastLimit(mask.get(0));
                tickAttribLast.unreported(mask.get(1));
                String exchange = readStr();
                String specialConditions = readStr();
                m_EWrapper.tickByTickAllLast(reqId, tickType, time, price, size, tickAttribLast, exchange, specialConditions);
                break;
            case 3: // BidAsk
                double bidPrice = readDouble();
                double askPrice = readDouble();
                Decimal bidSize = readDecimal();
                Decimal askSize = readDecimal();
                mask = new BitMask(readInt());
                TickAttribBidAsk tickAttribBidAsk = new TickAttribBidAsk();
                tickAttribBidAsk.bidPastLow(mask.get(0));
                tickAttribBidAsk.askPastHigh(mask.get(1));
                m_EWrapper.tickByTickBidAsk(reqId, time, bidPrice, askPrice, bidSize, askSize, tickAttribBidAsk);
                break;
            case 4: // MidPoint
                double midPoint = readDouble();
                m_EWrapper.tickByTickMidPoint(reqId, time, midPoint);
                break;
        }
    }

    private void processTickByTickMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        TickByTickDataProto.TickByTickData tickByTickDataProto = TickByTickDataProto.TickByTickData.parseFrom(byteArray);
        m_EWrapper.tickByTickDataProtoBuf(tickByTickDataProto);

        int reqId = tickByTickDataProto.hasReqId() ? tickByTickDataProto.getReqId() : EClientErrors.NO_VALID_ID;
        int tickType = tickByTickDataProto.hasTickType() ? tickByTickDataProto.getTickType() : 0;

        switch (tickType) {
            case 0: // None
                break;
            case 1: // Last
            case 2: // AllLast
                if (tickByTickDataProto.hasHistoricalTickLast()) {
                    HistoricalTickLast historicalTickLast = EDecoderUtils.decodeHistoricalTickLast(tickByTickDataProto.getHistoricalTickLast());
                    m_EWrapper.tickByTickAllLast(reqId, tickType, historicalTickLast.time(), historicalTickLast.price(), 
                                                historicalTickLast.size(), historicalTickLast.tickAttribLast(), 
                                                historicalTickLast.exchange(), historicalTickLast.specialConditions());
                }
                break;

            case 3: // BidAsk
                if (tickByTickDataProto.hasHistoricalTickBidAsk()) {
                    HistoricalTickBidAsk historicalTickBidAsk = EDecoderUtils.decodeHistoricalTickBidAsk(tickByTickDataProto.getHistoricalTickBidAsk());
                    m_EWrapper.tickByTickBidAsk(reqId, historicalTickBidAsk.time(), historicalTickBidAsk.priceBid(), 
                                               historicalTickBidAsk.priceAsk(), historicalTickBidAsk.sizeBid(), 
                                               historicalTickBidAsk.sizeAsk(), historicalTickBidAsk.tickAttribBidAsk());
                }
                break;

            case 4: // MidPoint
                if (tickByTickDataProto.hasHistoricalTickMidPoint()) {
                    HistoricalTick historicalTick = EDecoderUtils.decodeHistoricalTick(tickByTickDataProto.getHistoricalTickMidPoint());
                    m_EWrapper.tickByTickMidPoint(reqId, historicalTick.time(), historicalTick.price());
                }
                break;
        }
    }

    private void processOrderBoundMsg() throws IOException {
        long permId = readLong();
        int clientId = readInt();
        int orderId = readInt();
        m_EWrapper.orderBound(permId, clientId, orderId);
    }
    
    private void processOrderBoundMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        OrderBoundProto.OrderBound orderBoundProto = OrderBoundProto.OrderBound.parseFrom(byteArray);
        m_EWrapper.orderBoundProtoBuf(orderBoundProto);

        long permId = orderBoundProto.hasPermId() ? orderBoundProto.getPermId() : Long.MAX_VALUE;
        int clientId = orderBoundProto.hasClientId() ? orderBoundProto.getClientId() : Integer.MAX_VALUE;
        int orderId = orderBoundProto.hasOrderId() ? orderBoundProto.getOrderId() : Integer.MAX_VALUE;

        m_EWrapper.orderBound(permId, clientId, orderId);
    }

    private void processCompletedOrderMsg() throws IOException {
        Contract contract = new Contract();
        Order order = new Order();
        OrderState orderState = new OrderState();

        EOrderDecoder eOrderDecoder = new EOrderDecoder(this, contract, order, orderState, Integer.MAX_VALUE, m_serverVersion);

        // read contract fields 
        eOrderDecoder.readContractFields();
        
        // read order fields
        eOrderDecoder.readAction();
        eOrderDecoder.readTotalQuantity();
        eOrderDecoder.readOrderType();
        eOrderDecoder.readLmtPrice();
        eOrderDecoder.readAuxPrice();
        eOrderDecoder.readTIF();
        eOrderDecoder.readOcaGroup();
        eOrderDecoder.readAccount();
        eOrderDecoder.readOpenClose();
        eOrderDecoder.readOrigin();
        eOrderDecoder.readOrderRef();
        eOrderDecoder.readPermId();
        eOrderDecoder.readOutsideRth();
        eOrderDecoder.readHidden();
        eOrderDecoder.readDiscretionaryAmount();
        eOrderDecoder.readGoodAfterTime();
        eOrderDecoder.readFAParams();
        eOrderDecoder.readModelCode();
        eOrderDecoder.readGoodTillDate();
        eOrderDecoder.readRule80A();
        eOrderDecoder.readPercentOffset();
        eOrderDecoder.readSettlingFirm();
        eOrderDecoder.readShortSaleParams();
        eOrderDecoder.readBoxOrderParams();
        eOrderDecoder.readPegToStkOrVolOrderParams();
        eOrderDecoder.readDisplaySize();
        eOrderDecoder.readSweepToFill();
        eOrderDecoder.readAllOrNone();
        eOrderDecoder.readMinQty();
        eOrderDecoder.readOcaType();
        eOrderDecoder.readTriggerMethod();
        eOrderDecoder.readVolOrderParams(false);
        eOrderDecoder.readTrailParams();
        eOrderDecoder.readComboLegs();
        eOrderDecoder.readSmartComboRoutingParams();
        eOrderDecoder.readScaleOrderParams();
        eOrderDecoder.readHedgeParams();
        eOrderDecoder.readClearingParams();
        eOrderDecoder.readNotHeld();
        eOrderDecoder.readDeltaNeutral();
        eOrderDecoder.readAlgoParams();
        eOrderDecoder.readSolicited();
        eOrderDecoder.readOrderStatus();
        eOrderDecoder.readVolRandomizeFlags();
        eOrderDecoder.readPegToBenchParams();
        eOrderDecoder.readConditions();
        eOrderDecoder.readStopPriceAndLmtPriceOffset();
        eOrderDecoder.readCashQty();
        eOrderDecoder.readDontUseAutoPriceForHedge();
        eOrderDecoder.readIsOmsContainer();
        eOrderDecoder.readAutoCancelDate();
        eOrderDecoder.readFilledQuantity();
        eOrderDecoder.readRefFuturesConId();
        eOrderDecoder.readAutoCancelParent();
        eOrderDecoder.readShareholder();
        eOrderDecoder.readImbalanceOnly();
        eOrderDecoder.readRouteMarketableToBbo();
        eOrderDecoder.readParentPermId();
        eOrderDecoder.readCompletedTime();
        eOrderDecoder.readCompletedStatus();
        eOrderDecoder.readPegBestPegMidOrderAttributes();
        eOrderDecoder.readCustomerAccount();
        eOrderDecoder.readProfessionalCustomer();
        eOrderDecoder.readSubmitter();

        m_EWrapper.completedOrder(contract, order, orderState);
    }

    private void processCompletedOrderMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        
        CompletedOrderProto.CompletedOrder completedOrderProto = CompletedOrderProto.CompletedOrder.parseFrom(byteArray);
        m_EWrapper.completedOrderProtoBuf(completedOrderProto);

        // set contract fields
        if (!completedOrderProto.hasContract()) {
            return;
        }
        Contract contract = EDecoderUtils.decodeContract(completedOrderProto.getContract());

        // set order fields
        if (!completedOrderProto.hasOrder()) {
            return;
        }
        Order order  = EDecoderUtils.decodeOrder(Integer.MAX_VALUE, completedOrderProto.getContract(), completedOrderProto.getOrder());
        
        // set order state fields
        if (!completedOrderProto.hasOrderState()) {
            return;
        }
        OrderState orderState  = EDecoderUtils.decodeOrderState(completedOrderProto.getOrderState());
        m_EWrapper.completedOrder(contract, order, orderState);
    }

    private void processCompletedOrdersEndMsg() throws IOException {
        m_EWrapper.completedOrdersEnd();
    }

    private void processCompletedOrdersEndMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        CompletedOrdersEndProto.CompletedOrdersEnd completedOrdersEndProto = CompletedOrdersEndProto.CompletedOrdersEnd.parseFrom(byteArray);
        m_EWrapper.completedOrdersEndProtoBuf(completedOrdersEndProto);
        m_EWrapper.completedOrdersEnd();
    }

    private void processReplaceFAEndMsg() throws IOException {
        int reqId = readInt();
        String text = readStr();

        m_EWrapper.replaceFAEnd(reqId, text);
    }
    
    private void processReplaceFAEndMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        ReplaceFAEndProto.ReplaceFAEnd replaceFAEndProto = ReplaceFAEndProto.ReplaceFAEnd.parseFrom(byteArray);

        m_EWrapper.replaceFAEndProtoBuf(replaceFAEndProto);

        int reqId = replaceFAEndProto.hasReqId() ? replaceFAEndProto.getReqId() : EClientErrors.NO_VALID_ID;
        String text = replaceFAEndProto.hasText() ? replaceFAEndProto.getText() : "";

        m_EWrapper.replaceFAEnd(reqId, text);
    }

    private void processWshMetaData() throws IOException {
    	int reqId = readInt();
    	String dataJson = readStr();
    	
    	m_EWrapper.wshMetaData(reqId, dataJson);
    }
    
    private void processWshMetaDataMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        WshMetaDataProto.WshMetaData wshMetaDataProto = WshMetaDataProto.WshMetaData.parseFrom(byteArray);
        m_EWrapper.wshMetaDataProtoBuf(wshMetaDataProto);

        int reqId = wshMetaDataProto.hasReqId() ? wshMetaDataProto.getReqId() : EClientErrors.NO_VALID_ID;
        String dataJson = wshMetaDataProto.hasDataJson() ? wshMetaDataProto.getDataJson() : "";

        m_EWrapper.wshMetaData(reqId, dataJson);
    }

    private void processWshEventData() throws IOException {
    	int reqId = readInt();    	
    	String dataJson = readStr();
    	
    	m_EWrapper.wshEventData(reqId, dataJson);
    }

    private void processWshEventDataMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();

        WshEventDataProto.WshEventData wshEventDataProto = WshEventDataProto.WshEventData.parseFrom(byteArray);
        m_EWrapper.wshEventDataProtoBuf(wshEventDataProto);

        int reqId = wshEventDataProto.hasReqId() ? wshEventDataProto.getReqId() : EClientErrors.NO_VALID_ID;
        String dataJson = wshEventDataProto.hasDataJson() ? wshEventDataProto.getDataJson() : "";

        m_EWrapper.wshEventData(reqId, dataJson);
    }

    private void processHistoricalSchedule() throws IOException {
        int reqId = readInt();
        String startDateTime = readStr();
        String endDateTime = readStr();
        String timeZone = readStr();

        int sessionsCount = readInt();
        List<HistoricalSession> sessions = new ArrayList<>();
        for (int i = 0; i < sessionsCount; i++) {
            String sessionStartDateTime = readStr();
            String sessionEndDateTime = readStr();
            String sessionRefDate = readStr();
            sessions.add(new HistoricalSession(sessionStartDateTime, sessionEndDateTime, sessionRefDate));
        }

        m_EWrapper.historicalSchedule(reqId, startDateTime, endDateTime, timeZone, sessions);
    }

    private void processHistoricalScheduleMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        HistoricalScheduleProto.HistoricalSchedule historicalScheduleProto = HistoricalScheduleProto.HistoricalSchedule.parseFrom(byteArray);

        m_EWrapper.historicalScheduleProtoBuf(historicalScheduleProto);

        int reqId = historicalScheduleProto.hasReqId() ? historicalScheduleProto.getReqId() : EClientErrors.NO_VALID_ID;
        String startDateTime = historicalScheduleProto.hasStartDateTime() ? historicalScheduleProto.getStartDateTime() : "";
        String endDateTime = historicalScheduleProto.hasEndDateTime() ? historicalScheduleProto.getEndDateTime() : "";
        String timeZone = historicalScheduleProto.hasTimeZone() ? historicalScheduleProto.getTimeZone() : "";

        List<HistoricalSession> historicalSessions = new ArrayList<>();
        if (historicalScheduleProto.getHistoricalSessionsCount() > 0) {
            for (HistoricalSessionProto.HistoricalSession historicalSessionProto : historicalScheduleProto.getHistoricalSessionsList()) {
                String sessionStartDateTime = historicalSessionProto.hasStartDateTime() ? historicalSessionProto.getStartDateTime() : "";
                String sessionEndDateTime = historicalSessionProto.hasEndDateTime() ? historicalSessionProto.getEndDateTime() : "";
                String sessionRefDate = historicalSessionProto.hasRefDate() ? historicalSessionProto.getRefDate() : "";
                historicalSessions.add(new HistoricalSession(sessionStartDateTime, sessionEndDateTime, sessionRefDate));
            }
        }

        m_EWrapper.historicalSchedule(reqId, startDateTime, endDateTime, timeZone, historicalSessions);
    }

    private void processUserInfo() throws IOException {
        int reqId = readInt();
        String whiteBrandingId = readStr();

        m_EWrapper.userInfo(reqId, whiteBrandingId);
    }

    private void processUserInfoMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        UserInfoProto.UserInfo userInfoProto = UserInfoProto.UserInfo.parseFrom(byteArray);

        m_EWrapper.userInfoProtoBuf(userInfoProto);

        int reqId = userInfoProto.hasReqId() ? userInfoProto.getReqId() : EClientErrors.NO_VALID_ID;
        String whiteBrandingId = userInfoProto.hasWhiteBrandingId() ? userInfoProto.getWhiteBrandingId() : "";

        m_EWrapper.userInfo(reqId, whiteBrandingId);
    }

    private void processCurrentTimeInMillisMsg() throws IOException {
        long timeInMillis = readLong();
        m_EWrapper.currentTimeInMillis(timeInMillis);
    }

    private void processCurrentTimeInMillisMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        CurrentTimeInMillisProto.CurrentTimeInMillis currentTimeInMillisProto = CurrentTimeInMillisProto.CurrentTimeInMillis.parseFrom(byteArray);

        m_EWrapper.currentTimeInMillisProtoBuf(currentTimeInMillisProto);

        long currentTimeInMillis = currentTimeInMillisProto.hasCurrentTimeInMillis() ? currentTimeInMillisProto.getCurrentTimeInMillis() : 0;
        
        m_EWrapper.currentTimeInMillis(currentTimeInMillis);
    }

    private void processConfigMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        ConfigResponseProto.ConfigResponse configResponseProto = ConfigResponseProto.ConfigResponse.parseFrom(byteArray);

        m_EWrapper.configResponseProtoBuf(configResponseProto);
    }

    private void processUpdateConfigMsgProtoBuf() throws IOException {
        byte[] byteArray = readByteArray();
        UpdateConfigResponseProto.UpdateConfigResponse updateConfigResponseProto = UpdateConfigResponseProto.UpdateConfigResponse.parseFrom(byteArray);

        m_EWrapper.updateConfigResponseProtoBuf(updateConfigResponseProto);
    }

    private void readLastTradeDate(ContractDetails contract, boolean isBond) throws IOException {
        String lastTradeDateOrContractMonth = readStr();
        EDecoderUtils.setLastTradeDate(lastTradeDateOrContractMonth, contract, isBond);
    }

    public String readStr() throws IOException {
    	return m_messageReader.readStr();
    }

    public boolean readBoolFromInt() throws IOException {
        String str = readStr();
        return str != null && (Integer.parseInt(str) != 0);
    }

    public int readInt() throws IOException {
        String str = readStr();
        return str == null ? 0 : Integer.parseInt( str);
    }

    public int readRawInt() throws IOException {
        return m_messageReader.readInt();
    }

    public byte[] readByteArray() throws IOException {
        return m_messageReader.readByteArray();
    }
    
    public int readIntMax() throws IOException {
        String str = readStr();
        return (str == null || str.length() == 0) ? Integer.MAX_VALUE
        	                                      : Integer.parseInt( str);
    }

    public long readLong() throws IOException {
        String str = readStr();
        return str == null ? 0L : Long.parseLong(str);
    }

    public double readDouble() throws IOException {
        String str = readStr();
        return str == null ? 0 : Double.parseDouble( str);
    }

    public double readDoubleMax() throws IOException {
        String str = readStr();
        return (str == null || str.length() == 0) ? Double.MAX_VALUE
        	                                      : Double.parseDouble( str);
    }

    public Decimal readDecimal() throws IOException {
        String str = readStr();
        return Util.stringToDecimal(str);
    }
    
    /** Message reader interface */
    private interface IMessageReader extends Closeable {
    	String readStr() throws IOException;
    	int msgLength();
        int readInt() throws IOException;
        byte[] readByteArray() throws IOException;
    }

    private static class PreV100MessageReader implements IMessageReader {
    	private final InputStream m_din;
    	private int m_msgLength = 0;
    	
    	PreV100MessageReader( InputStream din ) {
    		m_din = din;
    	}
    	
    	@Override
        public int msgLength() {
    		return m_msgLength;
    	}
    	
    	@Override public String readStr() throws IOException {
    		 StringBuilder sb = new StringBuilder();
    		    		 
 	         for(; true; m_msgLength++) {
 	            int c = m_din.read();
 	            if( c <= 0) {
 	            	if ( c < 0 ) {
 	            		throw new EOFException();
 	            	}
 	            	
 	            	m_msgLength++;
 	                break;
 	            }
 	            sb.append( (char)c);
 	        }
 	
 	        String str = sb.toString();
 	        return str.length() == 0 ? null : str;    
 	    }

        @Override public int readInt() throws IOException {
            byte[] byteArray = new byte[4];
            byteArray[0] = (byte)m_din.read();
            if (byteArray[0] == -1) {
                throw new EOFException();
            }
            byteArray[1] = (byte)m_din.read();
            byteArray[2] = (byte)m_din.read();
            byteArray[3] = (byte)m_din.read();
            m_msgLength += 4;
            return Builder.bytesToInt(byteArray, 0);
        }

        @Override public byte[] readByteArray() throws IOException {
            m_msgLength += m_din.available();
            byte[] byteArray = m_din.readAllBytes();
            return byteArray;
        }
    	
    	@Override public void close() {
    	    /* noop in pre-v100 */
    	}
    }
    
    static String decodeUnicodeEscapedString(String str) {    
        
        if (str == null) {
            return str;
        }
        
        String v = new String(str);
        
        try {
            for (;;) {
                int escapeIndex = v.indexOf("\\u");

                if (escapeIndex == -1
                 || v.length() - escapeIndex < 6) {
                    break;
                }

                String escapeString = v.substring(escapeIndex ,  escapeIndex + 6);
                int hexVal = Integer.parseInt(escapeString.replace("\\u", ""), 16);

                v = v.replace(escapeString, "" + (char)hexVal);
            }
        } catch (NumberFormatException e) { }
                
        return v;
    }

	@Override
	public int skipBytes(int arg0) throws IOException { throw new UnsupportedOperationException(); }
	
	@Override
	public int readUnsignedShort() throws IOException { throw new UnsupportedOperationException(); }
	
	@Override
	public int readUnsignedByte() throws IOException { throw new UnsupportedOperationException(); }
	
	@Override
	public String readUTF() throws IOException { return readStr(); }
	
	@Override
	public short readShort() throws IOException { throw new UnsupportedOperationException(); }
		
	@Override
	public String readLine() throws IOException { return readStr(); }
		
	@Override
	public void readFully(byte[] arg0, int arg1, int arg2) throws IOException { throw new UnsupportedOperationException(); }
	
	@Override
	public void readFully(byte[] arg0) throws IOException { throw new UnsupportedOperationException(); }
	
	@Override
	public float readFloat() throws IOException { throw new UnsupportedOperationException(); }
	
	@Override
	public char readChar() throws IOException { return readStr().charAt(0); }
	
	@Override
	public byte readByte() throws IOException { throw new UnsupportedOperationException(); }
	
	@Override
	public boolean readBoolean() throws IOException { return readBoolFromInt(); }
	
	@Override
	public long skip(long arg0) throws IOException { throw new UnsupportedOperationException(); }
	
	@Override
	public Object readObject() throws ClassNotFoundException, IOException { throw new UnsupportedOperationException(); }
	
	@Override
	public int read(byte[] arg0, int arg1, int arg2) throws IOException { throw new UnsupportedOperationException(); }
	
	@Override
	public int read(byte[] arg0) throws IOException { throw new UnsupportedOperationException(); }
	
	@Override
	public int read() throws IOException { throw new UnsupportedOperationException(); }
	
	@Override
	public void close() throws IOException { m_messageReader.close(); }
	
	@Override
	public int available() throws IOException { throw new UnsupportedOperationException(); }

}
