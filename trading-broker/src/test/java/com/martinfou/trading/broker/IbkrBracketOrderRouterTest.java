package com.martinfou.trading.broker;

import com.martinfou.trading.core.Order;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("IbkrBracketOrderRouter Unit Tests (Story 46.2)")
class IbkrBracketOrderRouterTest {

    @Test
    @DisplayName("Should create linked bracket bundle with native OCA group and quantized prices")
    void testCreateBracketBundle() {
        Order entry = new Order("MES", Order.Side.BUY, Order.Type.MARKET, 1.0, 5000.0)
            .withStrategyId("STRAT-SQUEEZE");

        // Pass unquantized prices: SL 4980.1234 -> should snap to 4980.00 or 4980.25; TP 5050.3333 -> 5050.25
        IbkrBracketOrderRouter.BracketBundle bundle = IbkrBracketOrderRouter.createBracket(
            entry, 4980.1234, 5050.3333
        );

        assertNotNull(bundle);
        assertNotNull(bundle.ocaGroup());
        assertTrue(bundle.ocaGroup().startsWith("TB-OCA-"));

        // Parent entry order must NOT share the SL/TP OCA group (else its fill cancels the
        // protective orders and leaves a naked position).
        assertEquals(Order.Side.BUY, bundle.parentEntryOrder().side());
        assertNull(bundle.parentEntryOrder().ocaGroup());
        assertEquals(0, bundle.parentEntryOrder().ocaType());

        // Stop Loss Child
        assertNotNull(bundle.stopLossOrder());
        assertEquals(Order.Side.SELL, bundle.stopLossOrder().side());
        assertEquals(Order.Type.STOP, bundle.stopLossOrder().type());
        assertEquals(4980.00, bundle.stopLossOrder().price(), 0.001);
        assertEquals(bundle.parentEntryOrder().id(), bundle.stopLossOrder().parentId());
        assertEquals(bundle.ocaGroup(), bundle.stopLossOrder().ocaGroup());
        assertTrue(bundle.stopLossOrder().isCloseOnly());

        // Take Profit Child
        assertNotNull(bundle.takeProfitOrder());
        assertEquals(Order.Side.SELL, bundle.takeProfitOrder().side());
        assertEquals(Order.Type.LIMIT, bundle.takeProfitOrder().type());
        assertEquals(5050.25, bundle.takeProfitOrder().price(), 0.001);
        assertEquals(bundle.parentEntryOrder().id(), bundle.takeProfitOrder().parentId());
        assertEquals(bundle.ocaGroup(), bundle.takeProfitOrder().ocaGroup());
        assertTrue(bundle.takeProfitOrder().isCloseOnly());

        assertEquals(3, bundle.allOrders().size());
    }

    @Test
    @DisplayName("Should update trailing stop with tick quantization")
    void testUpdateTrailingStop() {
        Order stopOrder = new Order("MES", Order.Side.SELL, Order.Type.STOP, 1.0, 4980.0);
        Order updated = IbkrBracketOrderRouter.updateTrailingStop(stopOrder, 4995.3333);

        assertEquals(4995.25, updated.price(), 0.001);
        assertEquals(stopOrder.id(), updated.id());
    }
}
