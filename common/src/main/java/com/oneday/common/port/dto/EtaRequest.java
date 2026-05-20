package com.oneday.common.port.dto;

import com.oneday.common.domain.enums.ShipmentState;

import java.time.Instant;
import java.util.UUID;

/**
 * @param shipmentId   internal shipment UUID
 * @param currentState the state M4 is in when requesting the ETA; M9 uses this to select its model
 * @param occurredAt   timestamp at which the current state was entered; may differ from wall-clock
 *                     if Kafka processing had lag — M9 uses this, not now(), for accurate ETA
 * @param context      city pair, delivery type, booking time, and flight assignment; see EtaContext
 */
public record EtaRequest(
        UUID shipmentId,
        ShipmentState currentState,
        Instant occurredAt,
        EtaContext context
) {}
