package com.oneday.common.kafka.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.DomainEvent;
import com.oneday.common.kafka.enums.ScanEventType;

import java.time.Instant;
import java.util.UUID;

/**
 * Inbound event consumed by M4 from {@code oneday.scan.events} (produced by M8).
 *
 * <p>{@code parcelId} carries the generated barcode on {@code LABEL_GENERATED} (M4 stores it on the
 * shipment, no state transition); it is null on the state-transition scans. {@code occurredAt} is
 * the physical scan time. Tolerant reader — extra fields ignored (D-005).</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScanEvent(UUID shipmentId, ScanEventType eventType, String parcelId, Instant occurredAt)
        implements DomainEvent {

    /** Convenience for state-transition scans that carry no barcode. */
    public ScanEvent(UUID shipmentId, ScanEventType eventType) {
        this(shipmentId, eventType, null, Instant.now());
    }

    @Override
    public String partitionKey() {
        return shipmentId != null ? shipmentId.toString() : null;
    }

    @Override
    public String eventTypeName() {
        return eventType != null ? eventType.name() : null;
    }
}
