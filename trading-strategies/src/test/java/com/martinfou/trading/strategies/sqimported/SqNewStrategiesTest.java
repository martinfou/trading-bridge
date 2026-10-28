package com.martinfou.trading.strategies.sqimported;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.core.Strategy;
import com.martinfou.trading.strategies.StrategyCatalog;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings("checkstyle:TypeName")
class SqNewStrategiesTest {

  @Test
  void testAllSevenStrategiesRegisteredInCatalogsAndAutoClassified() {
    Map<String, String> expectedDefaults = Map.of(
        "SqMnqM15Momentum04248", "MNQ",
        "SqMnqM15Breakout01499", "MNQ",
        "SqMesH1MeanRev03553", "MES",
        "SqMesH1Pullback02700", "MES",
        "SqGbpJpyH1Breakout339125", "GBP_JPY",
        "SqGbpJpyH1Breakout220102", "GBP_JPY",
        "SqMgcM30Breakout129102", "MGC"
    );

    Map<String, String> expectedStyles = Map.of(
        "SqMnqM15Momentum04248", "MOMENTUM",
        "SqMnqM15Breakout01499", "BREAKOUT",
        "SqMesH1MeanRev03553", "MEAN_REVERSION",
        "SqMesH1Pullback02700", "TREND_FOLLOWING",
        "SqGbpJpyH1Breakout339125", "BREAKOUT",
        "SqGbpJpyH1Breakout220102", "BREAKOUT",
        "SqMgcM30Breakout129102", "BREAKOUT"
    );

    for (var entry : expectedDefaults.entrySet()) {
      String key = entry.getKey();
      String expectedSymbol = entry.getValue();

      Strategy s = SqImportedStrategyCatalog.create(key);
      assertNotNull(s, key + " must instantiate from SqImportedStrategyCatalog");
      assertEquals(expectedSymbol, SqImportedStrategyCatalog.defaultSymbol(key));

      assertTrue(StrategyCatalog.contains(key), key + " must be in unified StrategyCatalog");
      assertEquals(StrategyCatalog.Family.SQ_IMPORTED, StrategyCatalog.family(key));
      assertEquals(expectedSymbol, StrategyCatalog.defaultSymbol(key));

      var desc = StrategyCatalog.entries().stream()
          .filter(d -> d.id().equals(key))
          .findFirst()
          .orElseThrow();
      assertEquals(expectedStyles.get(key), desc.tradingStyle(),
          key + " should auto-resolve tradingStyle from archetype token");
    }
  }

  @Test
  void testMnq04248FiresWithZeroMartingaleAndProtectiveStops() {
    SqMnqM15Momentum04248 strategy = new SqMnqM15Momentum04248("MNQ");
    Instant t0 = Instant.parse("2026-10-01T12:00:00Z");

    for (int i = 0; i < 105; i++) {
      double price = 20000.0 + (i % 5) * 2.0;
      strategy.onBar(new Bar("MNQ", t0.plusSeconds(i * 900L), price - 5.0, price + 10.0, price - 10.0, price, 100L));
    }

    List<Order> orders = strategy.getPendingOrders();
    assertEquals(1, orders.size(), "Expected 1 MNQ 0.4248 order");
    Order o = orders.get(0);
    assertEquals("MNQ", o.symbol());
    assertEquals(1.0, o.quantity(), 1e-6, "Strict 1.0 contract (Zero Martingale)");
    assertEquals("sq-mnq-m15-momentum-0-4248", o.strategyId());
    assertTrue(o.hasProtectiveStop(), "Must carry protective stop loss");
    assertTrue(o.takeProfit() > 0, "Must carry take profit");
  }

  @Test
  void testMnq01499FiresOnSuperTrendBreakout() {
    SqMnqM15Breakout01499 strategy = new SqMnqM15Breakout01499("MNQ");
    Instant t0 = Instant.parse("2026-10-01T12:00:00Z");

    // 70 tight consolidation bars
    for (int i = 0; i < 70; i++) {
      strategy.onBar(new Bar("MNQ", t0.plusSeconds(i * 900L), 20000.0, 20002.0, 19998.0, 20000.0, 100L));
    }
    // Strong impulse bars above SuperTrend(24, 6.9) and Highest(58) + 2.3*ATR
    strategy.onBar(new Bar("MNQ", t0.plusSeconds(70 * 900L), 20000.0, 20050.0, 20000.0, 20045.0, 500L));
    strategy.onBar(new Bar("MNQ", t0.plusSeconds(71 * 900L), 20045.0, 20090.0, 20040.0, 20085.0, 500L));

    List<Order> orders = strategy.getPendingOrders();
    assertEquals(1, orders.size(), "Expected 1 MNQ 0.1499 breakout order");
    Order o = orders.get(0);
    assertEquals("MNQ", o.symbol());
    assertEquals(Order.Side.BUY, o.side());
    assertEquals(1.0, o.quantity(), 1e-6);
    assertEquals("sq-mnq-m15-breakout-0-1499", o.strategyId());
    assertTrue(o.stopLoss() > 0 && o.stopLoss() < o.price());
    assertTrue(o.takeProfit() > o.price());
  }

