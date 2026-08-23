/* Copyright (C) 2025 Interactive Brokers LLC. All rights reserved. This code is subject to the terms
 * and conditions of the IB API Non-Commercial License or the IB API Commercial License, as applicable. */

package com.ib.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.google.protobuf.InvalidProtocolBufferException;
import com.ib.client.protobuf.AccountDataRequestProto;
import com.ib.client.protobuf.AccountSummaryRequestProto;
import com.ib.client.protobuf.AccountUpdatesMultiRequestProto;
import com.ib.client.protobuf.AllOpenOrdersRequestProto;
import com.ib.client.protobuf.AttachedOrdersProto;
import com.ib.client.protobuf.AutoOpenOrdersRequestProto;
import com.ib.client.protobuf.CalculateImpliedVolatilityRequestProto;
import com.ib.client.protobuf.CalculateOptionPriceRequestProto;
import com.ib.client.protobuf.CancelAccountSummaryProto;
import com.ib.client.protobuf.CancelAccountUpdatesMultiProto;
import com.ib.client.protobuf.CancelCalculateImpliedVolatilityProto;
import com.ib.client.protobuf.CancelCalculateOptionPriceProto;
import com.ib.client.protobuf.CancelContractDataProto;
import com.ib.client.protobuf.CancelFundamentalsDataProto;
import com.ib.client.protobuf.CancelHeadTimestampProto;
import com.ib.client.protobuf.CancelHistogramDataProto;
import com.ib.client.protobuf.CancelHistoricalDataProto;
import com.ib.client.protobuf.CancelHistoricalTicksProto;
import com.ib.client.protobuf.CancelMarketDataProto;
import com.ib.client.protobuf.CancelMarketDepthProto;
import com.ib.client.protobuf.CancelNewsBulletinsProto;
import com.ib.client.protobuf.CancelOrderRequestProto;
import com.ib.client.protobuf.CancelPnLProto;
import com.ib.client.protobuf.CancelPnLSingleProto;
import com.ib.client.protobuf.CancelPositionsMultiProto;
import com.ib.client.protobuf.CancelPositionsProto;
import com.ib.client.protobuf.CancelRealTimeBarsProto;
import com.ib.client.protobuf.CancelScannerSubscriptionProto;
import com.ib.client.protobuf.CancelTickByTickProto;
import com.ib.client.protobuf.CancelWshEventDataProto;
import com.ib.client.protobuf.CancelWshMetaDataProto;
import com.ib.client.protobuf.ComboLegProto;
import com.ib.client.protobuf.CompletedOrdersRequestProto;
import com.ib.client.protobuf.ContractDataRequestProto;
import com.ib.client.protobuf.ContractProto;
import com.ib.client.protobuf.CurrentTimeInMillisRequestProto;
import com.ib.client.protobuf.CurrentTimeRequestProto;
import com.ib.client.protobuf.DeltaNeutralContractProto;
import com.ib.client.protobuf.ExecutionFilterProto;
import com.ib.client.protobuf.ExecutionRequestProto;
import com.ib.client.protobuf.ExerciseOptionsRequestProto;
import com.ib.client.protobuf.FAReplaceProto;
import com.ib.client.protobuf.FARequestProto;
import com.ib.client.protobuf.FamilyCodesRequestProto;
import com.ib.client.protobuf.FundamentalsDataRequestProto;
import com.ib.client.protobuf.GlobalCancelRequestProto;
import com.ib.client.protobuf.HeadTimestampRequestProto;
import com.ib.client.protobuf.HistogramDataRequestProto;
import com.ib.client.protobuf.HistoricalDataRequestProto;
import com.ib.client.protobuf.HistoricalNewsRequestProto;
import com.ib.client.protobuf.HistoricalTicksRequestProto;
import com.ib.client.protobuf.IdsRequestProto;
import com.ib.client.protobuf.ManagedAccountsRequestProto;
import com.ib.client.protobuf.MarketDataRequestProto;
import com.ib.client.protobuf.MarketDataTypeRequestProto;
import com.ib.client.protobuf.MarketDepthExchangesRequestProto;
import com.ib.client.protobuf.MarketDepthRequestProto;
import com.ib.client.protobuf.MarketRuleRequestProto;
import com.ib.client.protobuf.MatchingSymbolsRequestProto;
import com.ib.client.protobuf.NewsArticleRequestProto;
import com.ib.client.protobuf.NewsBulletinsRequestProto;
import com.ib.client.protobuf.NewsProvidersRequestProto;
import com.ib.client.protobuf.OpenOrdersRequestProto;
import com.ib.client.protobuf.OrderCancelProto;
import com.ib.client.protobuf.OrderConditionProto;
import com.ib.client.protobuf.OrderProto;
import com.ib.client.protobuf.PlaceOrderRequestProto;
import com.ib.client.protobuf.PnLRequestProto;
import com.ib.client.protobuf.PnLSingleRequestProto;
import com.ib.client.protobuf.PositionsMultiRequestProto;
import com.ib.client.protobuf.PositionsRequestProto;
import com.ib.client.protobuf.QueryDisplayGroupsRequestProto;
import com.ib.client.protobuf.RealTimeBarsRequestProto;
import com.ib.client.protobuf.ScannerParametersRequestProto;
import com.ib.client.protobuf.ScannerSubscriptionProto;
import com.ib.client.protobuf.ScannerSubscriptionRequestProto;
import com.ib.client.protobuf.SecDefOptParamsRequestProto;
import com.ib.client.protobuf.SetServerLogLevelRequestProto;
import com.ib.client.protobuf.SmartComponentsRequestProto;
import com.ib.client.protobuf.SoftDollarTierProto;
import com.ib.client.protobuf.SoftDollarTiersRequestProto;
import com.ib.client.protobuf.StartApiRequestProto;
import com.ib.client.protobuf.SubscribeToGroupEventsRequestProto;
import com.ib.client.protobuf.TickByTickRequestProto;
import com.ib.client.protobuf.UnsubscribeFromGroupEventsRequestProto;
import com.ib.client.protobuf.UpdateDisplayGroupRequestProto;
import com.ib.client.protobuf.UserInfoRequestProto;
import com.ib.client.protobuf.VerifyMessageRequestProto;
import com.ib.client.protobuf.VerifyRequestProto;
import com.ib.client.protobuf.WshEventDataRequestProto;
import com.ib.client.protobuf.WshMetaDataRequestProto;

public class EClientUtils {

    public static ExecutionRequestProto.ExecutionRequest createExecutionRequestProto(int reqId, ExecutionFilter filter) {
        ExecutionFilterProto.ExecutionFilter.Builder executionFilterBuilder = ExecutionFilterProto.ExecutionFilter.newBuilder();
        if (Util.isValidValue(filter.clientId())) executionFilterBuilder.setClientId(filter.clientId());
        if (!Util.StringIsEmpty(filter.acctCode())) executionFilterBuilder.setAcctCode(filter.acctCode());
        if (!Util.StringIsEmpty(filter.time())) executionFilterBuilder.setTime(filter.time());
        if (!Util.StringIsEmpty(filter.symbol())) executionFilterBuilder.setSymbol(filter.symbol());
        if (!Util.StringIsEmpty(filter.secType())) executionFilterBuilder.setSecType(filter.secType());
        if (!Util.StringIsEmpty(filter.exchange())) executionFilterBuilder.setExchange(filter.exchange());
        if (!Util.StringIsEmpty(filter.side())) executionFilterBuilder.setSide(filter.side());
        if (Util.isValidValue(filter.lastNDays())) executionFilterBuilder.setLastNDays(filter.lastNDays());
        if (filter.specificDates() != null) filter.specificDates().stream().forEach(specificDate -> executionFilterBuilder.addSpecificDates(specificDate));
        ExecutionRequestProto.ExecutionRequest.Builder executionRequestBuilder = ExecutionRequestProto.ExecutionRequest.newBuilder();
        if (Util.isValidValue(reqId)) executionRequestBuilder.setReqId(reqId);
        executionRequestBuilder.setExecutionFilter(executionFilterBuilder.build());
        return executionRequestBuilder.build();
    }
    
    public static PlaceOrderRequestProto.PlaceOrderRequest createPlaceOrderRequestProto(int orderId, Contract contract, Order order) throws EClientException {
        PlaceOrderRequestProto.PlaceOrderRequest.Builder placeOrderRequestBuilder = PlaceOrderRequestProto.PlaceOrderRequest.newBuilder();

        if (Util.isValidValue(orderId)) placeOrderRequestBuilder.setOrderId(orderId);

        ContractProto.Contract contractProto = createContractProto(contract, order);
        if (contractProto != null) placeOrderRequestBuilder.setContract(contractProto);

        OrderProto.Order orderProto = createOrderProto(order);
        if (orderProto != null) placeOrderRequestBuilder.setOrder(orderProto);

        AttachedOrdersProto.AttachedOrders attachedOrdersProto = createAttachedOrdersProto(order);
        if (attachedOrdersProto != null) placeOrderRequestBuilder.setAttachedOrders(attachedOrdersProto);

        return placeOrderRequestBuilder.build();
    }

    public static AttachedOrdersProto.AttachedOrders createAttachedOrdersProto(Order order) {
        AttachedOrdersProto.AttachedOrders.Builder attachedOrdersProtoBuilder = AttachedOrdersProto.AttachedOrders.newBuilder();
        if (Util.isValidValue(order.slOrderId())) attachedOrdersProtoBuilder.setSlOrderId(order.slOrderId());
        if (!Util.StringIsEmpty(order.slOrderType())) attachedOrdersProtoBuilder.setSlOrderType(order.slOrderType());
        if (Util.isValidValue(order.ptOrderId())) attachedOrdersProtoBuilder.setPtOrderId(order.ptOrderId());
        if (!Util.StringIsEmpty(order.ptOrderType())) attachedOrdersProtoBuilder.setPtOrderType(order.ptOrderType());
        return attachedOrdersProtoBuilder.build();
    }

