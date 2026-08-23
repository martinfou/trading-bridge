/*
 * Java TWS API Client
 *
 * Copyright (C) 2013-2026 Interactive Brokers LLC
 */

package com.ib.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

public abstract class EClient {

    public static final int CLIENT_VERSION = 66;
    public static final int MIN_SERVER_VER_SUPPORTED = 38;
    public static final int MIN_SERVER_VER_REAL_TIME_BARS = 34;
    public static final int MIN_SERVER_VER_SCALE_ORDERS = 35;
    public static final int MIN_SERVER_VER_SNAPSHOT_MKT_DATA = 35;
    public static final int MIN_SERVER_VER_SSHORTX_OLD = 36;
    public static final int MIN_SERVER_VER_SSHORTX = 37;
    public static final int MIN_SERVER_VER_REQ_HEAD_TIMESTAMP = 41;
    public static final int MIN_SERVER_VER_LINKING = 70;
    public static final int MIN_SERVER_VER_TRADING_CLASS = 71;
    public static final int MIN_SERVER_VER_REQ_MKT_DATA_CONID = 75;
    public static final int MIN_SERVER_VER_DELTA_NEUTRAL = 40;
    public static final int MIN_SERVER_VER_REQ_SMART_COMPONENTS = 114;
    public static final int MIN_SERVER_VER_SCANNER_GENERIC_OPTS = 115;
    public static final int MIN_SERVER_VER_ORDER_CONTAINER = 145;
    public static final int MIN_SERVER_VER_SMART_DEPTH = 146;
    public static final int MIN_SERVER_VER_REMOVE_ORDER_CONTAINER = 147;
    public static final int MIN_SERVER_VER_AUTO_CANCEL_PARENT = 148;
    public static final int MIN_SERVER_VER_MANUAL_ORDER_TIME = 158;
    public static final int MIN_SERVER_VER_CME_TAGGING_FIELDS = 192;
    public static final int MIN_SERVER_VER_PROTOBUF = 201;

    public static final int MIN_VERSION = 100;
    public static final int MAX_VERSION = 222;

    protected EReaderSignal m_signal;
    protected EWrapper m_eWrapper;
    protected int m_serverVersion;
    protected String m_TwsTime = "";
    protected int m_clientId;
    protected boolean m_extraAuth;
    protected boolean m_useV100Plus = true;
    private String m_optionalCapabilities = "";
    private String m_connectOptions = "";
    protected String m_host;
    protected ETransport m_socketTransport;

    public boolean isUseV100Plus() {
        return m_useV100Plus;
    }

    public int serverVersion() {
        return m_serverVersion;
    }

    public String getTwsConnectionTime() {
        return m_TwsTime;
    }

    public EWrapper wrapper() {
        return m_eWrapper;
    }

    public abstract boolean isConnected();

    protected synchronized void setExtraAuth(boolean extraAuth) {
        m_extraAuth = extraAuth;
    }

    public void optionalCapabilities(String val) {
        m_optionalCapabilities = val;
    }

    public String optionalCapabilities() {
        return m_optionalCapabilities;
    }

    public EClient(EWrapper eWrapper, EReaderSignal signal) {
        m_eWrapper = eWrapper;
        m_signal = signal;
        m_clientId = -1;
        m_extraAuth = false;
        m_optionalCapabilities = "";
        m_serverVersion = 0;
    }

    protected void sendConnectRequest() throws IOException {
        if (!m_useV100Plus || m_connectOptions == null) {
            send(CLIENT_VERSION);
        } else {
            sendV100APIHeader();
        }
    }

    public void disableUseV100Plus() {
        if (isConnected()) {
            m_eWrapper.error(EClientErrors.NO_VALID_ID, Util.currentTimeMillis(),
                EClientErrors.ALREADY_CONNECTED.code(), EClientErrors.ALREADY_CONNECTED.msg(), null);
            return;
        }
        m_connectOptions = "";
        m_useV100Plus = false;
    }

    public void setConnectOptions(String options) {
        if (isConnected()) {
            m_eWrapper.error(EClientErrors.NO_VALID_ID, Util.currentTimeMillis(),
                EClientErrors.ALREADY_CONNECTED.code(), EClientErrors.ALREADY_CONNECTED.msg(), null);
            return;
        }
        m_connectOptions = options;
    }

