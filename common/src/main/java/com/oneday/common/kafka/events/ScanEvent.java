package com.oneday.common.kafka.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.DomainEvent;
import com.oneday.common.kafka.enums.ScanEventType;

import java.util.UUID;

/**
 * Inbound event consumed by M4 from {@code oneday.scan.events} (produced by M8).
 *
 * <p>Minimal consumption contract — see {@link DaEvent} for the tolerant-reader rationale.
 * Note {@code LABEL_GENERATED} is not a state transition; it carries the generated
 * {@code parcelId} and is handled separately (TODO) rather than via the state machine.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScanEvent(UUID shipmentId, ScanEventType eventType) implements DomainEvent {

    @Override
    public String partitionKey() {
        return shipmentId != null ? shipmentId.toString() : null;
    }

    @Override
    public String eventTypeName() {
        return eventType != null ? eventType.name() : null;
    }
}
