package com.oneday.orders.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.List;

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
@Validated
public class IdempotencyProperties {

    /**
     * How long an idempotency key is retained after first use.
     * After expiry the key is removed by {@code IdempotencyKeyPurgeJob} and a
     * client that reuses the same key will be treated as a fresh request.
     * Default: 24 hours.
     */
    @NotNull
    private Duration ttl = Duration.ofHours(24);

    /**
     * Ant-style URL pattern to which the idempotency filter applies.
     * Requests whose path does not match this pattern are passed through
     * without any idempotency enforcement.
     * Default: all order API paths.
     */
    @NotBlank
    private String applyToPathPattern = "/api/v1/**";

    /**
     * Ant-style URL patterns that are exempt from idempotency enforcement even though they
     * match {@link #applyToPathPattern}. Use for simple resource CRUD that doesn't need
     * replay protection (e.g. saved-address and cart-item management); booking, payment,
     * and cart checkout deliberately stay enforced.
     */
    @NotNull
    private List<String> exemptPathPatterns = List.of(
            "/api/v1/addresses/**",
            "/api/v1/addresses",
            "/api/v1/cart/items/**",
            "/api/v1/cart/items",
            "/api/v1/bulk/**"
    );

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

    public List<String> getExemptPathPatterns() {
        return exemptPathPatterns;
    }

    public void setExemptPathPatterns(List<String> exemptPathPatterns) {
        this.exemptPathPatterns = exemptPathPatterns;
    }
}
