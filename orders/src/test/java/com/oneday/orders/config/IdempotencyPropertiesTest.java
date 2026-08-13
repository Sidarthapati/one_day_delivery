package com.oneday.orders.config;

import org.junit.jupiter.api.Test;
import org.springframework.util.AntPathMatcher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the exempt-path list against a class of silent bug: GPS telemetry heartbeats sit under
 * {@code /api/v1/**} (so the idempotency filter matches them) but must be exempt — the shuttle/van
 * apps fire-and-forget them every ~12s with no Idempotency-Key, and without the exemption every
 * ping is rejected 400 IDEMPOTENCY_KEY_REQUIRED and silently dropped.
 */
class IdempotencyPropertiesTest {

    private final AntPathMatcher matcher = new AntPathMatcher();
    private final IdempotencyProperties props = new IdempotencyProperties();

    private boolean isExempt(String uri) {
        return props.getExemptPathPatterns().stream().anyMatch(p -> matcher.match(p, uri));
    }

    @Test
    void telemetryHeartbeatsAreExempt() {
        assertTrue(isExempt("/api/v1/shuttle/eeceaa7e-07b2-48c8-8fef-a740312ff41e/telemetry"),
                "shuttle telemetry must be exempt or every GPS ping 400s");
        assertTrue(isExempt("/api/v1/van/123e4567-e89b-12d3-a456-426614174000/telemetry"),
                "van telemetry must be exempt or every GPS ping 400s");
    }

    @Test
    void bookingStaysEnforced() {
        assertFalse(isExempt("/api/v1/b2c/shipments"), "booking must keep idempotency enforcement");
    }
}
