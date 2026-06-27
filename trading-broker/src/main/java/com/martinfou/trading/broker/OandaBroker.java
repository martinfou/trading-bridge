package com.martinfou.trading.broker;

import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Position;
import com.martinfou.trading.data.oanda.OandaAccountSnapshot;
import com.martinfou.trading.data.oanda.OandaMarketOrderResult;
import com.martinfou.trading.data.oanda.OandaPositionSnapshot;
import com.martinfou.trading.data.oanda.OandaRestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * {@link Broker} adapter for OANDA demo/live via {@link OandaRestClient} (Story 16.3).
 */
public final class OandaBroker implements Broker {

    private static final Logger log = LoggerFactory.getLogger(OandaBroker.class);

    private final OandaRestClient client;
    private final List<Consumer<BrokerEvent>> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean connected;
    private java.util.concurrent.ScheduledExecutorService keepaliveScheduler;
    private final List<Long> orderTimestamps = new java.util.ArrayList<>();
    private final Object rateLimitLock = new Object();
    private volatile int retryCount;

    private final long keepaliveIntervalMs;
    private final long rateLimitWindowMs;
    private final int rateLimitMaxOrders;
    private final long rateLimitDelayMs;

    public OandaBroker(OandaRestClient client) {
        this(client, 30_000, 60_000, 50, 1000);
    }

    OandaBroker(OandaRestClient client, long keepaliveIntervalMs) {
        this(client, keepaliveIntervalMs, 60_000, 50, 1000);
    }

