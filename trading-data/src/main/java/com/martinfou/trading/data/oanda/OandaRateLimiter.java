package com.martinfou.trading.data.oanda;

/**
 * Thread-safe Token Bucket rate limiter for OANDA API requests.
 * Distinguishes between high-priority (prices, orders) and low-priority (reconciliation) calls.
 */
public class OandaRateLimiter {
    
    public static final OandaRateLimiter GLOBAL = new OandaRateLimiter(120, 120);

    private final int capacity;
    private final double refillPerMs;
    private double tokens;
    private long lastRefillTime;

    public OandaRateLimiter(int capacity, int refillPerSecond) {
        this.capacity = capacity;
        this.refillPerMs = (double) refillPerSecond / 1000.0;
        this.tokens = capacity;
        this.lastRefillTime = System.currentTimeMillis();
    }

    /**
     * Acquires a token. Blocks if no tokens are available.
     * High priority requests can consume down to 0 tokens.
     * Low priority requests can only consume if bucket level > 20% of capacity.
     */
    public synchronized void acquire(boolean highPriority) throws InterruptedException {
        double threshold = highPriority ? 0.0 : (capacity * 0.20);
        
        while (true) {
            refill();
            if (tokens >= threshold + 1.0) {
                tokens -= 1.0;
                return;
            }
            
            // Calculate time required to refill enough tokens
            double needed = (threshold + 1.0) - tokens;
            long sleepTime = (long) (needed / refillPerMs);
            sleepTime = Math.max(10, Math.min(1000, sleepTime));
            Thread.sleep(sleepTime);
        }
    }

    private void refill() {
        long now = System.currentTimeMillis();
        long elapsed = now - lastRefillTime;
        if (elapsed > 0) {
            double newTokens = elapsed * refillPerMs;
            tokens = Math.min(capacity, tokens + newTokens);
            lastRefillTime = now;
        }
    }

    public synchronized double getTokens() {
        refill();
        return tokens;
    }
}
