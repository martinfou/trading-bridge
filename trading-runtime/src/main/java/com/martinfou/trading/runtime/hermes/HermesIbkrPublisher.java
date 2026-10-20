package com.martinfou.trading.runtime.hermes;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.data.ibkr.IbkrAccountDetails;
import com.martinfou.trading.data.ibkr.IbkrExecutionReport;
import com.martinfou.trading.data.ibkr.IbkrGatewayClient;
import com.martinfou.trading.data.ibkr.IbkrOpenOrder;
import com.martinfou.trading.data.ibkr.IbkrPositionSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pushes the Interactive Brokers account to hermes-web, read-only.
 *
 * <p>hermes-web runs on DreamHost and can never reach IB Gateway, so this side pushes (same pattern as the
 * OANDA {@code trading_pnl_push.py}). Two payloads per cycle:</p>
 * <ul>
 *   <li>{@code POST /api/trading/broker/snapshot}: live state (account, positions, open orders) for the
 *       dashboard's IBKR panel, every cycle;</li>
 *   <li>{@code POST /api/trading/pnl-import} with {@code broker=ibkr}: one entry per closing execution
 *       (IB's realized P&amp;L, converted to the account's base currency with IB's own exchange rate), only when
 *       there is something new or at most once an hour as an account reading.</li>
 * </ul>
 *
 * <p>IB only returns the current day's executions, so a closing fill is written to a local outbox the first
 * time it is seen and removed only after the hub accepted it; a failed POST is retried next cycle and a
 * restart does not lose it. The publisher only calls the read methods of {@link IbkrGatewayClient}.</p>
 */
public final class HermesIbkrPublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(HermesIbkrPublisher.class);

    public static final String SNAPSHOT_PATH = "/api/trading/broker/snapshot";
    public static final String PNL_IMPORT_PATH = "/api/trading/pnl-import";
    static final Duration PNL_HEARTBEAT = Duration.ofHours(1);
    static final int SENT_MEMORY = 5_000;
    private static final String SOURCE = "trading-bridge/HermesIbkrPublisher";

    /** {@code SYM-STRAT-yyyyMMdd-HHmm-SIDE}, see {@code Order.generateCanonicalTag}; brackets add {@code -TP}/{@code -SL}. */
    private static final Pattern CANONICAL_TAG =
        Pattern.compile("^([A-Z0-9]+)-([A-Z0-9]+)-(\\d{8})-(\\d{4})-(BUY|SELL)$");
    private static final Pattern BRACKET_SUFFIX = Pattern.compile("^(.*)-(TP|SL)$");

    /** Transport to the hub; {@link HttpHermesSender} in production, a fake in tests. */
    public interface Sender {
        Response post(String path, String jsonBody) throws IOException, InterruptedException;

        record Response(int status, String body) {
            public boolean ok() {
                return status >= 200 && status < 300;
            }
        }
    }

    /** Outcome of one cycle, for logs and tests. */
    public record CycleResult(boolean snapshotPushed, int newClosingFills, int pnlEntriesPushed, int outboxSize,
                              String error) {}

    private final IbkrGatewayClient client;
    private final Sender sender;
    private final Path statePath;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    private final String gatewayLabel;
    private ScheduledExecutorService scheduler;

    private final Set<String> sentFillRefs = new LinkedHashSet<>();
    private final Map<String, Map<String, Object>> outbox = new LinkedHashMap<>();
    private Instant lastPnlPushAt = Instant.EPOCH;

    public HermesIbkrPublisher(IbkrGatewayClient client, Sender sender, Path stateDir, Clock clock,
                               String gatewayLabel) {
        this.client = client;
        this.sender = sender;
        this.statePath = stateDir.resolve("hermes-ibkr-state.json");
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.gatewayLabel = gatewayLabel;
        loadState();
    }

    // ------------------------------------------------------------------ lifecycle

    public synchronized void start(Duration interval) {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "hermes-ibkr-publisher");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                CycleResult r = runOnce();
                if (r.error() != null) {
                    log.warn("hermes IBKR push: {}", r.error());
                }
            } catch (RuntimeException e) {
                log.warn("hermes IBKR push failed: {}", e.toString());
            }
        }, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
        log.info("hermes IBKR publisher started (every {}s)", interval.toSeconds());
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    // ------------------------------------------------------------------ one cycle

    public synchronized CycleResult runOnce() {
        Instant now = clock.instant();
        try {
            if (!client.isConnected()) {
                client.connect();
            }
        } catch (RuntimeException e) {
            return new CycleResult(false, 0, 0, outbox.size(), "IB Gateway not reachable: " + e.getMessage());
        }

        IbkrAccountDetails account;
        List<IbkrPositionSnapshot> positions;
        List<IbkrOpenOrder> orders;
        List<IbkrExecutionReport> executions;
        try {
            account = client.fetchAccountDetails();
            positions = client.fetchOpenPositions();
            orders = client.fetchAllOpenOrders();
            executions = client.fetchExecutions();
        } catch (RuntimeException e) {
            return new CycleResult(false, 0, 0, outbox.size(), "IB read failed: " + e.getMessage());
        }
        String accountId = account.accountId();
        if (accountId == null || accountId.isBlank()) {
            return new CycleResult(false, 0, 0, outbox.size(), "IB did not report an account id");
        }

        StringBuilder errors = new StringBuilder();
        boolean snapshotPushed = post(SNAPSHOT_PATH, brokerSnapshot(accountId, account, positions, orders, now), errors);

        int newFills = 0;
        for (IbkrExecutionReport e : executions) {
            if (!e.isClosing() || sentFillRefs.contains(e.execId()) || outbox.containsKey(e.execId())) {
                continue;
            }
            Map<String, Object> entry = pnlEntry(e, account);
            if (entry == null) {
                errors.append("no ").append(e.commissionCurrency()).append(" exchange rate for fill ")
                    .append(e.execId()).append("; retrying next cycle. ");
                continue;
            }
            outbox.put(e.execId(), entry);
            newFills++;
        }

        int pushed = 0;
        boolean heartbeatDue = Duration.between(lastPnlPushAt, now).compareTo(PNL_HEARTBEAT) >= 0;
        if (!outbox.isEmpty() || heartbeatDue) {
            List<Map<String, Object>> batch = new ArrayList<>(outbox.values());
            if (post(PNL_IMPORT_PATH, pnlImport(accountId, account, positions.size(), batch, now), errors)) {
                batch.forEach(entry -> {
                    String ref = String.valueOf(entry.get("fill_ref"));
                    outbox.remove(ref);
                    rememberSent(ref);
                });
                pushed = batch.size();
                lastPnlPushAt = now;
            }
        }
        saveState();
        return new CycleResult(snapshotPushed, newFills, pushed, outbox.size(),
            errors.isEmpty() ? null : errors.toString().trim());
    }

    private boolean post(String path, Map<String, Object> payload, StringBuilder errors) {
        try {
            Sender.Response response = sender.post(path, json.writeValueAsString(payload));
            if (response.ok()) {
                return true;
            }
            errors.append(path).append(" -> HTTP ").append(response.status()).append(' ')
                .append(abbreviate(response.body())).append(". ");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            errors.append(path).append(" interrupted. ");
        } catch (IOException | RuntimeException e) {
            errors.append(path).append(" -> ").append(e.getMessage()).append(". ");
        }
        return false;
    }

    // ------------------------------------------------------------------ payloads

    Map<String, Object> brokerSnapshot(String accountId, IbkrAccountDetails account,
                                       List<IbkrPositionSnapshot> positions, List<IbkrOpenOrder> orders,
                                       Instant now) {
        Map<String, Object> acct = new LinkedHashMap<>();
        acct.put("currency", account.baseCurrency());
        acct.put("net_liquidation", account.value(IbkrAccountDetails.NET_LIQUIDATION));
        acct.put("total_cash", account.value(IbkrAccountDetails.TOTAL_CASH));
        acct.put("buying_power", account.value(IbkrAccountDetails.BUYING_POWER));
        acct.put("available_funds", account.value(IbkrAccountDetails.AVAILABLE_FUNDS));
        acct.put("init_margin", account.value(IbkrAccountDetails.INIT_MARGIN));
        acct.put("maint_margin", account.value(IbkrAccountDetails.MAINT_MARGIN));
        acct.put("gross_position_value", account.value(IbkrAccountDetails.GROSS_POSITION_VALUE));
        acct.put("unrealized_pl", account.value(IbkrAccountDetails.UNREALIZED_PNL));
        acct.put("realized_pl", account.value(IbkrAccountDetails.REALIZED_PNL));

        List<Map<String, Object>> pos = new ArrayList<>();
        for (IbkrPositionSnapshot p : positions) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("symbol", p.symbol());
            row.put("side", p.side() == Order.Side.SELL ? "SHORT" : "LONG");
            row.put("quantity", p.quantity());
            row.put("average_price", p.averagePrice());
            pos.add(row);
        }

        List<Map<String, Object>> open = new ArrayList<>();
        for (IbkrOpenOrder o : orders) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("order_id", o.brokerOrderId());
            row.put("parent_id", o.parentId());
            row.put("ref", o.clientOrderId());
            row.put("symbol", o.symbol());
            row.put("side", o.side() == null ? null : o.side().name());
            row.put("type", o.orderType());
            row.put("quantity", o.quantity());
            row.put("limit_price", positiveOrNull(o.limitPrice()));
            row.put("stop_price", positiveOrNull(o.auxPrice()));
            row.put("status", o.status());
            open.add(row);
        }

        Map<String, Object> connection = new LinkedHashMap<>();
        connection.put("connected", client.isConnected());
        connection.put("gateway", gatewayLabel);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("snapshot_id", "ibkr:" + accountId + ":" + now.toEpochMilli());
        payload.put("broker", "ibkr");
        payload.put("account_id", accountId);
        payload.put("environment", environmentOf(accountId));
        payload.put("captured_at_utc", now.toString());
        payload.put("source", SOURCE);
        payload.put("connection", connection);
        payload.put("account", acct);
        payload.put("fx_rates", account.exchangeRates());
        payload.put("positions", pos);
        payload.put("open_orders", open);
        return payload;
    }

    Map<String, Object> pnlImport(String accountId, IbkrAccountDetails account, int openPositions,
                                  List<Map<String, Object>> entries, Instant now) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("nav", account.value(IbkrAccountDetails.NET_LIQUIDATION));
        snapshot.put("balance", account.value(IbkrAccountDetails.TOTAL_CASH));
        snapshot.put("unrealized_pl", account.value(IbkrAccountDetails.UNREALIZED_PNL));
        snapshot.put("margin_used", account.value(IbkrAccountDetails.INIT_MARGIN));
        snapshot.put("open_trade_count", openPositions);
        snapshot.put("currency", account.baseCurrency());
        snapshot.put("extraction_at_utc", now.toString());
        snapshot.put("source", SOURCE);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("broker", "ibkr");
        payload.put("account_id", accountId);
        payload.put("environment", environmentOf(accountId));
        payload.put("snapshot", snapshot);
        payload.put("entries", entries);
        return payload;
    }

    /**
     * One closing execution as a mirror entry, or {@code null} when its currency cannot be converted yet.
     *
     * <p>{@code pl} is IB's realized P&amp;L (already net of both legs' commissions) in the account's base
     * currency; the native amount and currency are kept beside it. {@code direction} is the direction of the
     * trade that was closed, so a closing SELL is a BUY trade, matching the OANDA rows.</p>
     */
    static Map<String, Object> pnlEntry(IbkrExecutionReport e, IbkrAccountDetails account) {
        String ccy = e.commissionCurrency() != null ? e.commissionCurrency() : e.currency();
        Double rate = account.rateToBase(ccy);
        if (rate == null) {
            return null;
        }
        Attribution attribution = attribute(e.orderRef());
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("fill_ref", e.execId());
        entry.put("closed_at_utc", e.time().toString());
        entry.put("instrument", e.symbol());
        entry.put("kind", attribution.strategy() != null ? "strategy" : "leak");
        entry.put("strategy", attribution.strategy());
        entry.put("tag", e.orderRef() == null || e.orderRef().isBlank() ? null : e.orderRef());
        entry.put("direction", e.side() == Order.Side.SELL ? "BUY" : "SELL");
        entry.put("units", e.quantity());
        entry.put("exit_price", e.price());
        entry.put("pl", round(e.realizedPnl() * rate));
        entry.put("pl_native", round(e.realizedPnl()));
        entry.put("currency", ccy);
        entry.put("commission", round(e.commission() * rate));
        entry.put("exit_reason", attribution.exitReason());
        entry.put("order_id", e.permId() > 0 ? String.valueOf(e.permId()) : String.valueOf(e.orderId()));
        return entry;
    }

    record Attribution(String strategy, String exitReason) {}

    /** Strategy from the canonical order tag; anything else (manual, UUID, blank) is a leak, never a strategy. */
    static Attribution attribute(String orderRef) {
        if (orderRef == null || orderRef.isBlank()) {
            return new Attribution(null, "MANUAL");
        }
        String ref = orderRef.trim();
        String exit = "ORDER";
        Matcher bracket = BRACKET_SUFFIX.matcher(ref);
        if (bracket.matches()) {
            ref = bracket.group(1);
            exit = bracket.group(2);
        }
        Matcher m = CANONICAL_TAG.matcher(ref.toUpperCase());
        return new Attribution(m.matches() ? m.group(2).toLowerCase() : null, exit);
    }

    /** IB paper accounts start with DU; the hub's vocabulary is practice / live. */
    static String environmentOf(String accountId) {
        return accountId != null && accountId.toUpperCase().startsWith("DU") ? "practice" : "live";
    }

    private static Double positiveOrNull(double v) {
        return v > 0 && v != Double.MAX_VALUE ? v : null;
    }

    private static double round(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    // ------------------------------------------------------------------ state (outbox + sent memory)

    int outboxSize() {
        return outbox.size();
    }

    private void rememberSent(String ref) {
        sentFillRefs.add(ref);
        while (sentFillRefs.size() > SENT_MEMORY) {
            String oldest = sentFillRefs.iterator().next();
            sentFillRefs.remove(oldest);
        }
    }

    private void loadState() {
        if (!Files.exists(statePath)) {
            return;
        }
        try {
            Map<String, Object> state = json.readValue(statePath.toFile(), new TypeReference<>() {});
            Object sent = state.get("sent_fill_refs");
            if (sent instanceof List<?> list) {
                list.forEach(r -> sentFillRefs.add(String.valueOf(r)));
            }
            Object pending = state.get("outbox");
            if (pending instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m && m.get("fill_ref") != null) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> entry = new LinkedHashMap<>((Map<String, Object>) m);
                        outbox.put(String.valueOf(m.get("fill_ref")), entry);
                    }
                }
            }
            Object last = state.get("last_pnl_push_at");
            if (last instanceof String s && !s.isBlank()) {
                lastPnlPushAt = Instant.parse(s);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Ignoring unreadable hermes IBKR state {}: {}", statePath, e.toString());
        }
    }

    private void saveState() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("sent_fill_refs", new ArrayList<>(sentFillRefs));
        state.put("outbox", new ArrayList<>(outbox.values()));
        state.put("last_pnl_push_at", lastPnlPushAt.toString());
        try {
            Files.createDirectories(statePath.getParent());
            Path tmp = statePath.resolveSibling(statePath.getFileName() + ".tmp");
            json.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), state);
            Files.move(tmp, statePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Could not save hermes IBKR state {}: {}", statePath, e.toString());
        }
    }
}