    protected void connectionError() {
        m_eWrapper.error(EClientErrors.NO_VALID_ID, Util.currentTimeMillis(),
            EClientErrors.CONNECT_FAIL.code(), EClientErrors.CONNECT_FAIL.msg(), null);
    }

    protected String checkConnected(String host) {
        if (isConnected()) {
            m_eWrapper.error(EClientErrors.NO_VALID_ID, Util.currentTimeMillis(),
                EClientErrors.ALREADY_CONNECTED.code(), EClientErrors.ALREADY_CONNECTED.msg(), null);
            return null;
        }
        return host != null ? host : "127.0.0.1";
    }

    public abstract void eDisconnect();

    public synchronized void startAPI() {
        if (!isConnected()) {
            notConnected();
            return;
        }
        final int VERSION = 2;
        try {
            Builder b = prepareBuffer();
            sendMsgId(b, OutgoingMsgId.START_API);
            b.send(VERSION);
            b.send(m_clientId);
            if (m_serverVersion >= MIN_SERVER_VER_OPTIONAL_CAPABILITIES) {
                b.send(m_optionalCapabilities);
            }
            closeAndSend(b);
        } catch (Exception e) {
            error(EClientErrors.NO_VALID_ID, EClientErrors.FAIL_SEND_STARTAPI, e.toString());
            close();
        }
    }

    public synchronized void reqHistoricalData(int tickerId, Contract contract,
                                               String endDateTime, String durationStr,
                                               String barSizeSetting, String whatToShow,
                                               int useRTH, int formatDate, boolean keepUpToDate,
                                               List<TagValue> chartOptions) {
        if (!isConnected()) {
            notConnected();
            return;
        }
        final int VERSION = 6;
        try {
            Builder b = prepareBuffer();
            sendMsgId(b, OutgoingMsgId.REQ_HISTORICAL_DATA);
            if (m_serverVersion < MIN_SERVER_VER_SYNT_REALTIME_BARS) {
                b.send(VERSION);
            }
            b.send(tickerId);
            if (m_serverVersion >= MIN_SERVER_VER_REQ_MKT_DATA_CONID) {
                b.send(contract.conid());
            }
            b.send(contract.symbol());
            b.send(contract.getSecType());
            b.send(contract.lastTradeDateOrContractMonth());
            b.send(contract.strike());
            b.send(contract.getRight());
            b.send(contract.multiplier());
            b.send(contract.exchange());
            b.send(contract.primaryExch());
            b.send(contract.currency());
            b.send(contract.localSymbol());
            if (m_serverVersion >= MIN_SERVER_VER_TRADING_CLASS) {
                b.send(contract.tradingClass());
            }
            b.send(contract.includeExpired() ? 1 : 0);
            b.send(endDateTime);
            b.send(barSizeSetting);
            b.send(durationStr);
            b.send(useRTH);
            b.send(whatToShow);
            b.send(formatDate);
            b.send(keepUpToDate);
            if (chartOptions != null) {
                b.send(chartOptions);
            }
            closeAndSend(b);
        } catch (Exception e) {
            error(tickerId, EClientErrors.FAIL_SEND_REQHISTDATA, e.toString());
            close();
        }
    }

    public synchronized void cancelHistoricalData(int tickerId) {
        if (!isConnected()) {
            notConnected();
            return;
        }
        final int VERSION = 1;
        try {
            Builder b = prepareBuffer();
            sendMsgId(b, OutgoingMsgId.CANCEL_HISTORICAL_DATA);
            b.send(VERSION);
            b.send(tickerId);
            closeAndSend(b);
        } catch (Exception e) {
            error(tickerId, EClientErrors.FAIL_SEND_CANHISTDATA, e.toString());
            close();
        }
    }

    public synchronized void reqCurrentTime() {
        if (!isConnected()) {
            notConnected();
            return;
        }
        final int VERSION = 1;
        try {
            Builder b = prepareBuffer();
            sendMsgId(b, OutgoingMsgId.REQ_CURRENT_TIME);
            b.send(VERSION);
            closeAndSend(b);
        } catch (Exception e) {
            error(EClientErrors.NO_VALID_ID, EClientErrors.FAIL_SEND_REQCURRTIME, e.toString());
            close();
        }
    }

