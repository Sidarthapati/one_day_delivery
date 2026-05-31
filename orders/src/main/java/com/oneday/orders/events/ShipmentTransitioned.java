package com.oneday.orders.events;

import com.oneday.common.domain.enums.ShipmentState;
import com.oneday.orders.domain.enums.TriggerSource;

import java.time.Instant;
import java.util.UUID;

/**
 * In-process (NOT Kafka) Spring {@code ApplicationEvent}, published by
 * {@code ShipmentStateMachineImpl} after a successful state transition.
 *
 * <p>{@link ShipmentEventProducer} listens for it and emits the outbound Kafka
 * {@code ShipmentStateChangedEvent}. Keeping this internal decouples the state machine
 * from Kafka: the machine just announces "a transition happened" — it neither knows nor
 * cares that anything publishes it. Any future trigger of a transition (booking API, OTP
 * verify, or an inbound Kafka consumer) produces the outbound event automatically.</p>
 */
public record ShipmentTransitioned(
        UUID shipmentId,
        String shipmentRef,
        ShipmentState fromState,
        ShipmentState toState,
        String triggeredBy,
        TriggerSource triggerSource,
        Instant occurredAt
) {}
