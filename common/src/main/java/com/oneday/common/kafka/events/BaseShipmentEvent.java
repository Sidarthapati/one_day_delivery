package com.oneday.common.kafka.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
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
public abstract class BaseShipmentEvent {

    private UUID eventId;
    private ShipmentEventType eventType;
    private String schemaVersion = "1.0";
    private Instant occurredAt;
    private UUID shipmentId;
    private String shipmentRef;
}
