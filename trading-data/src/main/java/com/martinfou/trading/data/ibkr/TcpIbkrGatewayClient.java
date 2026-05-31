package com.martinfou.trading.data.ibkr;

import com.martinfou.trading.core.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

/**
 * Validates TCP reachability of TWS / IB Gateway, then delegates to an in-process session stub.
 * Full order transmission via official TwsApi.jar is a follow-on integration step.
 */
public final class TcpIbkrGatewayClient implements IbkrGatewayClient {

    private static final Logger log = LoggerFactory.getLogger(TcpIbkrGatewayClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    private final IbkrConnectionConfig config;
    private final StubIbkrGatewayClient session = new StubIbkrGatewayClient();
    private volatile boolean gatewayReachable;

    public TcpIbkrGatewayClient(IbkrConnectionConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config is required");
        }
        this.config = config;
    }

    @Override
    public void connect() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(config.host(), config.port()), (int) CONNECT_TIMEOUT.toMillis());
            gatewayReachable = true;
            session.connect();
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
        session.disconnect();
    }

    @Override
    public boolean isConnected() {
        return gatewayReachable && session.isConnected();
    }

    @Override
    public IbkrMarketOrderResult placeMarketOrder(String symbol, double quantity, Order.Side side, String clientTag) {
        return session.placeMarketOrder(symbol, quantity, side, clientTag);
    }

    @Override
    public IbkrAccountSnapshot fetchAccountSummary() {
        return session.fetchAccountSummary();
    }

    @Override
    public java.util.List<IbkrPositionSnapshot> fetchOpenPositions() {
        return session.fetchOpenPositions();
    }

    private static String maskAccount(String accountId) {
        if (accountId == null || accountId.length() <= 4) {
            return "****";
        }
        return "****" + accountId.substring(accountId.length() - 4);
    }
}
