package com.oneday.common.kafka.events.sla;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.domain.enums.SlaLegType;
import com.oneday.common.kafka.DomainEvent;
import com.oneday.common.kafka.enums.SlaEventType;

import java.time.Instant;
import java.util.UUID;

/**
 * M10 → M11 (exceptions) / Admin on {@code oneday.sla.events}: a parcel's SLA is confirmed breached
 * (the 16h internal target passed, or a hard failure). Carries the {@code reasonCode} M11 maps to a
 * breach reason. Keyed by shipment.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SlaBreachedEvent(
        UUID shipmentId,
        String shipmentRef,
        SlaLegType leg,
        String city,
        String reasonCode,
        Instant internalTargetAt,
        Instant breachedAt) implements DomainEvent {

    @Override
    public String partitionKey() {
        return shipmentId != null ? shipmentId.toString() : null;
    }

    @Override
    public String eventTypeName() {
        return SlaEventType.SLA_BREACHED.name();
    }
}