    public static OrderProto.Order createOrderProto(Order order) throws EClientException {
        OrderProto.Order.Builder orderBuilder = OrderProto.Order.newBuilder();
        if (Util.isValidValue(order.clientId())) orderBuilder.setClientId(order.clientId());
        if (Util.isValidValue(order.permId())) orderBuilder.setPermId(order.permId());
        if (Util.isValidValue(order.parentId())) orderBuilder.setParentId(order.parentId());
        if (!Util.StringIsEmpty(order.getAction())) orderBuilder.setAction(order.getAction());
        if (Util.isValidValue(order.totalQuantity())) orderBuilder.setTotalQuantity(order.totalQuantity().toString());
        if (Util.isValidValue(order.displaySize())) orderBuilder.setDisplaySize(order.displaySize());
        if (!Util.StringIsEmpty(order.getOrderType())) orderBuilder.setOrderType(order.getOrderType());
        if (Util.isValidValue(order.lmtPrice())) orderBuilder.setLmtPrice(order.lmtPrice());
        if (Util.isValidValue(order.auxPrice())) orderBuilder.setAuxPrice(order.auxPrice());
        if (!Util.StringIsEmpty(order.getTif())) orderBuilder.setTif(order.getTif());
        if (!Util.StringIsEmpty(order.account())) orderBuilder.setAccount(order.account());
        if (!Util.StringIsEmpty(order.settlingFirm())) orderBuilder.setSettlingFirm(order.settlingFirm());
        if (!Util.StringIsEmpty(order.clearingAccount())) orderBuilder.setClearingAccount(order.clearingAccount());
        if (!Util.StringIsEmpty(order.clearingIntent())) orderBuilder.setClearingIntent(order.clearingIntent());
        if (order.allOrNone()) orderBuilder.setAllOrNone(order.allOrNone());
        if (order.blockOrder()) orderBuilder.setBlockOrder(order.blockOrder());
        if (order.hidden()) orderBuilder.setHidden(order.hidden());
        if (order.outsideRth()) orderBuilder.setOutsideRth(order.outsideRth());
        if (order.sweepToFill()) orderBuilder.setSweepToFill(order.sweepToFill());
        if (Util.isValidValue(order.percentOffset())) orderBuilder.setPercentOffset(order.percentOffset());
        if (Util.isValidValue(order.trailingPercent())) orderBuilder.setTrailingPercent(order.trailingPercent());
        if (Util.isValidValue(order.trailStopPrice())) orderBuilder.setTrailStopPrice(order.trailStopPrice());
        if (Util.isValidValue(order.minQty())) orderBuilder.setMinQty(order.minQty());
        if (!Util.StringIsEmpty(order.goodAfterTime())) orderBuilder.setGoodAfterTime(order.goodAfterTime());
        if (!Util.StringIsEmpty(order.goodTillDate())) orderBuilder.setGoodTillDate(order.goodTillDate());
        if (!Util.StringIsEmpty(order.ocaGroup())) orderBuilder.setOcaGroup(order.ocaGroup());
        if (!Util.StringIsEmpty(order.orderRef())) orderBuilder.setOrderRef(order.orderRef());
        if (!Util.StringIsEmpty(order.getRule80A())) orderBuilder.setRule80A(order.getRule80A());
        if (Util.isValidValue(order.getOcaType())) orderBuilder.setOcaType(order.getOcaType());
        if (Util.isValidValue(order.getTriggerMethod())) orderBuilder.setTriggerMethod(order.getTriggerMethod());
        if (!Util.StringIsEmpty(order.activeStartTime())) orderBuilder.setActiveStartTime(order.activeStartTime());
        if (!Util.StringIsEmpty(order.activeStopTime())) orderBuilder.setActiveStopTime(order.activeStopTime());
        if (!Util.StringIsEmpty(order.faGroup())) orderBuilder.setFaGroup(order.faGroup());
        if (!Util.StringIsEmpty(order.getFaMethod())) orderBuilder.setFaMethod(order.getFaMethod());
        if (!Util.StringIsEmpty(order.faPercentage())) orderBuilder.setFaPercentage(order.faPercentage());
        if (Util.isValidValue(order.volatility()))  orderBuilder.setVolatility(order.volatility());
        if (Util.isValidValue(order.getVolatilityType())) orderBuilder.setVolatilityType(order.getVolatilityType());
        if (Util.isValidValue(order.continuousUpdate())) orderBuilder.setContinuousUpdate(order.continuousUpdate() == 1);
        if (Util.isValidValue(order.getReferencePriceType())) orderBuilder.setReferencePriceType(order.getReferencePriceType());
        if (!Util.StringIsEmpty(order.getDeltaNeutralOrderType())) orderBuilder.setDeltaNeutralOrderType(order.getDeltaNeutralOrderType());
        if (Util.isValidValue(order.deltaNeutralAuxPrice())) orderBuilder.setDeltaNeutralAuxPrice(order.deltaNeutralAuxPrice());
        if (Util.isValidValue(order.deltaNeutralConId())) orderBuilder.setDeltaNeutralConId(order.deltaNeutralConId());
        if (!Util.StringIsEmpty(order.deltaNeutralOpenClose())) orderBuilder.setDeltaNeutralOpenClose(order.deltaNeutralOpenClose());
        if (order.deltaNeutralShortSale()) orderBuilder.setDeltaNeutralShortSale(order.deltaNeutralShortSale());
        if (Util.isValidValue(order.deltaNeutralShortSaleSlot())) orderBuilder.setDeltaNeutralShortSaleSlot(order.deltaNeutralShortSaleSlot());
        if (!Util.StringIsEmpty(order.deltaNeutralDesignatedLocation())) orderBuilder.setDeltaNeutralDesignatedLocation(order.deltaNeutralDesignatedLocation());
        if (Util.isValidValue(order.scaleInitLevelSize())) orderBuilder.setScaleInitLevelSize(order.scaleInitLevelSize());
        if (Util.isValidValue(order.scaleSubsLevelSize())) orderBuilder.setScaleSubsLevelSize(order.scaleSubsLevelSize());
        if (Util.isValidValue(order.scalePriceIncrement())) orderBuilder.setScalePriceIncrement(order.scalePriceIncrement());
        if (Util.isValidValue(order.scalePriceAdjustValue())) orderBuilder.setScalePriceAdjustValue(order.scalePriceAdjustValue());
        if (Util.isValidValue(order.scalePriceAdjustInterval())) orderBuilder.setScalePriceAdjustInterval(order.scalePriceAdjustInterval());
        if (Util.isValidValue(order.scaleProfitOffset())) orderBuilder.setScaleProfitOffset(order.scaleProfitOffset());
        if (order.scaleAutoReset()) orderBuilder.setScaleAutoReset(order.scaleAutoReset());
        if (Util.isValidValue(order.scaleInitPosition())) orderBuilder.setScaleInitPosition(order.scaleInitPosition());
        if (Util.isValidValue(order.scaleInitFillQty())) orderBuilder.setScaleInitFillQty(order.scaleInitFillQty());
        if (order.scaleRandomPercent()) orderBuilder.setScaleRandomPercent(order.scaleRandomPercent());
        if (!Util.StringIsEmpty(order.scaleTable())) orderBuilder.setScaleTable(order.scaleTable());
        if (!Util.StringIsEmpty(order.getHedgeType())) orderBuilder.setHedgeType(order.getHedgeType());
        if (!Util.StringIsEmpty(order.hedgeParam())) orderBuilder.setHedgeParam(order.hedgeParam());
        if (Util.isValidValue(order.hedgeMaxSize())) orderBuilder.setHedgeMaxSize(order.hedgeMaxSize());

        if (!Util.StringIsEmpty(order.getAlgoStrategy())) orderBuilder.setAlgoStrategy(order.getAlgoStrategy());
        if (order.algoParams() != null && !order.algoParams().isEmpty()) {
            Map<String, String> algoParams = order.algoParams().stream().collect(Collectors.toMap(e -> e.m_tag, e -> e.m_value));
            orderBuilder.putAllAlgoParams(algoParams);
        }
        if (!Util.StringIsEmpty(order.algoId())) orderBuilder.setAlgoId(order.algoId());

        if (order.smartComboRoutingParams() != null && !order.smartComboRoutingParams().isEmpty()) {
            Map<String, String> smartComboRoutingParams = order.smartComboRoutingParams().stream().collect(Collectors.toMap(e -> e.m_tag, e -> e.m_value)); 
            orderBuilder.putAllSmartComboRoutingParams(smartComboRoutingParams);
        }

        if (order.whatIf()) orderBuilder.setWhatIf(order.whatIf());
        if (order.transmit()) orderBuilder.setTransmit(order.transmit());
        if (order.overridePercentageConstraints()) orderBuilder.setOverridePercentageConstraints(order.overridePercentageConstraints());
        if (!Util.StringIsEmpty(order.openClose())) orderBuilder.setOpenClose(order.openClose());
        if (Util.isValidValue(order.origin())) orderBuilder.setOrigin(order.origin());
        if (Util.isValidValue(order.shortSaleSlot())) orderBuilder.setShortSaleSlot(order.shortSaleSlot());
        if (!Util.StringIsEmpty(order.designatedLocation())) orderBuilder.setDesignatedLocation(order.designatedLocation());
        if (Util.isValidValue(order.exemptCode())) orderBuilder.setExemptCode(order.exemptCode());
        if (!Util.StringIsEmpty(order.deltaNeutralSettlingFirm())) orderBuilder.setDeltaNeutralSettlingFirm(order.deltaNeutralSettlingFirm());
        if (!Util.StringIsEmpty(order.deltaNeutralClearingAccount())) orderBuilder.setDeltaNeutralClearingAccount(order.deltaNeutralClearingAccount());
        if (!Util.StringIsEmpty(order.deltaNeutralClearingIntent())) orderBuilder.setDeltaNeutralClearingIntent(order.deltaNeutralClearingIntent());
        if (Util.isValidValue(order.discretionaryAmt())) orderBuilder.setDiscretionaryAmt(order.discretionaryAmt());
        if (order.optOutSmartRouting()) orderBuilder.setOptOutSmartRouting(order.optOutSmartRouting());
        if (Util.isValidValue(order.exemptCode())) orderBuilder.setExemptCode(order.exemptCode());
        if (Util.isValidValue(order.startingPrice())) orderBuilder.setStartingPrice(order.startingPrice());
        if (Util.isValidValue(order.stockRefPrice())) orderBuilder.setStockRefPrice(order.stockRefPrice());
        if (Util.isValidValue(order.delta())) orderBuilder.setDelta(order.delta());
        if (Util.isValidValue(order.stockRangeLower())) orderBuilder.setStockRangeLower(order.stockRangeLower());
        if (Util.isValidValue(order.stockRangeUpper())) orderBuilder.setStockRangeUpper(order.stockRangeUpper());
        if (order.notHeld()) orderBuilder.setNotHeld(order.notHeld());

        if (order.orderMiscOptions() != null && !order.orderMiscOptions().isEmpty()) {
            Map<String, String> orderMiscOptions = order.orderMiscOptions().stream().collect(Collectors.toMap(e -> e.m_tag, e -> e.m_value)); 
            orderBuilder.putAllOrderMiscOptions(orderMiscOptions);
        }

        if (order.solicited()) orderBuilder.setSolicited(order.solicited());
        if (order.randomizeSize()) orderBuilder.setRandomizeSize(order.randomizeSize());
        if (order.randomizePrice()) orderBuilder.setRandomizePrice(order.randomizePrice());
        if (Util.isValidValue(order.referenceContractId())) orderBuilder.setReferenceContractId(order.referenceContractId());
        if (Util.isValidValue(order.peggedChangeAmount())) orderBuilder.setPeggedChangeAmount(order.peggedChangeAmount());
        if (order.isPeggedChangeAmountDecrease()) orderBuilder.setIsPeggedChangeAmountDecrease(order.isPeggedChangeAmountDecrease());
        if (Util.isValidValue(order.referenceChangeAmount())) orderBuilder.setReferenceChangeAmount(order.referenceChangeAmount());
        if (!Util.StringIsEmpty(order.referenceExchangeId())) orderBuilder.setReferenceExchangeId(order.referenceExchangeId());
        if (order.adjustedOrderType() != null && !Util.StringIsEmpty(order.adjustedOrderType().getApiString())) orderBuilder.setAdjustedOrderType(order.adjustedOrderType().getApiString());
        if (Util.isValidValue(order.triggerPrice())) orderBuilder.setTriggerPrice(order.triggerPrice());
        if (Util.isValidValue(order.adjustedStopPrice())) orderBuilder.setAdjustedStopPrice(order.adjustedStopPrice());
        if (Util.isValidValue(order.adjustedStopLimitPrice())) orderBuilder.setAdjustedStopLimitPrice(order.adjustedStopLimitPrice());
        if (Util.isValidValue(order.adjustedTrailingAmount())) orderBuilder.setAdjustedTrailingAmount(order.adjustedTrailingAmount());
        if (Util.isValidValue(order.adjustableTrailingUnit())) orderBuilder.setAdjustableTrailingUnit(order.adjustableTrailingUnit());
        if (Util.isValidValue(order.lmtPriceOffset())) orderBuilder.setLmtPriceOffset(order.lmtPriceOffset());

        List<OrderConditionProto.OrderCondition> orderConditionList = createConditionsProto(order);
        if (!orderConditionList.isEmpty()) orderBuilder.addAllConditions(orderConditionList);
        if (order.conditionsCancelOrder()) orderBuilder.setConditionsCancelOrder(order.conditionsCancelOrder());
        if (order.conditionsIgnoreRth()) orderBuilder.setConditionsIgnoreRth(order.conditionsIgnoreRth());

        if (!Util.StringIsEmpty(order.modelCode())) orderBuilder.setModelCode(order.modelCode());
        if (!Util.StringIsEmpty(order.extOperator())) orderBuilder.setExtOperator(order.extOperator());

        SoftDollarTierProto.SoftDollarTier softDollarTier = createSoftDollarTierProto(order);
        if (softDollarTier != null) orderBuilder.setSoftDollarTier(softDollarTier);

        if (Util.isValidValue(order.cashQty())) orderBuilder.setCashQty(order.cashQty());
        if (!Util.StringIsEmpty(order.mifid2DecisionMaker())) orderBuilder.setMifid2DecisionMaker(order.mifid2DecisionMaker());
        if (!Util.StringIsEmpty(order.mifid2DecisionAlgo())) orderBuilder.setMifid2DecisionAlgo(order.mifid2DecisionAlgo());
        if (!Util.StringIsEmpty(order.mifid2ExecutionTrader())) orderBuilder.setMifid2ExecutionTrader(order.mifid2ExecutionTrader());
        if (!Util.StringIsEmpty(order.mifid2ExecutionAlgo())) orderBuilder.setMifid2ExecutionAlgo(order.mifid2ExecutionAlgo());
        if (order.dontUseAutoPriceForHedge()) orderBuilder.setDontUseAutoPriceForHedge(order.dontUseAutoPriceForHedge());
        if (order.isOmsContainer()) orderBuilder.setIsOmsContainer(order.isOmsContainer());
        if (order.discretionaryUpToLimitPrice()) orderBuilder.setDiscretionaryUpToLimitPrice(order.discretionaryUpToLimitPrice());
        if (order.usePriceMgmtAlgo() != null) orderBuilder.setUsePriceMgmtAlgo(order.usePriceMgmtAlgo() ? 1 : 0);
        if (Util.isValidValue(order.duration())) orderBuilder.setDuration(order.duration());
        if (Util.isValidValue(order.postToAts())) orderBuilder.setPostToAts(order.postToAts());
        if (!Util.StringIsEmpty(order.advancedErrorOverride())) orderBuilder.setAdvancedErrorOverride(order.advancedErrorOverride());
        if (!Util.StringIsEmpty(order.manualOrderTime())) orderBuilder.setManualOrderTime(order.manualOrderTime());
        if (Util.isValidValue(order.minTradeQty())) orderBuilder.setMinTradeQty(order.minTradeQty());
        if (Util.isValidValue(order.minCompeteSize())) orderBuilder.setMinCompeteSize(order.minCompeteSize());
        if (Util.isValidValue(order.competeAgainstBestOffset()) || order.isCompeteAgainstBestOffsetUpToMid()) orderBuilder.setCompeteAgainstBestOffset(order.competeAgainstBestOffset());
        if (Util.isValidValue(order.midOffsetAtWhole())) orderBuilder.setMidOffsetAtWhole(order.midOffsetAtWhole());
        if (Util.isValidValue(order.midOffsetAtHalf())) orderBuilder.setMidOffsetAtHalf(order.midOffsetAtHalf());
        if (!Util.StringIsEmpty(order.customerAccount())) orderBuilder.setCustomerAccount(order.customerAccount());
        if (order.professionalCustomer()) orderBuilder.setProfessionalCustomer(order.professionalCustomer());
        if (!Util.StringIsEmpty(order.bondAccruedInterest())) orderBuilder.setBondAccruedInterest(order.bondAccruedInterest());
        if (order.includeOvernight()) orderBuilder.setIncludeOvernight(order.includeOvernight());
        if (Util.isValidValue(order.manualOrderIndicator())) orderBuilder.setManualOrderIndicator(order.manualOrderIndicator());
        if (!Util.StringIsEmpty(order.submitter())) orderBuilder.setSubmitter(order.submitter());
        if (order.autoCancelParent()) orderBuilder.setAutoCancelParent(order.autoCancelParent());
        if (order.imbalanceOnly()) orderBuilder.setImbalanceOnly(order.imbalanceOnly());
        if (order.postOnly()) orderBuilder.setPostOnly(order.postOnly());
        if (order.allowPreOpen()) orderBuilder.setAllowPreOpen(order.allowPreOpen());
        if (order.ignoreOpenAuction()) orderBuilder.setIgnoreOpenAuction(order.ignoreOpenAuction());
        if (order.deactivate()) orderBuilder.setDeactivate(order.deactivate());
        if (order.seekPriceImprovement() != null) orderBuilder.setSeekPriceImprovement(order.seekPriceImprovement() ? 1 : 0);
        if (Util.isValidValue(order.whatIfType())) orderBuilder.setWhatIfType(order.whatIfType());
        if (order.routeMarketableToBbo() != null) orderBuilder.setRouteMarketableToBbo(order.routeMarketableToBbo() ? 1 : 0);

        return orderBuilder.build();
    }

