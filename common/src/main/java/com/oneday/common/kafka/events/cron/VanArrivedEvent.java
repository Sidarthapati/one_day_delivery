package com.oneday.common.kafka.events.cron;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.enums.CronEventType;

import java.time.Instant;
import java.util.UUID;

/**
 * Run-time: a van reached a meeting vertex. → M10, ops.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VanArrivedEvent(
        UUID vanId,
        UUID cityId,
        UUID routePlanId,
        int loopIndex,
        int stopSeq,
        UUID hexVertexId,
        Instant arrivedAt) implements CronEventPayload {

    @Override
    public CronEventType eventType() {
        return CronEventType.VAN_ARRIVED;
    }

    @Override
    public String partitionKey() {
        return vanId != null ? vanId.toString() : null;
    }
}
