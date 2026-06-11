package com.martinfou.trading.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martinfou.trading.broker.BrokerEvent;
import com.martinfou.trading.broker.BrokerEventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

public class OandaTransactionStreamer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OandaTransactionStreamer.class);
    private final String streamUrl;
    private final String accountId;
    private final String apiToken;
    private final EventStore eventStore;
    private final ObjectMapper mapper;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread streamThread;

    public OandaTransactionStreamer(String baseUrl, String accountId, String apiToken, EventStore eventStore) {
        // Fix up baseUrl if it doesn't have the stream subdomain. 
        // For example: https://api-fxpractice.oanda.com/ -> https://stream-fxpractice.oanda.com/
        this.streamUrl = baseUrl.replace("api-", "stream-") + "accounts/" + accountId + "/transactions/stream";
        this.accountId = accountId;
        this.apiToken = apiToken;
        this.eventStore = eventStore;
        this.mapper = new ObjectMapper();
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            streamThread = new Thread(this::runStream, "OandaTransactionStream-" + accountId);
            streamThread.setDaemon(true);
            streamThread.start();
        }
    }

    private void runStream() {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

        while (running.get()) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(streamUrl))
                    .header("Authorization", "Bearer " + apiToken)
                    .GET()
                    .build();

                log.info("Connecting to OANDA transaction stream at {}", streamUrl);
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    log.error("Failed to connect to transaction stream. Status: {}", response.statusCode());
                    Thread.sleep(5000);
                    continue;
                }

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body()))) {
                    String line;
                    while (running.get() && (line = reader.readLine()) != null) {
                        if (line.isBlank()) continue;
                        processTransaction(line);
                    }
                }
            } catch (Exception e) {
                if (running.get()) {
                    log.error("Transaction stream disconnected. Reconnecting in 5 seconds...", e);
                    try { Thread.sleep(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                }
            }
        }
    }

    private void processTransaction(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            String type = node.path("type").asText();

            // Ignore heartbeat
            if ("HEARTBEAT".equals(type)) return;

            String time = node.path("time").asText();
            String instrument = node.path("instrument").asText(null);

            if ("ORDER_FILL".equals(type)) {
                double price = node.path("price").asDouble(0);
                double units = node.path("units").asDouble(0);
                String reason = node.path("reason").asText("");

                if (reason.equals("MARKET_ORDER") || reason.equals("LIMIT_ORDER") || reason.equals("STOP_ORDER")) {
                    BrokerEvent event = BrokerEvent.fill(null, instrument, null, units, price, null, null);
                    log.info("ORDER_FILL: {} units of {} at {}", units, instrument, price);
                    eventStore.append("GLOBAL_BROKER", new com.martinfou.trading.backtest.events.RunEvent(
                        com.martinfou.trading.backtest.events.RunEvent.SCHEMA_VERSION,
                        com.martinfou.trading.backtest.events.RunEventType.FILL,
                        event.timestamp(),
                        "GLOBAL_BROKER",
                        "OANDA_STREAM",
                        instrument,
                        "LIVE",
                        event.toPayload()
                    ));
                }
            } else if ("DAILY_FINANCING".equals(type)) {
                double financing = node.path("financing").asDouble(0);
                BrokerEvent event = BrokerEvent.financing(instrument, financing);
                log.info("DAILY_FINANCING: {} for {}", financing, instrument);
                eventStore.append("GLOBAL_BROKER", new com.martinfou.trading.backtest.events.RunEvent(
                    com.martinfou.trading.backtest.events.RunEvent.SCHEMA_VERSION,
                    com.martinfou.trading.backtest.events.RunEventType.FILL,
                    event.timestamp(),
                    "GLOBAL_BROKER",
                    "OANDA_STREAM",
                    instrument,
                    "LIVE",
                    event.toPayload()
                ));
            }

            log.debug("OANDA Transaction: {}", json);

        } catch (Exception e) {
            log.error("Failed to parse OANDA transaction: {}", json, e);
        }
    }

    @Override
    public void close() {
        running.set(false);
        if (streamThread != null) {
            streamThread.interrupt();
        }
    }
}