    public static List<OrderConditionProto.OrderCondition> createConditionsProto(Order order) throws EClientException {
        List<OrderConditionProto.OrderCondition> orderConditionList = new ArrayList<OrderConditionProto.OrderCondition>();
        try {
            if (order.conditions() != null && !order.conditions().isEmpty()) {
                for (OrderCondition condition : order.conditions()) {
                    OrderConditionType type = condition.type();
                    OrderConditionProto.OrderCondition orderConditionProto = null;
                    switch(type) {
                        case Price:
                            orderConditionProto = createPriceConditionProto(condition);
                            break;
                        case Time:
                            orderConditionProto = createTimeConditionProto(condition);
                            break;
                        case Margin:
                            orderConditionProto = createMarginConditionProto(condition);
                            break;
                        case Execution:
                            orderConditionProto = createExecutionConditionProto(condition);
                            break;
                        case Volume:
                            orderConditionProto = createVolumeConditionProto(condition);
                            break;
                        case PercentChange:
                            orderConditionProto = createPercentChangeConditionProto(condition);
                            break;
                    }
                    if (orderConditionProto != null) {
                        orderConditionList.add(orderConditionProto);
                    }
                }
            }
        } catch (Exception e) {
            throw new EClientException(EClientErrors.ERROR_ENCODING_PROTOBUF, "Error encoding conditions");
        }
        return orderConditionList;
    }

    private static OrderConditionProto.OrderCondition createOrderConditionProto(OrderCondition condition) {
        int type = condition.type().val();
        boolean isConjunctionConnection = condition.conjunctionConnection();
        OrderConditionProto.OrderCondition.Builder orderConditionBuilder = OrderConditionProto.OrderCondition.newBuilder();
        if (Util.isValidValue(type)) orderConditionBuilder.setType(type);
        orderConditionBuilder.setIsConjunctionConnection(isConjunctionConnection);
        return orderConditionBuilder.build();
    }

    private static OrderConditionProto.OrderCondition createOperatorConditionProto(OrderCondition condition) throws InvalidProtocolBufferException  {
        OrderConditionProto.OrderCondition orderConditionProto = createOrderConditionProto(condition);
        OperatorCondition operatorCondition = (OperatorCondition)condition; 
        boolean isMore = operatorCondition.isMore();
        OrderConditionProto.OrderCondition.Builder orderConditionBuilder = OrderConditionProto.OrderCondition.newBuilder();
        orderConditionBuilder.mergeFrom(orderConditionProto.toByteArray());
        orderConditionBuilder.setIsMore(isMore);
        return orderConditionBuilder.build();
    }

    private static OrderConditionProto.OrderCondition createContractConditionProto(OrderCondition condition) throws InvalidProtocolBufferException {
        OrderConditionProto.OrderCondition orderConditionProto = createOperatorConditionProto(condition);
        ContractCondition contractCondition = (ContractCondition)condition; 
        int conId = contractCondition.conId();
        String exchange = contractCondition.exchange();
        OrderConditionProto.OrderCondition.Builder orderConditionBuilder = OrderConditionProto.OrderCondition.newBuilder();
        orderConditionBuilder.mergeFrom(orderConditionProto.toByteArray());
        if (Util.isValidValue(conId)) orderConditionBuilder.setConId(conId);
        if (!Util.StringIsEmpty(exchange)) orderConditionBuilder.setExchange(exchange);
        return orderConditionBuilder.build();
    }

    private static OrderConditionProto.OrderCondition createPriceConditionProto(OrderCondition condition) throws InvalidProtocolBufferException {
        OrderConditionProto.OrderCondition orderConditionProto = createContractConditionProto(condition);
        PriceCondition priceCondition = (PriceCondition)condition; 
        double price = priceCondition.price();
        int triggerMethod = priceCondition.triggerMethod();
        OrderConditionProto.OrderCondition.Builder orderConditionBuilder = OrderConditionProto.OrderCondition.newBuilder();
        orderConditionBuilder.mergeFrom(orderConditionProto.toByteArray());
        if (Util.isValidValue(price)) orderConditionBuilder.setPrice(price);
        if (Util.isValidValue(triggerMethod)) orderConditionBuilder.setTriggerMethod(triggerMethod);
        return orderConditionBuilder.build();
    }

    private static OrderConditionProto.OrderCondition createTimeConditionProto(OrderCondition condition) throws InvalidProtocolBufferException {
        OrderConditionProto.OrderCondition operatorConditionProto = createOperatorConditionProto(condition);
        TimeCondition timeCondition = (TimeCondition)condition; 
        String time = timeCondition.time();
        OrderConditionProto.OrderCondition.Builder orderConditionBuilder = OrderConditionProto.OrderCondition.newBuilder();
        orderConditionBuilder.mergeFrom(operatorConditionProto.toByteArray());
        if (!Util.StringIsEmpty(time)) orderConditionBuilder.setTime(time);
        return orderConditionBuilder.build();
    }

    private static OrderConditionProto.OrderCondition createMarginConditionProto(OrderCondition condition) throws InvalidProtocolBufferException {
        OrderConditionProto.OrderCondition operatorConditionProto = createOperatorConditionProto(condition);
        MarginCondition marginCondition = (MarginCondition)condition; 
        int percent = marginCondition.percent();
        OrderConditionProto.OrderCondition.Builder orderConditionBuilder = OrderConditionProto.OrderCondition.newBuilder();
        orderConditionBuilder.mergeFrom(operatorConditionProto.toByteArray());
        if (Util.isValidValue(percent)) orderConditionBuilder.setPercent(percent);
        return orderConditionBuilder.build();
    }

    private static OrderConditionProto.OrderCondition createExecutionConditionProto(OrderCondition condition) throws InvalidProtocolBufferException  {
        OrderConditionProto.OrderCondition orderConditionProto = createOrderConditionProto(condition);
        ExecutionCondition executionCondition = (ExecutionCondition)condition; 
        String secType = executionCondition.secType();
        String exchange = executionCondition.exchange();
        String symbol = executionCondition.symbol();
        OrderConditionProto.OrderCondition.Builder orderConditionBuilder = OrderConditionProto.OrderCondition.newBuilder();
        orderConditionBuilder.mergeFrom(orderConditionProto.toByteArray());
        if (!Util.StringIsEmpty(secType)) orderConditionBuilder.setSecType(secType);
        if (!Util.StringIsEmpty(exchange)) orderConditionBuilder.setExchange(exchange);
        if (!Util.StringIsEmpty(symbol)) orderConditionBuilder.setSymbol(symbol);
        return orderConditionBuilder.build();
    }

    private static OrderConditionProto.OrderCondition createVolumeConditionProto(OrderCondition condition) throws InvalidProtocolBufferException {
        OrderConditionProto.OrderCondition orderConditionProto = createContractConditionProto(condition);
        VolumeCondition volumeCondition = (VolumeCondition)condition; 
        int volume = volumeCondition.volume();
        OrderConditionProto.OrderCondition.Builder orderConditionBuilder = OrderConditionProto.OrderCondition.newBuilder();
        orderConditionBuilder.mergeFrom(orderConditionProto.toByteArray());
        if (Util.isValidValue(volume)) orderConditionBuilder.setVolume(volume);
        return orderConditionBuilder.build();
    }

    private static OrderConditionProto.OrderCondition createPercentChangeConditionProto(OrderCondition condition) throws InvalidProtocolBufferException {
        OrderConditionProto.OrderCondition orderConditionProto = createContractConditionProto(condition);
        PercentChangeCondition percentChangeCondition = (PercentChangeCondition)condition; 
        double changePercent = percentChangeCondition.changePercent();
        OrderConditionProto.OrderCondition.Builder orderConditionBuilder = OrderConditionProto.OrderCondition.newBuilder();
        orderConditionBuilder.mergeFrom(orderConditionProto.toByteArray());
        if (Util.isValidValue(changePercent)) orderConditionBuilder.setChangePercent(changePercent);
        return orderConditionBuilder.build();
    }

    public static SoftDollarTierProto.SoftDollarTier createSoftDollarTierProto(Order order) {
        SoftDollarTier tier = order.softDollarTier();
        if (tier == null) {
            return null;
        }

        SoftDollarTierProto.SoftDollarTier.Builder softDollarTierBuilder = SoftDollarTierProto.SoftDollarTier.newBuilder();
        if (!Util.StringIsEmpty(tier.name())) softDollarTierBuilder.setName(tier.name());
        if (!Util.StringIsEmpty(tier.value())) softDollarTierBuilder.setValue(tier.value());
        if (!Util.StringIsEmpty(tier.displayName())) softDollarTierBuilder.setDisplayName(tier.displayName());
        return softDollarTierBuilder.build();
    }

