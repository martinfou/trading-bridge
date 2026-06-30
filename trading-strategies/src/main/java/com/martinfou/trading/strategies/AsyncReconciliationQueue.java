package com.martinfou.trading.strategies;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Thread-safe single-threaded queue for asynchronous OANDA trade reconciliation.
 * Enforces rate limiting, retries failed attempts with exponential backoff, and falls back if needed.
 */
public class AsyncReconciliationQueue {
    private static final Logger log = LoggerFactory.getLogger(AsyncReconciliationQueue.class);
    
    public static final AsyncReconciliationQueue GLOBAL = new AsyncReconciliationQueue();

    public static class ReconciliationTask {
        final LiveStrategyRunner runner;
        final String tradeId;
        int attempts = 0;
        long nextRunTime = 0;

        public ReconciliationTask(LiveStrategyRunner runner, String tradeId) {
            this.runner = runner;
            this.tradeId = tradeId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ReconciliationTask)) return false;
            ReconciliationTask that = (ReconciliationTask) o;
            return tradeId.equals(that.tradeId);
        }

        @Override
        public int hashCode() {
            return tradeId.hashCode();
        }
    }

    private final LinkedBlockingQueue<ReconciliationTask> queue = new LinkedBlockingQueue<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "async-reconciliation-worker");
        t.setDaemon(true);
        return t;
    });

    private AsyncReconciliationQueue() {
        executor.submit(this::workerLoop);
    }

    /**
     * Submits a new trade ID for reconciliation.
     */
    public void submit(LiveStrategyRunner runner, String tradeId) {
        if (tradeId == null || runner == null) return;
        ReconciliationTask task = new ReconciliationTask(runner, tradeId);
        if (!queue.contains(task)) {
            queue.offer(task);
            log.info("Queued reconciliation task for trade ID: {}", tradeId);
        }
    }

    private void workerLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                ReconciliationTask task = queue.take();
                long now = System.currentTimeMillis();
                if (now < task.nextRunTime) {
                    // Task is not ready yet, sleep briefly and put it back to the end of the queue
                    Thread.sleep(100);
                    queue.offer(task);
                    continue;
                }

                try {
                    log.info("Processing reconciliation for trade ID: {}, attempt {}", task.tradeId, task.attempts + 1);
                    task.runner.reconcileTrade(task.tradeId);
                } catch (Exception e) {
                    task.attempts++;
                    if (task.attempts >= 5) {
                        log.error("❌ Max reconciliation attempts (5) reached for trade ID {}. Applying fallback local reconciliation.", task.tradeId, e);
                        try {
                            task.runner.reconcileTradeFallback(task.tradeId);
                        } catch (Exception fe) {
                            log.error("Failed final fallback reconciliation: {}", fe.getMessage());
                        }
                    } else {
                        // Exponential backoff: 2s, 4s, 8s, 16s...
                        long backoffMs = (long) Math.pow(2, task.attempts) * 1000L;
                        task.nextRunTime = System.currentTimeMillis() + backoffMs;
                        log.warn("⚠️ Reconciliation failed for trade ID {} (error: {}). Retrying in {} ms (attempt {}/5)", 
                            task.tradeId, e.getMessage(), backoffMs, task.attempts);
                        queue.offer(task);
                    }
                }
                
                // Rate limit: Sleep 1 second between requests
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Unexpected error in reconciliation worker loop", e);
            }
        }
    }
}
