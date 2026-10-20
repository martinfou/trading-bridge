package com.martinfou.trading.runtime.hermes;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.data.ibkr.IbkrAccountDetails;
import com.martinfou.trading.data.ibkr.IbkrAccountSnapshot;
import com.martinfou.trading.data.ibkr.IbkrExecutionReport;
import com.martinfou.trading.data.ibkr.IbkrGatewayClient;
import com.martinfou.trading.data.ibkr.IbkrOpenOrder;
import com.martinfou.trading.data.ibkr.IbkrOrderAck;
import com.martinfou.trading.data.ibkr.IbkrOrderRequest;
import com.martinfou.trading.data.ibkr.IbkrOrderUpdate;
import com.martinfou.trading.data.ibkr.IbkrPositionSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class HermesIbkrPublisherTest {

    private static final String ACCOUNT = "DU1234322";
    private static final Instant NOW = Instant.parse("2026-10-19T14:00:00Z");
    private final ObjectMapper json = new ObjectMapper();

    @TempDir
    Path stateDir;

    /** Read-only fake: any order method fails the test, which is the publisher's read-only guarantee. */
    static class FakeClient implements IbkrGatewayClient {
        boolean connected = true;
        IbkrAccountDetails account = new IbkrAccountDetails(ACCOUNT, "CAD",
            Map.of(IbkrAccountDetails.NET_LIQUIDATION, 11_285.63, IbkrAccountDetails.TOTAL_CASH, 11_285.63,
                IbkrAccountDetails.UNREALIZED_PNL, -12.5),
            Map.of("USD", 1.38));
        List<IbkrPositionSnapshot> positions = List.of(new IbkrPositionSnapshot("MES", Order.Side.BUY, 1, 7767.5));
        List<IbkrOpenOrder> orders = List.of(
            new IbkrOpenOrder("11", "MES-ORB-20261019-1400-BUY-TP", "MES", Order.Side.SELL, "LMT", 1, 7790, 0, "10", "Submitted"),
            new IbkrOpenOrder("12", "MES-ORB-20261019-1400-BUY-SL", "MES", Order.Side.SELL, "STP", 1, 0, 7740, "10", "PreSubmitted"));
        List<IbkrExecutionReport> executions = new ArrayList<>();

        @Override public void connect() { connected = true; }
        @Override public void disconnect() { connected = false; }
        @Override public boolean isConnected() { return connected; }
        @Override public IbkrOrderAck placeOrder(IbkrOrderRequest request) { return fail("publisher must not place orders"); }
        @Override public IbkrOrderAck cancelOrder(String brokerOrderId) { return fail("publisher must not cancel orders"); }
        @Override public void addOrderListener(Consumer<IbkrOrderUpdate> listener) { }
        @Override public IbkrAccountSnapshot fetchAccountSummary() { return new IbkrAccountSnapshot(0, 0, "CAD"); }
        @Override public List<IbkrPositionSnapshot> fetchOpenPositions() { return positions; }
        @Override public List<IbkrOpenOrder> fetchAllOpenOrders() { return orders; }
        @Override public IbkrAccountDetails fetchAccountDetails() { return account; }
        @Override public List<IbkrExecutionReport> fetchExecutions() { return executions; }
    }

    static final class RecordingSender implements HermesIbkrPublisher.Sender {
        final List<String> paths = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        int pnlStatus = 200;

        @Override
        public Response post(String path, String body) {
            paths.add(path);
            bodies.add(body);
            return new Response(path.equals(HermesIbkrPublisher.PNL_IMPORT_PATH) ? pnlStatus : 201, "{}");
        }

        Map<String, Object> last(String path) throws Exception {
            for (int i = paths.size() - 1; i >= 0; i--) {
                if (paths.get(i).equals(path)) {
                    return new ObjectMapper().readValue(bodies.get(i), new TypeReference<>() {});
                }
            }
            return null;
        }

        long count(String path) {
            return paths.stream().filter(path::equals).count();
        }
    }

    private static IbkrExecutionReport closingSell(String execId, double realizedUsd) {
        return new IbkrExecutionReport(execId, 815_000_001L, 10, "MES-ORB-20261019-1400-BUY-TP", ACCOUNT, "MES",
            "FUT", "USD", Order.Side.SELL, 1, 7790, Instant.parse("2026-10-19T13:55:00Z"),
            true, 0.62, "USD", realizedUsd);
    }

    private static IbkrExecutionReport openingBuy(String execId) {
        return new IbkrExecutionReport(execId, 815_000_000L, 9, "MES-ORB-20261019-1400-BUY", ACCOUNT, "MES",
            "FUT", "USD", Order.Side.BUY, 1, 7767.5, Instant.parse("2026-10-19T13:50:00Z"),
            true, 0.62, "USD", Double.NaN);
    }

    private HermesIbkrPublisher publisher(FakeClient client, RecordingSender sender) {
        return new HermesIbkrPublisher(client, sender, stateDir, Clock.fixed(NOW, ZoneOffset.UTC), "127.0.0.1:4002");
    }

    @Test
    void snapshotCarriesAccountPositionsAndBracketLegsAndNullForUnknown() throws Exception {
        RecordingSender sender = new RecordingSender();
        HermesIbkrPublisher.CycleResult r = publisher(new FakeClient(), sender).runOnce();

        assertTrue(r.snapshotPushed());
        Map<String, Object> snap = sender.last(HermesIbkrPublisher.SNAPSHOT_PATH);
        assertEquals("ibkr:" + ACCOUNT + ":" + NOW.toEpochMilli(), snap.get("snapshot_id"));
        assertEquals("practice", snap.get("environment"));
        @SuppressWarnings("unchecked") Map<String, Object> acct = (Map<String, Object>) snap.get("account");
        assertEquals(11_285.63, acct.get("net_liquidation"));
        assertEquals("CAD", acct.get("currency"));
        assertTrue(acct.containsKey("buying_power"));
        assertNull(acct.get("buying_power"), "unknown must be null, never 0");
        @SuppressWarnings("unchecked") List<Map<String, Object>> orders = (List<Map<String, Object>>) snap.get("open_orders");
        assertEquals("10", orders.get(0).get("parent_id"));
        assertEquals(7790.0, orders.get(0).get("limit_price"));
        assertNull(orders.get(0).get("stop_price"));
        assertEquals(7740.0, orders.get(1).get("stop_price"));
    }

    @Test
    void closingFillIsConvertedToBaseCurrencyAndAttributedToItsStrategy() throws Exception {
        FakeClient client = new FakeClient();
        client.executions.add(openingBuy("e-open"));
        client.executions.add(closingSell("e-close", 21.88));
        RecordingSender sender = new RecordingSender();

        HermesIbkrPublisher.CycleResult r = publisher(client, sender).runOnce();

        assertEquals(1, r.newClosingFills(), "the opening execution has no realized P&L");
        assertEquals(1, r.pnlEntriesPushed());
        Map<String, Object> pnl = sender.last(HermesIbkrPublisher.PNL_IMPORT_PATH);
        assertEquals("ibkr", pnl.get("broker"));
        @SuppressWarnings("unchecked") Map<String, Object> entry = ((List<Map<String, Object>>) pnl.get("entries")).get(0);
        assertEquals("e-close", entry.get("fill_ref"));
        assertEquals("strategy", entry.get("kind"));
        assertEquals("orb", entry.get("strategy"));
        assertEquals("TP", entry.get("exit_reason"));
        assertEquals("BUY", entry.get("direction"), "a closing SELL closes a BUY trade");
        assertEquals(30.1944, entry.get("pl"));
        assertEquals(21.88, entry.get("pl_native"));
        assertEquals("USD", entry.get("currency"));
        assertEquals("815000001", entry.get("order_id"));
    }

    @Test
    void aFillIsPushedOnceAcrossCyclesAndRestarts() throws Exception {
        FakeClient client = new FakeClient();
        client.executions.add(closingSell("e-1", 5));
        RecordingSender sender = new RecordingSender();
        publisher(client, sender).runOnce();

        HermesIbkrPublisher.CycleResult again = publisher(client, sender).runOnce(); // new instance = restart

        assertEquals(0, again.newClosingFills());
        assertEquals(0, again.pnlEntriesPushed());
        assertEquals(1, sender.count(HermesIbkrPublisher.PNL_IMPORT_PATH), "heartbeat not due within the hour");
    }

    @Test
    void aFailedPushKeepsTheFillInTheOutboxAndRetriesIt() throws Exception {
        FakeClient client = new FakeClient();
        client.executions.add(closingSell("e-1", 5));
        RecordingSender sender = new RecordingSender();
        sender.pnlStatus = 500;

        HermesIbkrPublisher.CycleResult failed = publisher(client, sender).runOnce();
        assertEquals(1, failed.outboxSize());
        assertTrue(failed.error().contains("HTTP 500"));

        client.executions.clear(); // next IB day: the execution is gone from reqExecutions
        sender.pnlStatus = 200;
        HermesIbkrPublisher.CycleResult retried = publisher(client, sender).runOnce();

        assertEquals(1, retried.pnlEntriesPushed());
        assertEquals(0, retried.outboxSize());
    }

    @Test
    void missingExchangeRateDefersTheFillInsteadOfGuessing() {
        FakeClient client = new FakeClient();
        client.account = new IbkrAccountDetails(ACCOUNT, "CAD", Map.of(), Map.of());
        client.executions.add(closingSell("e-1", 5));
        RecordingSender sender = new RecordingSender();

        HermesIbkrPublisher.CycleResult r = publisher(client, sender).runOnce();

        assertEquals(0, r.newClosingFills());
        assertTrue(r.error().contains("exchange rate"));
    }

    @Test
    void unreachableGatewayReportsWithoutPosting() {
        FakeClient client = new FakeClient() {
            @Override public void connect() { throw new IllegalStateException("connection refused"); }
        };
        client.connected = false;
        RecordingSender sender = new RecordingSender();

        HermesIbkrPublisher.CycleResult r = publisher(client, sender).runOnce();

        assertFalse(r.snapshotPushed());
        assertTrue(sender.paths.isEmpty());
        assertTrue(r.error().contains("not reachable"));
    }

    @Test
    void attributionTreatsNonCanonicalRefsAsLeaks() {
        assertEquals("orb", HermesIbkrPublisher.attribute("MES-ORB-20261019-1400-BUY-SL").strategy());
        assertEquals("SL", HermesIbkrPublisher.attribute("MES-ORB-20261019-1400-BUY-SL").exitReason());
        assertEquals("ORDER", HermesIbkrPublisher.attribute("SPY-VWAP-20261019-1530-SELL").exitReason());
        assertNull(HermesIbkrPublisher.attribute("3f2a9c1e-uuid").strategy());
        assertNull(HermesIbkrPublisher.attribute(null).strategy());
        assertEquals("MANUAL", HermesIbkrPublisher.attribute("").exitReason());
        assertEquals("live", HermesIbkrPublisher.environmentOf("U7654321"));
    }

    @Test
    void stateFileIsWritten() throws Exception {
        FakeClient client = new FakeClient();
        client.executions.add(closingSell("e-1", 5));
        publisher(client, new RecordingSender()).runOnce();
        Map<String, Object> state = json.readValue(stateDir.resolve("hermes-ibkr-state.json").toFile(),
            new TypeReference<>() {});
        assertEquals(List.of("e-1"), state.get("sent_fill_refs"));
    }
}