    public static ContractProto.Contract createContractProto(Contract contract, Order order) {
        ContractProto.Contract.Builder contractBuilder = ContractProto.Contract.newBuilder();
        if (Util.isValidValue(contract.conid())) contractBuilder.setConId(contract.conid());
        if (!Util.StringIsEmpty(contract.symbol())) contractBuilder.setSymbol(contract.symbol());
        if (!Util.StringIsEmpty(contract.getSecType())) contractBuilder.setSecType(contract.getSecType());
        if (!Util.StringIsEmpty(contract.lastTradeDateOrContractMonth())) contractBuilder.setLastTradeDateOrContractMonth(contract.lastTradeDateOrContractMonth());
        if (Util.isValidValue(contract.strike())) contractBuilder.setStrike(contract.strike());
        if (!Util.StringIsEmpty(contract.getRight())) contractBuilder.setRight(contract.getRight());
        if (!Util.StringIsEmpty(contract.multiplier())) contractBuilder.setMultiplier(Double.parseDouble(contract.multiplier()));
        if (!Util.StringIsEmpty(contract.exchange())) contractBuilder.setExchange(contract.exchange());
        if (!Util.StringIsEmpty(contract.primaryExch())) contractBuilder.setPrimaryExch(contract.primaryExch());
        if (!Util.StringIsEmpty(contract.currency())) contractBuilder.setCurrency(contract.currency());
        if (!Util.StringIsEmpty(contract.localSymbol())) contractBuilder.setLocalSymbol(contract.localSymbol());
        if (!Util.StringIsEmpty(contract.tradingClass())) contractBuilder.setTradingClass(contract.tradingClass());
        if (!Util.StringIsEmpty(contract.getSecIdType())) contractBuilder.setSecIdType(contract.getSecIdType());
        if (!Util.StringIsEmpty(contract.secId())) contractBuilder.setSecId(contract.secId());
        if (contract.includeExpired()) contractBuilder.setIncludeExpired(contract.includeExpired());
        if (!Util.StringIsEmpty(contract.comboLegsDescrip())) contractBuilder.setComboLegsDescrip(contract.comboLegsDescrip());
        if (!Util.StringIsEmpty(contract.description())) contractBuilder.setDescription(contract.description());
        if (!Util.StringIsEmpty(contract.issuerId())) contractBuilder.setIssuerId(contract.issuerId());

        List<ComboLegProto.ComboLeg> comboLegProtoList = createComboLegProtoList(contract, order);
        if (comboLegProtoList != null) {
            contractBuilder.addAllComboLegs(comboLegProtoList);
        }
        DeltaNeutralContractProto.DeltaNeutralContract deltaNeutralContractProto = createDeltaNeutralContractProto(contract);
        if (deltaNeutralContractProto != null) {
            contractBuilder.setDeltaNeutralContract(deltaNeutralContractProto);
        }
        return contractBuilder.build();
    }

    public static DeltaNeutralContractProto.DeltaNeutralContract createDeltaNeutralContractProto(Contract contract) {
        if (contract.deltaNeutralContract() == null) {
            return null;
        }
        DeltaNeutralContract deltaNeutralContract = contract.deltaNeutralContract();
        DeltaNeutralContractProto.DeltaNeutralContract.Builder deltaNeutralContractBuilder = DeltaNeutralContractProto.DeltaNeutralContract.newBuilder();
        if (Util.isValidValue(deltaNeutralContract.conid())) deltaNeutralContractBuilder.setConId(deltaNeutralContract.conid());
        if (Util.isValidValue(deltaNeutralContract.delta())) deltaNeutralContractBuilder.setDelta(deltaNeutralContract.delta());
        if (Util.isValidValue(deltaNeutralContract.price())) deltaNeutralContractBuilder.setPrice(deltaNeutralContract.price());
        return deltaNeutralContractBuilder.build();
    }

    public static List<ComboLegProto.ComboLeg> createComboLegProtoList(Contract contract, Order order) {
        List<ComboLeg> comboLegs = contract.comboLegs();
        if (comboLegs == null || comboLegs.isEmpty()) {
            return null;
        }
        List<ComboLegProto.ComboLeg> comboLegProtoList = new ArrayList<ComboLegProto.ComboLeg>();
        for(int i = 0; i < comboLegs.size(); i++) {
            ComboLeg comboLeg = comboLegs.get(i);
            double perLegPrice = Double.MAX_VALUE;
            if (order != null && i < order.orderComboLegs().size()) {
                perLegPrice = order.orderComboLegs().get(i).price();
            }
            ComboLegProto.ComboLeg comboLegProto = createComboLegProto(comboLeg, perLegPrice);
            comboLegProtoList.add(comboLegProto);
        }
        return comboLegProtoList;
    }

    public static ComboLegProto.ComboLeg createComboLegProto(ComboLeg comboLeg, double perLegPrice) {
        ComboLegProto.ComboLeg.Builder comboLegBuilder = ComboLegProto.ComboLeg.newBuilder();
        if (Util.isValidValue(comboLeg.conid())) comboLegBuilder.setConId(comboLeg.conid());
        if (Util.isValidValue(comboLeg.ratio())) comboLegBuilder.setRatio(comboLeg.ratio());
        if (!Util.StringIsEmpty(comboLeg.getAction())) comboLegBuilder.setAction(comboLeg.getAction());
        if (!Util.StringIsEmpty(comboLeg.exchange())) comboLegBuilder.setExchange(comboLeg.exchange());
        if (Util.isValidValue(comboLeg.getOpenClose())) comboLegBuilder.setOpenClose(comboLeg.getOpenClose());
        if (Util.isValidValue(comboLeg.shortSaleSlot())) comboLegBuilder.setShortSalesSlot(comboLeg.shortSaleSlot());
        if (!Util.StringIsEmpty(comboLeg.designatedLocation())) comboLegBuilder.setDesignatedLocation(comboLeg.designatedLocation());
        if (Util.isValidValue(comboLeg.exemptCode())) comboLegBuilder.setExemptCode(comboLeg.exemptCode());
        if (Util.isValidValue(perLegPrice)) comboLegBuilder.setPerLegPrice(perLegPrice);
        return comboLegBuilder.build();
    }

    public static CancelOrderRequestProto.CancelOrderRequest createCancelOrderRequestProto(int id, OrderCancel orderCancel) {
        CancelOrderRequestProto.CancelOrderRequest.Builder cancelOrderRequestBuilder = CancelOrderRequestProto.CancelOrderRequest.newBuilder();
        if (Util.isValidValue(id)) cancelOrderRequestBuilder.setOrderId(id);
        OrderCancelProto.OrderCancel orderCancelproto = createOrderCancelProto(orderCancel);
        if (orderCancelproto != null) cancelOrderRequestBuilder.setOrderCancel(orderCancelproto);
        return cancelOrderRequestBuilder.build();
    }

    public static GlobalCancelRequestProto.GlobalCancelRequest createGlobalCancelRequestProto(OrderCancel orderCancel) {
        GlobalCancelRequestProto.GlobalCancelRequest.Builder globalCancelRequestBuilder = GlobalCancelRequestProto.GlobalCancelRequest.newBuilder();
        OrderCancelProto.OrderCancel orderCancelproto = createOrderCancelProto(orderCancel);
        if (orderCancelproto != null) globalCancelRequestBuilder.setOrderCancel(orderCancelproto);
        return globalCancelRequestBuilder.build();
    }

    public static OrderCancelProto.OrderCancel createOrderCancelProto(OrderCancel orderCancel) {
        if (orderCancel == null) {
            return null;
        }
        OrderCancelProto.OrderCancel.Builder orderCancelBuilder = OrderCancelProto.OrderCancel.newBuilder();
        if (!Util.StringIsEmpty(orderCancel.manualOrderCancelTime())) orderCancelBuilder.setManualOrderCancelTime(orderCancel.manualOrderCancelTime());
        if (!Util.StringIsEmpty(orderCancel.extOperator())) orderCancelBuilder.setExtOperator(orderCancel.extOperator());
        if (Util.isValidValue(orderCancel.manualOrderIndicator())) orderCancelBuilder.setManualOrderIndicator(orderCancel.manualOrderIndicator());
        return orderCancelBuilder.build();
    }
    
    public static AllOpenOrdersRequestProto.AllOpenOrdersRequest createAllOpenOrdersRequestProto() {
        AllOpenOrdersRequestProto.AllOpenOrdersRequest.Builder allOpenOrdersRequestBuilder = AllOpenOrdersRequestProto.AllOpenOrdersRequest.newBuilder();
        return allOpenOrdersRequestBuilder.build();
    }

    public static AutoOpenOrdersRequestProto.AutoOpenOrdersRequest createAutoOpenOrdersRequestProto(boolean autoBind) {
        AutoOpenOrdersRequestProto.AutoOpenOrdersRequest.Builder autoOpenOrdersRequestBuilder = AutoOpenOrdersRequestProto.AutoOpenOrdersRequest.newBuilder();
        if (autoBind) autoOpenOrdersRequestBuilder.setAutoBind(autoBind);
        return autoOpenOrdersRequestBuilder.build();
    }

    public static OpenOrdersRequestProto.OpenOrdersRequest createOpenOrdersRequestProto() {
        OpenOrdersRequestProto.OpenOrdersRequest.Builder openOrdersRequestBuilder = OpenOrdersRequestProto.OpenOrdersRequest.newBuilder();
        return openOrdersRequestBuilder.build();
    }

    public static CompletedOrdersRequestProto.CompletedOrdersRequest createCompletedOrdersRequestProto(boolean apiOnly) {
        CompletedOrdersRequestProto.CompletedOrdersRequest.Builder completedOrdersRequestBuilder = CompletedOrdersRequestProto.CompletedOrdersRequest.newBuilder();
        if (apiOnly) completedOrdersRequestBuilder.setApiOnly(apiOnly);
        return completedOrdersRequestBuilder.build();
    }
    
    public static ContractDataRequestProto.ContractDataRequest createContractDataRequestProto(int reqId, Contract contract) {
        ContractDataRequestProto.ContractDataRequest.Builder contractDataRequestBuilder = ContractDataRequestProto.ContractDataRequest.newBuilder();
        if (Util.isValidValue(reqId)) contractDataRequestBuilder.setReqId(reqId);
        ContractProto.Contract contractProto = createContractProto(contract, null);
        if (contractProto != null) contractDataRequestBuilder.setContract(contractProto);
        return contractDataRequestBuilder.build();
    }

    public static MarketDataRequestProto.MarketDataRequest createMarketDataRequestProto(int reqId, Contract contract, String genericTickList, boolean snapshot, boolean regulatorySnapshot, List<TagValue> marketDataOptionsList) {
        MarketDataRequestProto.MarketDataRequest.Builder marketDataRequestBuilder = MarketDataRequestProto.MarketDataRequest.newBuilder();
        if (Util.isValidValue(reqId)) marketDataRequestBuilder.setReqId(reqId);
        ContractProto.Contract contractProto = createContractProto(contract, null);
        if (contractProto != null) marketDataRequestBuilder.setContract(contractProto);
        if (!Util.StringIsEmpty(genericTickList)) marketDataRequestBuilder.setGenericTickList(genericTickList);
        if (snapshot) marketDataRequestBuilder.setSnapshot(snapshot);
        if (regulatorySnapshot) marketDataRequestBuilder.setRegulatorySnapshot(regulatorySnapshot);
        if (marketDataOptionsList != null && !marketDataOptionsList.isEmpty()) {
            Map<String, String> marketDataOptions = marketDataOptionsList.stream().collect(Collectors.toMap(e -> e.m_tag, e -> e.m_value)); 
            marketDataRequestBuilder.putAllMarketDataOptions(marketDataOptions);
        }
        return marketDataRequestBuilder.build();
    }

    public static MarketDepthRequestProto.MarketDepthRequest createMarketDepthRequestProto(int reqId, Contract contract, int numRows, boolean isSmartDepth, List<TagValue> marketDepthOptionsList) {
        MarketDepthRequestProto.MarketDepthRequest.Builder marketDepthRequestBuilder = MarketDepthRequestProto.MarketDepthRequest.newBuilder();
        if (Util.isValidValue(reqId)) marketDepthRequestBuilder.setReqId(reqId);
        ContractProto.Contract contractProto = createContractProto(contract, null);
        if (contractProto != null) marketDepthRequestBuilder.setContract(contractProto);
        if (Util.isValidValue(numRows)) marketDepthRequestBuilder.setNumRows(numRows);
        if (isSmartDepth) marketDepthRequestBuilder.setIsSmartDepth(isSmartDepth);
        
        if (marketDepthOptionsList != null && !marketDepthOptionsList.isEmpty()) {
            Map<String, String> marketDepthOptions = marketDepthOptionsList.stream().collect(Collectors.toMap(e -> e.m_tag, e -> e.m_value)); 
            marketDepthRequestBuilder.putAllMarketDepthOptions(marketDepthOptions);
        }
        return marketDepthRequestBuilder.build();
    }

    public static MarketDataTypeRequestProto.MarketDataTypeRequest createMarketDataTypeRequestProto(int marketDataType) {
        MarketDataTypeRequestProto.MarketDataTypeRequest.Builder marketDataTypeRequestBuilder = MarketDataTypeRequestProto.MarketDataTypeRequest.newBuilder();
        if (Util.isValidValue(marketDataType)) marketDataTypeRequestBuilder.setMarketDataType(marketDataType);
        return marketDataTypeRequestBuilder.build();
    }

