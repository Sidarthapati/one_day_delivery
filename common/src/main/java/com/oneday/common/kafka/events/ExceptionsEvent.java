package com.oneday.common.kafka.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.DomainEvent;
import com.oneday.common.kafka.enums.ExceptionsEventType;

import java.util.UUID;

/**
 * Inbound event consumed by M4 from {@code oneday.exceptions.events} (produced by M11).
 *
 * <p>Minimal consumption contract — see {@link DaEvent} for the tolerant-reader rationale.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExceptionsEvent(UUID shipmentId, ExceptionsEventType eventType) implements DomainEvent {

    @Override
    public String partitionKey() {
        return shipmentId != null ? shipmentId.toString() : null;
    }

    @Override
    public String eventTypeName() {
        return eventType != null ? eventType.name() : null;
    }
}
