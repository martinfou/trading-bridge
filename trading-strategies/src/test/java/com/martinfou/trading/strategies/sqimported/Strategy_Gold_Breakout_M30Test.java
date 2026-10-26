package com.martinfou.trading.strategies.sqimported;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

@SuppressWarnings("checkstyle:TypeName")
class Strategy_Gold_Breakout_M30Test {

  @Test
  void testCatalogRegistrationAndDefaults() {
    Strategy strategy = SqImportedStrategyCatalog.create("Strategy_Gold_Breakout_M30");
    assertNotNull(strategy);
    assertEquals("SQ_Gold_Breakout_M30_1_10_110", strategy.name());
    assertEquals("MGC", SqImportedStrategyCatalog.defaultSymbol("Strategy_Gold_Breakout_M30"));
  }

  @Test
  void testZeroMartingaleAndD69ComplianceOnLongBreakout() {
    Strategy_Gold_Breakout_M30 strategy = new Strategy_Gold_Breakout_M30("MGC");

    // Feed 405 consolidation bars around 2700.00
    Instant now = Instant.parse("2026-10-25T12:00:00Z");
    double basePrice = 2700.0;
    for (int i = 0; i < 405; i++) {
      Instant barTime = now.plusSeconds(i * 1800L);
      Bar bar = new Bar("MGC", barTime, basePrice - 1.0, basePrice + 1.0, basePrice - 2.0, basePrice, 100L);
      strategy.onBar(bar);
      assertTrue(strategy.getPendingOrders().isEmpty(), "No order before breakout");
    }

    // Bar 406 breaks out above the highest close of 2700.00
    Instant breakoutTime = now.plusSeconds(405 * 1800L);
    Bar breakoutBar = new Bar("MGC", breakoutTime, 2700.0, 2715.0, 2699.0, 2710.0, 500L);
    strategy.onBar(breakoutBar);

    List<Order> orders = strategy.getPendingOrders();
    assertEquals(1, orders.size(), "Expected 1 breakout order");
    Order order = orders.get(0);

    // Mandate checks
    assertEquals("MGC", order.symbol());
    assertEquals(Order.Side.BUY, order.side());
    assertEquals(Order.Type.MARKET, order.type());

    // ZERO MARTINGALE: quantity must be strictly 1.0
    assertEquals(1.0, order.quantity(), 1e-6, "Quantity must strictly be 1.0 (Zero Martingale)");

    // Decision D69 compliance: must carry strategyId
    assertEquals("sq-gold-m30", order.strategyId(), "Must satisfy D69 strategyId tracking");

    // Must have non-zero protective StopLoss and TakeProfit
    assertTrue(order.stopLoss() > 0 && order.stopLoss() < order.price(), "SL must be below entry");
    assertTrue(order.takeProfit() > order.price(), "TP must be above entry");
  }

  @Test
  void testResetClearsState() {
    Strategy_Gold_Breakout_M30 strategy = new Strategy_Gold_Breakout_M30("MGC");
    Instant now = Instant.parse("2026-10-25T12:00:00Z");
    for (int i = 0; i < 50; i++) {
      strategy.onBar(new Bar("MGC", now.plusSeconds(i * 1800L), 2700.0, 2705.0, 2695.0, 2700.0, 10L));
    }
    strategy.reset();
    assertTrue(strategy.getPendingOrders().isEmpty());
  }
}
