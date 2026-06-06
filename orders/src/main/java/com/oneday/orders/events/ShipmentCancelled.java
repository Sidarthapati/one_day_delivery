package com.oneday.orders.events;

import com.oneday.common.domain.enums.ShipmentState;

import java.time.Instant;
import java.util.UUID;

/**
 * In-process (NOT Kafka) Spring {@code ApplicationEvent}, published by
 * {@code CancellationServiceImpl} after a shipment is cancelled and its refund/credit reversal
 * decided.
 *
 * <p>{@link ShipmentEventProducer} maps it to the outbound Kafka {@code ShipmentCancelledEvent}
 * AFTER_COMMIT. The plain {@code → CANCELLED} STATE_CHANGED event still rides
 * {@link ShipmentTransitioned} from the state machine; this carries the <em>rich</em> cancellation
 * detail (reason + refund) that the state machine has no knowledge of.</p>
 */
public record ShipmentCancelled(
        UUID shipmentId,
        String shipmentRef,
        ShipmentState cancelledAtState,
        String reason,
        boolean refundInitiated,
        Long refundAmountPaise,
        Instant occurredAt
) {}