    public synchronized void reqPositions() {
        if (!isConnected()) {
            notConnected();
            return;
        }
        final int VERSION = 1;
        try {
            Builder b = prepareBuffer();
            sendMsgId(b, OutgoingMsgId.REQ_POSITIONS);
            b.send(VERSION);
            closeAndSend(b);
        } catch (Exception e) {
            error(EClientErrors.NO_VALID_ID, EClientErrors.FAIL_SEND_REQPOSITIONS, e.toString());
            close();
        }
    }

    public synchronized void cancelPositions() {
        if (!isConnected()) {
            notConnected();
            return;
        }
        final int VERSION = 1;
        try {
            Builder b = prepareBuffer();
            sendMsgId(b, OutgoingMsgId.CANCEL_POSITIONS);
            b.send(VERSION);
            closeAndSend(b);
        } catch (Exception e) {
            error(EClientErrors.NO_VALID_ID, EClientErrors.FAIL_SEND_CANPOSITIONS, e.toString());
            close();
        }
    }

    public synchronized void reqOpenOrders() {
        if (!isConnected()) {
            notConnected();
            return;
        }
        final int VERSION = 1;
        try {
            Builder b = prepareBuffer();
            sendMsgId(b, OutgoingMsgId.REQ_OPEN_ORDERS);
            b.send(VERSION);
            closeAndSend(b);
        } catch (Exception e) {
            error(EClientErrors.NO_VALID_ID, EClientErrors.FAIL_SEND_REQOPENORDERS, e.toString());
            close();
        }
    }

    public synchronized void reqAllOpenOrders() {
        if (!isConnected()) {
            notConnected();
            return;
        }
        final int VERSION = 1;
        try {
            Builder b = prepareBuffer();
            sendMsgId(b, OutgoingMsgId.REQ_ALL_OPEN_ORDERS);
            b.send(VERSION);
            closeAndSend(b);
        } catch (Exception e) {
            error(EClientErrors.NO_VALID_ID, EClientErrors.FAIL_SEND_REQALLOPENORDERS, e.toString());
            close();
        }
    }

    public synchronized void reqAutoOpenOrders(boolean bAutoBind) {
        if (!isConnected()) {
            notConnected();
            return;
        }
        final int VERSION = 1;
        try {
            Builder b = prepareBuffer();
            sendMsgId(b, OutgoingMsgId.REQ_AUTO_OPEN_ORDERS);
            b.send(VERSION);
            b.send(bAutoBind);
            closeAndSend(b);
        } catch (Exception e) {
            error(EClientErrors.NO_VALID_ID, EClientErrors.FAIL_SEND_REQAUTOOPENORDERS, e.toString());
            close();
        }
    }

    public synchronized void reqIds(int numIds) {
        if (!isConnected()) {
            notConnected();
            return;
        }
        final int VERSION = 1;
        try {
            Builder b = prepareBuffer();
            sendMsgId(b, OutgoingMsgId.REQ_IDS);
            b.send(VERSION);
            b.send(numIds);
            closeAndSend(b);
        } catch (Exception e) {
            error(EClientErrors.NO_VALID_ID, EClientErrors.FAIL_SEND_REQIDS, e.toString());
            close();
        }
    }

    public synchronized void reqManagedAccts() {
        if (!isConnected()) {
            notConnected();
            return;
        }
        final int VERSION = 1;
        try {
            Builder b = prepareBuffer();
            sendMsgId(b, OutgoingMsgId.REQ_MANAGED_ACCTS);
            b.send(VERSION);
            closeAndSend(b);
        } catch (Exception e) {
            error(EClientErrors.NO_VALID_ID, EClientErrors.FAIL_SEND_REQACCOUNTDATA, e.toString());
            close();
        }
    }

