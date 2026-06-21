package com.oneday.dispatch.metrics;

import com.oneday.dispatch.service.AssignmentOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * M5's Micrometer counters. Kept in one place so the service/job code records outcomes with a single
 * call and the metric names/tags stay consistent.
 */
@Component
public class DispatchMetrics {

    private final MeterRegistry registry;

    public DispatchMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** One assignment decision (ASSIGNED / CROSS_TERRITORY_ASSIGNED / DEFERRED), tagged by city. */
    public void assignment(AssignmentOutcome outcome, UUID cityId) {
        registry.counter("m5.assignment", "outcome", outcome.name(), "city", tag(cityId)).increment();
    }

    /** A deferred dispatch escalated to M11 after exhausting retries. */
    public void deferredEscalated(UUID cityId) {
        registry.counter("m5.deferred.escalated", "city", tag(cityId)).increment();
    }

    /** A pickup-OTP verification attempt, tagged by outcome (SUCCESS / INVALID / ERROR). */
    public void otpVerify(String outcome) {
        registry.counter("m5.otp.verify", "outcome", outcome).increment();
    }

    private static String tag(UUID cityId) {
        return cityId != null ? cityId.toString() : "unknown";
    }
}
