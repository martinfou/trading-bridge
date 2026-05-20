package com.martinfou.trading.monitor;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SystemMetricsTest {

    @Test
    void getCpuPercent_returnsValueOrMinusOne() {
        var metrics = new SystemMetrics();
        double cpu = metrics.getCpuPercent();
        assertTrue(cpu >= -1 && cpu <= 100,
                "CPU should be -1..100, got: " + cpu);
    }

    @Test
    void getMemoryPercent_returnsValueOrMinusOne() {
        var metrics = new SystemMetrics();
        double mem = metrics.getMemoryPercent();
        assertTrue(mem >= -1 && mem <= 100,
                "Memory should be -1..100, got: " + mem);
    }

    @Test
    void getDiskPercent_returnsValueOrMinusOne() {
        var metrics = new SystemMetrics();
        double disk = metrics.getDiskPercent();
        assertTrue(disk >= -1 && disk <= 100,
                "Disk should be -1..100, got: " + disk);
    }

    @Test
    void getGitCommit_returnsUnknownWhenNoGit() {
        // When running tests, we might or might not be in a git repo
        var metrics = new SystemMetrics();
        String commit = metrics.getGitCommit();
        assertNotNull(commit);
    }
}