    public static CancelMarketDataProto.CancelMarketData createCancelMarketDataProto(int reqId) {
        CancelMarketDataProto.CancelMarketData.Builder cancelMarketDataBuilder = CancelMarketDataProto.CancelMarketData.newBuilder();
        if (Util.isValidValue(reqId)) cancelMarketDataBuilder.setReqId(reqId);
        return cancelMarketDataBuilder.build();
    }

    public static CancelMarketDepthProto.CancelMarketDepth createCancelMarketDepthProto(int reqId, boolean isSmartDepth) {
        CancelMarketDepthProto.CancelMarketDepth.Builder cancelMarketDepthBuilder = CancelMarketDepthProto.CancelMarketDepth.newBuilder();
        if (Util.isValidValue(reqId)) cancelMarketDepthBuilder.setReqId(reqId);
        if (isSmartDepth) cancelMarketDepthBuilder.setIsSmartDepth(isSmartDepth);
        return cancelMarketDepthBuilder.build();
    }
    
    public static AccountDataRequestProto.AccountDataRequest createAccountDataRequestProto(boolean subscribe, String acctCode) {
        AccountDataRequestProto.AccountDataRequest.Builder accountDataRequestBuilder = AccountDataRequestProto.AccountDataRequest.newBuilder();
        if (subscribe) accountDataRequestBuilder.setSubscribe(subscribe);
        if (!Util.StringIsEmpty(acctCode)) accountDataRequestBuilder.setAcctCode(acctCode);
        return accountDataRequestBuilder.build();
    }

    public static ManagedAccountsRequestProto.ManagedAccountsRequest createManagedAccountsRequestProto() {
        ManagedAccountsRequestProto.ManagedAccountsRequest.Builder managedAccountsRequestBuilder = ManagedAccountsRequestProto.ManagedAccountsRequest.newBuilder();
        return managedAccountsRequestBuilder.build();
    }

    public static PositionsRequestProto.PositionsRequest createPositionsRequestProto() {
        PositionsRequestProto.PositionsRequest.Builder positionsRequestBuilder = PositionsRequestProto.PositionsRequest.newBuilder();
        return positionsRequestBuilder.build();
    }

    public static CancelPositionsProto.CancelPositions createCancelPositionsRequestProto() {
        CancelPositionsProto.CancelPositions.Builder cancelPositionsBuilder = CancelPositionsProto.CancelPositions.newBuilder();
        return cancelPositionsBuilder.build();
    }

    public static AccountSummaryRequestProto.AccountSummaryRequest createAccountSummaryRequestProto(int reqId, String group, String tags) {
        AccountSummaryRequestProto.AccountSummaryRequest.Builder accountSummaryRequestBuilder = AccountSummaryRequestProto.AccountSummaryRequest.newBuilder();
        if (Util.isValidValue(reqId)) accountSummaryRequestBuilder.setReqId(reqId);
        if (!Util.StringIsEmpty(group)) accountSummaryRequestBuilder.setGroup(group);
        if (!Util.StringIsEmpty(tags)) accountSummaryRequestBuilder.setTags(tags);
        return accountSummaryRequestBuilder.build();
    }

    public static CancelAccountSummaryProto.CancelAccountSummary createCancelAccountSummaryRequestProto(int reqId) {
        CancelAccountSummaryProto.CancelAccountSummary.Builder cancelAccountSummaryBuilder = CancelAccountSummaryProto.CancelAccountSummary.newBuilder();
        if (Util.isValidValue(reqId)) cancelAccountSummaryBuilder.setReqId(reqId);
        return cancelAccountSummaryBuilder.build();
    }

    public static PositionsMultiRequestProto.PositionsMultiRequest createPositionsMultiRequestProto(int reqId, String account, String modelCode) {
        PositionsMultiRequestProto.PositionsMultiRequest.Builder positionsMultiRequestBuilder = PositionsMultiRequestProto.PositionsMultiRequest.newBuilder();
        if (Util.isValidValue(reqId)) positionsMultiRequestBuilder.setReqId(reqId);
        if (!Util.StringIsEmpty(account)) positionsMultiRequestBuilder.setAccount(account);
        if (!Util.StringIsEmpty(modelCode)) positionsMultiRequestBuilder.setModelCode(modelCode);
        return positionsMultiRequestBuilder.build();
    }

    public static CancelPositionsMultiProto.CancelPositionsMulti createCancelPositionsMultiRequestProto(int reqId) {
        CancelPositionsMultiProto.CancelPositionsMulti.Builder cancelPositionsMultiBuilder = CancelPositionsMultiProto.CancelPositionsMulti.newBuilder();
        if (Util.isValidValue(reqId)) cancelPositionsMultiBuilder.setReqId(reqId);
        return cancelPositionsMultiBuilder.build();
    }

    public static AccountUpdatesMultiRequestProto.AccountUpdatesMultiRequest createAccountUpdatesMultiRequestProto(int reqId, String account, String modelCode, boolean ledgerAndNLV) {
        AccountUpdatesMultiRequestProto.AccountUpdatesMultiRequest.Builder accountUpdatesMultiRequestBuilder = AccountUpdatesMultiRequestProto.AccountUpdatesMultiRequest.newBuilder();
        if (Util.isValidValue(reqId)) accountUpdatesMultiRequestBuilder.setReqId(reqId);
        if (!Util.StringIsEmpty(account)) accountUpdatesMultiRequestBuilder.setAccount(account);
        if (!Util.StringIsEmpty(modelCode)) accountUpdatesMultiRequestBuilder.setModelCode(modelCode);
        if (ledgerAndNLV) accountUpdatesMultiRequestBuilder.setLedgerAndNLV(ledgerAndNLV);
        return accountUpdatesMultiRequestBuilder.build();
    }

    public static CancelAccountUpdatesMultiProto.CancelAccountUpdatesMulti createCancelAccountUpdatesMultiRequestProto(int reqId) {
        CancelAccountUpdatesMultiProto.CancelAccountUpdatesMulti.Builder cancelAccountUpdatesMultiBuilder = CancelAccountUpdatesMultiProto.CancelAccountUpdatesMulti.newBuilder();
        if (Util.isValidValue(reqId)) cancelAccountUpdatesMultiBuilder.setReqId(reqId);
        return cancelAccountUpdatesMultiBuilder.build();
    }

    public static HistoricalDataRequestProto.HistoricalDataRequest createHistoricalDataRequestProto(int reqId, Contract contract, 
            String endDateTime, String duration, String barSizeSetting, String whatToShow, boolean useRTH, int formatDate, boolean keepUpToDate, List<TagValue> chartOptionsList) {
        HistoricalDataRequestProto.HistoricalDataRequest.Builder historicalDataRequestBuilder = HistoricalDataRequestProto.HistoricalDataRequest.newBuilder();
        if (Util.isValidValue(reqId)) historicalDataRequestBuilder.setReqId(reqId);
        ContractProto.Contract contractProto = createContractProto(contract, null);
        if (contractProto != null) historicalDataRequestBuilder.setContract(contractProto);
        if (!Util.StringIsEmpty(endDateTime)) historicalDataRequestBuilder.setEndDateTime(endDateTime);
        if (!Util.StringIsEmpty(duration)) historicalDataRequestBuilder.setDuration(duration);
        if (!Util.StringIsEmpty(barSizeSetting)) historicalDataRequestBuilder.setBarSizeSetting(barSizeSetting);
        if (!Util.StringIsEmpty(whatToShow)) historicalDataRequestBuilder.setWhatToShow(whatToShow);
        if (useRTH) historicalDataRequestBuilder.setUseRTH(useRTH);
        if (Util.isValidValue(formatDate)) historicalDataRequestBuilder.setFormatDate(formatDate);
        if (keepUpToDate) historicalDataRequestBuilder.setKeepUpToDate(keepUpToDate);
        if (chartOptionsList != null && !chartOptionsList.isEmpty()) {
            Map<String, String> chartOptionsMap = chartOptionsList.stream().collect(Collectors.toMap(e -> e.m_tag, e -> e.m_value));
            historicalDataRequestBuilder.putAllChartOptions(chartOptionsMap);
        }
        return historicalDataRequestBuilder.build();
    }

    public static RealTimeBarsRequestProto.RealTimeBarsRequest createRealTimeBarsRequestProto(int reqId, Contract contract, int barSize, String whatToShow, boolean useRTH, List<TagValue> realTimeBarsOptionsList) {
        RealTimeBarsRequestProto.RealTimeBarsRequest.Builder realTimeBarsRequestBuilder = RealTimeBarsRequestProto.RealTimeBarsRequest.newBuilder();
        if (Util.isValidValue(reqId)) realTimeBarsRequestBuilder.setReqId(reqId);
        ContractProto.Contract contractProto = createContractProto(contract, null);
        if (contractProto != null) realTimeBarsRequestBuilder.setContract(contractProto);
        if (Util.isValidValue(barSize)) realTimeBarsRequestBuilder.setBarSize(barSize);
        if (!Util.StringIsEmpty(whatToShow)) realTimeBarsRequestBuilder.setWhatToShow(whatToShow);
        if (useRTH) realTimeBarsRequestBuilder.setUseRTH(useRTH);
        if (realTimeBarsOptionsList != null && !realTimeBarsOptionsList.isEmpty()) {
            Map<String, String> realTimeBarsOptionsMap = realTimeBarsOptionsList.stream().collect(Collectors.toMap(e -> e.m_tag, e -> e.m_value));
            realTimeBarsRequestBuilder.putAllRealTimeBarsOptions(realTimeBarsOptionsMap);
        }
        return realTimeBarsRequestBuilder.build();
    }

    public static HeadTimestampRequestProto.HeadTimestampRequest createHeadTimestampRequestProto(int reqId, Contract contract, String whatToShow, boolean useRTH, int formatDate) {
        HeadTimestampRequestProto.HeadTimestampRequest.Builder headTimestampRequestBuilder = HeadTimestampRequestProto.HeadTimestampRequest.newBuilder();
        if (Util.isValidValue(reqId)) headTimestampRequestBuilder.setReqId(reqId);
        ContractProto.Contract contractProto = createContractProto(contract, null);
        if (contractProto != null) headTimestampRequestBuilder.setContract(contractProto);
        if (!Util.StringIsEmpty(whatToShow)) headTimestampRequestBuilder.setWhatToShow(whatToShow);
        if (useRTH) headTimestampRequestBuilder.setUseRTH(useRTH);
        if (Util.isValidValue(formatDate)) headTimestampRequestBuilder.setFormatDate(formatDate);
        
        return headTimestampRequestBuilder.build();
    }

    public static HistogramDataRequestProto.HistogramDataRequest createHistogramDataRequestProto(int reqId, Contract contract, boolean useRTH, String timePeriod) {
        HistogramDataRequestProto.HistogramDataRequest.Builder histogramDataRequestBuilder = HistogramDataRequestProto.HistogramDataRequest.newBuilder();
        if (Util.isValidValue(reqId)) histogramDataRequestBuilder.setReqId(reqId);
        ContractProto.Contract contractProto = createContractProto(contract, null);
        if (contractProto != null) histogramDataRequestBuilder.setContract(contractProto);
        if (useRTH) histogramDataRequestBuilder.setUseRTH(useRTH);
        if (!Util.StringIsEmpty(timePeriod)) histogramDataRequestBuilder.setTimePeriod(timePeriod);
        return histogramDataRequestBuilder.build();
    }

    public static HistoricalTicksRequestProto.HistoricalTicksRequest createHistoricalTicksRequestProto(int reqId, Contract contract, String startDateTime, String endDateTime, 
            int numberOfTicks, String whatToShow, boolean useRTH, boolean ignoreSize, List<TagValue> miscOptionsList) {
        HistoricalTicksRequestProto.HistoricalTicksRequest.Builder historicalTicksRequestBuilder = HistoricalTicksRequestProto.HistoricalTicksRequest.newBuilder();
        if (Util.isValidValue(reqId)) historicalTicksRequestBuilder.setReqId(reqId);
        ContractProto.Contract contractProto = createContractProto(contract, null);
        if (contractProto != null) historicalTicksRequestBuilder.setContract(contractProto);
        if (!Util.StringIsEmpty(startDateTime)) historicalTicksRequestBuilder.setStartDateTime(startDateTime);
        if (!Util.StringIsEmpty(endDateTime)) historicalTicksRequestBuilder.setEndDateTime(endDateTime);
        if (Util.isValidValue(numberOfTicks)) historicalTicksRequestBuilder.setNumberOfTicks(numberOfTicks);
        if (!Util.StringIsEmpty(whatToShow)) historicalTicksRequestBuilder.setWhatToShow(whatToShow);
        if (useRTH) historicalTicksRequestBuilder.setUseRTH(useRTH);
        if (ignoreSize) historicalTicksRequestBuilder.setIgnoreSize(ignoreSize);
        
        if (miscOptionsList != null && !miscOptionsList.isEmpty()) {
            Map<String, String> miscOptionsMap = miscOptionsList.stream().collect(Collectors.toMap(e -> e.m_tag, e -> e.m_value));
            historicalTicksRequestBuilder.putAllMiscOptions(miscOptionsMap);
        }

        return historicalTicksRequestBuilder.build();
    }

