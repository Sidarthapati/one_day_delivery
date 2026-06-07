package com.oneday.common.kafka.events.cron;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.enums.CronEventType;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Plan-time: a DA's meeting vertex and the full day's meeting times (M6-D-008). → M5.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DaCronScheduledEvent(
        UUID cityId,
        LocalDate validDate,
        UUID daId,
        UUID cronVertexId,
        double meetingLat,
        double meetingLon,
        List<LocalTime> meetingTimes,
        UUID vanId,
        UUID routePlanId) implements CronEventPayload {

    @Override
    public CronEventType eventType() {
        return CronEventType.DA_CRON_SCHEDULED;
    }

    @Override
    public String partitionKey() {
        return daId != null ? daId.toString() : null;
    }
}
