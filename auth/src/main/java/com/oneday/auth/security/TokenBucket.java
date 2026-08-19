package com.oneday.auth.security;

/**
 * Minimal thread-safe token bucket: {@code capacity} tokens, refilled continuously so the bucket
 * tops up over {@code windowNanos}. Per-instance, in-memory. No external dependency (keeps the
 * supply-chain surface unchanged).
 */
final class TokenBucket {

    private final double capacity;
    private final double refillPerNano;
    private double tokens;
    private long lastRefillNanos;
    private volatile long lastAccessMillis;

    TokenBucket(int capacity, long windowNanos) {
        this.capacity = capacity;
        this.refillPerNano = (double) capacity / windowNanos;
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
        this.lastAccessMillis = System.currentTimeMillis();
    }

    synchronized boolean tryConsume() {
        refill();
        this.lastAccessMillis = System.currentTimeMillis();
        if (tokens >= 1d) {
            tokens -= 1d;
            return true;
        }
        return false;
    }

    /** Seconds until at least one token is available (>= 1). Call after a rejected consume. */
    synchronized long retryAfterSeconds() {
        refill();
        if (tokens >= 1d) {
            return 1;
        }
        double deficit = 1d - tokens;
        double secondsToOne = deficit / (refillPerNano * 1_000_000_000d);
        return Math.max(1, (long) Math.ceil(secondsToOne));
    }

    long lastAccessMillis() {
        return lastAccessMillis;
    }

    private void refill() {
        long now = System.nanoTime();
        double replenished = (now - lastRefillNanos) * refillPerNano;
        if (replenished > 0) {
            tokens = Math.min(capacity, tokens + replenished);
            lastRefillNanos = now;
        }
    }
}
