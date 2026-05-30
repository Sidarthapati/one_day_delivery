package com.oneday.common.kafka.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.oneday.common.kafka.DomainEvent;
import com.oneday.common.kafka.enums.ShipmentEventType;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract class BaseShipmentEvent implements DomainEvent {

    private UUID eventId;
    private ShipmentEventType eventType;
    private String schemaVersion = "1.0";
    private Instant occurredAt;
    private UUID shipmentId;
    private String shipmentRef;

    // ── DomainEvent: shipment events are keyed by shipmentId ──
    @Override
    public String partitionKey() {
        return shipmentId != null ? shipmentId.toString() : null;
    }

    @Override
    public String eventTypeName() {
        return eventType != null ? eventType.name() : null;
    }
}