    public synchronized void placeOrder(int id, Contract contract, Order order) {
        if (!isConnected()) {
            notConnected();
            return;
        }
        try {
            Builder b = prepareBuffer();
            sendMsgId(b, OutgoingMsgId.PLACE_ORDER);
            b.send(id);
            b.send(contract.conid());
            b.send(contract.symbol());
            b.send(contract.getSecType());
            b.send(contract.lastTradeDateOrContractMonth());
            b.send(contract.strike());
            b.send(contract.getRight());
            b.send(contract.multiplier());
            b.send(contract.exchange());
            b.send(contract.primaryExch());
            b.send(contract.currency());
            b.send(contract.localSymbol());
            b.send(contract.tradingClass());
            b.send(order.getAction());
            b.send(order.totalQuantity());
            b.send(order.getOrderType());
            b.send(order.lmtPrice());
            b.send(order.auxPrice());
            b.send(order.getTif());
            b.send(order.ocaGroup());
            b.send(order.account());
            b.send(order.openClose());
            b.send(order.origin());
            b.send(order.orderRef());
            b.send(order.transmit());
            b.send(order.parentId());
            b.send(order.blockOrder());
            b.send(order.sweepToFill());
            b.send(order.displaySize());
            b.send(order.triggerMethod());
            b.send(order.outsideRth());
            b.send(order.hidden());
            closeAndSend(b);
        } catch (Exception e) {
            error(id, EClientErrors.FAIL_SEND_ORDER, e.toString());
            close();
        }
    }

    public synchronized void cancelOrder(int id, OrderCancel orderCancel) {
        if (!isConnected()) {
            notConnected();
            return;
        }
        final int VERSION = 1;
        try {
            Builder b = prepareBuffer();
            sendMsgId(b, OutgoingMsgId.CANCEL_ORDER);
            b.send(VERSION);
            b.send(id);
            if (orderCancel != null) {
                b.send(orderCancel.manualOrderCancelTime());
            }
            closeAndSend(b);
        } catch (Exception e) {
            error(id, EClientErrors.FAIL_SEND_CORDER, e.toString());
            close();
        }
    }

    public synchronized void reqMktData(int tickerId, Contract contract,
                                        String genericTickList, boolean snapshot,
                                        boolean regulatorySnapshot, List<TagValue> mktDataOptions) {
        if (!isConnected()) {
            notConnected();
            return;
        }
        final int VERSION = 11;
        try {
            Builder b = prepareBuffer();
            sendMsgId(b, OutgoingMsgId.REQ_MKT_DATA);
            b.send(VERSION);
            b.send(tickerId);
            b.send(contract.conid());
            b.send(contract.symbol());
            b.send(contract.getSecType());
            b.send(contract.lastTradeDateOrContractMonth());
            b.send(contract.strike());
            b.send(contract.getRight());
            b.send(contract.multiplier());
            b.send(contract.exchange());
            b.send(contract.primaryExch());
            b.send(contract.currency());
            b.send(contract.localSymbol());
            b.send(contract.tradingClass());
            b.send(genericTickList);
            b.send(snapshot);
            b.send(regulatorySnapshot);
            if (mktDataOptions != null) {
                b.send(mktDataOptions);
            }
            closeAndSend(b);
        } catch (Exception e) {
            error(tickerId, EClientErrors.FAIL_SEND_REQMKT, e.toString());
            close();
        }
    }

    public synchronized void cancelMktData(int tickerId) {
        if (!isConnected()) {
            notConnected();
            return;
        }
        final int VERSION = 1;
        try {
            Builder b = prepareBuffer();
            sendMsgId(b, OutgoingMsgId.CANCEL_MKT_DATA);
            b.send(VERSION);
            b.send(tickerId);
            closeAndSend(b);
        } catch (Exception e) {
            error(tickerId, EClientErrors.FAIL_SEND_CANMKT, e.toString());
            close();
        }
    }

    public synchronized void reqRealTimeBars(int tickerId, Contract contract, int barSize,
                                             String whatToShow, boolean useRTH,
                                             List<TagValue> realTimeBarsOptions) {
        if (!isConnected()) {
            notConnected();
            return;
        }
        final int VERSION = 3;
        try {
            Builder b = prepareBuffer();
            sendMsgId(b, OutgoingMsgId.REQ_REAL_TIME_BARS);
            b.send(VERSION);
            b.send(tickerId);
            b.send(contract.conid());
            b.send(contract.symbol());
            b.send(contract.getSecType());
            b.send(contract.lastTradeDateOrContractMonth());
            b.send(contract.strike());
            b.send(contract.getRight());
            b.send(contract.multiplier());
            b.send(contract.exchange());
            b.send(contract.primaryExch());
            b.send(contract.currency());
            b.send(contract.localSymbol());
            b.send(contract.tradingClass());
            b.send(barSize);
            b.send(whatToShow);
            b.send(useRTH);
            if (realTimeBarsOptions != null) {
                b.send(realTimeBarsOptions);
            }
            closeAndSend(b);
        } catch (Exception e) {
            error(tickerId, EClientErrors.FAIL_SEND_REQRTBARS, e.toString());
            close();
        }
    }