    OandaBroker(OandaRestClient client, long keepaliveIntervalMs, long rateLimitWindowMs, int rateLimitMaxOrders, long rateLimitDelayMs) {
        if (client == null) {
            throw new IllegalArgumentException("client is required");
        }
        this.client = client;
        this.keepaliveIntervalMs = keepaliveIntervalMs;
        this.rateLimitWindowMs = rateLimitWindowMs;
        this.rateLimitMaxOrders = rateLimitMaxOrders;
        this.rateLimitDelayMs = rateLimitDelayMs;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public synchronized void connect() {
        client.fetchAccountSummary();
        connected = true;
        emit(BrokerEvent.connection("CONNECTED", "Account verified"));
        startKeepalive();
    }

    @Override
    public synchronized void disconnect() {
        connected = false;
        stopKeepalive();
    }

    @Override
    public void reconnect() {
        log.info("Attempting to reconnect OandaBroker (retryCount: {})", retryCount);
        
        long backoffSec = (long) Math.min(60, Math.pow(2, retryCount));
        if (retryCount > 0) {
            log.info("Sleeping for {}s due to reconnect backoff", backoffSec);
            try {
                Thread.sleep(backoffSec * 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        synchronized (this) {
            disconnect();
            client.reset();
            
            try {
                retryCount++;
                connect();
                retryCount = 0;
                log.info("OandaBroker reconnected successfully");
            } catch (Exception e) {
                log.warn("OandaBroker reconnect attempt failed: {}", e.getMessage());
                throw e;
            }
        }
    }

    public int getRetryCount() {
        return retryCount;
    }

    private void startKeepalive() {
        stopKeepalive();
        keepaliveScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "oanda-keepalive");
            t.setDaemon(true);
            return t;
        });

        final java.util.concurrent.atomic.AtomicInteger consecutiveFailures = new java.util.concurrent.atomic.AtomicInteger(0);

        keepaliveScheduler.scheduleAtFixedRate(() -> {
            if (!connected) {
                return;
            }
            try {
                client.fetchAccountSummary();
                consecutiveFailures.set(0);
            } catch (Exception e) {
                int fails = consecutiveFailures.incrementAndGet();
                log.warn("OANDA keepalive heartbeat failed (consecutive failures: {})", fails, e);
                if (fails >= 2) {
                    log.error("OANDA keepalive failed 2 consecutive heartbeats. Declaring broker disconnected.");
                    connected = false;
                    emit(BrokerEvent.connection("DISCONNECTED", "Keepalive ping failed: " + e.getMessage()));
                    stopKeepalive();
                }
            }
        }, keepaliveIntervalMs, keepaliveIntervalMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void stopKeepalive() {
        if (keepaliveScheduler != null) {
            keepaliveScheduler.shutdownNow();
            keepaliveScheduler = null;
        }
    }

    @Override
    public OrderSubmitResult submitOrder(Order order) {
        emit(BrokerEvent.submitted(order));

        if (!connected) {
            emit(BrokerEvent.reject(order, "Broker not connected"));
            return OrderSubmitResult.rejected("Broker not connected");
        }

        enforceRateLimit();

        if (order.isCloseOnly()) {
            double remainingQty = order.quantity();
            String instrument = toOandaInstrument(order.symbol());
            List<Position> openPositions = getPositions();
            List<Position> oppositePositions = new ArrayList<>();
            for (Position p : openPositions) {
                if (p.symbol().equalsIgnoreCase(order.symbol()) && p.side() != order.side()) {
                    oppositePositions.add(p);
                }
            }
            oppositePositions.sort(java.util.Comparator.comparing(Position::entryTime));

            double closedTotal = 0;
            boolean success = true;
            String errorMsg = null;
            double avgClosePrice = 0.0;

            for (Position p : oppositePositions) {
                if (remainingQty <= 0) break;
                
                String tradeId = p.brokerTradeId();
                if (tradeId == null || tradeId.isBlank()) {
                    log.warn("Cannot close OANDA position/trade: missing trade ID");
                    continue;
                }
                double qtyToClose = Math.min(p.quantity(), remainingQty);

                log.info("Closing OANDA trade {} (quantity: {})", tradeId, qtyToClose);
                double fillPrice = client.closeTrade(tradeId, String.valueOf(Math.round(qtyToClose)));
                if (fillPrice >= 0.0) {
                    remainingQty -= qtyToClose;
                    closedTotal += qtyToClose;
                    avgClosePrice = fillPrice;
                } else {
                    success = false;
                    errorMsg = "Failed to close trade: " + tradeId;
                    log.error("Failed to close OANDA trade: {}", tradeId);
                }
            }

            if (closedTotal > 0) {
                order.fill();
                emit(BrokerEvent.fill(order, avgClosePrice));
                return OrderSubmitResult.filled("CLOSE_MULTIPLE");
            } else if (!success) {
                emit(BrokerEvent.reject(order, errorMsg));
                return OrderSubmitResult.rejected(errorMsg);
            } else {
                log.info("No opposite positions found to close for closeOnly order {}", order.id());
                order.fill();
                // Do NOT emit a BrokerEvent.fill because no execution occurred on the broker
                return OrderSubmitResult.filled("NO_OP_CLOSE");
            }
        }

        long units = Math.round(order.quantity());
        if (order.side() == Order.Side.SELL) {
            units = -units;
        }
        String instrument = toOandaInstrument(order.symbol());
        OandaMarketOrderResult result;

        if (order.type() == Order.Type.MARKET) {
            if (order.stopLoss() > 0 || order.takeProfit() > 0 || order.trailingStop() > 0) {
                result = client.placeOrder("MARKET", instrument, units, 0.0, order.stopLoss(), order.takeProfit(), order.trailingStop(), order.guaranteed(), order.id());
            } else {
                result = client.placeMarketOrder(instrument, units, order.id());
            }
        } else {
            result = client.placeOrder(order.type().name(), instrument, units, order.price(), order.stopLoss(), order.takeProfit(), order.trailingStop(), order.guaranteed(), order.id());
        }

        if (!result.success()) {
            String reason = result.errorMessage() != null ? result.errorMessage() : "OANDA order rejected";
            log.warn("OANDA reject {} {} type={}: {}", instrument, units, order.type(), reason);
            emit(BrokerEvent.reject(order, reason));
            return OrderSubmitResult.rejected(reason);
        }

        if (order.type() == Order.Type.MARKET) {
            order.fill();
            double fillPrice = result.fillPrice() != null ? result.fillPrice() : order.price();
            emit(BrokerEvent.fill(order, fillPrice));
        }
        return OrderSubmitResult.filled(result.orderId());
    }

    @Override
    public OrderSubmitResult cancelOrder(String brokerOrderId) {
        if (!connected) {
            return OrderSubmitResult.rejected("Broker not connected");
        }
        boolean ok = client.cancelOrder(brokerOrderId);
        if (ok) {
            emit(BrokerEvent.reject(brokerOrderId, "", "", 0.0, 0.0, "CANCELLED", null, null));
            return OrderSubmitResult.filled(brokerOrderId);
        }
        return OrderSubmitResult.rejected("Failed to cancel order on OANDA");
    }

    @Override
    public List<Position> getPositions() {
        List<Position> out = new ArrayList<>();
        for (OandaPositionSnapshot row : client.fetchOpenPositions()) {
            java.time.Instant entryTime = row.entryTime() != null ? row.entryTime() : java.time.Instant.EPOCH;
            out.add(new Position(row.instrument(), row.side(), row.units(), row.averagePrice(), entryTime, row.clientTag(), row.tradeId()));
        }
        return List.copyOf(out);
    }

    @Override
    public AccountState getAccountState() {
        OandaAccountSnapshot account = client.fetchAccountSummary();
        return new AccountState(account.balance(), account.nav(), account.currency());
    }

    @Override
    public void addEventListener(Consumer<BrokerEvent> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    static String toOandaInstrument(String symbol) {
        if (symbol == null) {
            throw new IllegalArgumentException("symbol is required");
        }
        return symbol.replace("/", "_").replace("-", "_").toUpperCase();
    }

    private void enforceRateLimit() {
        if (rateLimitMaxOrders <= 0) {
            return;
        }
        while (true) {
            long sleepTime = 0;
            synchronized (rateLimitLock) {
                long now = System.currentTimeMillis();
                orderTimestamps.removeIf(t -> t < now - rateLimitWindowMs);
                if (orderTimestamps.size() < rateLimitMaxOrders) {
                    orderTimestamps.add(now);
                    break;
                }
                sleepTime = rateLimitDelayMs;
            }
            
            if (sleepTime > 0) {
                log.warn("OANDA order submission rate limit reached (>= {} orders/{}ms). Delaying order submission.", rateLimitMaxOrders, rateLimitWindowMs);
                emit(BrokerEvent.rateLimit("Rate limit exceeded. Delaying submission."));
                try {
                    Thread.sleep(sleepTime);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void emit(BrokerEvent event) {
        for (Consumer<BrokerEvent> listener : listeners) {
            listener.accept(event);
        }
    }
}
