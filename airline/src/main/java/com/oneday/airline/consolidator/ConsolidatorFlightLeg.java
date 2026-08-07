package com.oneday.airline.consolidator;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One row of the consolidator's (mocked) {@code flight_leg} table — a concrete, date-specific
 * flight, not a recurring schedule slot. {@code status} is the consolidator's own word on the leg:
 * {@code SCHEDULED|DELAYED|CANCELLED}; {@code estimatedDepartureAt}/{@code estimatedArrivalAt} are
 * only set when {@code status} is {@code DELAYED}.
 */
public record ConsolidatorFlightLeg(
        String flightNo,
        String carrier,
        String originHub,
        String destHub,
        LocalDate flightDate,
        Instant departureAt,
        Instant arrivalAt,
        int capacityKg,
        String status,
        Instant estimatedDepartureAt,
        Instant estimatedArrivalAt) {
}
