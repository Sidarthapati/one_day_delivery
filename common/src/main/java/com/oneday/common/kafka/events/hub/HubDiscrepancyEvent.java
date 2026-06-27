package com.oneday.common.kafka.events.hub;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.enums.HubEventType;

import java.util.UUID;

/**
 * Dock reconciliation mismatch — expected ≠ actual at receive (§6, C13) → M11, M10.
 * {@code discrepancyType} is the hub-local enum name (SHORTFALL | SURPLUS | MISSORT).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HubDiscrepancyEvent(
        UUID shipmentId,
        UUID cityId,
        UUID hubId,
        String arrivalMode,
        String discrepancyType) implements HubEventPayload {

    @Override
    public HubEventType eventType() {
        return HubEventType.HUB_DISCREPANCY;
    }

    @Override
    public String partitionKey() {
        return shipmentId != null ? shipmentId.toString()
                : (cityId != null ? cityId.toString() : null);
    }
}