    public static TickByTickRequestProto.TickByTickRequest createTickByTickRequestProto(int reqId, Contract contract, String tickType, int numberOfTicks, boolean ignoreSize) {
        TickByTickRequestProto.TickByTickRequest.Builder tickByTickRequestBuilder = TickByTickRequestProto.TickByTickRequest.newBuilder();
        if (Util.isValidValue(reqId)) tickByTickRequestBuilder.setReqId(reqId);
        ContractProto.Contract contractProto = createContractProto(contract, null);
        if (contractProto != null) tickByTickRequestBuilder.setContract(contractProto);
        if (!Util.StringIsEmpty(tickType)) tickByTickRequestBuilder.setTickType(tickType);
        if (Util.isValidValue(numberOfTicks)) tickByTickRequestBuilder.setNumberOfTicks(numberOfTicks);
        if (ignoreSize) tickByTickRequestBuilder.setIgnoreSize(ignoreSize);
        return tickByTickRequestBuilder.build();
    }
    
    public static CancelHistoricalDataProto.CancelHistoricalData createCancelHistoricalDataProto(int reqId) {
        CancelHistoricalDataProto.CancelHistoricalData.Builder cancelHistoricalDataBuilder = CancelHistoricalDataProto.CancelHistoricalData.newBuilder();
        if (Util.isValidValue(reqId)) cancelHistoricalDataBuilder.setReqId(reqId);
        return cancelHistoricalDataBuilder.build();
    }
    
    public static CancelRealTimeBarsProto.CancelRealTimeBars createCancelRealTimeBarsProto(int reqId) {
        CancelRealTimeBarsProto.CancelRealTimeBars.Builder cancelRealTimeBarsBuilder = CancelRealTimeBarsProto.CancelRealTimeBars.newBuilder();
        if (Util.isValidValue(reqId)) cancelRealTimeBarsBuilder.setReqId(reqId);
        return cancelRealTimeBarsBuilder.build();
    }
    
    public static CancelHeadTimestampProto.CancelHeadTimestamp createCancelHeadTimestampProto(int reqId) {
        CancelHeadTimestampProto.CancelHeadTimestamp.Builder cancelHeadTimestampBuilder = CancelHeadTimestampProto.CancelHeadTimestamp.newBuilder();
        if (Util.isValidValue(reqId)) cancelHeadTimestampBuilder.setReqId(reqId);
        return cancelHeadTimestampBuilder.build();
    }
    
    public static CancelHistogramDataProto.CancelHistogramData createCancelHistogramDataProto(int reqId) {
        CancelHistogramDataProto.CancelHistogramData.Builder cancelHistogramDataBuilder = CancelHistogramDataProto.CancelHistogramData.newBuilder();
        if (Util.isValidValue(reqId)) cancelHistogramDataBuilder.setReqId(reqId);
        return cancelHistogramDataBuilder.build();
    }
    
    public static CancelTickByTickProto.CancelTickByTick createCancelTickByTickProto(int reqId) {
        CancelTickByTickProto.CancelTickByTick.Builder cancelTickByTickBuilder = CancelTickByTickProto.CancelTickByTick.newBuilder();
        if (Util.isValidValue(reqId)) cancelTickByTickBuilder.setReqId(reqId);
        return cancelTickByTickBuilder.build();
    }

    public static NewsBulletinsRequestProto.NewsBulletinsRequest createNewsBulletinsRequestProto(boolean allMessages) {
        NewsBulletinsRequestProto.NewsBulletinsRequest.Builder newsBulletinsRequestBuilder = NewsBulletinsRequestProto.NewsBulletinsRequest.newBuilder();
        if (allMessages) newsBulletinsRequestBuilder.setAllMessages(allMessages);
        return newsBulletinsRequestBuilder.build();
    }

    public static CancelNewsBulletinsProto.CancelNewsBulletins createCancelNewsBulletinsProto() {
        CancelNewsBulletinsProto.CancelNewsBulletins.Builder cancelNewsBulletinsBuilder = CancelNewsBulletinsProto.CancelNewsBulletins.newBuilder();
        return cancelNewsBulletinsBuilder.build();
    }

    public static NewsArticleRequestProto.NewsArticleRequest createNewsArticleRequestProto(int reqId, String providerCode, String articleId, List<TagValue> newsArticleOptionsList) {
        NewsArticleRequestProto.NewsArticleRequest.Builder newsArticleRequestBuilder = NewsArticleRequestProto.NewsArticleRequest.newBuilder();
        if (Util.isValidValue(reqId)) newsArticleRequestBuilder.setReqId(reqId);
        if (!Util.StringIsEmpty(providerCode)) newsArticleRequestBuilder.setProviderCode(providerCode);
        if (!Util.StringIsEmpty(articleId)) newsArticleRequestBuilder.setArticleId(articleId);

        if (newsArticleOptionsList != null && !newsArticleOptionsList.isEmpty()) {
            Map<String, String> newsArticleOptions = newsArticleOptionsList.stream().collect(Collectors.toMap(e -> e.m_tag, e -> e.m_value));
            newsArticleRequestBuilder.putAllNewsArticleOptions(newsArticleOptions);
        }

        return newsArticleRequestBuilder.build();
    }

    public static NewsProvidersRequestProto.NewsProvidersRequest createNewsProvidersRequestProto() {
        NewsProvidersRequestProto.NewsProvidersRequest.Builder newsProvidersRequestBuilder = NewsProvidersRequestProto.NewsProvidersRequest.newBuilder();
        return newsProvidersRequestBuilder.build();
    }

    public static HistoricalNewsRequestProto.HistoricalNewsRequest createHistoricalNewsRequestProto(int reqId, int conId, String providerCodes, 
            String startDateTime, String endDateTime, int totalResults, List<TagValue> historicalNewsOptionsList) {

        HistoricalNewsRequestProto.HistoricalNewsRequest.Builder historicalNewsRequestBuilder = HistoricalNewsRequestProto.HistoricalNewsRequest.newBuilder();
        if (Util.isValidValue(reqId)) historicalNewsRequestBuilder.setReqId(reqId);
        if (Util.isValidValue(conId)) historicalNewsRequestBuilder.setConId(conId);
        if (!Util.StringIsEmpty(providerCodes)) historicalNewsRequestBuilder.setProviderCodes(providerCodes);
        if (!Util.StringIsEmpty(startDateTime)) historicalNewsRequestBuilder.setStartDateTime(startDateTime);
        if (!Util.StringIsEmpty(endDateTime)) historicalNewsRequestBuilder.setEndDateTime(endDateTime);
        if (Util.isValidValue(totalResults)) historicalNewsRequestBuilder.setTotalResults(totalResults);
        
        if (historicalNewsOptionsList != null && !historicalNewsOptionsList.isEmpty()) {
            Map<String, String> historicalNewsOptions = historicalNewsOptionsList.stream().collect(Collectors.toMap(e -> e.m_tag, e -> e.m_value));
            historicalNewsRequestBuilder.putAllHistoricalNewsOptions(historicalNewsOptions);
        }

        return historicalNewsRequestBuilder.build();
    }

    public static WshMetaDataRequestProto.WshMetaDataRequest createWshMetaDataRequestProto(int reqId) {
        WshMetaDataRequestProto.WshMetaDataRequest.Builder wshMetaDataRequestBuilder = WshMetaDataRequestProto.WshMetaDataRequest.newBuilder();
        if (Util.isValidValue(reqId)) wshMetaDataRequestBuilder.setReqId(reqId);
        return wshMetaDataRequestBuilder.build();
    }

    public static CancelWshMetaDataProto.CancelWshMetaData createCancelWshMetaDataProto(int reqId) {
        CancelWshMetaDataProto.CancelWshMetaData.Builder cancelWshMetaDataBuilder = CancelWshMetaDataProto.CancelWshMetaData.newBuilder();
        if (Util.isValidValue(reqId)) cancelWshMetaDataBuilder.setReqId(reqId);
        return cancelWshMetaDataBuilder.build();
    }

    public static WshEventDataRequestProto.WshEventDataRequest createWshEventDataRequestProto(int reqId, WshEventData wshEventData) {
        WshEventDataRequestProto.WshEventDataRequest.Builder wshEventDataRequestBuilder = WshEventDataRequestProto.WshEventDataRequest.newBuilder();
        if (Util.isValidValue(reqId)) wshEventDataRequestBuilder.setReqId(reqId);

        if (wshEventData != null) {
            if (Util.isValidValue(wshEventData.conId())) wshEventDataRequestBuilder.setConId(wshEventData.conId());
            if (!Util.StringIsEmpty(wshEventData.filter())) wshEventDataRequestBuilder.setFilter(wshEventData.filter());
            if (wshEventData.fillWatchlist()) wshEventDataRequestBuilder.setFillWatchlist(wshEventData.fillWatchlist());
            if (wshEventData.fillPortfolio()) wshEventDataRequestBuilder.setFillPortfolio(wshEventData.fillPortfolio());
            if (wshEventData.fillCompetitors()) wshEventDataRequestBuilder.setFillCompetitors(wshEventData.fillCompetitors());
            if (!Util.StringIsEmpty(wshEventData.startDate())) wshEventDataRequestBuilder.setStartDate(wshEventData.startDate());
            if (!Util.StringIsEmpty(wshEventData.endDate())) wshEventDataRequestBuilder.setEndDate(wshEventData.endDate());
            if (Util.isValidValue(wshEventData.totalLimit())) wshEventDataRequestBuilder.setTotalLimit(wshEventData.totalLimit());
        }

        return wshEventDataRequestBuilder.build();
    }

    public static CancelWshEventDataProto.CancelWshEventData createCancelWshEventDataProto(int reqId) {
        CancelWshEventDataProto.CancelWshEventData.Builder cancelWshEventDataBuilder = CancelWshEventDataProto.CancelWshEventData.newBuilder();
        if (Util.isValidValue(reqId)) cancelWshEventDataBuilder.setReqId(reqId);
        return cancelWshEventDataBuilder.build();
    }

    public static ScannerParametersRequestProto.ScannerParametersRequest createScannerParametersRequestProto() {
        ScannerParametersRequestProto.ScannerParametersRequest.Builder scannerParametersRequestBuilder = ScannerParametersRequestProto.ScannerParametersRequest.newBuilder();
        return scannerParametersRequestBuilder.build();
    }

    public static ScannerSubscriptionRequestProto.ScannerSubscriptionRequest createScannerSubscriptionRequestProto(int reqId, ScannerSubscription subscription, 
            List<TagValue> scannerSubscriptionOptionsList, List<TagValue> scannerSubscriptionFilterOptionsList) {
        ScannerSubscriptionRequestProto.ScannerSubscriptionRequest.Builder scannerSubscriptionRequestBuilder = ScannerSubscriptionRequestProto.ScannerSubscriptionRequest.newBuilder();
        if (Util.isValidValue(reqId)) scannerSubscriptionRequestBuilder.setReqId(reqId);
        ScannerSubscriptionProto.ScannerSubscription scannerSubscriptionProto = createScannerSubscriptionProto(subscription, scannerSubscriptionOptionsList, scannerSubscriptionFilterOptionsList);
        if (scannerSubscriptionProto != null) scannerSubscriptionRequestBuilder.setScannerSubscription(scannerSubscriptionProto);
        return scannerSubscriptionRequestBuilder.build();
    }

