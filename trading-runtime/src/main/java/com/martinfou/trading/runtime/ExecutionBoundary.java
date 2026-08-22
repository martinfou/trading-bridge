package com.martinfou.trading.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ensures that unhandled exceptions in background threads safely terminate the process
 * instead of leaving the application in a zombie or inconsistent state.
 */
public final class ExecutionBoundary {

    private static final Logger log = LoggerFactory.getLogger(ExecutionBoundary.class);

    private ExecutionBoundary() {}

    /**
     * Executes a global background task within a circuit breaker boundary.
     * If an unhandled Throwable escapes the task, it logs the fatal error
     * and forcefully terminates the JVM.
     *
     * @param component the name of the component executing the task (for logging)
     * @param task      the task to execute
     */
    public static void executeGlobal(String component, Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            log.error("FATAL: Unhandled exception in global component '{}'. Shutting down JVM to prevent inconsistent state.", component, t);
            System.exit(1);
        }
    }

    /**
     * Executes a strategy-specific task within a circuit breaker boundary.
     * If an unhandled Throwable escapes the task, it attempts to trip the KillSwitchRegistry
     * for the specific strategy, then forcefully terminates the JVM.
     *
     * @param strategyId the strategy ID
     * @param killSwitch the kill switch registry (nullable)
     * @param task       the task to execute
     */
    public static void executeStrategy(String strategyId, KillSwitchRegistry killSwitch, Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            log.error("FATAL: Unhandled exception in strategy '{}'. Tripping kill switch and shutting down JVM.", strategyId, t);
            try {
                if (killSwitch != null && strategyId != null) {
                    killSwitch.kill(strategyId);
                }
            } catch (Exception e) {
                log.error("Failed to trip kill switch for {}", strategyId, e);
            }
            System.exit(1);
        }
    }
}
