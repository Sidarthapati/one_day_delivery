package com.oneday.common.kafka.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.oneday.common.kafka.DomainEvent;

import java.util.UUID;

/**
 * Produced by M4 on {@code oneday.delivery.confirmations} when a receiver rejects today's delivery and
 * picks a next-day shift. M5 re-parks the last-mile delivery for that day/shift (a courtesy reschedule,
 * NOT a failed attempt). Carries the destination geography M5 needs so it can defer without a GET back
 * to M4; M5 resolves the dispatch city from the coordinates.
 *
 * @param targetShift SHIFT_1 | SHIFT_2 the receiver chose (null = next available)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReceiverRejectedEvent(
        UUID shipmentId,
        UUID orderId,
        String orderRef,
        String targetShift,
        Double destLat,
        Double destLon,
        UUID destTileId
) implements DomainEvent {

    @Override
    public String partitionKey() {
        return shipmentId != null ? shipmentId.toString() : null;
    }

    @Override
    public String eventTypeName() {
        return "RECEIVER_REJECTED";
    }
}
