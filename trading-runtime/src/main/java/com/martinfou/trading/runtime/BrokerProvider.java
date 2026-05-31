package com.martinfou.trading.runtime;

import com.martinfou.trading.broker.Broker;
import com.martinfou.trading.broker.BrokerCredentials;
import com.martinfou.trading.broker.FakeBroker;
import com.martinfou.trading.broker.IbkrBroker;
import com.martinfou.trading.broker.OandaBroker;
import com.martinfou.trading.data.ibkr.IbkrConnectionConfig;
import com.martinfou.trading.data.ibkr.IbkrGatewayClient;
import com.martinfou.trading.data.ibkr.StubIbkrGatewayClient;
import com.martinfou.trading.data.ibkr.TcpIbkrGatewayClient;
import com.martinfou.trading.data.oanda.HttpOandaRestClient;
import com.martinfou.trading.data.oanda.OandaRestClient;

import java.util.Optional;

/**
 * Runtime composition for broker access — depends on {@link Broker} interface only (Story 16.2–16.3).
 */
public final class BrokerProvider {

    private BrokerProvider() {}

    /** Dev/test broker — not connected to any external venue. */
    public static Broker fakeBroker(double initialBalance) {
        return new FakeBroker(initialBalance);
    }

    /** OANDA demo/live broker when credentials are present in environment. */
    public static Optional<Broker> oandaBrokerFromEnvironment() {
        return BrokerCredentials.oandaFromEnvironment().map(BrokerProvider::oandaBroker);
    }

    public static Broker oandaBroker(BrokerCredentials credentials) {
        String baseUrl = credentials.restUrl();
        if (!baseUrl.endsWith("/v3/")) {
            baseUrl = baseUrl.replaceAll("/$", "") + "/v3/";
        }
        OandaRestClient client = new HttpOandaRestClient(
            credentials.apiToken(),
            credentials.accountId(),
            baseUrl);
        return new OandaBroker(client);
    }

    /** Test hook — inject a stub {@link OandaRestClient}. */
    static Broker oandaBroker(OandaRestClient client) {
        return new OandaBroker(client);
    }

    public static Broker ibkrBroker(IbkrConnectionConfig config) {
        IbkrGatewayClient client = useIbkrStub()
            ? new StubIbkrGatewayClient()
            : new TcpIbkrGatewayClient(config);
        return new IbkrBroker(client);
    }

    /** Test hook — inject a stub {@link IbkrGatewayClient}. */
    static Broker ibkrBroker(IbkrGatewayClient client) {
        return new IbkrBroker(client);
    }

    private static boolean useIbkrStub() {
        return "true".equalsIgnoreCase(System.getenv("IBKR_USE_STUB"));
    }
}
