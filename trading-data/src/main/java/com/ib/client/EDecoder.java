/*
 * Java TWS API Client
 *
 * Copyright (C) 2013-2026  Interactive Brokers LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

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
public class EDecoder {

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
    	
        int rawMsgId = m_serverVersion >= EClient.MIN_SERVER_VER_PROTOBUF ? readRawInt() : readInt();
        if (rawMsgId > EClient.PROTOBUF_MSG_ID) {
            rawMsgId -= EClient.PROTOBUF_MSG_ID;
        }
        IncomingMsgId msgId = IncomingMsgId.fromId(rawMsgId);
        if (msgId == null) {
            m_EWrapper.error(EClientErrors.NO_VALID_ID, Util.currentTimeMillis(), EClientErrors.UNKNOWN_ID.code(),
                    EClientErrors.UNKNOWN_ID.msg() + rawMsgId, null);
            return 0;
        }

        switch (msgId) {
                case END_CONN -> { return 0; }
                case TICK_PRICE -> processTickPriceMsg();
                case TICK_SIZE -> processTickSizeMsg();
                case POSITION -> processPositionMsg();
                case POSITION_END -> processPositionEndMsg();
                case ACCOUNT_SUMMARY -> processAccountSummaryMsg();
                case ACCOUNT_SUMMARY_END -> processAccountSummaryEndMsg();
                case TICK_OPTION_COMPUTATION -> processTickOptionComputationMsg();
                case TICK_GENERIC -> processTickGenericMsg();
                case TICK_STRING -> processTickStringMsg();
                case TICK_EFP -> processTickEFPMsg();
                case ORDER_STATUS -> processOrderStatusMsg();
                case ACCT_VALUE -> processAcctValueMsg();
                case PORTFOLIO_VALUE -> processPortfolioValueMsg();
                case ACCT_UPDATE_TIME -> processAcctUpdateTimeMsg();
                case ERR_MSG -> processErrorMsg();
                case OPEN_ORDER -> processOpenOrderMsg();
                case NEXT_VALID_ID -> processNextValidIdMsg();
                case SCANNER_DATA -> processScannerDataMsg();
                case CONTRACT_DATA -> processContractDataMsg();
                case BOND_CONTRACT_DATA -> processBondContractDataMsg();
                case EXECUTION_DATA -> processExecutionDataMsg();
                case MARKET_DEPTH -> processMarketDepthMsg();
                case MARKET_DEPTH_L2 -> processMarketDepthL2Msg();
                case NEWS_BULLETINS -> processNewsBulletinsMsg();
                case MANAGED_ACCTS -> processManagedAcctsMsg();
                case RECEIVE_FA -> processReceiveFaMsg();
                case HISTORICAL_DATA -> processHistoricalDataMsg();
                case SCANNER_PARAMETERS -> processScannerParametersMsg();
                case CURRENT_TIME -> processCurrentTimeMsg();
                case REAL_TIME_BARS -> processRealTimeBarsMsg();
                case CONTRACT_DATA_END -> processContractDataEndMsg();
                case OPEN_ORDER_END -> processOpenOrderEndMsg();
                case ACCT_DOWNLOAD_END -> processAcctDownloadEndMsg();
                case EXECUTION_DATA_END -> processExecutionDataEndMsg();
                case DELTA_NEUTRAL_VALIDATION -> processDeltaNeutralValidationMsg();
                case TICK_SNAPSHOT_END -> processTickSnapshotEndMsg();
                case MARKET_DATA_TYPE -> processMarketDataTypeMsg();
                case COMMISSION_AND_FEES_REPORT -> processCommissionAndFeesReportMsg();
                case VERIFY_MESSAGE_API -> processVerifyMessageApiMsg();
                case VERIFY_COMPLETED -> processVerifyCompletedMsg();
                case DISPLAY_GROUP_LIST -> processDisplayGroupListMsg();
                case DISPLAY_GROUP_UPDATED -> processDisplayGroupUpdatedMsg();
                case VERIFY_AND_AUTH_MESSAGE_API -> processVerifyAndAuthMessageMsg();
                case VERIFY_AND_AUTH_COMPLETED -> processVerifyAndAuthCompletedMsg();
                case POSITION_MULTI -> processPositionMultiMsg();
                case POSITION_MULTI_END -> processPositionMultiEndMsg();
                case ACCOUNT_UPDATE_MULTI -> processAccountUpdateMultiMsg();
                case ACCOUNT_UPDATE_MULTI_END -> processAccountUpdateMultiEndMsg();
                case SECURITY_DEFINITION_OPTION_PARAMETER -> processSecurityDefinitionOptionalParameterMsg();
                case SECURITY_DEFINITION_OPTION_PARAMETER_END -> processSecurityDefinitionOptionalParameterEndMsg();
                case SOFT_DOLLAR_TIERS -> processSoftDollarTiersMsg();
                case FAMILY_CODES -> processFamilyCodesMsg();
                case SMART_COMPONENTS -> processSmartComponentsMsg();
                case TICK_REQ_PARAMS -> processTickReqParamsMsg();
                case SYMBOL_SAMPLES -> processSymbolSamplesMsg();
                case MKT_DEPTH_EXCHANGES -> processMktDepthExchangesMsg();
                case HEAD_TIMESTAMP -> processHeadTimestampMsg();
                case TICK_NEWS -> processTickNewsMsg();
                case NEWS_PROVIDERS -> processNewsProvidersMsg();
                case NEWS_ARTICLE -> processNewsArticleMsg();
                case HISTORICAL_NEWS -> processHistoricalNewsMsg();
                case HISTORICAL_NEWS_END -> processHistoricalNewsEndMsg();
                case HISTOGRAM_DATA -> processHistogramDataMsg();
                case HISTORICAL_DATA_UPDATE -> processHistoricalDataUpdateMsg();
                case REROUTE_MKT_DATA_REQ -> processRerouteMktDataReq();
                case REROUTE_MKT_DEPTH_REQ -> processRerouteMktDepthReq();
                case MARKET_RULE -> processMarketRuleMsg();
                case PNL -> processPnLMsg();
                case PNL_SINGLE -> processPnLSingleMsg();
                case HISTORICAL_TICKS -> processHistoricalTicks();
                case HISTORICAL_TICKS_BID_ASK -> processHistoricalTicksBidAsk();
                case HISTORICAL_TICKS_LAST -> processHistoricalTicksLast();
                case TICK_BY_TICK -> processTickByTickMsg();
                case ORDER_BOUND -> processOrderBoundMsg();
                case COMPLETED_ORDER -> processCompletedOrderMsg();
                case COMPLETED_ORDERS_END -> processCompletedOrdersEndMsg();
                case REPLACE_FA_END -> processReplaceFAEndMsg();
                case WSH_META_DATA -> processWshMetaData();
                case WSH_EVENT_DATA -> processWshEventData();
                case HISTORICAL_SCHEDULE -> processHistoricalSchedule();
                case USER_INFO -> processUserInfo();
                case HISTORICAL_DATA_END -> processHistoricalDataEndMsg();
                case CURRENT_TIME_IN_MILLIS -> processCurrentTimeInMillisMsg();
                default -> {
                    m_EWrapper.error(EClientErrors.NO_VALID_ID, Util.currentTimeMillis(), EClientErrors.UNKNOWN_ID.code(), EClientErrors.UNKNOWN_ID.msg(), null);
                    return 0;
                }
        return 1;
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



    private void processRerouteMktDepthReq() throws IOException {
        int reqId = readInt();
        int conId = readInt();
        String exchange = readStr();

        m_EWrapper.rerouteMktDepthReq(reqId, conId, exchange);
    }


    private void processRerouteMktDataReq() throws IOException {
        int reqId = readInt();
        int conId = readInt();
        String exchange = readStr();

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



    private void processHistogramDataMsg() throws IOException {
    	int reqId = readInt();
    	int n = readInt();
    	List<HistogramEntry> items = new ArrayList<>(n);
    	
    	for (int i = 0; i < n; i++) {
    		items.add(new HistogramEntry(readDouble(), readDecimal()));
    	}
    	
    	m_EWrapper.histogramData(reqId, items);
	}
    


	private void processHistoricalNewsEndMsg() throws IOException {
        int requestId = readInt();
        boolean hasMore = readBoolFromInt();

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


    private void processNewsArticleMsg() throws IOException {
        int requestId = readInt();
        int articleType = readInt();
        String articleText = readStr();

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



    private void processTickNewsMsg() throws IOException {
        int tickerId = readInt();
        long timeStamp = readLong();
        String providerCode = readStr();
        String articleId = readStr();
        String headline = readStr();
        String extraData = readStr();

        m_EWrapper.tickNews(tickerId, timeStamp, providerCode, articleId, headline, extraData);
    }


    private void processHeadTimestampMsg() throws IOException {
		int reqId = readInt();
		String headTimestamp = readStr();
		
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



	private void processSoftDollarTiersMsg() throws IOException {
		int reqId = readInt();
		int nTiers = readInt();
		SoftDollarTier[] tiers = new SoftDollarTier[nTiers]; 
		
		for (int i = 0; i < nTiers; i++) {
			tiers[i] = new SoftDollarTier(readStr(), readStr(), readStr()); 
		}
		
		m_EWrapper.softDollarTiers(reqId, tiers);
	}



	private void processSecurityDefinitionOptionalParameterEndMsg() throws IOException {
		int reqId = readInt();
		
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


	private void processDisplayGroupListMsg() throws IOException {
		/*int version =*/ readInt();
		int reqId = readInt();
		String groups = readStr();

		m_EWrapper.displayGroupList(reqId, groups);
	}


	private void processVerifyCompletedMsg() throws IOException {
		/*int version =*/ readInt();
		String isSuccessfulStr = readStr();
		boolean isSuccessful = "true".equals(isSuccessfulStr);
		String errorText = readStr();

		m_EWrapper.verifyCompleted(isSuccessful, errorText);
	}


	private void processVerifyMessageApiMsg() throws IOException {
		/*int version =*/ readInt();
		String apiData = readStr();

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


	private void processMarketDataTypeMsg() throws IOException {
		/*int version =*/ readInt();
		int reqId = readInt();
		int marketDataType = readInt();

		m_EWrapper.marketDataType( reqId, marketDataType);
	}
	

	private void processTickSnapshotEndMsg() throws IOException {
		/*int version =*/ readInt();
		int reqId = readInt();

		m_EWrapper.tickSnapshotEnd( reqId);
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


	private void processAcctDownloadEndMsg() throws IOException {
		/*int version =*/ readInt();
		String accountName = readStr();
		m_EWrapper.accountDownloadEnd( accountName);
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
	

	private void processCurrentTimeMsg() throws IOException {
		/*int version =*/ readInt();
		long time = readLong();
		m_EWrapper.currentTime(time);
	}


	private void processScannerParametersMsg() throws IOException {
		/*int version =*/ readInt();
		String xml = readStr();
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
	

    private void processHistoricalDataEndMsg() throws IOException {
        int reqId = readInt();
        String startDateStr = readStr();
        String endDateStr = readStr();
        m_EWrapper.historicalDataEnd(reqId, startDateStr, endDateStr);
    }


	private void processReceiveFaMsg() throws IOException {
	    /*int version =*/ readInt();
	    int faDataType = readInt();
	    String xml = readStr();

	    m_EWrapper.receiveFA(faDataType, xml);
	}
	

	private void processManagedAcctsMsg() throws IOException {
		/*int version =*/ readInt();
		String accountsList = readStr();

		m_EWrapper.managedAccounts( accountsList);
	}


	private void processNewsBulletinsMsg() throws IOException {
		/*int version =*/ readInt();
		int newsMsgId = readInt();
		int newsMsgType = readInt();
		String newsMessage = readStr();
		String originatingExch = readStr();

		m_EWrapper.updateNewsBulletin( newsMsgId, newsMsgType, newsMessage, originatingExch);
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


    }


        // set execution fields
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
	
        // set contract details fields
        
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

        // set contract details fields
        
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





	private void processNextValidIdMsg() throws IOException {
		/*int version =*/ readInt();
		int orderId = readInt();
		m_EWrapper.nextValidId( orderId);
	}



        // set order fields
        }
        
        // set order state fields
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


	private void processTickGenericMsg() throws IOException {
		/*int version =*/ readInt();
		int tickerId = readInt();
		int tickType = readInt();
		double value = readDouble();

		m_EWrapper.tickGeneric( tickerId, tickType, value);
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



	private void processAccountSummaryEndMsg() throws IOException {
		/*int version =*/ readInt();
		int reqId = readInt();
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


	private void processPositionEndMsg() throws IOException {
		/*int version =*/ readInt();
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




	private void processTickSizeMsg() throws IOException {
		/*int version =*/ readInt();
		int tickerId = readInt();
		int tickType = readInt();
		Decimal size = readDecimal();

		m_EWrapper.tickSize( tickerId, tickType, size);
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




    private void processPositionMultiEndMsg() throws IOException {
        /*int version =*/ readInt();
        int reqId = readInt();

        m_EWrapper.positionMultiEnd( reqId);
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


    private void processAccountUpdateMultiEndMsg() throws IOException {
        /*int version =*/ readInt();
        int reqId = readInt();

        m_EWrapper.accountUpdateMultiEnd( reqId);
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


    private void processTickReqParamsMsg() throws IOException {
    	int tickerId = readInt();
    	double minTick = readDouble();
    	String bboExchange = readStr();
    	int snapshotPermissions = readInt();
    	
    	m_EWrapper.tickReqParams(tickerId, minTick, bboExchange, snapshotPermissions);
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




    private void processOrderBoundMsg() throws IOException {
        long permId = readLong();
        int clientId = readInt();
        int orderId = readInt();
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


        // set order fields
        }
        
        // set order state fields
        }

    private void processCompletedOrdersEndMsg() throws IOException {
        m_EWrapper.completedOrdersEnd();
    }


    private void processReplaceFAEndMsg() throws IOException {
        int reqId = readInt();
        String text = readStr();

        m_EWrapper.replaceFAEnd(reqId, text);
    }
    

    private void processWshMetaData() throws IOException {
    	int reqId = readInt();
    	String dataJson = readStr();
    	
    	m_EWrapper.wshMetaData(reqId, dataJson);
    }
    

    private void processWshEventData() throws IOException {
    	int reqId = readInt();    	
    	String dataJson = readStr();
    	
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



    private void processUserInfo() throws IOException {
        int reqId = readInt();
        String whiteBrandingId = readStr();

        m_EWrapper.userInfo(reqId, whiteBrandingId);
    }


    private void processCurrentTimeInMillisMsg() throws IOException {
        long timeInMillis = readLong();
        m_EWrapper.currentTimeInMillis(timeInMillis);
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
    
        
        }
        
        



                

	public int skipBytes(int arg0) throws IOException { throw new UnsupportedOperationException(); }
	
	public int readUnsignedShort() throws IOException { throw new UnsupportedOperationException(); }
	
	public int readUnsignedByte() throws IOException { throw new UnsupportedOperationException(); }
	
	public String readUTF() throws IOException { return readStr(); }
	
	public short readShort() throws IOException { throw new UnsupportedOperationException(); }
		
	public String readLine() throws IOException { return readStr(); }
		
	public void readFully(byte[] arg0, int arg1, int arg2) throws IOException { throw new UnsupportedOperationException(); }
	
	public void readFully(byte[] arg0) throws IOException { throw new UnsupportedOperationException(); }
	
	public float readFloat() throws IOException { throw new UnsupportedOperationException(); }
	
	public char readChar() throws IOException { return readStr().charAt(0); }
	
	public byte readByte() throws IOException { throw new UnsupportedOperationException(); }
	
	public boolean readBoolean() throws IOException { return readBoolFromInt(); }
	
	public long skip(long arg0) throws IOException { throw new UnsupportedOperationException(); }
	
	public Object readObject() throws ClassNotFoundException, IOException { throw new UnsupportedOperationException(); }
	
	public int read(byte[] arg0, int arg1, int arg2) throws IOException { throw new UnsupportedOperationException(); }
	
	public int read(byte[] arg0) throws IOException { throw new UnsupportedOperationException(); }
	
	public int read() throws IOException { throw new UnsupportedOperationException(); }
	
	public void close() throws IOException { m_messageReader.close(); }
	
	public int available() throws IOException { throw new UnsupportedOperationException(); }

}
