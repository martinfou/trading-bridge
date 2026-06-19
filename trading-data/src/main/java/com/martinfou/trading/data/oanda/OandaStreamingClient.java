package com.martinfou.trading.data.oanda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Establish connection to OANDA v20 pricing stream with backoff and heartbeat checks. */
public final class OandaStreamingClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OandaStreamingClient.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private final String apiToken;
    private final String accountId;
    private final String streamBaseUrl;
    private final Set<String> subscribedInstruments = ConcurrentHashMap.newKeySet();
    private final List<OandaTickListener> listeners = new CopyOnWriteArrayList<>();

    private HttpClient client;
    private Thread connectionThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Instant lastHeartbeatOrTick = Instant.now();
    private volatile java.io.InputStream activeInputStream;

    public interface OandaTickListener {
        void onTick(String instrument, Instant timestamp, double bid, double ask);
        void onConnectionStateChange(boolean active);
    }

    public OandaStreamingClient(String apiToken, String accountId, boolean practice) {
        this.apiToken = apiToken;
        this.accountId = accountId;
        this.streamBaseUrl = practice
            ? "https://stream-fxpractice.oanda.com/v3/"
            : "https://stream-fxtrade.oanda.com/v3/";
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public void addListener(OandaTickListener listener) {
        listeners.add(listener);
    }

    public void removeListener(OandaTickListener listener) {
        listeners.remove(listener);
    }

    public synchronized void subscribe(String instrument) {
        if (subscribedInstruments.add(instrument)) {
            log.info("Subscribed to streaming prices for {}", instrument);
            reconnectStream();
        }
    }

    public synchronized void unsubscribe(String instrument) {
        if (subscribedInstruments.remove(instrument)) {
            log.info("Unsubscribed from streaming prices for {}", instrument);
            reconnectStream();
        }
    }

    public synchronized void start() {
        if (running.get()) return;
        running.set(true);
        startConnectionThread();
        startWatchdogThread();
    }

    public synchronized void stop() {
        if (!running.get()) return;
        running.set(false);
        if (activeInputStream != null) {
            try {
                activeInputStream.close();
            } catch (java.io.IOException e) {
                // ignore
            }
            activeInputStream = null;
        }
        if (connectionThread != null) {
            connectionThread.interrupt();
            connectionThread = null;
        }
        notifyConnectionState(false);
    }

    @Override
    public void close() {
        stop();
    }

    public Instant lastHeartbeatOrTick() {
        return lastHeartbeatOrTick;
    }

    private synchronized void reconnectStream() {
        if (!running.get()) return;
        if (activeInputStream != null) {
            try {
                activeInputStream.close();
            } catch (java.io.IOException e) {
                // ignore
            }
            activeInputStream = null;
        }
        if (connectionThread != null) {
            connectionThread.interrupt();
        }
        startConnectionThread();
    }

    private void startWatchdogThread() {
        Thread.ofVirtual().start(() -> {
            while (running.get()) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    break;
                }
                if (activeInputStream != null && Instant.now().isAfter(lastHeartbeatOrTick.plus(Duration.ofSeconds(20)))) {
                    log.warn("OANDA pricing stream heartbeat timeout (no ticks/heartbeats for 20s) for account {}. Triggering reconnect...", accountId);
                    closeActiveStream();
                }
            }
        });
    }

    private synchronized void closeActiveStream() {
        if (activeInputStream != null) {
            try {
                activeInputStream.close();
            } catch (java.io.IOException e) {
                // ignore
            }
            activeInputStream = null;
        }
    }

    private void startConnectionThread() {
        connectionThread = Thread.ofVirtual().unstarted(this::runConnectionLoop);
        connectionThread.setName("OandaStreamingClient-" + accountId);
        connectionThread.start();
    }

    private void runConnectionLoop() {
        int backoffSecs = 1;
        while (running.get()) {
            if (subscribedInstruments.isEmpty()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    break;
                }
                continue;
            }

            String instrumentsParam = String.join(",", subscribedInstruments);
            String urlStr = streamBaseUrl + "accounts/" + accountId + "/pricing/stream?instruments=" + instrumentsParam;

            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(urlStr))
                    .header("Authorization", "Bearer " + apiToken)
                    .GET()
                    .build();

                log.info("Connecting to OANDA pricing stream: {}", urlStr);
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (response.statusCode() != 200) {
                    try (InputStream is = response.body()) {
                        String body = new String(is.readAllBytes());
                        log.warn("OANDA stream connection failed with status {}: {}", response.statusCode(), body);
                    }
                    throw new IllegalStateException("Status " + response.statusCode());
                }

                backoffSecs = 1;
                notifyConnectionState(true);
                lastHeartbeatOrTick = Instant.now();
                activeInputStream = response.body();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(activeInputStream))) {
                    String line;
                    while (running.get() && (line = reader.readLine()) != null) {
                        parseLine(line);
                    }
                } finally {
                    activeInputStream = null;
                }
            } catch (Exception e) {
                notifyConnectionState(false);
                if (!running.get() || Thread.currentThread().isInterrupted()) {
                    break;
                }
                log.warn("OANDA pricing stream disconnected: {}. Retrying in {}s...", e.getMessage(), backoffSecs);
                try {
                    Thread.sleep(backoffSecs * 1000L);
                } catch (InterruptedException ie) {
                    break;
                }
                backoffSecs = Math.min(backoffSecs * 2, 60);
            }
        }
    }

    private void parseLine(String line) {
        try {
            if (line.isBlank()) return;
            JsonNode node = mapper.readTree(line);
            if (!node.has("type")) return;

            String type = node.get("type").asText();
            lastHeartbeatOrTick = Instant.now();

            if ("PRICE".equals(type)) {
                String instrument = node.get("instrument").asText();
                Instant time = Instant.parse(node.get("time").asText());
                double bid = node.get("bids").get(0).get("price").asDouble();
                double ask = node.get("asks").get(0).get("price").asDouble();
                notifyTick(instrument, time, bid, ask);
            }
        } catch (Exception e) {
            log.warn("Failed to parse OANDA stream line: {}", e.getMessage());
        }
    }

    private void notifyTick(String instrument, Instant timestamp, double bid, double ask) {
        for (OandaTickListener listener : listeners) {
            listener.onTick(instrument, timestamp, bid, ask);
        }
    }

    private void notifyConnectionState(boolean active) {
        for (OandaTickListener listener : listeners) {
            listener.onConnectionStateChange(active);
        }
    }
}
