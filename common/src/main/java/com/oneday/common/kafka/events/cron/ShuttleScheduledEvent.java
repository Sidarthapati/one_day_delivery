package com.oneday.common.kafka.events.cron;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.enums.CronEventType;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Plan-time: the periodic hub↔airport shuttle timetable (M6 §9). → M9, M10.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ShuttleScheduledEvent(
        UUID cityId,
        LocalDate validDate,
        UUID routePlanId,
        List<LocalTime> departureTimes,
        int hubToAirportMinutes) implements CronEventPayload {

    @Override
    public CronEventType eventType() {
        return CronEventType.SHUTTLE_SCHEDULED;
    }

    @Override
    public String partitionKey() {
        return cityId != null ? cityId.toString() : null;
    }
}
