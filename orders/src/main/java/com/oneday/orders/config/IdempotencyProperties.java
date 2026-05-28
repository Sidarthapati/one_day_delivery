package com.oneday.orders.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Configuration properties for the idempotency infrastructure.
 *
 * <p>Defaults are production-safe. Override in {@code application.yml} under the
 * {@code orders.idempotency} prefix.</p>
 *
 * <pre>{@code
 * orders:
 *   idempotency:
 *     ttl: 24h
 *     apply-to-path-pattern: /api/v1/**
 * }</pre>
 */
@Component
@ConfigurationProperties(prefix = "orders.idempotency")
public class IdempotencyProperties {

    /**
     * How long an idempotency key is retained after first use.
     * After expiry the key is removed by {@code IdempotencyKeyPurgeJob} and a
     * client that reuses the same key will be treated as a fresh request.
     * Default: 24 hours.
     */
    private Duration ttl = Duration.ofHours(24);

    /**
     * Ant-style URL pattern to which the idempotency filter applies.
     * Requests whose path does not match this pattern are passed through
     * without any idempotency enforcement.
     * Default: all order API paths.
     */
    private String applyToPathPattern = "/api/v1/**";

    public Duration getTtl() {
        return ttl;
    }

    public void setTtl(Duration ttl) {
        this.ttl = ttl;
    }

    public String getApplyToPathPattern() {
        return applyToPathPattern;
    }

    public void setApplyToPathPattern(String applyToPathPattern) {
        this.applyToPathPattern = applyToPathPattern;
    }
}
