package com.oneday.common.kafka.events.cron;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.enums.CronEventType;

import java.util.UUID;

/**
 * Run-time: live ETA slipped past the lateness threshold. → M5, M10, ops.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VanRunningLateEvent(
        UUID vanId,
        UUID cityId,
        UUID routePlanId,
        int loopIndex,
        int stopSeq,
        int minutesLate) implements CronEventPayload {

    @Override
    public CronEventType eventType() {
        return CronEventType.VAN_RUNNING_LATE;
    }

    @Override
    public String partitionKey() {
        return vanId != null ? vanId.toString() : null;
    }
}
