package com.oneday.common.kafka.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.DomainEvent;
import com.oneday.common.kafka.enums.MeasurementEventType;

import java.time.Instant;
import java.util.UUID;

/**
 * A parcel-dimension measurement was recorded at some point in the chain (first-mile DA scan today).
 * Emitted by M4 on {@code oneday.shipments.events}. The routing key is the {@link MeasurementEventType}
 * — {@code DIMENSION_DISCREPANCY_FLAGGED} is the hook a future chargeback/ops flow consumes; the hub
 * measurement remains the billing source of truth, so this event never charges anyone by itself.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ParcelMeasuredEvent(
        UUID eventId,
        MeasurementEventType eventType,
        Instant occurredAt,
        UUID shipmentId,
        String shipmentRef,
        String source,
        String status,
        Double lengthCm,
        Double widthCm,
        Double heightCm,
        Integer volumetricWeightGrams,
        Short declaredLengthCm,
        Short declaredWidthCm,
        Short declaredHeightCm,
        boolean overDeclared,
        String discrepancyDetail,
        UUID measuredBy) implements DomainEvent {

    @Override
    public String partitionKey() {
        return shipmentId != null ? shipmentId.toString() : shipmentRef;
    }

    @Override
    public String eventTypeName() {
        return eventType.name();
    }
}