    private static ScannerSubscriptionProto.ScannerSubscription createScannerSubscriptionProto(ScannerSubscription subscription,
            List<TagValue> scannerSubscriptionOptionsList, List<TagValue> scannerSubscriptionFilterOptionsList) {
        if (subscription == null) {
            return null;
        }
        ScannerSubscriptionProto.ScannerSubscription.Builder scannerSubscriptionBuilder = ScannerSubscriptionProto.ScannerSubscription.newBuilder();
        if (Util.isValidValue(subscription.numberOfRows())) scannerSubscriptionBuilder.setNumberOfRows(subscription.numberOfRows());
        if (!Util.StringIsEmpty(subscription.instrument())) scannerSubscriptionBuilder.setInstrument(subscription.instrument());
        if (!Util.StringIsEmpty(subscription.locationCode())) scannerSubscriptionBuilder.setLocationCode(subscription.locationCode());
        if (!Util.StringIsEmpty(subscription.scanCode())) scannerSubscriptionBuilder.setScanCode(subscription.scanCode());
        if (Util.isValidValue(subscription.abovePrice())) scannerSubscriptionBuilder.setAbovePrice(subscription.abovePrice());
        if (Util.isValidValue(subscription.belowPrice())) scannerSubscriptionBuilder.setBelowPrice(subscription.belowPrice());
        if (Util.isValidValue(subscription.aboveVolume())) scannerSubscriptionBuilder.setAboveVolume(subscription.aboveVolume());
        if (Util.isValidValue(subscription.averageOptionVolumeAbove())) scannerSubscriptionBuilder.setAverageOptionVolumeAbove(subscription.averageOptionVolumeAbove());
        if (Util.isValidValue(subscription.marketCapAbove())) scannerSubscriptionBuilder.setMarketCapAbove(subscription.marketCapAbove());
        if (Util.isValidValue(subscription.marketCapBelow())) scannerSubscriptionBuilder.setMarketCapBelow(subscription.marketCapBelow());
        if (!Util.StringIsEmpty(subscription.moodyRatingAbove())) scannerSubscriptionBuilder.setMoodyRatingAbove(subscription.moodyRatingAbove());
        if (!Util.StringIsEmpty(subscription.moodyRatingBelow())) scannerSubscriptionBuilder.setMoodyRatingBelow(subscription.moodyRatingBelow());
        if (!Util.StringIsEmpty(subscription.spRatingAbove())) scannerSubscriptionBuilder.setSpRatingAbove(subscription.spRatingAbove());
        if (!Util.StringIsEmpty(subscription.spRatingBelow())) scannerSubscriptionBuilder.setSpRatingBelow(subscription.spRatingBelow());
        if (!Util.StringIsEmpty(subscription.maturityDateAbove())) scannerSubscriptionBuilder.setMaturityDateAbove(subscription.maturityDateAbove());
        if (!Util.StringIsEmpty(subscription.maturityDateBelow())) scannerSubscriptionBuilder.setMaturityDateBelow(subscription.maturityDateBelow());
        if (Util.isValidValue(subscription.couponRateAbove())) scannerSubscriptionBuilder.setCouponRateAbove(subscription.couponRateAbove());
        if (Util.isValidValue(subscription.couponRateBelow())) scannerSubscriptionBuilder.setCouponRateBelow(subscription.couponRateBelow());
        if (subscription.excludeConvertible()) scannerSubscriptionBuilder.setExcludeConvertible(subscription.excludeConvertible());
        if (!Util.StringIsEmpty(subscription.scannerSettingPairs())) scannerSubscriptionBuilder.setScannerSettingPairs(subscription.scannerSettingPairs());
        if (!Util.StringIsEmpty(subscription.stockTypeFilter())) scannerSubscriptionBuilder.setStockTypeFilter(subscription.stockTypeFilter());
        
        if (scannerSubscriptionOptionsList != null && !scannerSubscriptionOptionsList.isEmpty()) {
            Map<String, String> scannerSubscriptionOptions = scannerSubscriptionOptionsList.stream().collect(Collectors.toMap(e -> e.m_tag, e -> e.m_value));
            scannerSubscriptionBuilder.putAllScannerSubscriptionOptions(scannerSubscriptionOptions);
        }
        if (scannerSubscriptionFilterOptionsList != null && !scannerSubscriptionFilterOptionsList.isEmpty()) {
            Map<String, String> scannerSubscriptionFilterOptions = scannerSubscriptionFilterOptionsList.stream().collect(Collectors.toMap(e -> e.m_tag, e -> e.m_value));
            scannerSubscriptionBuilder.putAllScannerSubscriptionFilterOptions(scannerSubscriptionFilterOptions);
        }
        return scannerSubscriptionBuilder.build();
    }
    
    public static FundamentalsDataRequestProto.FundamentalsDataRequest createFundamentalsDataRequestProto(int reqId, Contract contract, String reportType, List<TagValue> fundamentalsDataOptionsList) {
        FundamentalsDataRequestProto.FundamentalsDataRequest.Builder fundamentalsDataRequestBuilder = FundamentalsDataRequestProto.FundamentalsDataRequest.newBuilder();
        if (Util.isValidValue(reqId)) fundamentalsDataRequestBuilder.setReqId(reqId);
        ContractProto.Contract contractProto = createContractProto(contract, null);
        if (contractProto != null) fundamentalsDataRequestBuilder.setContract(contractProto);
        if (!Util.StringIsEmpty(reportType)) fundamentalsDataRequestBuilder.setReportType(reportType);
        if (fundamentalsDataOptionsList != null && !fundamentalsDataOptionsList.isEmpty()) {
            Map<String, String> fundamentalsDataOptions = fundamentalsDataOptionsList.stream().collect(Collectors.toMap(e -> e.m_tag, e -> e.m_value));
            fundamentalsDataRequestBuilder.putAllFundamentalsDataOptions(fundamentalsDataOptions);
        }
        return fundamentalsDataRequestBuilder.build();
    }

    public static PnLRequestProto.PnLRequest createPnLRequestProto(int reqId, String account, String modelCode) {
        PnLRequestProto.PnLRequest.Builder pnlRequestBuilder = PnLRequestProto.PnLRequest.newBuilder();
        if (Util.isValidValue(reqId)) pnlRequestBuilder.setReqId(reqId);
        if (!Util.StringIsEmpty(account)) pnlRequestBuilder.setAccount(account);
        if (!Util.StringIsEmpty(modelCode)) pnlRequestBuilder.setModelCode(modelCode);
        return pnlRequestBuilder.build();
    }

    public static PnLSingleRequestProto.PnLSingleRequest createPnLSingleRequestProto(int reqId, String account, String modelCode, int conId) {
        PnLSingleRequestProto.PnLSingleRequest.Builder pnlSingleRequestBuilder = PnLSingleRequestProto.PnLSingleRequest.newBuilder();
        if (Util.isValidValue(reqId)) pnlSingleRequestBuilder.setReqId(reqId);
        if (!Util.StringIsEmpty(account)) pnlSingleRequestBuilder.setAccount(account);
        if (!Util.StringIsEmpty(modelCode)) pnlSingleRequestBuilder.setModelCode(modelCode);
        if (Util.isValidValue(conId)) pnlSingleRequestBuilder.setConId(conId);
        return pnlSingleRequestBuilder.build();
    }

    public static CancelScannerSubscriptionProto.CancelScannerSubscription createCancelScannerSubscriptionProto(int reqId) {
        CancelScannerSubscriptionProto.CancelScannerSubscription.Builder cancelScannerSubscriptionBuilder = CancelScannerSubscriptionProto.CancelScannerSubscription.newBuilder();
        if (Util.isValidValue(reqId)) cancelScannerSubscriptionBuilder.setReqId(reqId);
        return cancelScannerSubscriptionBuilder.build();
    }

    public static CancelFundamentalsDataProto.CancelFundamentalsData createCancelFundamentalsDataProto(int reqId) {
        CancelFundamentalsDataProto.CancelFundamentalsData.Builder cancelFundamentalsDataBuilder = CancelFundamentalsDataProto.CancelFundamentalsData.newBuilder();
        if (Util.isValidValue(reqId)) cancelFundamentalsDataBuilder.setReqId(reqId);
        return cancelFundamentalsDataBuilder.build();
    }

    public static CancelPnLProto.CancelPnL createCancelPnLProto(int reqId) {
        CancelPnLProto.CancelPnL.Builder cancelPnLBuilder = CancelPnLProto.CancelPnL.newBuilder();
        if (Util.isValidValue(reqId)) cancelPnLBuilder.setReqId(reqId);
        return cancelPnLBuilder.build();
    }

    public static CancelPnLSingleProto.CancelPnLSingle createCancelPnLSingleProto(int reqId) {
        CancelPnLSingleProto.CancelPnLSingle.Builder cancelPnLSingleBuilder = CancelPnLSingleProto.CancelPnLSingle.newBuilder();
        if (Util.isValidValue(reqId)) cancelPnLSingleBuilder.setReqId(reqId);
        return cancelPnLSingleBuilder.build();
    }

    public static FARequestProto.FARequest createFARequestProto(int faDataType) {
        FARequestProto.FARequest.Builder faRequestBuilder = FARequestProto.FARequest.newBuilder();
        if (Util.isValidValue(faDataType)) faRequestBuilder.setFaDataType(faDataType);
        return faRequestBuilder.build();
    }

    public static FAReplaceProto.FAReplace createFAReplaceProto(int reqId, int faDataType, String xml) {
        FAReplaceProto.FAReplace.Builder faReplaceBuilder = FAReplaceProto.FAReplace.newBuilder();
        if (Util.isValidValue(reqId)) faReplaceBuilder.setReqId(reqId);
        if (Util.isValidValue(faDataType)) faReplaceBuilder.setFaDataType(faDataType);
        if (!Util.StringIsEmpty(xml)) faReplaceBuilder.setXml(xml);
        return faReplaceBuilder.build();
    }

    public static ExerciseOptionsRequestProto.ExerciseOptionsRequest createExerciseOptionsRequestProto(int orderId, Contract contract, int exerciseAction, int exerciseQuantity, 
            String account, boolean override, String manualOrderTime, String customerAccount, boolean professionalCustomer) {
        ExerciseOptionsRequestProto.ExerciseOptionsRequest.Builder exerciseOptionsRequestBuilder = ExerciseOptionsRequestProto.ExerciseOptionsRequest.newBuilder();
        if (Util.isValidValue(orderId)) exerciseOptionsRequestBuilder.setOrderId(orderId);
        ContractProto.Contract contractProto = createContractProto(contract, null);
        if (contractProto != null) exerciseOptionsRequestBuilder.setContract(contractProto);
        if (Util.isValidValue(exerciseAction)) exerciseOptionsRequestBuilder.setExerciseAction(exerciseAction);
        if (Util.isValidValue(exerciseQuantity)) exerciseOptionsRequestBuilder.setExerciseQuantity(exerciseQuantity);
        if (!Util.StringIsEmpty(account)) exerciseOptionsRequestBuilder.setAccount(account);
        if (override) exerciseOptionsRequestBuilder.setOverride(override);
        if (!Util.StringIsEmpty(manualOrderTime)) exerciseOptionsRequestBuilder.setManualOrderTime(manualOrderTime);
        if (!Util.StringIsEmpty(customerAccount)) exerciseOptionsRequestBuilder.setCustomerAccount(customerAccount);
        if (professionalCustomer) exerciseOptionsRequestBuilder.setProfessionalCustomer(professionalCustomer);
        return exerciseOptionsRequestBuilder.build();
    }

    public static CalculateImpliedVolatilityRequestProto.CalculateImpliedVolatilityRequest createCalculateImpliedVolatilityRequestProto(int reqId, Contract contract, double optionPrice, double underPrice, List<TagValue> impliedVolatilityOptionsList) {
        CalculateImpliedVolatilityRequestProto.CalculateImpliedVolatilityRequest.Builder calculateImpliedVolatilityRequestBuilder = CalculateImpliedVolatilityRequestProto.CalculateImpliedVolatilityRequest.newBuilder();
        if (Util.isValidValue(reqId)) calculateImpliedVolatilityRequestBuilder.setReqId(reqId);
        ContractProto.Contract contractProto = createContractProto(contract, null);
        if (contractProto != null) calculateImpliedVolatilityRequestBuilder.setContract(contractProto);
        if (Util.isValidValue(optionPrice)) calculateImpliedVolatilityRequestBuilder.setOptionPrice(optionPrice);
        if (Util.isValidValue(underPrice)) calculateImpliedVolatilityRequestBuilder.setUnderPrice(underPrice);
        if (impliedVolatilityOptionsList != null && !impliedVolatilityOptionsList.isEmpty()) {
            Map<String, String> impliedVolatilityOptions = impliedVolatilityOptionsList.stream().collect(Collectors.toMap(e -> e.m_tag, e -> e.m_value));
            calculateImpliedVolatilityRequestBuilder.putAllImpliedVolatilityOptions(impliedVolatilityOptions);
        }
        return calculateImpliedVolatilityRequestBuilder.build();
    }

    public static CancelCalculateImpliedVolatilityProto.CancelCalculateImpliedVolatility createCancelCalculateImpliedVolatilityProto(int reqId) {
        CancelCalculateImpliedVolatilityProto.CancelCalculateImpliedVolatility.Builder cancelCalculateImpliedVolatilityBuilder = CancelCalculateImpliedVolatilityProto.CancelCalculateImpliedVolatility.newBuilder();
        if (Util.isValidValue(reqId)) cancelCalculateImpliedVolatilityBuilder.setReqId(reqId);
        return cancelCalculateImpliedVolatilityBuilder.build();
    }

