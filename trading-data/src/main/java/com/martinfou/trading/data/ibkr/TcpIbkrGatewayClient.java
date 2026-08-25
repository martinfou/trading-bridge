package com.martinfou.trading.data.ibkr;

import com.martinfou.trading.core.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

/**
 * Validates TCP reachability of TWS / IB Gateway.
 *
 * <p>NOTE (audit P0): order placement and account/position retrieval are <b>not yet migrated</b>
 * to the official {@code com.ib.client} TWS API (v1045.01, restored in trading-data on 2026-08-23).
 * A prior implementation silently delegated these calls to an in-memory {@link StubIbkrGatewayClient},
 * which fabricated fills, positions and a $100k account — a live-trading hazard. These methods now
 * fail loudly instead of pretending to trade. Use the stub only for unit tests.</p>
 */
public final class TcpIbkrGatewayClient implements IbkrGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(TcpIbkrGatewayClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    private final IbkrConnectionConfig config;
    private volatile boolean gatewayReachable;
    private volatile Socket socket;

    public TcpIbkrGatewayClient(IbkrConnectionConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        this.config = config;
    }

    @Override
    public void connect() {
        try {
            this.socket = new Socket();
            this.socket.connect(new InetSocketAddress(config.host(), config.port()), (int) CONNECT_TIMEOUT.toMillis());
            gatewayReachable = true;
            log.info("IB Gateway reachable at {}:{} (clientId={}, account={})",
                config.host(), config.port(), config.clientId(), maskAccount(config.accountId()));
        } catch (IOException e) {
            throw new IllegalStateException(
                "Failed to connect to IB Gateway at " + config.host() + ":" + config.port(), e);
        }
    }

    @Override
    public void disconnect() {
        gatewayReachable = false;
        Socket s = this.socket;
        this.socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException e) {
                log.warn("Failed to close IB Gateway socket", e);
            }
        }
    }

    @Override
    public boolean isConnected() {
        return gatewayReachable;
    }

    @Override
    public IbkrMarketOrderResult placeMarketOrder(String symbol, double quantity, Order.Side side, String clientTag) {
        throw new UnsupportedOperationException(
            "IBKR live order placement is not implemented: TcpIbkrGatewayClient only validates TCP "
            + "reachability. Migrate to com.ib.client EClientSocket (reqIds/placeOrder + EWrapper "
            + "openOrder/orderStatus/execDetails) before trading futures on IBKR.");
    }

    @Override
    public IbkrAccountSnapshot fetchAccountSummary() {
        throw new UnsupportedOperationException(
            "IBKR account summary is not implemented: migrate to com.ib.client reqAccountUpdates. "
            + "Refusing to fabricate account state.");
    }

    @Override
    public java.util.List<IbkrPositionSnapshot> fetchOpenPositions() {
        throw new UnsupportedOperationException(
            "IBKR position retrieval is not implemented: migrate to com.ib.client reqPositions. "
            + "Refusing to fabricate positions.");
    }

    @Override
    public void addExecutionListener(java.util.function.Consumer<com.martinfou.trading.data.ibkr.IbkrExecution> listener) {
        // No executions can arrive: order placement is not implemented (see placeMarketOrder).
        // A no-op listener registry is safe; the broker will receive no fabricated fills.
        if (listener == null) {
            throw new IllegalArgumentException("listener is required");
        }
    }

    private static String maskAccount(String accountId) {
        if (accountId == null || accountId.length() <= 4) {
            return "****";
        }
        return "****" + accountId.substring(accountId.length() - 4);
    }
}
