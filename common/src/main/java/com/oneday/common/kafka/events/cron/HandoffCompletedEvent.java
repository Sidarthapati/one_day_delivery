package com.oneday.common.kafka.events.cron;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.enums.CronEventType;

import java.util.UUID;

/**
 * Run-time: a stop's per-DA handoff reconciled OK (expected == actual). → M4, M10.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HandoffCompletedEvent(
        UUID manifestId,
        UUID vanId,
        UUID cityId,
        int stopSeq,
        UUID daId) implements CronEventPayload {

    @Override
    public CronEventType eventType() {
        return CronEventType.HANDOFF_COMPLETED;
    }

    @Override
    public String partitionKey() {
        return vanId != null ? vanId.toString() : null;
    }
}