    public static CalculateOptionPriceRequestProto.CalculateOptionPriceRequest createCalculateOptionPriceRequestProto(int reqId, Contract contract, double volatility, double underPrice, List<TagValue> optionPriceOptionsList) {
        CalculateOptionPriceRequestProto.CalculateOptionPriceRequest.Builder calculateOptionPriceRequestBuilder = CalculateOptionPriceRequestProto.CalculateOptionPriceRequest.newBuilder();
        if (Util.isValidValue(reqId)) calculateOptionPriceRequestBuilder.setReqId(reqId);
        ContractProto.Contract contractProto = createContractProto(contract, null);
        if (contractProto != null) calculateOptionPriceRequestBuilder.setContract(contractProto);
        if (Util.isValidValue(volatility)) calculateOptionPriceRequestBuilder.setVolatility(volatility);
        if (Util.isValidValue(underPrice)) calculateOptionPriceRequestBuilder.setUnderPrice(underPrice);
        if (optionPriceOptionsList != null && !optionPriceOptionsList.isEmpty()) {
            Map<String, String> optionPriceOptions = optionPriceOptionsList.stream().collect(Collectors.toMap(e -> e.m_tag, e -> e.m_value));
            calculateOptionPriceRequestBuilder.putAllOptionPriceOptions(optionPriceOptions);
        }
        return calculateOptionPriceRequestBuilder.build();
    }

    public static CancelCalculateOptionPriceProto.CancelCalculateOptionPrice createCancelCalculateOptionPriceProto(int reqId) {
        CancelCalculateOptionPriceProto.CancelCalculateOptionPrice.Builder cancelCalculateOptionPriceBuilder = CancelCalculateOptionPriceProto.CancelCalculateOptionPrice.newBuilder();
        if (Util.isValidValue(reqId)) cancelCalculateOptionPriceBuilder.setReqId(reqId);
        return cancelCalculateOptionPriceBuilder.build();
    }

    public static SecDefOptParamsRequestProto.SecDefOptParamsRequest createSecDefOptParamsRequestProto(int reqId, String underlyingSymbol, String futFopExchange, String underlyingSecType, int underlyingConId) {
        SecDefOptParamsRequestProto.SecDefOptParamsRequest.Builder secDefOptParamsRequestBuilder = SecDefOptParamsRequestProto.SecDefOptParamsRequest.newBuilder();
        if (Util.isValidValue(reqId)) secDefOptParamsRequestBuilder.setReqId(reqId);
        if (!Util.StringIsEmpty(underlyingSymbol)) secDefOptParamsRequestBuilder.setUnderlyingSymbol(underlyingSymbol);
        if (!Util.StringIsEmpty(futFopExchange)) secDefOptParamsRequestBuilder.setFutFopExchange(futFopExchange);
        if (!Util.StringIsEmpty(underlyingSecType)) secDefOptParamsRequestBuilder.setUnderlyingSecType(underlyingSecType);
        if (Util.isValidValue(underlyingConId)) secDefOptParamsRequestBuilder.setUnderlyingConId(underlyingConId);
        return secDefOptParamsRequestBuilder.build();
    }

    public static SoftDollarTiersRequestProto.SoftDollarTiersRequest createSoftDollarTiersRequestProto(int reqId) {
        SoftDollarTiersRequestProto.SoftDollarTiersRequest.Builder softDollarTiersRequestBuilder = SoftDollarTiersRequestProto.SoftDollarTiersRequest.newBuilder();
        if (Util.isValidValue(reqId)) softDollarTiersRequestBuilder.setReqId(reqId);
        return softDollarTiersRequestBuilder.build();
    }

    public static FamilyCodesRequestProto.FamilyCodesRequest createFamilyCodesRequestProto() {
        FamilyCodesRequestProto.FamilyCodesRequest.Builder familyCodesRequestBuilder = FamilyCodesRequestProto.FamilyCodesRequest.newBuilder();
        return familyCodesRequestBuilder.build();
    }

    public static MatchingSymbolsRequestProto.MatchingSymbolsRequest createMatchingSymbolsRequestProto(int reqId, String pattern) {
        MatchingSymbolsRequestProto.MatchingSymbolsRequest.Builder matchingSymbolsRequestBuilder = MatchingSymbolsRequestProto.MatchingSymbolsRequest.newBuilder();
        if (Util.isValidValue(reqId)) matchingSymbolsRequestBuilder.setReqId(reqId);
        if (!Util.StringIsEmpty(pattern)) matchingSymbolsRequestBuilder.setPattern(pattern);
        return matchingSymbolsRequestBuilder.build();
    }

    public static SmartComponentsRequestProto.SmartComponentsRequest createSmartComponentsRequestProto(int reqId, String bboExchange) {
        SmartComponentsRequestProto.SmartComponentsRequest.Builder smartComponentsRequestBuilder = SmartComponentsRequestProto.SmartComponentsRequest.newBuilder();
        if (Util.isValidValue(reqId)) smartComponentsRequestBuilder.setReqId(reqId);
        if (!Util.StringIsEmpty(bboExchange)) smartComponentsRequestBuilder.setBboExchange(bboExchange);
        return smartComponentsRequestBuilder.build();
    }

    public static MarketRuleRequestProto.MarketRuleRequest createMarketRuleRequestProto(int marketRuleId) {
        MarketRuleRequestProto.MarketRuleRequest.Builder marketRuleRequestBuilder = MarketRuleRequestProto.MarketRuleRequest.newBuilder();
        if (Util.isValidValue(marketRuleId)) marketRuleRequestBuilder.setMarketRuleId(marketRuleId);
        return marketRuleRequestBuilder.build();
    }

    public static UserInfoRequestProto.UserInfoRequest createUserInfoRequestProto(int reqId) {
        UserInfoRequestProto.UserInfoRequest.Builder userInfoRequestBuilder = UserInfoRequestProto.UserInfoRequest.newBuilder();
        if (Util.isValidValue(reqId)) userInfoRequestBuilder.setReqId(reqId);
        return userInfoRequestBuilder.build();
    }

    public static IdsRequestProto.IdsRequest createIdsRequestProto(int numIds) {
        IdsRequestProto.IdsRequest.Builder idsRequestBuilder = IdsRequestProto.IdsRequest.newBuilder();
        if (Util.isValidValue(numIds)) idsRequestBuilder.setNumIds(numIds);
        return idsRequestBuilder.build();
    }

    public static CurrentTimeRequestProto.CurrentTimeRequest createCurrentTimeRequestProto() {
        CurrentTimeRequestProto.CurrentTimeRequest.Builder currentTimeRequestBuilder = CurrentTimeRequestProto.CurrentTimeRequest.newBuilder();
        return currentTimeRequestBuilder.build();
    }

    public static CurrentTimeInMillisRequestProto.CurrentTimeInMillisRequest createCurrentTimeInMillisRequestProto() {
        CurrentTimeInMillisRequestProto.CurrentTimeInMillisRequest.Builder currentTimeInMillisRequestBuilder = CurrentTimeInMillisRequestProto.CurrentTimeInMillisRequest.newBuilder();
        return currentTimeInMillisRequestBuilder.build();
    }

    public static StartApiRequestProto.StartApiRequest createStartApiRequestProto(int clientId, String optionalCapabilities) {
        StartApiRequestProto.StartApiRequest.Builder startApiRequestBuilder = StartApiRequestProto.StartApiRequest.newBuilder();
        if (Util.isValidValue(clientId)) startApiRequestBuilder.setClientId(clientId);
        if (!Util.StringIsEmpty(optionalCapabilities)) startApiRequestBuilder.setOptionalCapabilities(optionalCapabilities);
        return startApiRequestBuilder.build();
    }

    public static SetServerLogLevelRequestProto.SetServerLogLevelRequest createSetServerLogLevelRequestProto(int logLevel) {
        SetServerLogLevelRequestProto.SetServerLogLevelRequest.Builder setServerLogLevelRequestBuilder = SetServerLogLevelRequestProto.SetServerLogLevelRequest.newBuilder();
        if (Util.isValidValue(logLevel)) setServerLogLevelRequestBuilder.setLogLevel(logLevel);
        return setServerLogLevelRequestBuilder.build();
    }

    public static VerifyRequestProto.VerifyRequest createVerifyRequestProto(String apiName, String apiVersion) {
        VerifyRequestProto.VerifyRequest.Builder verifyRequestBuilder = VerifyRequestProto.VerifyRequest.newBuilder();
        if (!Util.StringIsEmpty(apiName)) verifyRequestBuilder.setApiName(apiName);
        if (!Util.StringIsEmpty(apiVersion)) verifyRequestBuilder.setApiVersion(apiVersion);
        return verifyRequestBuilder.build();
    }

    public static VerifyMessageRequestProto.VerifyMessageRequest createVerifyMessageRequestProto(String apiData) {
        VerifyMessageRequestProto.VerifyMessageRequest.Builder verifyMessageRequestBuilder = VerifyMessageRequestProto.VerifyMessageRequest.newBuilder();
        if (!Util.StringIsEmpty(apiData)) verifyMessageRequestBuilder.setApiData(apiData);
        return verifyMessageRequestBuilder.build();
    }

    public static QueryDisplayGroupsRequestProto.QueryDisplayGroupsRequest createQueryDisplayGroupsRequestProto(int reqId) {
        QueryDisplayGroupsRequestProto.QueryDisplayGroupsRequest.Builder queryDisplayGroupsRequestBuilder = QueryDisplayGroupsRequestProto.QueryDisplayGroupsRequest.newBuilder();
        if (Util.isValidValue(reqId)) queryDisplayGroupsRequestBuilder.setReqId(reqId);
        return queryDisplayGroupsRequestBuilder.build();
    }

    public static SubscribeToGroupEventsRequestProto.SubscribeToGroupEventsRequest createSubscribeToGroupEventsRequestProto(int reqId, int groupId) {
        SubscribeToGroupEventsRequestProto.SubscribeToGroupEventsRequest.Builder subscribeToGroupEventsRequestBuilder = SubscribeToGroupEventsRequestProto.SubscribeToGroupEventsRequest.newBuilder();
        if (Util.isValidValue(reqId)) subscribeToGroupEventsRequestBuilder.setReqId(reqId);
        if (Util.isValidValue(groupId)) subscribeToGroupEventsRequestBuilder.setGroupId(groupId);
        return subscribeToGroupEventsRequestBuilder.build();
    }

    public static UpdateDisplayGroupRequestProto.UpdateDisplayGroupRequest createUpdateDisplayGroupRequestProto(int reqId, String contractInfo) {
        UpdateDisplayGroupRequestProto.UpdateDisplayGroupRequest.Builder updateDisplayGroupRequestBuilder = UpdateDisplayGroupRequestProto.UpdateDisplayGroupRequest.newBuilder();
        if (Util.isValidValue(reqId)) updateDisplayGroupRequestBuilder.setReqId(reqId);
        if (!Util.StringIsEmpty(contractInfo)) updateDisplayGroupRequestBuilder.setContractInfo(contractInfo);
        return updateDisplayGroupRequestBuilder.build();
    }

    public static UnsubscribeFromGroupEventsRequestProto.UnsubscribeFromGroupEventsRequest createUnsubscribeFromGroupEventsRequestProto(int reqId) {
        UnsubscribeFromGroupEventsRequestProto.UnsubscribeFromGroupEventsRequest.Builder unsubscribeFromGroupEventsRequestBuilder = UnsubscribeFromGroupEventsRequestProto.UnsubscribeFromGroupEventsRequest.newBuilder();
        if (Util.isValidValue(reqId)) unsubscribeFromGroupEventsRequestBuilder.setReqId(reqId);
        return unsubscribeFromGroupEventsRequestBuilder.build();
    }

    public static MarketDepthExchangesRequestProto.MarketDepthExchangesRequest createMarketDepthExchangesRequestProto() {
        MarketDepthExchangesRequestProto.MarketDepthExchangesRequest.Builder marketDepthExchangesRequestBuilder = MarketDepthExchangesRequestProto.MarketDepthExchangesRequest.newBuilder();
        return marketDepthExchangesRequestBuilder.build();
    }

    public static CancelContractDataProto.CancelContractData createCancelContractDataProto(int reqId) {
        CancelContractDataProto.CancelContractData.Builder builder = CancelContractDataProto.CancelContractData.newBuilder();
        builder.setReqId(reqId);
        return builder.build();
    }

    public static CancelHistoricalTicksProto.CancelHistoricalTicks createCancelHistoricalTicksProto(int reqId) {
        CancelHistoricalTicksProto.CancelHistoricalTicks.Builder builder = CancelHistoricalTicksProto.CancelHistoricalTicks.newBuilder();
        builder.setReqId(reqId);
        return builder.build();
    }
}