    public synchronized void cancelRealTimeBars(int tickerId) {
        if (!isConnected()) {
            notConnected();
            return;
        }
        final int VERSION = 1;
        try {
            Builder b = prepareBuffer();
            sendMsgId(b, OutgoingMsgId.CANCEL_REAL_TIME_BARS);
            b.send(VERSION);
            b.send(tickerId);
            closeAndSend(b);
        } catch (Exception e) {
            error(tickerId, EClientErrors.FAIL_SEND_CANRTBARS, e.toString());
            close();
        }
    }

    public synchronized void reqContractDetails(int reqId, Contract contract) {
        if (!isConnected()) {
            notConnected();
            return;
        }
        final int VERSION = 8;
        try {
            Builder b = prepareBuffer();
            sendMsgId(b, OutgoingMsgId.REQ_CONTRACT_DATA);
            b.send(VERSION);
            b.send(reqId);
            b.send(contract.conid());
            b.send(contract.symbol());
            b.send(contract.getSecType());
            b.send(contract.lastTradeDateOrContractMonth());
            b.send(contract.strike());
            b.send(contract.getRight());
            b.send(contract.multiplier());
            b.send(contract.exchange());
            b.send(contract.primaryExch());
            b.send(contract.currency());
            b.send(contract.localSymbol());
            b.send(contract.tradingClass());
            b.send(contract.includeExpired() ? 1 : 0);
            closeAndSend(b);
        } catch (Exception e) {
            error(reqId, EClientErrors.FAIL_SEND_REQCONTRACTDETAILS, e.toString());
            close();
        }
    }

    public synchronized void serverVersion(int version, String time) {
        m_serverVersion = version;
        m_TwsTime = time;
    }

    protected synchronized void error(String err) {
        m_eWrapper.error(err);
    }

    protected synchronized void error(int id, int errorCode, String errorMsg) {
        m_eWrapper.error(id, Util.currentTimeMillis(), errorCode, errorMsg, null);
    }

    protected void close() {
        eDisconnect();
    }

    protected void error(int id, EClientErrors.CodeMsgPair pair, String tail) {
        m_eWrapper.error(id, Util.currentTimeMillis(), pair.code(), pair.msg() + tail, null);
    }

    protected abstract Builder prepareBuffer();

    protected abstract void closeAndSend(Builder buf) throws IOException;

    protected void validateInvalidSymbols(String host) throws EClientException {
        if (host != null && host.contains("..")) {
            throw new EClientException(EClientErrors.INVALID_SYMBOL, host);
        }
    }

    private void sendV100APIHeader() throws IOException {
        String v100Prefix = "API\0";
        String v100Version = buildVersionString(MIN_VERSION, MAX_VERSION);
        Builder b = new Builder(1024);
        b.send(v100Prefix.getBytes(StandardCharsets.US_ASCII));
        b.send(v100Version);
        if (m_connectOptions != null && !m_connectOptions.isEmpty()) {
            b.send(m_connectOptions);
        }
        closeAndSend(b);
    }

    private String buildVersionString(int minVersion, int maxVersion) {
        return "v" + (minVersion < maxVersion ? minVersion + ".." + maxVersion : String.valueOf(minVersion));
    }

    protected void sendMsg(EMessage msg) throws IOException {
        m_socketTransport.send(msg);
    }

    protected void notConnected() {
        error(EClientErrors.NO_VALID_ID, EClientErrors.NOT_CONNECTED, "");
    }

    public String connectedHost() {
        return m_host;
    }

    protected void send(int val) throws IOException {
        send(String.valueOf(val));
    }

    protected void send(String str) throws IOException {
        Builder b = prepareBuffer();
        b.send(str);
        closeAndSend(b);
    }

    private void sendMsgId(Builder b, OutgoingMsgId msgId) throws IOException {
        sendMsgId(b, msgId.id());
    }

    private void sendMsgId(Builder b, int msgId) throws IOException {
        b.send(msgId);
    }

    private static final int MIN_SERVER_VER_OPTIONAL_CAPABILITIES = 72;
    private static final int MIN_SERVER_VER_SYNT_REALTIME_BARS = 135;
}
