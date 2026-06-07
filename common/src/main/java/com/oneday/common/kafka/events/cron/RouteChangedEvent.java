package com.oneday.common.kafka.events.cron;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.enums.CronEventType;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Plan-time: an intraday override took effect (new append-only plan revision). → M5, M10, station mgr.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RouteChangedEvent(
        UUID cityId,
        LocalDate validDate,
        UUID routePlanId,
        UUID actorId,
        String reason) implements CronEventPayload {

    @Override
    public CronEventType eventType() {
        return CronEventType.ROUTE_CHANGED;
    }

    @Override
    public String partitionKey() {
        return cityId != null ? cityId.toString() : null;
    }
}
