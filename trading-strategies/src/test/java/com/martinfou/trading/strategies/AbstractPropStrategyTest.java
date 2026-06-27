package com.martinfou.trading.strategies;

import com.martinfou.trading.core.Bar;
import com.martinfou.trading.core.Order;
import com.martinfou.trading.strategies.prop.AbstractPropStrategy;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class AbstractPropStrategyTest {

    static class TestPropStrategy extends AbstractPropStrategy {
        public TestPropStrategy(String name, String symbol) {
            super(name, symbol);
        }
        @Override
        protected void evaluate(Bar bar) {}
    }

    @Test
    void testSyncPosition() throws Exception {
        TestPropStrategy strategy = new TestPropStrategy("TestProp", "EUR_USD");
        
        // Initial state
        var activeSideField = AbstractPropStrategy.class.getDeclaredField("activeSide");
        activeSideField.setAccessible(true);
        assertNull(activeSideField.get(strategy));

        // Sync long position
        strategy.syncPosition(Order.Side.BUY, 1000.0, 1.0900, 1.1200);
        assertEquals(com.martinfou.trading.core.indicators.Indicators.TradeSide.LONG, activeSideField.get(strategy));
        
        var activeSlField = AbstractPropStrategy.class.getDeclaredField("activeSl");
        activeSlField.setAccessible(true);
        assertEquals(1.0900, (Double) activeSlField.get(strategy), 1e-9);

        // Sync short position
        strategy.syncPosition(Order.Side.SELL, 1000.0, 1.1200, 1.0900);
        assertEquals(com.martinfou.trading.core.indicators.Indicators.TradeSide.SHORT, activeSideField.get(strategy));

        // Sync flat
        strategy.syncPosition(null, 0.0, 0.0, 0.0);
        assertNull(activeSideField.get(strategy));
    }
}
