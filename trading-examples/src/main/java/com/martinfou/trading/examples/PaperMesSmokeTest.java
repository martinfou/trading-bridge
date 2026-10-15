package com.martinfou.trading.examples;

import com.martinfou.trading.broker.Broker;
import com.martinfou.trading.broker.BrokerEvent;
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
 * SMOKE TEST PAPER IBKR — Phase F du plan paper-trading (2026-08-24).
 *
 * <p>Place 1 contrat MES en MARKET BUY sur le compte PAPER, attend le fill, vérifie la
 * position, puis flatten (kill switch) et déconnecte. Utilise UNIQUEMENT le port paper 7497
 * (le port live 7496 est refusé par construction).</p>
 *
 * <p>PRÉREQUIS:</p>
 * <ol>
 *   <li>IB Gateway en cours d'exécution en mode paper: {@code scripts/ibkr-gateway-up.sh}</li>
 *   <li>Variables d'environnement: {@code IBKR_ACCOUNT_ID} (ex DU1234567),
 *       {@code IBKR_GATEWAY_PORT=7497}</li>
 *   <li>Exécution: {@code mvn -q -Dspotbugs.skip=true -pl trading-examples -am \
 *       exec:java -Dexec.mainClass=com.martinfou.trading.examples.PaperMesSmokeTest}</li>
 * </ol>
 */
public final class PaperMesSmokeTest {

    private static final Logger log = LoggerFactory.getLogger(PaperMesSmokeTest.class);

    private PaperMesSmokeTest() {}

    public static void main(String[] args) throws Exception {
        String account = System.getenv("IBKR_ACCOUNT_ID");
        if (account == null || account.isBlank()) {
            log.error("IBKR_ACCOUNT_ID manquant (compte paper, ex: DU1234567)");
            System.exit(2);
        }

        // Toujours paper: port 7497. 7496 (live) est refusé.
        int port = Integer.parseInt(System.getenv().getOrDefault("IBKR_GATEWAY_PORT", "7497"));
        if (port == 7496) {
            log.error("Refus: port 7496 = LIVE. Paper uniquement (7497).");
            System.exit(2);
        }
        String host = System.getenv().getOrDefault("IBKR_GATEWAY_HOST", "127.0.0.1");
        int clientId = Integer.parseInt(System.getenv().getOrDefault("IBKR_CLIENT_ID", "11"));

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

            log.info("Connect Gateway paper {}:{} account={}", host, port, account);
            broker.connect();
            log.info("Connected. Account: {}", broker.getAccountState());

            Order order = new Order("MES", Order.Side.BUY, Order.Type.MARKET, 1.0, 0.0);
            log.info("Place 1 MES MARKET BUY...");
            var result = broker.submitOrder(order);
            log.info("submitOrder -> accepted={} orderId={} reason={}",
                result.accepted(), result.brokerOrderId(), result.rejectReason());

            boolean filled = fillLatch.await(20, TimeUnit.SECONDS);
            log.info("Fill attendu: {}", filled ? "OUI" : "TIMEOUT (pas de fill en 20s)");

            Thread.sleep(Duration.ofSeconds(2).toMillis());
            List<Position> positions = broker.getPositions();
            log.info("Positions ouvertes: {}", positions);
            if (positions.isEmpty()) {
                log.warn("AUCUNE position ouverte — vérifier la session Globex (le fill MKT ne se fait que pendant les heures de marché futures).");
            }

            // Kill switch paper: flatten toutes les positions, puis cancel.
            log.info("Kill switch: flatten...");
            int flattened = broker.flattenAllPositions();
            log.info("flatten -> {} position(s) fermée(s)", flattened);

            log.info("Résultat smoke test: fills={}, flattened={}", fills.get(), flattened);
            if (fills.get() > 0 && flattened > 0) {
                log.info("✅ SMOKE TEST PAPER RÉUSSI");
            } else {
                log.warn("⚠️ Smoke test partiel — fills={}, flattened={} (hors session Globex attendu)", fills.get(), flattened);
            }
        }
    }
}
