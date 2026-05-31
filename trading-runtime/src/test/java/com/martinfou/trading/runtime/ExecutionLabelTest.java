package com.martinfou.trading.runtime;

import com.martinfou.trading.backtest.RunMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExecutionLabelTest {

    @Test
    void forRunMode_mapsStubPaperToPaperStub() {
        assertEquals(ExecutionLabel.BACKTEST, ExecutionLabel.forRunMode(RunMode.BACKTEST));
        assertEquals(ExecutionLabel.PAPER_STUB, ExecutionLabel.forRunMode(RunMode.PAPER));
        assertEquals(ExecutionLabel.LIVE_OANDA, ExecutionLabel.forRunMode(RunMode.LIVE));
    }

    @Test
    void countsTowardPaperPeriod_onlyOandaPaper() {
        assertFalse(ExecutionLabel.PAPER_STUB.countsTowardPaperPeriod());
        assertTrue(ExecutionLabel.PAPER_OANDA.countsTowardPaperPeriod());
        assertFalse(ExecutionLabel.PAPER_IBKR.countsTowardPaperPeriod());
        assertFalse(ExecutionLabel.BACKTEST.countsTowardPaperPeriod());
    }

    @Test
    void isBrokerBacked_includesOandaAndIbkr() {
        assertTrue(ExecutionLabel.PAPER_OANDA.isBrokerBacked());
        assertTrue(ExecutionLabel.LIVE_OANDA.isBrokerBacked());
        assertTrue(ExecutionLabel.PAPER_IBKR.isBrokerBacked());
        assertTrue(ExecutionLabel.LIVE_IBKR.isBrokerBacked());
        assertFalse(ExecutionLabel.PAPER_STUB.isBrokerBacked());
    }
}
