package com.oneday.common.kafka.events.cron;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.enums.CronEventType;

import java.time.Instant;
import java.util.UUID;

/**
 * Run-time: a van was disabled mid-loop (M6-D-021). → M10, station mgr, M11.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VanBreakdownEvent(
        UUID vanId,
        UUID cityId,
        UUID routePlanId,
        double lastLat,
        double lastLon,
        Instant reportedAt) implements CronEventPayload {

    @Override
    public CronEventType eventType() {
        return CronEventType.VAN_BREAKDOWN;
    }

    @Override
    public String partitionKey() {
        return vanId != null ? vanId.toString() : null;
    }
}
