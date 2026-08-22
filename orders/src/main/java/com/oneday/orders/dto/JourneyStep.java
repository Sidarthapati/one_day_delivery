package com.oneday.orders.dto;

import com.oneday.common.domain.enums.ShipmentState;

import java.time.Instant;

/**
 * One step of a shipment's internal state trail — the ops-facing counterpart to the customer-labelled
 * milestones in {@link ShipmentTrackResponse}. Sourced from {@code ShipmentStateHistory}, so it carries
 * the actor and source the customer view hides.
 */
public record JourneyStep(
        ShipmentState toState,
        Instant occurredAt,
        String triggeredBy,
        String triggerSource,
        String notes) {
}
