package com.oneday.common.kafka.events.cron;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.enums.CronEventType;

import java.time.Instant;
import java.util.UUID;

/**
 * Run-time: a parcel can't fit a feasible loop before its deadline (M6-D-017). → M10, station mgr.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LoopOverflowEvent(
        UUID cityId,
        UUID vanId,
        UUID parcelId,
        int loopIndex,
        Instant deadline) implements CronEventPayload {

    @Override
    public CronEventType eventType() {
        return CronEventType.LOOP_OVERFLOW;
    }

    @Override
    public String partitionKey() {
        return vanId != null ? vanId.toString() : (cityId != null ? cityId.toString() : null);
    }
}
