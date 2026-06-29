package com.martinfou.trading.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class DriftReporterTest {

    @Test
    void testCheckDriftWithNullService() {
        DriftReporter reporter = new DriftReporter(null);
        assertDoesNotThrow(reporter::checkDrift);
    }
}
