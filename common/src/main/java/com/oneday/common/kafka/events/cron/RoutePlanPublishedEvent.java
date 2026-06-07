package com.oneday.common.kafka.events.cron;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.enums.CronEventType;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Plan-time: a city's van plan was approved & is now active. → M10, van app.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RoutePlanPublishedEvent(
        UUID cityId,
        LocalDate validDate,
        UUID routePlanId,
        int vansUsed,
        int recommendedVanCount,
        String provisioningFlag) implements CronEventPayload {

    @Override
    public CronEventType eventType() {
        return CronEventType.ROUTE_PLAN_PUBLISHED;
    }

    @Override
    public String partitionKey() {
        return cityId != null ? cityId.toString() : null;
    }
}
