package com.martinfou.trading.examples;

import com.martinfou.trading.broker.Broker;
import com.martinfou.trading.broker.BrokerEventType;
import com.martinfou.trading.broker.IbkrBroker;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Position;
import com.martinfou.trading.data.ibkr.IbkrConnectionConfig;
import com.martinfou.trading.data.ibkr.TcpIbkrGatewayClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SMOKE TEST PAPER IBKR — Micro Gold Futures (MGC) on COMEX.
 *
 * <p>Places 1 MGC contract in MARKET BUY on the PAPER account, awaits fill, verifies
 * position, then flattens (kill switch) and disconnects. Strictly paper on port 4002 or 7497.</p>
 */
public final class PaperMgcSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(PaperMgcSmokeTest.class);

    private PaperMgcSmokeTest() {}

    public static void main(String[] args) throws Exception {
        String account = System.getenv().getOrDefault("IBKR_ACCOUNT_ID", "DUR967322");
        int port = Integer.parseInt(System.getenv().getOrDefault("IBKR_GATEWAY_PORT", "4002"));

        if (port == 7496 || port == 4001) {
            log.error("Refus: port {} = LIVE. Paper uniquement (7497 / 4002).", port);
            System.exit(2);
        }
        String host = System.getenv().getOrDefault("IBKR_GATEWAY_HOST", "127.0.0.1");
        int clientId = Integer.parseInt(System.getenv().getOrDefault("IBKR_CLIENT_ID", "22"));

        IbkrConnectionConfig config = new IbkrConnectionConfig(host, port, clientId, account);
        try (Broker broker = new IbkrBroker(new TcpIbkrGatewayClient(config))) {
            CountDownLatch fillLatch = new CountDownLatch(1);
            AtomicInteger fills = new AtomicInteger(0);
            broker.addEventListener(event -> {
                if (event.type() == BrokerEventType.FILL) {
                    log.info("FILL reçu: {}", event);
                    fills.incrementAndGet();
                    fillLatch.countDown();
                }
            });

            log.info("Connecting Gateway paper {}:{} account={}", host, port, account);
            broker.connect();
            log.info("Connected. Account: {}", broker.getAccountState());

            // Micro Gold (MGC) contract - ZERO Martingale: strictly 1 contract
            Order order = new Order("MGC", Order.Side.BUY, Order.Type.MARKET, 1.0, 0.0)
                .withStrategyId("sq-gold-m30");
            log.info("Placing 1 MGC MARKET BUY (strategyId=sq-gold-m30, zero martingale)...");
            var result = broker.submitOrder(order);
            log.info("submitOrder -> accepted={} orderId={} reason={}",
                result.accepted(), result.brokerOrderId(), result.rejectReason());

            boolean filled = fillLatch.await(15, TimeUnit.SECONDS);
            log.info("Fill attendu: {}", filled ? "OUI" : "TIMEOUT (pas de fill en 15s)");

            Thread.sleep(Duration.ofSeconds(2).toMillis());
            List<Position> positions = broker.getPositions();
            log.info("Positions ouvertes: {}", positions);

            // Kill switch paper: flatten toutes les positions, puis cancel.
            log.info("Kill switch: flatten...");
            int flattened = broker.flattenAllPositions();
            log.info("flatten -> {} position(s) fermée(s)", flattened);

            log.info("Résultat smoke test MGC: fills={}, flattened={}", fills.get(), flattened);
            if (fills.get() > 0 && flattened > 0) {
                log.info("✅ SMOKE TEST PAPER MGC RÉUSSI");
            } else {
                log.warn("⚠️ Smoke test partiel — fills={}, flattened={}", fills.get(), flattened);
            }
        }
    }
}
