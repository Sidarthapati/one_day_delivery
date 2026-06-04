package com.oneday.common.kafka.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.DomainEvent;
import com.oneday.common.kafka.enums.DaEventType;

import java.util.UUID;

/**
 * Inbound event consumed by M4 from {@code oneday.da.events} (produced by M5).
 *
 * <p>Minimal consumption contract: M4 needs only the shipment and the event type to drive its
 * state machine. The producing module (M5) owns the full payload and may ADD fields (DA id,
 * location, timestamps, …); this reader ignores anything it does not use
 * ({@code @JsonIgnoreProperties(ignoreUnknown = true)}), so producer additions never break it.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DaEvent(UUID shipmentId, DaEventType eventType) implements DomainEvent {

    @Override
    public String partitionKey() {
        return shipmentId != null ? shipmentId.toString() : null;
    }

    @Override
    public String eventTypeName() {
        return eventType != null ? eventType.name() : null;
    }
}