  @Test
  void testMes03553AndMes02700FireWithProtectiveStops() {
    SqMesH1MeanRev03553 mes3553 = new SqMesH1MeanRev03553("MES");
    Instant t0 = Instant.parse("2026-10-01T12:00:00Z");

    // Gentle steady uptrend with alternating high/low wicks so ADX(40) stays low (~15) while RSI(20) > 75
    for (int i = 0; i < 65; i++) {
      double base = 5800.0 + i * 0.5;
      double highWick = (i % 2 == 0) ? 6.0 : 2.0;
      double lowWick = (i % 2 == 0) ? 2.0 : 6.0;
      mes3553.onBar(new Bar("MES", t0.plusSeconds(i * 3600L), base - 0.5, base + highWick, base - lowWick, base + 0.5, 200L));
    }
    List<Order> orders3553 = mes3553.getPendingOrders();
    assertEquals(1, orders3553.size(), "Expected 1 MES 0.3553 order");
    Order o1 = orders3553.get(0);
    assertEquals("MES", o1.symbol());
    assertEquals(1.0, o1.quantity(), 1e-6);
    assertEquals("sq-mes-h1-meanrev-0-3553", o1.strategyId());
    assertTrue(o1.hasProtectiveStop());

    SqMesH1Pullback02700 mes2700 = new SqMesH1Pullback02700("MES");
    for (int i = 0; i < 50; i++) {
      mes2700.onBar(new Bar("MES", t0.plusSeconds(i * 3600L), 5800.0, 5805.0, 5795.0, 5800.0, 200L));
    }
    // Pullback bar dipping below LowD[1] - 1.4*ATR(30)
    mes2700.onBar(new Bar("MES", t0.plusSeconds(50 * 3600L), 5800.0, 5801.0, 5770.0, 5775.0, 400L));

    List<Order> orders2700 = mes2700.getPendingOrders();
    assertEquals(1, orders2700.size(), "Expected 1 MES 0.2700 limit pullback order");
    Order o2 = orders2700.get(0);
    assertEquals("MES", o2.symbol());
    assertEquals(1.0, o2.quantity(), 1e-6);
    assertEquals("sq-mes-h1-pullback-0-2700", o2.strategyId());
    assertTrue(o2.hasProtectiveStop());
  }

  @Test
  void testGbpJpyAndMgcStrategiesFireAndResetCleanly() {
    SqGbpJpyH1Breakout220102 gbp220 = new SqGbpJpyH1Breakout220102("GBP_JPY");
    Instant t0 = Instant.parse("2026-10-01T12:00:00Z");

    // High volatility bars followed by tighter bars so Keltner Upper is falling while price is above SMA(50)
    for (int i = 0; i < 40; i++) {
      gbp220.onBar(new Bar("GBP_JPY", t0.plusSeconds(i * 3600L), 195.0, 197.0, 193.0, 195.0, 100L));
    }
    for (int i = 40; i < 58; i++) {
      gbp220.onBar(new Bar("GBP_JPY", t0.plusSeconds(i * 3600L), 195.5, 195.8, 195.2, 195.6, 100L));
    }
    List<Order> gbpOrders = gbp220.getPendingOrders();
    assertEquals(1, gbpOrders.size(), "Expected 1 GBP_JPY 2.20.102 order");
    assertEquals(1000.0, gbpOrders.get(0).quantity(), 1e-6);
    assertEquals("sq-gbpjpy-h1-breakout-2-20-102", gbpOrders.get(0).strategyId());

    SqGbpJpyH1Breakout339125 gbp339 = new SqGbpJpyH1Breakout339125("GBP_JPY");
    for (int i = 0; i < 55; i++) {
      gbp339.onBar(new Bar("GBP_JPY", t0.plusSeconds(i * 3600L), 195.0, 195.1, 194.9, 195.0, 100L));
    }
    // Bar opens below BB(20, 2.0).Lower[1] and rallies above Highest(50)[1] + 1.6*SmallestRange(20)[2]
    gbp339.onBar(new Bar("GBP_JPY", t0.plusSeconds(55 * 3600L), 194.5, 196.0, 194.4, 195.8, 300L));
    List<Order> gbp339Orders = gbp339.getPendingOrders();
    assertEquals(1, gbp339Orders.size(), "Expected 1 GBP_JPY 3.39.125 order");
    assertEquals(1000.0, gbp339Orders.get(0).quantity(), 1e-6);
    assertEquals("sq-gbpjpy-h1-breakout-3-39-125", gbp339Orders.get(0).strategyId());

    SqMgcM30Breakout129102 mgc = new SqMgcM30Breakout129102("MGC");
    for (int i = 0; i < 425; i++) {
      mgc.onBar(new Bar("MGC", t0.plusSeconds(i * 1800L), 2700.0, 2702.0, 2698.0, 2700.0, 100L));
    }
    mgc.onBar(new Bar("MGC", t0.plusSeconds(425 * 1800L), 2700.0, 2720.0, 2699.0, 2715.0, 500L));
    List<Order> mgcOrders = mgc.getPendingOrders();
    assertEquals(1, mgcOrders.size(), "Expected 1 MGC 1.29.102 breakout order");
    assertEquals(1.0, mgcOrders.get(0).quantity(), 1e-6, "Strict 1.0 contract (Zero Martingale)");
    assertEquals("sq-mgc-m30-breakout-1-29-102", mgcOrders.get(0).strategyId());

    mgc.reset();
    assertTrue(mgc.getPendingOrders().isEmpty());
  }
}
