package com.oneday.common.kafka.events.cron;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.enums.CronEventType;

import java.util.List;
import java.util.UUID;

/**
 * Run-time: a missing / extra / rejected parcel at a stop (M6-D-018). → M11, M10.
 * {@code discrepancyType} mirrors the routing {@code DiscrepancyType} enum (MISSING|EXTRA|REJECTED).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HandoffDiscrepancyEvent(
        UUID manifestId,
        UUID vanId,
        UUID cityId,
        int stopSeq,
        UUID daId,
        String discrepancyType,
        List<UUID> parcelIds) implements CronEventPayload {

    @Override
    public CronEventType eventType() {
        return CronEventType.HANDOFF_DISCREPANCY;
    }

    @Override
    public String partitionKey() {
        return vanId != null ? vanId.toString() : null;
    }
}
